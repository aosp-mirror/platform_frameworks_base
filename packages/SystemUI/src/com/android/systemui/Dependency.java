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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.statusbar.phone.ManagedProfileController;
import com.android.systemui.statusbar.phone.ManagedProfileControllerImpl;
import com.android.systemui.statusbar.phone.StatusBarWindowManager;
import com.android.systemui.statusbar.policy.AccessibilityController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryControllerImpl;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.BluetoothControllerImpl;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastControllerImpl;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedControllerImpl;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.FlashlightControllerImpl;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.HotspotControllerImpl;
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
import com.android.systemui.tuner.TunerService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;

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

    /**
     * Key for getting a background Looper for background work.
     */
    public static final String BG_LOOPER = "background_loooper";
    /**
     * Key for getting a Handler for receiving time tick broadcasts on.
     */
    public static final String TIME_TICK_HANDLER = "time_tick_handler";
    /**
     * Generic handler on the main thread.
     */
    public static final String MAIN_HANDLER = "main_handler";

    private final ArrayMap<String, Object> mDependencies = new ArrayMap<>();
    private final ArrayMap<String, DependencyProvider> mProviders = new ArrayMap<>();

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
        mProviders.put(ActivityStarter.class.getName(), () -> new ActivityStarterDelegate());
        mProviders.put(ActivityStarterDelegate.class.getName(), () ->
                getDependency(ActivityStarter.class));

        mProviders.put(BluetoothController.class.getName(), () ->
                new BluetoothControllerImpl(mContext, getDependency(BG_LOOPER)));

        mProviders.put(LocationController.class.getName(), () ->
                new LocationControllerImpl(mContext, getDependency(BG_LOOPER)));

        mProviders.put(RotationLockController.class.getName(), () ->
                new RotationLockControllerImpl(mContext));

        mProviders.put(NetworkController.class.getName(), () ->
                new NetworkControllerImpl(mContext, getDependency(BG_LOOPER),
                        getDependency(DeviceProvisionedController.class)));

        mProviders.put(ZenModeController.class.getName(), () ->
                new ZenModeControllerImpl(mContext, getDependency(MAIN_HANDLER)));

        mProviders.put(HotspotController.class.getName(), () ->
                new HotspotControllerImpl(mContext));

        mProviders.put(CastController.class.getName(), () ->
                new CastControllerImpl(mContext));

        mProviders.put(FlashlightController.class.getName(), () ->
                new FlashlightControllerImpl(mContext));

        mProviders.put(KeyguardMonitor.class.getName(), () ->
                new KeyguardMonitorImpl(mContext));

        mProviders.put(UserSwitcherController.class.getName(), () ->
                new UserSwitcherController(mContext, getDependency(KeyguardMonitor.class),
                        getDependency(MAIN_HANDLER), getDependency(ActivityStarter.class)));

        mProviders.put(UserInfoController.class.getName(), () ->
                new UserInfoControllerImpl(mContext));

        mProviders.put(BatteryController.class.getName(), () ->
                new BatteryControllerImpl(mContext));

        mProviders.put(ManagedProfileController.class.getName(), () ->
                new ManagedProfileControllerImpl(mContext));

        mProviders.put(NextAlarmController.class.getName(), () ->
                new NextAlarmControllerImpl(mContext));

        mProviders.put(DataSaverController.class.getName(), () ->
                get(NetworkController.class).getDataSaverController());

        mProviders.put(AccessibilityController.class.getName(), () ->
                new AccessibilityController(mContext));

        mProviders.put(DeviceProvisionedController.class.getName(), () ->
                new DeviceProvisionedControllerImpl(mContext));

        mProviders.put(PluginManager.class.getName(), () ->
                new PluginManager(mContext));

        mProviders.put(AssistManager.class.getName(), () ->
                new AssistManager(getDependency(DeviceProvisionedController.class), mContext));

        mProviders.put(SecurityController.class.getName(), () ->
                new SecurityControllerImpl(mContext));

        mProviders.put(TunerService.class.getName(), () ->
                new TunerService(mContext));

        mProviders.put(StatusBarWindowManager.class.getName(), () ->
                new StatusBarWindowManager(mContext));

        // Put all dependencies above here so the factory can override them if it wants.
        SystemUIFactory.getInstance().injectDependencies(mProviders, mContext);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        pw.println("Dumping existing controllers:");
        mDependencies.values().stream().filter(obj -> obj instanceof Dumpable)
                .forEach(o -> ((Dumpable) o).dump(fd, pw, args));
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDependencies.values().stream().filter(obj -> obj instanceof ConfigurationChangedReceiver)
                .forEach(o -> ((ConfigurationChangedReceiver) o).onConfigurationChanged(newConfig));
    }

    protected final <T> T getDependency(Class<T> cls) {
        return getDependency(cls.getName());
    }

    protected final <T> T getDependency(String cls) {
        T obj = (T) mDependencies.get(cls);
        if (obj == null) {
            obj = createDependency(cls);
            mDependencies.put(cls, obj);
        }
        return obj;
    }

    @VisibleForTesting
    protected <T> T createDependency(String cls) {
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

    /**
     * Used in separate processes (like tuner settings) to init the dependencies.
     */
    public static void initDependencies(Context context) {
        if (sDependency != null) return;
        Dependency d = new Dependency();
        d.mContext = context.getApplicationContext();
        d.mComponents = new HashMap<>();
        d.start();
    }

    public static <T> T get(Class<T> cls) {
        return sDependency.getDependency(cls.getName());
    }

    public static <T> T get(String cls) {
        return sDependency.getDependency(cls);
    }
}
