/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui;

import android.content.Context;
import android.content.res.Configuration;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.ArrayMap;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.NightDisplayController;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.Preconditions;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.PluginDependencyProvider;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.plugins.PluginManagerImpl;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.power.PowerNotificationWarnings;
import com.android.systemui.power.PowerUI;
import com.android.systemui.statusbar.phone.ConfigurationControllerImpl;
import com.android.systemui.statusbar.phone.DarkIconDispatcherImpl;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.ManagedProfileController;
import com.android.systemui.statusbar.phone.ManagedProfileControllerImpl;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarIconControllerImpl;
import com.android.systemui.statusbar.phone.StatusBarWindowManager;
import com.android.systemui.statusbar.policy.AccessibilityController;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryControllerImpl;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.BluetoothControllerImpl;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastControllerImpl;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedControllerImpl;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.ExtensionControllerImpl;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.FlashlightControllerImpl;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.HotspotControllerImpl;
import com.android.systemui.statusbar.policy.IconLogger;
import com.android.systemui.statusbar.policy.IconLoggerImpl;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.KeyguardMonitorImpl;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.LocationControllerImpl;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkControllerImpl;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.NextAlarmControllerImpl;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockControllerImpl;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.statusbar.policy.SecurityControllerImpl;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.ZenModeControllerImpl;
import com.android.systemui.tuner.TunablePadding.TunablePaddingService;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerServiceImpl;
import com.android.systemui.util.AsyncSensorManager;
import com.android.systemui.util.leak.GarbageMonitor;
import com.android.systemui.util.leak.LeakDetector;
import com.android.systemui.util.leak.LeakReporter;
import com.android.systemui.volume.VolumeDialogControllerImpl;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.function.Consumer;

/**
 * Class to handle ugly dependencies throughout sysui until we determine the
 * long-term dependency injection solution.
 *
 * Classes added here should be things that are expected to live the lifetime of sysui,
 * and are generally applicable to many parts of sysui. They will be lazily
 * initialized to ensure they aren't created on form factors that don't need them
 * (e.g. HotspotController on TV). Despite being lazily initialized, it is expected
 * that all dependencies will be gotten during sysui startup, and not during runtime
 * to avoid jank.
 *
 * All classes used here are expected to manage their own lifecycle, meaning if
 * they have no clients they should not have any registered resources like bound
 * services, registered receivers, etc.
 */
public class Dependency extends SystemUI {
    private static final String TAG = "Dependency";

    /**
     * Key for getting a background Looper for background work.
     */
    public static final DependencyKey<Looper> BG_LOOPER = new DependencyKey<>("background_looper");
    /**
     * Key for getting a Handler for receiving time tick broadcasts on.
     */
    public static final DependencyKey<Handler> TIME_TICK_HANDLER =
            new DependencyKey<>("time_tick_handler");
    /**
     * Generic handler on the main thread.
     */
    public static final DependencyKey<Handler> MAIN_HANDLER = new DependencyKey<>("main_handler");

    /**
     * An email address to send memory leak reports to by default.
     */
    public static final DependencyKey<String> LEAK_REPORT_EMAIL
            = new DependencyKey<>("leak_report_email");

    private final ArrayMap<Object, Object> mDependencies = new ArrayMap<>();
    private final ArrayMap<Object, DependencyProvider> mProviders = new ArrayMap<>();

    @Override
    public void start() {
        sDependency = this;
        // TODO: Think about ways to push these creation rules out of Dependency to cut down
        // on imports.
        mProviders.put(TIME_TICK_HANDLER, () -> {
            HandlerThread thread = new HandlerThread("TimeTick");
            thread.start();
            return new Handler(thread.getLooper());
        });
        mProviders.put(BG_LOOPER, () -> {
            HandlerThread thread = new HandlerThread("SysUiBg",
                    Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            return thread.getLooper();
        });
        mProviders.put(MAIN_HANDLER, () -> new Handler(Looper.getMainLooper()));
        mProviders.put(ActivityStarter.class, () -> new ActivityStarterDelegate());
        mProviders.put(ActivityStarterDelegate.class, () ->
                getDependency(ActivityStarter.class));

        mProviders.put(AsyncSensorManager.class, () ->
                new AsyncSensorManager(mContext.getSystemService(SensorManager.class)));

        mProviders.put(BluetoothController.class, () ->
                new BluetoothControllerImpl(mContext, getDependency(BG_LOOPER)));

        mProviders.put(LocationController.class, () ->
                new LocationControllerImpl(mContext, getDependency(BG_LOOPER)));

        mProviders.put(RotationLockController.class, () ->
                new RotationLockControllerImpl(mContext));

        mProviders.put(NetworkController.class, () ->
                new NetworkControllerImpl(mContext, getDependency(BG_LOOPER),
                        getDependency(DeviceProvisionedController.class)));

        mProviders.put(ZenModeController.class, () ->
                new ZenModeControllerImpl(mContext, getDependency(MAIN_HANDLER)));

        mProviders.put(HotspotController.class, () ->
                new HotspotControllerImpl(mContext));

        mProviders.put(CastController.class, () ->
                new CastControllerImpl(mContext));

        mProviders.put(FlashlightController.class, () ->
                new FlashlightControllerImpl(mContext));

        mProviders.put(KeyguardMonitor.class, () ->
                new KeyguardMonitorImpl(mContext));

        mProviders.put(UserSwitcherController.class, () ->
                new UserSwitcherController(mContext, getDependency(KeyguardMonitor.class),
                        getDependency(MAIN_HANDLER), getDependency(ActivityStarter.class)));

        mProviders.put(UserInfoController.class, () ->
                new UserInfoControllerImpl(mContext));

        mProviders.put(BatteryController.class, () ->
                new BatteryControllerImpl(mContext));

        mProviders.put(NightDisplayController.class, () ->
                new NightDisplayController(mContext));

        mProviders.put(ManagedProfileController.class, () ->
                new ManagedProfileControllerImpl(mContext));

        mProviders.put(NextAlarmController.class, () ->
                new NextAlarmControllerImpl(mContext));

        mProviders.put(DataSaverController.class, () ->
                get(NetworkController.class).getDataSaverController());

        mProviders.put(AccessibilityController.class, () ->
                new AccessibilityController(mContext));

        mProviders.put(DeviceProvisionedController.class, () ->
                new DeviceProvisionedControllerImpl(mContext));

        mProviders.put(PluginManager.class, () ->
                new PluginManagerImpl(mContext));

        mProviders.put(AssistManager.class, () ->
                new AssistManager(getDependency(DeviceProvisionedController.class), mContext));

        mProviders.put(SecurityController.class, () ->
                new SecurityControllerImpl(mContext));

        mProviders.put(LeakDetector.class, LeakDetector::create);

        mProviders.put(LEAK_REPORT_EMAIL, () -> null);

        mProviders.put(LeakReporter.class, () -> new LeakReporter(
                mContext,
                getDependency(LeakDetector.class),
                getDependency(LEAK_REPORT_EMAIL)));

        mProviders.put(GarbageMonitor.class, () -> new GarbageMonitor(
                getDependency(BG_LOOPER),
                getDependency(LeakDetector.class),
                getDependency(LeakReporter.class)));

        mProviders.put(TunerService.class, () ->
                new TunerServiceImpl(mContext));

        mProviders.put(StatusBarWindowManager.class, () ->
                new StatusBarWindowManager(mContext));

        mProviders.put(DarkIconDispatcher.class, () ->
                new DarkIconDispatcherImpl(mContext));

        mProviders.put(ConfigurationController.class, () ->
                new ConfigurationControllerImpl(mContext));

        mProviders.put(StatusBarIconController.class, () ->
                new StatusBarIconControllerImpl(mContext));

        mProviders.put(ScreenLifecycle.class, () ->
                new ScreenLifecycle());

        mProviders.put(WakefulnessLifecycle.class, () ->
                new WakefulnessLifecycle());

        mProviders.put(FragmentService.class, () ->
                new FragmentService(mContext));

        mProviders.put(ExtensionController.class, () ->
                new ExtensionControllerImpl(mContext));

        mProviders.put(PluginDependencyProvider.class, () ->
                new PluginDependencyProvider(get(PluginManager.class)));

        mProviders.put(LocalBluetoothManager.class, () ->
                LocalBluetoothManager.getInstance(mContext, null));

        mProviders.put(VolumeDialogController.class, () ->
                new VolumeDialogControllerImpl(mContext));

        mProviders.put(MetricsLogger.class, () -> new MetricsLogger());

        mProviders.put(AccessibilityManagerWrapper.class,
                () -> new AccessibilityManagerWrapper(mContext));

        // Creating a new instance will trigger color extraction.
        // Thankfully this only happens once - during boot - and WallpaperManagerService
        // loads colors from cache.
        mProviders.put(SysuiColorExtractor.class, () -> new SysuiColorExtractor(mContext));

        mProviders.put(TunablePaddingService.class, () -> new TunablePaddingService());

        mProviders.put(ForegroundServiceController.class,
                () -> new ForegroundServiceControllerImpl(mContext));

        mProviders.put(UiOffloadThread.class, UiOffloadThread::new);

        mProviders.put(PowerUI.WarningsUI.class, () -> new PowerNotificationWarnings(mContext));

        mProviders.put(IconLogger.class, () -> new IconLoggerImpl(mContext,
                getDependency(BG_LOOPER), getDependency(MetricsLogger.class)));

        mProviders.put(LightBarController.class, () -> new LightBarController(mContext));

        mProviders.put(IWindowManager.class, () -> WindowManagerGlobal.getWindowManagerService());

        // Put all dependencies above here so the factory can override them if it wants.
        SystemUIFactory.getInstance().injectDependencies(mProviders, mContext);
    }

    @Override
    public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        pw.println("Dumping existing controllers:");
        mDependencies.values().stream().filter(obj -> obj instanceof Dumpable)
                .forEach(o -> ((Dumpable) o).dump(fd, pw, args));
    }

    @Override
    protected synchronized void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDependencies.values().stream().filter(obj -> obj instanceof ConfigurationChangedReceiver)
                .forEach(o -> ((ConfigurationChangedReceiver) o).onConfigurationChanged(newConfig));
    }

    protected final <T> T getDependency(Class<T> cls) {
        return getDependencyInner(cls);
    }

    protected final <T> T getDependency(DependencyKey<T> key) {
        return getDependencyInner(key);
    }

    private synchronized <T> T getDependencyInner(Object key) {
        @SuppressWarnings("unchecked")
        T obj = (T) mDependencies.get(key);
        if (obj == null) {
            obj = createDependency(key);
            mDependencies.put(key, obj);
        }
        return obj;
    }

    @VisibleForTesting
    protected <T> T createDependency(Object cls) {
        Preconditions.checkArgument(cls instanceof DependencyKey<?> || cls instanceof Class<?>);

        @SuppressWarnings("unchecked")
        DependencyProvider<T> provider = mProviders.get(cls);
        if (provider == null) {
            throw new IllegalArgumentException("Unsupported dependency " + cls);
        }
        return provider.createDependency();
    }

    private static Dependency sDependency;

    public interface DependencyProvider<T> {
        T createDependency();
    }

    private <T> void destroyDependency(Class<T> cls, Consumer<T> destroy) {
        T dep = (T) mDependencies.remove(cls);
        if (dep != null && destroy != null) {
            destroy.accept(dep);
        }
    }

    /**
     * Used in separate processes (like tuner settings) to init the dependencies.
     */
    public static void initDependencies(Context context) {
        if (sDependency != null) return;
        Dependency d = new Dependency();
        d.mContext = context;
        d.mComponents = new HashMap<>();
        d.start();
    }

    /**
     * Used in separate process teardown to ensure the context isn't leaked.
     *
     * TODO: Remove once PreferenceFragment doesn't reference getActivity()
     * anymore and these context hacks are no longer needed.
     */
    public static void clearDependencies() {
        sDependency = null;
    }

    /**
     * Checks to see if a dependency is instantiated, if it is it removes it from
     * the cache and calls the destroy callback.
     */
    public static <T> void destroy(Class<T> cls, Consumer<T> destroy) {
        sDependency.destroyDependency(cls, destroy);
    }

    public static <T> T get(Class<T> cls) {
        return sDependency.getDependency(cls);
    }

    public static <T> T get(DependencyKey<T> cls) {
        return sDependency.getDependency(cls);
    }

    public static final class DependencyKey<V> {
        private final String mDisplayName;

        public DependencyKey(String displayName) {
            mDisplayName = displayName;
        }

        @Override
        public String toString() {
            return mDisplayName;
        }
    }
}
