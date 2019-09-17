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
import android.app.AlarmManager;
import android.app.INotificationManager;
import android.content.res.Configuration;
import android.hardware.SensorPrivacyManager;
import android.hardware.display.NightDisplayListener;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.view.IWindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.Preconditions;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.clock.ClockManager;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.systemui.appops.AppOpsController;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dock.DockManager;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.PluginDependencyProvider;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.power.EnhancedEstimates;
import com.android.systemui.power.PowerUI;
import com.android.systemui.privacy.PrivacyItemController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.DevicePolicyManagerWrapper;
import com.android.systemui.shared.system.PackageManagerWrapper;
import com.android.systemui.statusbar.NavigationBarController;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.notification.NotificationAlertingManager;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationFilter;
import com.android.systemui.statusbar.notification.NotificationInterruptionStateProvider;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationData.KeyguardEnvironment;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.ChannelEditorDialogController;
import com.android.systemui.statusbar.notification.row.NotificationBlockingHelperManager;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger;
import com.android.systemui.statusbar.phone.ManagedProfileController;
import com.android.systemui.statusbar.phone.NavigationModeController;
import com.android.systemui.statusbar.phone.NotificationGroupAlertTransferHelper;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarWindowController;
import com.android.systemui.statusbar.policy.AccessibilityController;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.statusbar.policy.SensorPrivacyController;
import com.android.systemui.statusbar.policy.SmartReplyConstants;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.tuner.TunablePadding.TunablePaddingService;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.leak.GarbageMonitor;
import com.android.systemui.util.leak.LeakDetector;
import com.android.systemui.util.leak.LeakReporter;
import com.android.systemui.util.sensors.AsyncSensorManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Named;

import dagger.Lazy;
import dagger.Subcomponent;

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
public class Dependency {
    /**
     * Key for getting a the main looper.
     */
    public static final String MAIN_LOOPER_NAME = "main_looper";

    /**
     * Key for getting a background Looper for background work.
     */
    public static final String BG_LOOPER_NAME = "background_looper";
    /**
     * Key for getting a background Handler for background work.
     */
    public static final String BG_HANDLER_NAME = "background_handler";
    /**
     * Key for getting a Handler for receiving time tick broadcasts on.
     */
    public static final String TIME_TICK_HANDLER_NAME = "time_tick_handler";
    /**
     * Generic handler on the main thread.
     */
    public static final String MAIN_HANDLER_NAME = "main_handler";

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
     * Key for getting a mainer Looper.
     */
    public static final DependencyKey<Looper> MAIN_LOOPER = new DependencyKey<>(MAIN_LOOPER_NAME);
    /**
     * Key for getting a background Handler for background work.
     */
    public static final DependencyKey<Handler> BG_HANDLER = new DependencyKey<>(BG_HANDLER_NAME);
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

    /**
     * An email address to send memory leak reports to by default.
     */
    public static final DependencyKey<String> LEAK_REPORT_EMAIL =
            new DependencyKey<>(LEAK_REPORT_EMAIL_NAME);

    private final ArrayMap<Object, Object> mDependencies = new ArrayMap<>();
    private final ArrayMap<Object, LazyDependencyCreator> mProviders = new ArrayMap<>();

    @Inject Lazy<ActivityStarter> mActivityStarter;
    @Inject Lazy<ActivityStarterDelegate> mActivityStarterDelegate;
    @Inject Lazy<BroadcastDispatcher> mBroadcastDispatcher;
    @Inject Lazy<AsyncSensorManager> mAsyncSensorManager;
    @Inject Lazy<BluetoothController> mBluetoothController;
    @Inject Lazy<LocationController> mLocationController;
    @Inject Lazy<RotationLockController> mRotationLockController;
    @Inject Lazy<NetworkController> mNetworkController;
    @Inject Lazy<ZenModeController> mZenModeController;
    @Inject Lazy<HotspotController> mHotspotController;
    @Inject Lazy<CastController> mCastController;
    @Inject Lazy<FlashlightController> mFlashlightController;
    @Inject Lazy<UserSwitcherController> mUserSwitcherController;
    @Inject Lazy<UserInfoController> mUserInfoController;
    @Inject Lazy<KeyguardStateController> mKeyguardMonitor;
    @Inject Lazy<KeyguardUpdateMonitor> mKeyguardUpdateMonitor;
    @Inject Lazy<BatteryController> mBatteryController;
    @Inject Lazy<NightDisplayListener> mNightDisplayListener;
    @Inject Lazy<ManagedProfileController> mManagedProfileController;
    @Inject Lazy<NextAlarmController> mNextAlarmController;
    @Inject Lazy<DataSaverController> mDataSaverController;
    @Inject Lazy<AccessibilityController> mAccessibilityController;
    @Inject Lazy<DeviceProvisionedController> mDeviceProvisionedController;
    @Inject Lazy<PluginManager> mPluginManager;
    @Inject Lazy<AssistManager> mAssistManager;
    @Inject Lazy<SecurityController> mSecurityController;
    @Inject Lazy<LeakDetector> mLeakDetector;
    @Inject Lazy<LeakReporter> mLeakReporter;
    @Inject Lazy<GarbageMonitor> mGarbageMonitor;
    @Inject Lazy<TunerService> mTunerService;
    @Inject Lazy<StatusBarWindowController> mStatusBarWindowController;
    @Inject Lazy<DarkIconDispatcher> mDarkIconDispatcher;
    @Inject Lazy<ConfigurationController> mConfigurationController;
    @Inject Lazy<StatusBarIconController> mStatusBarIconController;
    @Inject Lazy<ScreenLifecycle> mScreenLifecycle;
    @Inject Lazy<WakefulnessLifecycle> mWakefulnessLifecycle;
    @Inject Lazy<FragmentService> mFragmentService;
    @Inject Lazy<ExtensionController> mExtensionController;
    @Inject Lazy<PluginDependencyProvider> mPluginDependencyProvider;
    @Nullable
    @Inject Lazy<LocalBluetoothManager> mLocalBluetoothManager;
    @Inject Lazy<VolumeDialogController> mVolumeDialogController;
    @Inject Lazy<MetricsLogger> mMetricsLogger;
    @Inject Lazy<AccessibilityManagerWrapper> mAccessibilityManagerWrapper;
    @Inject Lazy<SysuiColorExtractor> mSysuiColorExtractor;
    @Inject Lazy<TunablePaddingService> mTunablePaddingService;
    @Inject Lazy<ForegroundServiceController> mForegroundServiceController;
    @Inject Lazy<UiOffloadThread> mUiOffloadThread;
    @Inject Lazy<PowerUI.WarningsUI> mWarningsUI;
    @Inject Lazy<LightBarController> mLightBarController;
    @Inject Lazy<IWindowManager> mIWindowManager;
    @Inject Lazy<OverviewProxyService> mOverviewProxyService;
    @Inject Lazy<NavigationModeController> mNavBarModeController;
    @Inject Lazy<EnhancedEstimates> mEnhancedEstimates;
    @Inject Lazy<VibratorHelper> mVibratorHelper;
    @Inject Lazy<IStatusBarService> mIStatusBarService;
    @Inject Lazy<DisplayMetrics> mDisplayMetrics;
    @Inject Lazy<LockscreenGestureLogger> mLockscreenGestureLogger;
    @Inject Lazy<KeyguardEnvironment> mKeyguardEnvironment;
    @Inject Lazy<ShadeController> mShadeController;
    @Inject Lazy<NotificationRemoteInputManager.Callback> mNotificationRemoteInputManagerCallback;
    @Inject Lazy<InitController> mInitController;
    @Inject Lazy<AppOpsController> mAppOpsController;
    @Inject Lazy<NavigationBarController> mNavigationBarController;
    @Inject Lazy<StatusBarStateController> mStatusBarStateController;
    @Inject Lazy<NotificationLockscreenUserManager> mNotificationLockscreenUserManager;
    @Inject Lazy<NotificationGroupAlertTransferHelper> mNotificationGroupAlertTransferHelper;
    @Inject Lazy<NotificationGroupManager> mNotificationGroupManager;
    @Inject Lazy<VisualStabilityManager> mVisualStabilityManager;
    @Inject Lazy<NotificationGutsManager> mNotificationGutsManager;
    @Inject Lazy<NotificationMediaManager> mNotificationMediaManager;
    @Inject Lazy<NotificationBlockingHelperManager> mNotificationBlockingHelperManager;
    @Inject Lazy<NotificationRemoteInputManager> mNotificationRemoteInputManager;
    @Inject Lazy<SmartReplyConstants> mSmartReplyConstants;
    @Inject Lazy<NotificationListener> mNotificationListener;
    @Inject Lazy<NotificationLogger> mNotificationLogger;
    @Inject Lazy<NotificationViewHierarchyManager> mNotificationViewHierarchyManager;
    @Inject Lazy<NotificationFilter> mNotificationFilter;
    @Inject Lazy<NotificationInterruptionStateProvider> mNotificationInterruptionStateProvider;
    @Inject Lazy<KeyguardDismissUtil> mKeyguardDismissUtil;
    @Inject Lazy<SmartReplyController> mSmartReplyController;
    @Inject Lazy<RemoteInputQuickSettingsDisabler> mRemoteInputQuickSettingsDisabler;
    @Inject Lazy<BubbleController> mBubbleController;
    @Inject Lazy<NotificationEntryManager> mNotificationEntryManager;
    @Inject
    Lazy<NotificationAlertingManager> mNotificationAlertingManager;
    @Inject Lazy<SensorPrivacyManager> mSensorPrivacyManager;
    @Inject Lazy<AutoHideController> mAutoHideController;
    @Inject Lazy<ForegroundServiceNotificationListener> mForegroundServiceNotificationListener;
    @Inject Lazy<PrivacyItemController> mPrivacyItemController;
    @Inject @Named(BG_LOOPER_NAME) Lazy<Looper> mBgLooper;
    @Inject @Named(BG_HANDLER_NAME) Lazy<Handler> mBgHandler;
    @Inject @Named(MAIN_LOOPER_NAME) Lazy<Looper> mMainLooper;
    @Inject @Named(MAIN_HANDLER_NAME) Lazy<Handler> mMainHandler;
    @Inject @Named(TIME_TICK_HANDLER_NAME) Lazy<Handler> mTimeTickHandler;
    @Nullable
    @Inject @Named(LEAK_REPORT_EMAIL_NAME) Lazy<String> mLeakReportEmail;
    @Inject Lazy<ClockManager> mClockManager;
    @Inject Lazy<ActivityManagerWrapper> mActivityManagerWrapper;
    @Inject Lazy<DevicePolicyManagerWrapper> mDevicePolicyManagerWrapper;
    @Inject Lazy<PackageManagerWrapper> mPackageManagerWrapper;
    @Inject Lazy<SensorPrivacyController> mSensorPrivacyController;
    @Inject Lazy<DumpController> mDumpController;
    @Inject Lazy<DockManager> mDockManager;
    @Inject Lazy<ChannelEditorDialogController> mChannelEditorDialogController;
    @Inject Lazy<INotificationManager> mINotificationManager;
    @Inject Lazy<FalsingManager> mFalsingManager;
    @Inject Lazy<SysUiState> mSysUiStateFlagsContainer;
    @Inject Lazy<AlarmManager> mAlarmManager;

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
        mProviders.put(BG_HANDLER, mBgHandler::get);
        mProviders.put(MAIN_LOOPER, mMainLooper::get);
        mProviders.put(MAIN_HANDLER, mMainHandler::get);
        mProviders.put(ActivityStarter.class, mActivityStarter::get);
        mProviders.put(ActivityStarterDelegate.class, mActivityStarterDelegate::get);
        mProviders.put(BroadcastDispatcher.class, mBroadcastDispatcher::get);

        mProviders.put(AsyncSensorManager.class, mAsyncSensorManager::get);

        mProviders.put(BluetoothController.class, mBluetoothController::get);
        mProviders.put(SensorPrivacyManager.class, mSensorPrivacyManager::get);

        mProviders.put(LocationController.class, mLocationController::get);

        mProviders.put(RotationLockController.class, mRotationLockController::get);

        mProviders.put(NetworkController.class, mNetworkController::get);

        mProviders.put(ZenModeController.class, mZenModeController::get);

        mProviders.put(HotspotController.class, mHotspotController::get);

        mProviders.put(CastController.class, mCastController::get);

        mProviders.put(FlashlightController.class, mFlashlightController::get);

        mProviders.put(KeyguardStateController.class, mKeyguardMonitor::get);

        mProviders.put(KeyguardUpdateMonitor.class, mKeyguardUpdateMonitor::get);

        mProviders.put(UserSwitcherController.class, mUserSwitcherController::get);

        mProviders.put(UserInfoController.class, mUserInfoController::get);

        mProviders.put(BatteryController.class, mBatteryController::get);

        mProviders.put(NightDisplayListener.class, mNightDisplayListener::get);

        mProviders.put(ManagedProfileController.class, mManagedProfileController::get);

        mProviders.put(NextAlarmController.class, mNextAlarmController::get);

        mProviders.put(DataSaverController.class, mDataSaverController::get);

        mProviders.put(AccessibilityController.class, mAccessibilityController::get);

        mProviders.put(DeviceProvisionedController.class, mDeviceProvisionedController::get);

        mProviders.put(PluginManager.class, mPluginManager::get);

        mProviders.put(AssistManager.class, mAssistManager::get);

        mProviders.put(SecurityController.class, mSecurityController::get);

        mProviders.put(LeakDetector.class, mLeakDetector::get);

        mProviders.put(LEAK_REPORT_EMAIL, mLeakReportEmail::get);

        mProviders.put(LeakReporter.class, mLeakReporter::get);

        mProviders.put(GarbageMonitor.class, mGarbageMonitor::get);

        mProviders.put(TunerService.class, mTunerService::get);

        mProviders.put(StatusBarWindowController.class, mStatusBarWindowController::get);

        mProviders.put(DarkIconDispatcher.class, mDarkIconDispatcher::get);

        mProviders.put(ConfigurationController.class, mConfigurationController::get);

        mProviders.put(StatusBarIconController.class, mStatusBarIconController::get);

        mProviders.put(ScreenLifecycle.class, mScreenLifecycle::get);

        mProviders.put(WakefulnessLifecycle.class, mWakefulnessLifecycle::get);

        mProviders.put(FragmentService.class, mFragmentService::get);

        mProviders.put(ExtensionController.class, mExtensionController::get);

        mProviders.put(PluginDependencyProvider.class, mPluginDependencyProvider::get);

        mProviders.put(LocalBluetoothManager.class, mLocalBluetoothManager::get);

        mProviders.put(VolumeDialogController.class, mVolumeDialogController::get);

        mProviders.put(MetricsLogger.class, mMetricsLogger::get);

        mProviders.put(AccessibilityManagerWrapper.class, mAccessibilityManagerWrapper::get);

        mProviders.put(SysuiColorExtractor.class, mSysuiColorExtractor::get);

        mProviders.put(TunablePaddingService.class, mTunablePaddingService::get);

        mProviders.put(ForegroundServiceController.class, mForegroundServiceController::get);

        mProviders.put(UiOffloadThread.class, mUiOffloadThread::get);

        mProviders.put(PowerUI.WarningsUI.class, mWarningsUI::get);

        mProviders.put(LightBarController.class, mLightBarController::get);

        mProviders.put(IWindowManager.class, mIWindowManager::get);

        mProviders.put(OverviewProxyService.class, mOverviewProxyService::get);

        mProviders.put(NavigationModeController.class, mNavBarModeController::get);

        mProviders.put(EnhancedEstimates.class, mEnhancedEstimates::get);

        mProviders.put(VibratorHelper.class, mVibratorHelper::get);

        mProviders.put(IStatusBarService.class, mIStatusBarService::get);

        mProviders.put(DisplayMetrics.class, mDisplayMetrics::get);

        mProviders.put(LockscreenGestureLogger.class, mLockscreenGestureLogger::get);

        mProviders.put(KeyguardEnvironment.class, mKeyguardEnvironment::get);
        mProviders.put(ShadeController.class, mShadeController::get);
        mProviders.put(NotificationRemoteInputManager.Callback.class,
                mNotificationRemoteInputManagerCallback::get);

        mProviders.put(InitController.class, mInitController::get);

        mProviders.put(AppOpsController.class, mAppOpsController::get);

        mProviders.put(NavigationBarController.class, mNavigationBarController::get);

        mProviders.put(StatusBarStateController.class, mStatusBarStateController::get);
        mProviders.put(NotificationLockscreenUserManager.class,
                mNotificationLockscreenUserManager::get);
        mProviders.put(VisualStabilityManager.class, mVisualStabilityManager::get);
        mProviders.put(NotificationGroupManager.class, mNotificationGroupManager::get);
        mProviders.put(NotificationGroupAlertTransferHelper.class,
                mNotificationGroupAlertTransferHelper::get);
        mProviders.put(NotificationMediaManager.class, mNotificationMediaManager::get);
        mProviders.put(NotificationGutsManager.class, mNotificationGutsManager::get);
        mProviders.put(NotificationBlockingHelperManager.class,
                mNotificationBlockingHelperManager::get);
        mProviders.put(NotificationRemoteInputManager.class,
                mNotificationRemoteInputManager::get);
        mProviders.put(SmartReplyConstants.class, mSmartReplyConstants::get);
        mProviders.put(NotificationListener.class, mNotificationListener::get);
        mProviders.put(NotificationLogger.class, mNotificationLogger::get);
        mProviders.put(NotificationViewHierarchyManager.class,
                mNotificationViewHierarchyManager::get);
        mProviders.put(NotificationFilter.class, mNotificationFilter::get);
        mProviders.put(NotificationInterruptionStateProvider.class,
                mNotificationInterruptionStateProvider::get);
        mProviders.put(KeyguardDismissUtil.class, mKeyguardDismissUtil::get);
        mProviders.put(SmartReplyController.class, mSmartReplyController::get);
        mProviders.put(RemoteInputQuickSettingsDisabler.class,
                mRemoteInputQuickSettingsDisabler::get);
        mProviders.put(BubbleController.class, mBubbleController::get);
        mProviders.put(NotificationEntryManager.class, mNotificationEntryManager::get);
        mProviders.put(NotificationAlertingManager.class, mNotificationAlertingManager::get);
        mProviders.put(ForegroundServiceNotificationListener.class,
                mForegroundServiceNotificationListener::get);
        mProviders.put(ClockManager.class, mClockManager::get);
        mProviders.put(PrivacyItemController.class, mPrivacyItemController::get);
        mProviders.put(ActivityManagerWrapper.class, mActivityManagerWrapper::get);
        mProviders.put(DevicePolicyManagerWrapper.class, mDevicePolicyManagerWrapper::get);
        mProviders.put(PackageManagerWrapper.class, mPackageManagerWrapper::get);
        mProviders.put(SensorPrivacyController.class, mSensorPrivacyController::get);
        mProviders.put(DumpController.class, mDumpController::get);
        mProviders.put(DockManager.class, mDockManager::get);
        mProviders.put(ChannelEditorDialogController.class, mChannelEditorDialogController::get);
        mProviders.put(INotificationManager.class, mINotificationManager::get);
        mProviders.put(FalsingManager.class, mFalsingManager::get);
        mProviders.put(SysUiState.class, mSysUiStateFlagsContainer::get);
        mProviders.put(AlarmManager.class, mAlarmManager::get);

        // TODO(b/118592525): to support multi-display , we start to add something which is
        //                    per-display, while others may be global. I think it's time to add
        //                    a new class maybe named DisplayDependency to solve per-display
        //                    Dependency problem.
        mProviders.put(AutoHideController.class, mAutoHideController::get);

        sDependency = this;
    }

    static void staticDump(FileDescriptor fd, PrintWriter pw, String[] args) {
        sDependency.dump(fd, pw, args);
    }

    /**
     * {@see SystemUI.dump}
     */
    public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        // Make sure that the DumpController gets added to mDependencies, as they are only added
        // with Dependency#get.
        getDependency(DumpController.class);
        getDependency(BroadcastDispatcher.class);

        // If an arg is specified, try to dump the dependency
        String controller = args != null && args.length > 1
                ? args[1].toLowerCase()
                : null;
        if (controller != null) {
            pw.println("Dumping controller=" + controller + ":");
        } else {
            pw.println("Dumping existing controllers:");
        }
        mDependencies.values().stream()
                .filter(obj -> obj instanceof Dumpable && (controller == null
                        || obj.getClass().getName().toLowerCase().endsWith(controller)))
                .forEach(o -> ((Dumpable) o).dump(fd, pw, args));
    }

    protected static void staticOnConfigurationChanged(Configuration newConfig) {
        sDependency.onConfigurationChanged(newConfig);
    }

    protected synchronized void onConfigurationChanged(Configuration newConfig) {
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

    @Subcomponent
    public interface DependencyInjector {
        void createSystemUI(Dependency dependency);
    }
}
