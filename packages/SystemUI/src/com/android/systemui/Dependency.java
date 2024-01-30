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

import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.Preconditions;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.accessibility.AccessibilityButtonModeObserver;
import com.android.systemui.accessibility.AccessibilityButtonTargetsObserver;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.NotificationSectionsManager;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider;
import com.android.systemui.statusbar.phone.SystemUIDialogManager;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.tuner.TunablePadding.TunablePaddingService;
import com.android.systemui.tuner.TunerService;

import dagger.Lazy;

import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Named;

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
@SysUISingleton
public class Dependency {

    /**
     * Key for getting a background Looper for background work.
     */
    private static final String BG_LOOPER_NAME = "background_looper";
    /**
     * Key for getting a Handler for receiving time tick broadcasts on.
     */
    public static final String TIME_TICK_HANDLER_NAME = "time_tick_handler";
    /**
     * Generic handler on the main thread.
     */
    private static final String MAIN_HANDLER_NAME = "main_handler";

    /**
     * An email address to send memory leak reports to by default.
     */
    public static final String LEAK_REPORT_EMAIL_NAME = "leak_report_email";

    /**
     * Whether this platform supports long-pressing notifications to show notification channel
     * settings.
     */
    public static final String ALLOW_NOTIFICATION_LONG_PRESS_NAME = "allow_notif_longpress";

    /**
     * Key for getting a background Looper for background work.
     */
    public static final DependencyKey<Looper> BG_LOOPER = new DependencyKey<>(BG_LOOPER_NAME);
    /**
     * Key for getting a Handler for receiving time tick broadcasts on.
     */
    public static final DependencyKey<Handler> TIME_TICK_HANDLER =
            new DependencyKey<>(TIME_TICK_HANDLER_NAME);
    /**
     * Generic handler on the main thread.
     */
    public static final DependencyKey<Handler> MAIN_HANDLER =
            new DependencyKey<>(MAIN_HANDLER_NAME);

    private final ArrayMap<Object, Object> mDependencies = new ArrayMap<>();
    private final ArrayMap<Object, LazyDependencyCreator> mProviders = new ArrayMap<>();

    @Inject DumpManager mDumpManager;

    @Inject Lazy<BroadcastDispatcher> mBroadcastDispatcher;
    @Inject Lazy<BluetoothController> mBluetoothController;
    @Inject Lazy<FlashlightController> mFlashlightController;
    @Inject Lazy<KeyguardUpdateMonitor> mKeyguardUpdateMonitor;
    @Inject Lazy<DeviceProvisionedController> mDeviceProvisionedController;
    @Inject Lazy<PluginManager> mPluginManager;
    @Inject Lazy<AssistManager> mAssistManager;
    @Inject Lazy<TunerService> mTunerService;
    @Inject Lazy<DarkIconDispatcher> mDarkIconDispatcher;
    @Inject Lazy<FragmentService> mFragmentService;
    @Nullable
    @Inject Lazy<VolumeDialogController> mVolumeDialogController;
    @Inject Lazy<MetricsLogger> mMetricsLogger;
    @Inject Lazy<TunablePaddingService> mTunablePaddingService;
    @Inject Lazy<UiOffloadThread> mUiOffloadThread;
    @Inject Lazy<LightBarController> mLightBarController;
    @Inject Lazy<OverviewProxyService> mOverviewProxyService;
    @Inject Lazy<NavigationModeController> mNavBarModeController;
    @Inject Lazy<AccessibilityButtonModeObserver> mAccessibilityButtonModeObserver;
    @Inject Lazy<AccessibilityButtonTargetsObserver> mAccessibilityButtonListController;
    @Inject Lazy<IStatusBarService> mIStatusBarService;
    @Inject Lazy<NotificationRemoteInputManager.Callback> mNotificationRemoteInputManagerCallback;
    @Inject Lazy<NavigationBarController> mNavigationBarController;
    @Inject Lazy<StatusBarStateController> mStatusBarStateController;
    @Inject Lazy<NotificationMediaManager> mNotificationMediaManager;
    @Inject @Background Lazy<Looper> mBgLooper;
    @Inject @Main Lazy<Handler> mMainHandler;
    @Inject @Named(TIME_TICK_HANDLER_NAME) Lazy<Handler> mTimeTickHandler;
    @Inject Lazy<SysUiState> mSysUiStateFlagsContainer;
    @Inject Lazy<CommandQueue> mCommandQueue;
    @Inject Lazy<UiEventLogger> mUiEventLogger;
    @Inject Lazy<StatusBarContentInsetsProvider> mContentInsetsProviderLazy;
    @Inject Lazy<FeatureFlags> mFeatureFlagsLazy;
    @Inject Lazy<NotificationSectionsManager> mNotificationSectionsManagerLazy;
    @Inject Lazy<ScreenOffAnimationController> mScreenOffAnimationController;
    @Inject Lazy<AmbientState> mAmbientStateLazy;
    @Inject Lazy<GroupMembershipManager> mGroupMembershipManagerLazy;
    @Inject Lazy<GroupExpansionManager> mGroupExpansionManagerLazy;
    @Inject Lazy<SystemUIDialogManager> mSystemUIDialogManagerLazy;
    @Inject Lazy<DialogLaunchAnimator> mDialogLaunchAnimatorLazy;
    @Inject Lazy<UserTracker> mUserTrackerLazy;

    @Inject
    public Dependency() {
    }

    /**
     * Initialize Depenency.
     */
    protected void start() {
        // TODO: Think about ways to push these creation rules out of Dependency to cut down
        // on imports.
        mProviders.put(TIME_TICK_HANDLER, mTimeTickHandler::get);
        mProviders.put(BG_LOOPER, mBgLooper::get);
        mProviders.put(MAIN_HANDLER, mMainHandler::get);
        mProviders.put(BroadcastDispatcher.class, mBroadcastDispatcher::get);
        mProviders.put(BluetoothController.class, mBluetoothController::get);
        mProviders.put(FlashlightController.class, mFlashlightController::get);
        mProviders.put(KeyguardUpdateMonitor.class, mKeyguardUpdateMonitor::get);
        mProviders.put(DeviceProvisionedController.class, mDeviceProvisionedController::get);
        mProviders.put(PluginManager.class, mPluginManager::get);
        mProviders.put(AssistManager.class, mAssistManager::get);
        mProviders.put(TunerService.class, mTunerService::get);
        mProviders.put(DarkIconDispatcher.class, mDarkIconDispatcher::get);
        mProviders.put(FragmentService.class, mFragmentService::get);
        mProviders.put(VolumeDialogController.class, mVolumeDialogController::get);
        mProviders.put(MetricsLogger.class, mMetricsLogger::get);
        mProviders.put(TunablePaddingService.class, mTunablePaddingService::get);
        mProviders.put(UiOffloadThread.class, mUiOffloadThread::get);
        mProviders.put(LightBarController.class, mLightBarController::get);
        mProviders.put(OverviewProxyService.class, mOverviewProxyService::get);
        mProviders.put(NavigationModeController.class, mNavBarModeController::get);
        mProviders.put(AccessibilityButtonModeObserver.class,
                mAccessibilityButtonModeObserver::get);
        mProviders.put(AccessibilityButtonTargetsObserver.class,
                mAccessibilityButtonListController::get);
        mProviders.put(IStatusBarService.class, mIStatusBarService::get);
        mProviders.put(NotificationRemoteInputManager.Callback.class,
                mNotificationRemoteInputManagerCallback::get);
        mProviders.put(NavigationBarController.class, mNavigationBarController::get);
        mProviders.put(StatusBarStateController.class, mStatusBarStateController::get);
        mProviders.put(NotificationMediaManager.class, mNotificationMediaManager::get);
        mProviders.put(SysUiState.class, mSysUiStateFlagsContainer::get);
        mProviders.put(CommandQueue.class, mCommandQueue::get);
        mProviders.put(UiEventLogger.class, mUiEventLogger::get);
        mProviders.put(FeatureFlags.class, mFeatureFlagsLazy::get);
        mProviders.put(StatusBarContentInsetsProvider.class, mContentInsetsProviderLazy::get);
        mProviders.put(NotificationSectionsManager.class, mNotificationSectionsManagerLazy::get);
        mProviders.put(ScreenOffAnimationController.class, mScreenOffAnimationController::get);
        mProviders.put(AmbientState.class, mAmbientStateLazy::get);
        mProviders.put(GroupMembershipManager.class, mGroupMembershipManagerLazy::get);
        mProviders.put(GroupExpansionManager.class, mGroupExpansionManagerLazy::get);
        mProviders.put(SystemUIDialogManager.class, mSystemUIDialogManagerLazy::get);
        mProviders.put(DialogLaunchAnimator.class, mDialogLaunchAnimatorLazy::get);
        mProviders.put(UserTracker.class, mUserTrackerLazy::get);

        Dependency.setInstance(this);
    }

    @VisibleForTesting
    public static void setInstance(Dependency dependency) {
        sDependency = dependency;
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
    public <T> T createDependency(Object cls) {
        Preconditions.checkArgument(cls instanceof DependencyKey<?> || cls instanceof Class<?>);

        @SuppressWarnings("unchecked")
        LazyDependencyCreator<T> provider = mProviders.get(cls);
        if (provider == null) {
            throw new IllegalArgumentException("Unsupported dependency " + cls
                    + ". " + mProviders.size() + " providers known.");
        }
        return provider.createDependency();
    }

    private static Dependency sDependency;

    /**
     * Interface for a class that can create a dependency. Used to implement laziness
     * @param <T> The type of the dependency being created
     */
    private interface LazyDependencyCreator<T> {
        T createDependency();
    }

    private <T> void destroyDependency(Class<T> cls, Consumer<T> destroy) {
        T dep = (T) mDependencies.remove(cls);
        if (dep instanceof Dumpable) {
            mDumpManager.unregisterDumpable(dep.getClass().getName());
        }
        if (dep != null && destroy != null) {
            destroy.accept(dep);
        }
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

    /**
     * @deprecated see docs/dagger.md
     */
    @Deprecated
    public static <T> T get(Class<T> cls) {
        return sDependency.getDependency(cls);
    }

    /**
     * @deprecated see docs/dagger.md
     */
    @Deprecated
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
