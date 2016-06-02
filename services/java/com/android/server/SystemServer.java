/*
 * Copyright (C) 2006 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.server;

import android.app.ActivityThread;
import android.app.INotificationManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources.Theme;
import android.database.ContentObserver;
import android.os.BaseBundle;
import android.os.Build;
import android.os.Environment;
import android.os.FactoryTest;
import android.os.FileUtils;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.IMountService;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Slog;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.app.NightDisplayController;
import com.android.internal.os.BinderInternal;
import com.android.internal.os.SamplingProfilerIntegration;
import com.android.internal.os.ZygoteInit;
import com.android.internal.policy.EmergencyAffordanceManager;
import com.android.internal.widget.ILockSettings;
import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.am.ActivityManagerService;
import com.android.server.audio.AudioService;
import com.android.server.camera.CameraService;
import com.android.server.clipboard.ClipboardService;
import com.android.server.connectivity.IpConnectivityMetrics;
import com.android.server.connectivity.MetricsLoggerService;
import com.android.server.devicepolicy.DevicePolicyManagerService;
import com.android.server.display.DisplayManagerService;
import com.android.server.display.NightDisplayService;
import com.android.server.dreams.DreamManagerService;
import com.android.server.emergency.EmergencyAffordanceService;
import com.android.server.fingerprint.FingerprintService;
import com.android.server.hdmi.HdmiControlService;
import com.android.server.input.InputManagerService;
import com.android.server.job.JobSchedulerService;
import com.android.server.lights.LightsService;
import com.android.server.media.MediaResourceMonitorService;
import com.android.server.media.MediaRouterService;
import com.android.server.media.MediaSessionService;
import com.android.server.media.projection.MediaProjectionManagerService;
import com.android.server.net.NetworkPolicyManagerService;
import com.android.server.net.NetworkStatsService;
import com.android.server.notification.NotificationManagerService;
import com.android.server.om.OverlayManagerService;
import com.android.server.os.SchedulingPolicyService;
import com.android.server.pm.BackgroundDexOptService;
import com.android.server.pm.Installer;
import com.android.server.pm.LauncherAppsService;
import com.android.server.pm.OtaDexoptService;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.ShortcutService;
import com.android.server.pm.UserManagerService;
import com.android.server.power.PowerManagerService;
import com.android.server.power.ShutdownThread;
import com.android.server.restrictions.RestrictionsManagerService;
import com.android.server.retaildemo.RetailDemoModeService;
import com.android.server.soundtrigger.SoundTriggerService;
import com.android.server.statusbar.StatusBarManagerService;
import com.android.server.storage.DeviceStorageMonitorService;
import com.android.server.telecom.TelecomLoaderService;
import com.android.server.trust.TrustManagerService;
import com.android.server.tv.TvRemoteService;
import com.android.server.tv.TvInputManagerService;
import com.android.server.twilight.TwilightService;
import com.android.server.usage.UsageStatsService;
import com.android.server.vr.VrManagerService;
import com.android.server.webkit.WebViewUpdateService;
import com.android.server.wm.WindowManagerService;

import dalvik.system.VMRuntime;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public final class SystemServer {
    private static final String TAG = "SystemServer";

    private static final String ENCRYPTING_STATE = "trigger_restart_min_framework";
    private static final String ENCRYPTED_STATE = "1";

    private static final long SNAPSHOT_INTERVAL = 60 * 60 * 1000; // 1hr

    // The earliest supported time.  We pick one day into 1970, to
    // give any timezone code room without going into negative time.
    private static final long EARLIEST_SUPPORTED_TIME = 86400 * 1000;

    /*
     * Implementation class names. TODO: Move them to a codegen class or load
     * them from the build system somehow.
     */
    private static final String BACKUP_MANAGER_SERVICE_CLASS =
            "com.android.server.backup.BackupManagerService$Lifecycle";
    private static final String APPWIDGET_SERVICE_CLASS =
            "com.android.server.appwidget.AppWidgetService";
    private static final String VOICE_RECOGNITION_MANAGER_SERVICE_CLASS =
            "com.android.server.voiceinteraction.VoiceInteractionManagerService";
    private static final String PRINT_MANAGER_SERVICE_CLASS =
            "com.android.server.print.PrintManagerService";
    private static final String USB_SERVICE_CLASS =
            "com.android.server.usb.UsbService$Lifecycle";
    private static final String MIDI_SERVICE_CLASS =
            "com.android.server.midi.MidiService$Lifecycle";
    private static final String WIFI_SERVICE_CLASS =
            "com.android.server.wifi.WifiService";
    private static final String WIFI_NAN_SERVICE_CLASS =
            "com.android.server.wifi.nan.WifiNanService";
    private static final String WIFI_P2P_SERVICE_CLASS =
            "com.android.server.wifi.p2p.WifiP2pService";
    private static final String ETHERNET_SERVICE_CLASS =
            "com.android.server.ethernet.EthernetService";
    private static final String JOB_SCHEDULER_SERVICE_CLASS =
            "com.android.server.job.JobSchedulerService";
    private static final String LOCK_SETTINGS_SERVICE_CLASS =
            "com.android.server.LockSettingsService$Lifecycle";
    private static final String MOUNT_SERVICE_CLASS =
            "com.android.server.MountService$Lifecycle";
    private static final String SEARCH_MANAGER_SERVICE_CLASS =
            "com.android.server.search.SearchManagerService$Lifecycle";
    private static final String THERMAL_OBSERVER_CLASS =
            "com.google.android.clockwork.ThermalObserver";
    private static final String WEAR_BLUETOOTH_SERVICE_CLASS =
            "com.google.android.clockwork.bluetooth.WearBluetoothService";
    private static final String WEAR_WIFI_MEDIATOR_SERVICE_CLASS =
            "com.google.android.clockwork.wifi.WearWifiMediatorService";
    private static final String WEAR_CELLULAR_MEDIATOR_SERVICE_CLASS =
            "com.google.android.clockwork.cellular.WearCellularMediatorService";
    private static final String WEAR_TIME_SERVICE_CLASS =
            "com.google.android.clockwork.time.WearTimeService";
    private static final String ACCOUNT_SERVICE_CLASS =
            "com.android.server.accounts.AccountManagerService$Lifecycle";
    private static final String CONTENT_SERVICE_CLASS =
            "com.android.server.content.ContentService$Lifecycle";
    private static final String WALLPAPER_SERVICE_CLASS =
            "com.android.server.wallpaper.WallpaperManagerService$Lifecycle";

    private static final String PERSISTENT_DATA_BLOCK_PROP = "ro.frp.pst";

    private static final String UNCRYPT_PACKAGE_FILE = "/cache/recovery/uncrypt_file";
    private static final String BLOCK_MAP_FILE = "/cache/recovery/block.map";

    // maximum number of binder threads used for system_server
    // will be higher than the system default
    private static final int sMaxBinderThreads = 31;

    /**
     * Default theme used by the system context. This is used to style
     * system-provided dialogs, such as the Power Off dialog, and other
     * visual content.
     */
    private static final int DEFAULT_SYSTEM_THEME =
            com.android.internal.R.style.Theme_DeviceDefault_System;

    private final int mFactoryTestMode;
    private Timer mProfilerSnapshotTimer;

    private Context mSystemContext;
    private SystemServiceManager mSystemServiceManager;

    // TODO: remove all of these references by improving dependency resolution and boot phases
    private PowerManagerService mPowerManagerService;
    private ActivityManagerService mActivityManagerService;
    private WebViewUpdateService mWebViewUpdateService;
    private DisplayManagerService mDisplayManagerService;
    private PackageManagerService mPackageManagerService;
    private PackageManager mPackageManager;
    private ContentResolver mContentResolver;
    private EntropyMixer mEntropyMixer;

    private boolean mOnlyCore;
    private boolean mFirstBoot;
    private final boolean mRuntimeRestart;

    /**
     * Start the sensor service.
     */
    private static native void startSensorService();

    /**
     * The main entry point from zygote.
     */
    public static void main(String[] args) {
        new SystemServer().run();
    }

    public SystemServer() {
        // Check for factory test mode.
        mFactoryTestMode = FactoryTest.getMode();
        // Remember if it's runtime restart(when sys.boot_completed is already set) or reboot
        mRuntimeRestart = "1".equals(SystemProperties.get("sys.boot_completed"));
    }

    private class AdbPortObserver extends ContentObserver {
        public AdbPortObserver() {
            super(null);
        }
        @Override
        public void onChange(boolean selfChange) {
            int adbPort = Settings.Secure.getInt(mContentResolver,
                Settings.Secure.ADB_PORT, 0);
            // setting this will control whether ADB runs on TCP/IP or USB
            SystemProperties.set("adb.network.port", Integer.toString(adbPort));
        }
    }

    private void run() {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "InitBeforeStartServices");
            // If a device's clock is before 1970 (before 0), a lot of
            // APIs crash dealing with negative numbers, notably
            // java.io.File#setLastModified, so instead we fake it and
            // hope that time from cell towers or NTP fixes it shortly.
            if (System.currentTimeMillis() < EARLIEST_SUPPORTED_TIME) {
                Slog.w(TAG, "System clock is before 1970; setting to 1970.");
                SystemClock.setCurrentTimeMillis(EARLIEST_SUPPORTED_TIME);
            }

            // If the system has "persist.sys.language" and friends set, replace them with
            // "persist.sys.locale". Note that the default locale at this point is calculated
            // using the "-Duser.locale" command line flag. That flag is usually populated by
            // AndroidRuntime using the same set of system properties, but only the system_server
            // and system apps are allowed to set them.
            //
            // NOTE: Most changes made here will need an equivalent change to
            // core/jni/AndroidRuntime.cpp
            if (!SystemProperties.get("persist.sys.language").isEmpty()) {
                final String languageTag = Locale.getDefault().toLanguageTag();

                SystemProperties.set("persist.sys.locale", languageTag);
                SystemProperties.set("persist.sys.language", "");
                SystemProperties.set("persist.sys.country", "");
                SystemProperties.set("persist.sys.localevar", "");
            }

            // Here we go!
            Slog.i(TAG, "Entered the Android system server!");
            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_SYSTEM_RUN, SystemClock.uptimeMillis());

            // In case the runtime switched since last boot (such as when
            // the old runtime was removed in an OTA), set the system
            // property so that it is in sync. We can't do this in
            // libnativehelper's JniInvocation::Init code where we already
            // had to fallback to a different runtime because it is
            // running as root and we need to be the system user to set
            // the property. http://b/11463182
            SystemProperties.set("persist.sys.dalvik.vm.lib.2", VMRuntime.getRuntime().vmLibrary());

            // Enable the sampling profiler.
            if (SamplingProfilerIntegration.isEnabled()) {
                SamplingProfilerIntegration.start();
                mProfilerSnapshotTimer = new Timer();
                mProfilerSnapshotTimer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            SamplingProfilerIntegration.writeSnapshot("system_server", null);
                        }
                    }, SNAPSHOT_INTERVAL, SNAPSHOT_INTERVAL);
            }

            // Mmmmmm... more memory!
            VMRuntime.getRuntime().clearGrowthLimit();

            // The system server has to run all of the time, so it needs to be
            // as efficient as possible with its memory usage.
            VMRuntime.getRuntime().setTargetHeapUtilization(0.8f);

            // Some devices rely on runtime fingerprint generation, so make sure
            // we've defined it before booting further.
            Build.ensureFingerprintProperty();

            // Within the system server, it is an error to access Environment paths without
            // explicitly specifying a user.
            Environment.setUserRequired(true);

            // Within the system server, any incoming Bundles should be defused
            // to avoid throwing BadParcelableException.
            BaseBundle.setShouldDefuse(true);

            // Ensure binder calls into the system always run at foreground priority.
            BinderInternal.disableBackgroundScheduling(true);

            // Increase the number of binder threads in system_server
            BinderInternal.setMaxThreads(sMaxBinderThreads);

            // Prepare the main looper thread (this thread).
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_FOREGROUND);
            android.os.Process.setCanSelfBackground(false);
            Looper.prepareMainLooper();

            // Initialize native services.
            System.loadLibrary("android_servers");

            // Check whether we failed to shut down last time we tried.
            // This call may not return.
            performPendingShutdown();

            // Initialize the system context.
            createSystemContext();

            // Create the system service manager.
            mSystemServiceManager = new SystemServiceManager(mSystemContext);
            mSystemServiceManager.setRuntimeRestarted(mRuntimeRestart);
            LocalServices.addService(SystemServiceManager.class, mSystemServiceManager);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }

        // Start services.
        try {
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "StartServices");
            startBootstrapServices();
            startCoreServices();
            startOtherServices();
        } catch (Throwable ex) {
            Slog.e("System", "******************************************");
            Slog.e("System", "************ Failure starting system services", ex);
            throw ex;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }

        // For debug builds, log event loop stalls to dropbox for analysis.
        if (StrictMode.conditionallyEnableDebugLogging()) {
            Slog.i(TAG, "Enabled StrictMode for system server main thread.");
        }

        // Loop forever.
        Looper.loop();
        throw new RuntimeException("Main thread loop unexpectedly exited");
    }

    private void reportWtf(String msg, Throwable e) {
        Slog.w(TAG, "***********************************************");
        Slog.wtf(TAG, "BOOT FAILURE " + msg, e);
    }

    private void performPendingShutdown() {
        final String shutdownAction = SystemProperties.get(
                ShutdownThread.SHUTDOWN_ACTION_PROPERTY, "");
        if (shutdownAction != null && shutdownAction.length() > 0) {
            boolean reboot = (shutdownAction.charAt(0) == '1');

            final String reason;
            if (shutdownAction.length() > 1) {
                reason = shutdownAction.substring(1, shutdownAction.length());
            } else {
                reason = null;
            }

            // If it's a pending reboot into recovery to apply an update,
            // always make sure uncrypt gets executed properly when needed.
            // If '/cache/recovery/block.map' hasn't been created, stop the
            // reboot which will fail for sure, and get a chance to capture a
            // bugreport when that's still feasible. (Bug: 26444951)
            if (PowerManager.REBOOT_RECOVERY_UPDATE.equals(reason)) {
                File packageFile = new File(UNCRYPT_PACKAGE_FILE);
                if (packageFile.exists()) {
                    String filename = null;
                    try {
                        filename = FileUtils.readTextFile(packageFile, 0, null);
                    } catch (IOException e) {
                        Slog.e(TAG, "Error reading uncrypt package file", e);
                    }

                    if (filename != null && filename.startsWith("/data")) {
                        if (!new File(BLOCK_MAP_FILE).exists()) {
                            Slog.e(TAG, "Can't find block map file, uncrypt failed or " +
                                       "unexpected runtime restart?");
                            return;
                        }
                    }
                }
            }
            ShutdownThread.rebootOrShutdown(null, reboot, reason);
        }
    }

    private void createSystemContext() {
        ActivityThread activityThread = ActivityThread.systemMain();
        mSystemContext = activityThread.getSystemContext();
        mSystemContext.setTheme(DEFAULT_SYSTEM_THEME);
    }

    /**
     * Starts the small tangle of critical services that are needed to get
     * the system off the ground.  These services have complex mutual dependencies
     * which is why we initialize them all in one place here.  Unless your service
     * is also entwined in these dependencies, it should be initialized in one of
     * the other functions.
     */
    private void startBootstrapServices() {
        // Wait for installd to finish starting up so that it has a chance to
        // create critical directories such as /data/user with the appropriate
        // permissions.  We need this to complete before we initialize other services.
        Installer installer = mSystemServiceManager.startService(Installer.class);

        // Activity manager runs the show.
        mActivityManagerService = mSystemServiceManager.startService(
                ActivityManagerService.Lifecycle.class).getService();
        mActivityManagerService.setSystemServiceManager(mSystemServiceManager);
        mActivityManagerService.setInstaller(installer);

        // Power manager needs to be started early because other services need it.
        // Native daemons may be watching for it to be registered so it must be ready
        // to handle incoming binder calls immediately (including being able to verify
        // the permissions for those calls).
        mPowerManagerService = mSystemServiceManager.startService(PowerManagerService.class);

        // Now that the power manager has been started, let the activity manager
        // initialize power management features.
        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "InitPowerManagement");
        mActivityManagerService.initPowerManagement();
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

        // Manages LEDs and display backlight so we need it to bring up the display.
        mSystemServiceManager.startService(LightsService.class);

        // Display manager is needed to provide display metrics before package manager
        // starts up.
        mDisplayManagerService = mSystemServiceManager.startService(DisplayManagerService.class);

        // We need the default display before we can initialize the package manager.
        mSystemServiceManager.startBootPhase(SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);

        // Only run "core" apps if we're encrypting the device.
        String cryptState = SystemProperties.get("vold.decrypt");
        if (ENCRYPTING_STATE.equals(cryptState)) {
            Slog.w(TAG, "Detected encryption in progress - only parsing core apps");
            mOnlyCore = true;
        } else if (ENCRYPTED_STATE.equals(cryptState)) {
            Slog.w(TAG, "Device encrypted - only parsing core apps");
            mOnlyCore = true;
        }

        // Start the package manager.
        traceBeginAndSlog("StartPackageManagerService");
        mPackageManagerService = PackageManagerService.main(mSystemContext, installer,
                mFactoryTestMode != FactoryTest.FACTORY_TEST_OFF, mOnlyCore);
        mFirstBoot = mPackageManagerService.isFirstBoot();
        mPackageManager = mSystemContext.getPackageManager();
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

        // Manages A/B OTA dexopting. This is a bootstrap service as we need it to rename
        // A/B artifacts after boot, before anything else might touch/need them.
        // Note: this isn't needed during decryption (we don't have /data anyways).
        if (!mOnlyCore) {
            boolean disableOtaDexopt = SystemProperties.getBoolean("config.disable_otadexopt",
                    false);
            if (!disableOtaDexopt) {
                traceBeginAndSlog("StartOtaDexOptService");
                try {
                    OtaDexoptService.main(mSystemContext, mPackageManagerService);
                } catch (Throwable e) {
                    reportWtf("starting OtaDexOptService", e);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
                }
            }
        }

        traceBeginAndSlog("StartUserManagerService");
        mSystemServiceManager.startService(UserManagerService.LifeCycle.class);
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

        // Initialize attribute cache used to cache resources from packages.
        AttributeCache.init(mSystemContext);

        // Set up the Application instance for the system process and get started.
        mActivityManagerService.setSystemProcess();

        // Manages Overlay packages
        mSystemServiceManager.startService(new OverlayManagerService(mSystemContext, installer));

        // The sensor service needs access to package manager service, app ops
        // service, and permissions service, therefore we start it after them.
        startSensorService();
    }

    /**
     * Starts some essential services that are not tangled up in the bootstrap process.
     */
    private void startCoreServices() {
        // Tracks the battery level.  Requires LightService.
        mSystemServiceManager.startService(BatteryService.class);

        // Tracks application usage stats.
        mSystemServiceManager.startService(UsageStatsService.class);
        mActivityManagerService.setUsageStatsManager(
                LocalServices.getService(UsageStatsManagerInternal.class));

        // Tracks whether the updatable WebView is in a ready state and watches for update installs.
        mWebViewUpdateService = mSystemServiceManager.startService(WebViewUpdateService.class);
    }

    /**
     * Starts a miscellaneous grab bag of stuff that has yet to be refactored
     * and organized.
     */
    private void startOtherServices() {
        final Context context = mSystemContext;
        VibratorService vibrator = null;
        IMountService mountService = null;
        NetworkManagementService networkManagement = null;
        NetworkStatsService networkStats = null;
        NetworkPolicyManagerService networkPolicy = null;
        ConnectivityService connectivity = null;
        NetworkScoreService networkScore = null;
        NsdService serviceDiscovery= null;
        WindowManagerService wm = null;
        SerialService serial = null;
        NetworkTimeUpdateService networkTimeUpdater = null;
        CommonTimeManagementService commonTimeMgmtService = null;
        InputManagerService inputManager = null;
        TelephonyRegistry telephonyRegistry = null;
        ConsumerIrService consumerIr = null;
        MmsServiceBroker mmsService = null;
        HardwarePropertiesManagerService hardwarePropertiesService = null;

        boolean disableStorage = SystemProperties.getBoolean("config.disable_storage", false);
        boolean disableBluetooth = SystemProperties.getBoolean("config.disable_bluetooth", false);
        boolean disableLocation = SystemProperties.getBoolean("config.disable_location", false);
        boolean disableSystemUI = SystemProperties.getBoolean("config.disable_systemui", false);
        boolean disableNonCoreServices = SystemProperties.getBoolean("config.disable_noncore", false);
        boolean disableNetwork = SystemProperties.getBoolean("config.disable_network", false);
        boolean disableNetworkTime = SystemProperties.getBoolean("config.disable_networktime", false);
        boolean disableRtt = SystemProperties.getBoolean("config.disable_rtt", false);
        boolean disableMediaProjection = SystemProperties.getBoolean("config.disable_mediaproj",
                false);
        boolean disableSerial = SystemProperties.getBoolean("config.disable_serial", false);
        boolean disableSearchManager = SystemProperties.getBoolean("config.disable_searchmanager",
                false);
        boolean disableTrustManager = SystemProperties.getBoolean("config.disable_trustmanager",
                false);
        boolean disableTextServices = SystemProperties.getBoolean("config.disable_textservices",
                false);
        boolean disableSamplingProfiler = SystemProperties.getBoolean("config.disable_samplingprof",
                false);
        boolean disableConsumerIr = SystemProperties.getBoolean("config.disable_consumerir", false);
        boolean disableVrManager = SystemProperties.getBoolean("config.disable_vrmanager", false);
        boolean disableCameraService = SystemProperties.getBoolean("config.disable_cameraservice",
                false);

        boolean isEmulator = SystemProperties.get("ro.kernel.qemu").equals("1");

        try {
            Slog.i(TAG, "Reading configuration...");
            SystemConfig.getInstance();

            traceBeginAndSlog("StartSchedulingPolicyService");
            ServiceManager.addService("scheduling_policy", new SchedulingPolicyService());
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

            mSystemServiceManager.startService(TelecomLoaderService.class);

            traceBeginAndSlog("StartTelephonyRegistry");
            telephonyRegistry = new TelephonyRegistry(context);
            ServiceManager.addService("telephony.registry", telephonyRegistry);
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

            traceBeginAndSlog("StartEntropyMixer");
            mEntropyMixer = new EntropyMixer(context);
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

            mContentResolver = context.getContentResolver();

            if (!disableCameraService) {
                Slog.i(TAG, "Camera Service");
                mSystemServiceManager.startService(CameraService.class);
            }

            // The AccountManager must come before the ContentService
            traceBeginAndSlog("StartAccountManagerService");
            mSystemServiceManager.startService(ACCOUNT_SERVICE_CLASS);
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

            traceBeginAndSlog("StartContentService");
            mSystemServiceManager.startService(CONTENT_SERVICE_CLASS);
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

            traceBeginAndSlog("InstallSystemProviders");
            mActivityManagerService.installSystemProviders();
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

            traceBeginAndSlog("StartVibratorService");
            vibrator = new VibratorService(context);
            ServiceManager.addService("vibrator", vibrator);
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

            if (!disableConsumerIr) {
                traceBeginAndSlog("StartConsumerIrService");
                consumerIr = new ConsumerIrService(context);
                ServiceManager.addService(Context.CONSUMER_IR_SERVICE, consumerIr);
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }

            traceBeginAndSlog("StartAlarmManagerService");
            mSystemServiceManager.startService(AlarmManagerService.class);
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

            traceBeginAndSlog("InitWatchdog");
            final Watchdog watchdog = Watchdog.getInstance();
            watchdog.init(context, mActivityManagerService);
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

            traceBeginAndSlog("StartInputManagerService");
            inputManager = new InputManagerService(context);
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

            traceBeginAndSlog("StartWindowManagerService");
            wm = WindowManagerService.main(context, inputManager,
                    mFactoryTestMode != FactoryTest.FACTORY_TEST_LOW_LEVEL,
                    !mFirstBoot, mOnlyCore);
            ServiceManager.addService(Context.WINDOW_SERVICE, wm);
            ServiceManager.addService(Context.INPUT_SERVICE, inputManager);
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

            if (!disableVrManager) {
                traceBeginAndSlog("StartVrManagerService");
                mSystemServiceManager.startService(VrManagerService.class);
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }

            mActivityManagerService.setWindowManager(wm);

            inputManager.setWindowManagerCallbacks(wm.getInputMonitor());
            inputManager.start();

            // TODO: Use service dependencies instead.
            mDisplayManagerService.windowManagerAndInputReady();

            // Skip Bluetooth if we have an emulator kernel
            // TODO: Use a more reliable check to see if this product should
            // support Bluetooth - see bug 988521
            if (isEmulator) {
                Slog.i(TAG, "No Bluetooth Service (emulator)");
            } else if (mFactoryTestMode == FactoryTest.FACTORY_TEST_LOW_LEVEL) {
                Slog.i(TAG, "No Bluetooth Service (factory test)");
            } else if (!context.getPackageManager().hasSystemFeature
                       (PackageManager.FEATURE_BLUETOOTH)) {
                Slog.i(TAG, "No Bluetooth Service (Bluetooth Hardware Not Present)");
            } else if (disableBluetooth) {
                Slog.i(TAG, "Bluetooth Service disabled by config");
            } else {
                mSystemServiceManager.startService(BluetoothService.class);
            }

            traceBeginAndSlog("ConnectivityMetricsLoggerService");
            mSystemServiceManager.startService(MetricsLoggerService.class);
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

            traceBeginAndSlog("IpConnectivityMetrics");
            mSystemServiceManager.startService(IpConnectivityMetrics.class);
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

            traceBeginAndSlog("PinnerService");
            mSystemServiceManager.startService(PinnerService.class);
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        } catch (RuntimeException e) {
            Slog.e("System", "******************************************");
            Slog.e("System", "************ Failure starting core service", e);
        }

        StatusBarManagerService statusBar = null;
        INotificationManager notification = null;
        LocationManagerService location = null;
        CountryDetectorService countryDetector = null;
        ILockSettings lockSettings = null;
        AssetAtlasService atlas = null;
        MediaRouterService mediaRouter = null;

        // Bring up services needed for UI.
        if (mFactoryTestMode != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
            mSystemServiceManager.startService(InputMethodManagerService.Lifecycle.class);

            traceBeginAndSlog("StartAccessibilityManagerService");
            try {
                ServiceManager.addService(Context.ACCESSIBILITY_SERVICE,
                        new AccessibilityManagerService(context));
            } catch (Throwable e) {
                reportWtf("starting Accessibility Manager", e);
            }
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }

        try {
            wm.displayReady();
        } catch (Throwable e) {
            reportWtf("making display ready", e);
        }

        if (mFactoryTestMode != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
            if (!disableStorage &&
                !"0".equals(SystemProperties.get("system_init.startmountservice"))) {
                try {
                    /*
                     * NotificationManagerService is dependant on MountService,
                     * (for media / usb notifications) so we must start MountService first.
                     */
                    mSystemServiceManager.startService(MOUNT_SERVICE_CLASS);
                    mountService = IMountService.Stub.asInterface(
                            ServiceManager.getService("mount"));
                } catch (Throwable e) {
                    reportWtf("starting Mount Service", e);
                }
            }
        }

        // We start this here so that we update our configuration to set watch or television
        // as appropriate.
        mSystemServiceManager.startService(UiModeManagerService.class);

        if (!mOnlyCore) {
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "UpdatePackagesIfNeeded");
            try {
                mPackageManagerService.updatePackagesIfNeeded();
            } catch (Throwable e) {
                reportWtf("update packages", e);
            }
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }

        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "PerformFstrimIfNeeded");
        try {
            mPackageManagerService.performFstrimIfNeeded();
        } catch (Throwable e) {
            reportWtf("performing fstrim", e);
        }
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

        if (mFactoryTestMode != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
            if (!disableNonCoreServices) {
                traceBeginAndSlog("StartLockSettingsService");
                try {
                    mSystemServiceManager.startService(LOCK_SETTINGS_SERVICE_CLASS);
                    lockSettings = ILockSettings.Stub.asInterface(
                            ServiceManager.getService("lock_settings"));
                } catch (Throwable e) {
                    reportWtf("starting LockSettingsService service", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

                if (!SystemProperties.get(PERSISTENT_DATA_BLOCK_PROP).equals("")) {
                    mSystemServiceManager.startService(PersistentDataBlockService.class);
                }

                mSystemServiceManager.startService(DeviceIdleController.class);

                // Always start the Device Policy Manager, so that the API is compatible with
                // API8.
                mSystemServiceManager.startService(DevicePolicyManagerService.Lifecycle.class);
            }

            if (!disableSystemUI) {
                traceBeginAndSlog("StartStatusBarManagerService");
                try {
                    statusBar = new StatusBarManagerService(context, wm);
                    ServiceManager.addService(Context.STATUS_BAR_SERVICE, statusBar);
                } catch (Throwable e) {
                    reportWtf("starting StatusBarManagerService", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }

            if (!disableNonCoreServices) {
                traceBeginAndSlog("StartClipboardService");
                try {
                    ServiceManager.addService(Context.CLIPBOARD_SERVICE,
                            new ClipboardService(context));
                } catch (Throwable e) {
                    reportWtf("starting Clipboard Service", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }

            if (!disableNetwork) {
                traceBeginAndSlog("StartNetworkManagementService");
                try {
                    networkManagement = NetworkManagementService.create(context);
                    ServiceManager.addService(Context.NETWORKMANAGEMENT_SERVICE, networkManagement);
                } catch (Throwable e) {
                    reportWtf("starting NetworkManagement Service", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }

            if (!disableNonCoreServices && !disableTextServices) {
                mSystemServiceManager.startService(TextServicesManagerService.Lifecycle.class);
            }

            if (!disableNetwork) {
                traceBeginAndSlog("StartNetworkScoreService");
                try {
                    networkScore = new NetworkScoreService(context);
                    ServiceManager.addService(Context.NETWORK_SCORE_SERVICE, networkScore);
                } catch (Throwable e) {
                    reportWtf("starting Network Score Service", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

                traceBeginAndSlog("StartNetworkStatsService");
                try {
                    networkStats = NetworkStatsService.create(context, networkManagement);
                    ServiceManager.addService(Context.NETWORK_STATS_SERVICE, networkStats);
                } catch (Throwable e) {
                    reportWtf("starting NetworkStats Service", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

                traceBeginAndSlog("StartNetworkPolicyManagerService");
                try {
                    networkPolicy = new NetworkPolicyManagerService(context,
                            mActivityManagerService, networkStats, networkManagement);
                    ServiceManager.addService(Context.NETWORK_POLICY_SERVICE, networkPolicy);
                } catch (Throwable e) {
                    reportWtf("starting NetworkPolicy Service", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

                if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_NAN)) {
                    mSystemServiceManager.startService(WIFI_NAN_SERVICE_CLASS);
                } else {
                    Slog.i(TAG, "No Wi-Fi NAN Service (NAN support Not Present)");
                }
                mSystemServiceManager.startService(WIFI_P2P_SERVICE_CLASS);
                mSystemServiceManager.startService(WIFI_SERVICE_CLASS);
                mSystemServiceManager.startService(
                            "com.android.server.wifi.scanner.WifiScanningService");

                if (!disableRtt) {
                    mSystemServiceManager.startService("com.android.server.wifi.RttService");
                }

                if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_ETHERNET) ||
                    mPackageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                    mSystemServiceManager.startService(ETHERNET_SERVICE_CLASS);
                }

                traceBeginAndSlog("StartConnectivityService");
                try {
                    connectivity = new ConnectivityService(
                            context, networkManagement, networkStats, networkPolicy);
                    ServiceManager.addService(Context.CONNECTIVITY_SERVICE, connectivity);
                    networkStats.bindConnectivityManager(connectivity);
                    networkPolicy.bindConnectivityManager(connectivity);
                } catch (Throwable e) {
                    reportWtf("starting Connectivity Service", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

                traceBeginAndSlog("StartNsdService");
                try {
                    serviceDiscovery = NsdService.create(context);
                    ServiceManager.addService(
                            Context.NSD_SERVICE, serviceDiscovery);
                } catch (Throwable e) {
                    reportWtf("starting Service Discovery Service", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }

            if (!disableNonCoreServices) {
                traceBeginAndSlog("StartUpdateLockService");
                try {
                    ServiceManager.addService(Context.UPDATE_LOCK_SERVICE,
                            new UpdateLockService(context));
                } catch (Throwable e) {
                    reportWtf("starting UpdateLockService", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }

            if (!disableNonCoreServices) {
                mSystemServiceManager.startService(RecoverySystemService.class);
            }

            /*
             * MountService has a few dependencies: Notification Manager and
             * AppWidget Provider. Make sure MountService is completely started
             * first before continuing.
             */
            if (mountService != null && !mOnlyCore) {
                Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "WaitForAsecScan");
                try {
                    mountService.waitForAsecScan();
                } catch (RemoteException ignored) {
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }

            mSystemServiceManager.startService(NotificationManagerService.class);
            notification = INotificationManager.Stub.asInterface(
                    ServiceManager.getService(Context.NOTIFICATION_SERVICE));
            networkPolicy.bindNotificationManager(notification);

            mSystemServiceManager.startService(DeviceStorageMonitorService.class);

            if (!disableLocation) {
                traceBeginAndSlog("StartLocationManagerService");
                try {
                    location = new LocationManagerService(context);
                    ServiceManager.addService(Context.LOCATION_SERVICE, location);
                } catch (Throwable e) {
                    reportWtf("starting Location Manager", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

                traceBeginAndSlog("StartCountryDetectorService");
                try {
                    countryDetector = new CountryDetectorService(context);
                    ServiceManager.addService(Context.COUNTRY_DETECTOR, countryDetector);
                } catch (Throwable e) {
                    reportWtf("starting Country Detector", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }

            if (!disableNonCoreServices && !disableSearchManager) {
                traceBeginAndSlog("StartSearchManagerService");
                try {
                    mSystemServiceManager.startService(SEARCH_MANAGER_SERVICE_CLASS);
                } catch (Throwable e) {
                    reportWtf("starting Search Service", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }

            mSystemServiceManager.startService(DropBoxManagerService.class);

            if (!disableNonCoreServices && context.getResources().getBoolean(
                        R.bool.config_enableWallpaperService)) {
                traceBeginAndSlog("StartWallpaperManagerService");
                mSystemServiceManager.startService(WALLPAPER_SERVICE_CLASS);
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }

            traceBeginAndSlog("StartAudioService");
            mSystemServiceManager.startService(AudioService.Lifecycle.class);
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

            if (!disableNonCoreServices) {
                mSystemServiceManager.startService(DockObserver.class);

                if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH)) {
                    mSystemServiceManager.startService(THERMAL_OBSERVER_CLASS);
                }
            }

            traceBeginAndSlog("StartWiredAccessoryManager");
            try {
                // Listen for wired headset changes
                inputManager.setWiredAccessoryCallbacks(
                        new WiredAccessoryManager(context, inputManager));
            } catch (Throwable e) {
                reportWtf("starting WiredAccessoryManager", e);
            }
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

            if (!disableNonCoreServices) {
                if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
                    // Start MIDI Manager service
                    mSystemServiceManager.startService(MIDI_SERVICE_CLASS);
                }

                if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
                        || mPackageManager.hasSystemFeature(
                                PackageManager.FEATURE_USB_ACCESSORY)) {
                    // Manage USB host and device support
                    Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "StartUsbService");
                    mSystemServiceManager.startService(USB_SERVICE_CLASS);
                    Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
                }

                if (!disableSerial) {
                    traceBeginAndSlog("StartSerialService");
                    try {
                        // Serial port support
                        serial = new SerialService(context);
                        ServiceManager.addService(Context.SERIAL_SERVICE, serial);
                    } catch (Throwable e) {
                        Slog.e(TAG, "Failure starting SerialService", e);
                    }
                    Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
                }

                Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER,
                        "StartHardwarePropertiesManagerService");
                try {
                    hardwarePropertiesService = new HardwarePropertiesManagerService(context);
                    ServiceManager.addService(Context.HARDWARE_PROPERTIES_SERVICE,
                            hardwarePropertiesService);
                } catch (Throwable e) {
                    Slog.e(TAG, "Failure starting HardwarePropertiesManagerService", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }

            mSystemServiceManager.startService(TwilightService.class);

            if (NightDisplayController.isAvailable(context)) {
                mSystemServiceManager.startService(NightDisplayService.class);
            }

            mSystemServiceManager.startService(JobSchedulerService.class);

            mSystemServiceManager.startService(SoundTriggerService.class);

            if (!disableNonCoreServices) {
                if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_BACKUP)) {
                    mSystemServiceManager.startService(BACKUP_MANAGER_SERVICE_CLASS);
                }

                if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_APP_WIDGETS)
                    || context.getResources().getBoolean(R.bool.config_enableAppWidgetService)) {
                    mSystemServiceManager.startService(APPWIDGET_SERVICE_CLASS);
                }

                if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_VOICE_RECOGNIZERS)) {
                    mSystemServiceManager.startService(VOICE_RECOGNITION_MANAGER_SERVICE_CLASS);
                }

                if (GestureLauncherService.isGestureLauncherEnabled(context.getResources())) {
                    Slog.i(TAG, "Gesture Launcher Service");
                    mSystemServiceManager.startService(GestureLauncherService.class);
                }
                mSystemServiceManager.startService(SensorNotificationService.class);
                mSystemServiceManager.startService(ContextHubSystemService.class);
            }

            traceBeginAndSlog("StartDiskStatsService");
            try {
                ServiceManager.addService("diskstats", new DiskStatsService(context));
            } catch (Throwable e) {
                reportWtf("starting DiskStats Service", e);
            }
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

            if (!disableSamplingProfiler) {
                traceBeginAndSlog("StartSamplingProfilerService");
                try {
                    // need to add this service even if SamplingProfilerIntegration.isEnabled()
                    // is false, because it is this service that detects system property change and
                    // turns on SamplingProfilerIntegration. Plus, when sampling profiler doesn't work,
                    // there is little overhead for running this service.
                    ServiceManager.addService("samplingprofiler",
                                new SamplingProfilerService(context));
                } catch (Throwable e) {
                    reportWtf("starting SamplingProfiler Service", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }

            if (!disableNetwork && !disableNetworkTime) {
                traceBeginAndSlog("StartNetworkTimeUpdateService");
                try {
                    networkTimeUpdater = new NetworkTimeUpdateService(context);
                    ServiceManager.addService("network_time_update_service", networkTimeUpdater);
                } catch (Throwable e) {
                    reportWtf("starting NetworkTimeUpdate service", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }

            traceBeginAndSlog("StartCommonTimeManagementService");
            try {
                commonTimeMgmtService = new CommonTimeManagementService(context);
                ServiceManager.addService("commontime_management", commonTimeMgmtService);
            } catch (Throwable e) {
                reportWtf("starting CommonTimeManagementService service", e);
            }
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

            if (!disableNetwork) {
                traceBeginAndSlog("CertBlacklister");
                try {
                    CertBlacklister blacklister = new CertBlacklister(context);
                } catch (Throwable e) {
                    reportWtf("starting CertBlacklister", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }

            if (!disableNetwork && !disableNonCoreServices && EmergencyAffordanceManager.ENABLED) {
                // EmergencyMode sevice
                mSystemServiceManager.startService(EmergencyAffordanceService.class);
            }

            if (!disableNonCoreServices) {
                // Dreams (interactive idle-time views, a/k/a screen savers, and doze mode)
                mSystemServiceManager.startService(DreamManagerService.class);
            }

            if (!disableNonCoreServices && ZygoteInit.PRELOAD_RESOURCES) {
                traceBeginAndSlog("StartAssetAtlasService");
                try {
                    atlas = new AssetAtlasService(context);
                    ServiceManager.addService(AssetAtlasService.ASSET_ATLAS_SERVICE, atlas);
                } catch (Throwable e) {
                    reportWtf("starting AssetAtlasService", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }

            if (!disableNonCoreServices) {
                ServiceManager.addService(GraphicsStatsService.GRAPHICS_STATS_SERVICE,
                        new GraphicsStatsService(context));
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_PRINTING)) {
                mSystemServiceManager.startService(PRINT_MANAGER_SERVICE_CLASS);
            }

            mSystemServiceManager.startService(RestrictionsManagerService.class);

            mSystemServiceManager.startService(MediaSessionService.class);

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_HDMI_CEC)) {
                mSystemServiceManager.startService(HdmiControlService.class);
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_TV)) {
                mSystemServiceManager.startService(TvInputManagerService.class);
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                mSystemServiceManager.startService(MediaResourceMonitorService.class);
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                mSystemServiceManager.startService(TvRemoteService.class);
            }

            if (!disableNonCoreServices) {
                traceBeginAndSlog("StartMediaRouterService");
                try {
                    mediaRouter = new MediaRouterService(context);
                    ServiceManager.addService(Context.MEDIA_ROUTER_SERVICE, mediaRouter);
                } catch (Throwable e) {
                    reportWtf("starting MediaRouterService", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

                if (!disableTrustManager) {
                    mSystemServiceManager.startService(TrustManagerService.class);
                }

                if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
                    mSystemServiceManager.startService(FingerprintService.class);
                }

                traceBeginAndSlog("StartBackgroundDexOptService");
                try {
                    BackgroundDexOptService.schedule(context);
                } catch (Throwable e) {
                    reportWtf("starting BackgroundDexOptService", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }
            // LauncherAppsService uses ShortcutService.
            mSystemServiceManager.startService(ShortcutService.Lifecycle.class);

            mSystemServiceManager.startService(LauncherAppsService.class);
        }

        if (!disableNonCoreServices && !disableMediaProjection) {
            mSystemServiceManager.startService(MediaProjectionManagerService.class);
        }

        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH)) {
            mSystemServiceManager.startService(WEAR_BLUETOOTH_SERVICE_CLASS);
            mSystemServiceManager.startService(WEAR_WIFI_MEDIATOR_SERVICE_CLASS);
            if (SystemProperties.getBoolean("config.enable_cellmediator", false)) {
                mSystemServiceManager.startService(WEAR_CELLULAR_MEDIATOR_SERVICE_CLASS);
            }
          if (!disableNonCoreServices) {
              mSystemServiceManager.startService(WEAR_TIME_SERVICE_CLASS);
          }
        }

        // make sure the ADB_ENABLED setting value matches the secure property value
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ADB_PORT,
                Integer.parseInt(SystemProperties.get("service.adb.tcp.port", "-1")));

        // register observer to listen for settings changes
        mContentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ADB_PORT),
            false, new AdbPortObserver());

        // Before things start rolling, be sure we have decided whether
        // we are in safe mode.
        final boolean safeMode = wm.detectSafeMode();
        if (safeMode) {
            mActivityManagerService.enterSafeMode();
            // Disable the JIT for the system_server process
            VMRuntime.getRuntime().disableJitCompilation();
        } else {
            // Enable the JIT for the system_server process
            VMRuntime.getRuntime().startJitCompilation();
        }

        // MMS service broker
        mmsService = mSystemServiceManager.startService(MmsServiceBroker.class);

        if (Settings.Global.getInt(mContentResolver, Settings.Global.DEVICE_PROVISIONED, 0) == 0 ||
                UserManager.isDeviceInDemoMode(mSystemContext)) {
            mSystemServiceManager.startService(RetailDemoModeService.class);
        }

        // It is now time to start up the app processes...

        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "MakeVibratorServiceReady");
        try {
            vibrator.systemReady();
        } catch (Throwable e) {
            reportWtf("making Vibrator Service ready", e);
        }
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "MakeLockSettingsServiceReady");
        if (lockSettings != null) {
            try {
                lockSettings.systemReady();
            } catch (Throwable e) {
                reportWtf("making Lock Settings Service ready", e);
            }
        }
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

        // Needed by DevicePolicyManager for initialization
        mSystemServiceManager.startBootPhase(SystemService.PHASE_LOCK_SETTINGS_READY);

        mSystemServiceManager.startBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);

        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "MakeWindowManagerServiceReady");
        try {
            wm.systemReady();
        } catch (Throwable e) {
            reportWtf("making Window Manager Service ready", e);
        }
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

        if (safeMode) {
            mActivityManagerService.showSafeModeOverlay();
        }

        // Update the configuration for this context by hand, because we're going
        // to start using it before the config change done in wm.systemReady() will
        // propagate to it.
        Configuration config = wm.computeNewConfiguration();
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager w = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        w.getDefaultDisplay().getMetrics(metrics);
        context.getResources().updateConfiguration(config, metrics);

        // The system context's theme may be configuration-dependent.
        final Theme systemTheme = context.getTheme();
        if (systemTheme.getChangingConfigurations() != 0) {
            systemTheme.rebase();
        }

        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "MakePowerManagerServiceReady");
        try {
            // TODO: use boot phase
            mPowerManagerService.systemReady(mActivityManagerService.getAppOpsService());
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        } catch (Throwable e) {
            reportWtf("making Power Manager Service ready", e);
        }
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "MakePackageManagerServiceReady");
        try {
            mPackageManagerService.systemReady();
        } catch (Throwable e) {
            reportWtf("making Package Manager Service ready", e);
        }
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "MakeDisplayManagerServiceReady");
        try {
            // TODO: use boot phase and communicate these flags some other way
            mDisplayManagerService.systemReady(safeMode, mOnlyCore);
        } catch (Throwable e) {
            reportWtf("making Display Manager Service ready", e);
        }
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

        // These are needed to propagate to the runnable below.
        final NetworkManagementService networkManagementF = networkManagement;
        final NetworkStatsService networkStatsF = networkStats;
        final NetworkPolicyManagerService networkPolicyF = networkPolicy;
        final ConnectivityService connectivityF = connectivity;
        final NetworkScoreService networkScoreF = networkScore;
        final LocationManagerService locationF = location;
        final CountryDetectorService countryDetectorF = countryDetector;
        final NetworkTimeUpdateService networkTimeUpdaterF = networkTimeUpdater;
        final CommonTimeManagementService commonTimeMgmtServiceF = commonTimeMgmtService;
        final AssetAtlasService atlasF = atlas;
        final InputManagerService inputManagerF = inputManager;
        final TelephonyRegistry telephonyRegistryF = telephonyRegistry;
        final MediaRouterService mediaRouterF = mediaRouter;
        final MmsServiceBroker mmsServiceF = mmsService;

        // We now tell the activity manager it is okay to run third party
        // code.  It will call back into us once it has gotten to the state
        // where third party code can really run (but before it has actually
        // started launching the initial applications), for us to complete our
        // initialization.
        mActivityManagerService.systemReady(new Runnable() {
            @Override
            public void run() {
                Slog.i(TAG, "Making services ready");
                mSystemServiceManager.startBootPhase(
                        SystemService.PHASE_ACTIVITY_MANAGER_READY);
                Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "PhaseActivityManagerReady");

                Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "StartObservingNativeCrashes");
                try {
                    mActivityManagerService.startObservingNativeCrashes();
                } catch (Throwable e) {
                    reportWtf("observing native crashes", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

                if (!mOnlyCore) {
                    Slog.i(TAG, "WebViewFactory preparation");
                    Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "WebViewFactoryPreparation");
                    mWebViewUpdateService.prepareWebViewInSystemServer();
                    Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
                }

                Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "StartSystemUI");
                try {
                    startSystemUi(context);
                } catch (Throwable e) {
                    reportWtf("starting System UI", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
                Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "MakeNetworkScoreReady");
                try {
                    if (networkScoreF != null) networkScoreF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Network Score Service ready", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
                Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "MakeNetworkManagementServiceReady");
                try {
                    if (networkManagementF != null) networkManagementF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Network Managment Service ready", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
                Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "MakeNetworkStatsServiceReady");
                try {
                    if (networkStatsF != null) networkStatsF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Network Stats Service ready", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
                Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "MakeNetworkPolicyServiceReady");
                try {
                    if (networkPolicyF != null) networkPolicyF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Network Policy Service ready", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
                Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "MakeConnectivityServiceReady");
                try {
                    if (connectivityF != null) connectivityF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Connectivity Service ready", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);

                Watchdog.getInstance().start();

                // It is now okay to let the various system services start their
                // third party code...
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
                Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "PhaseThirdPartyAppsCanStart");
                mSystemServiceManager.startBootPhase(
                        SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

                try {
                    if (locationF != null) locationF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying Location Service running", e);
                }
                try {
                    if (countryDetectorF != null) countryDetectorF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying CountryDetectorService running", e);
                }
                try {
                    if (networkTimeUpdaterF != null) networkTimeUpdaterF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying NetworkTimeService running", e);
                }
                try {
                    if (commonTimeMgmtServiceF != null) {
                        commonTimeMgmtServiceF.systemRunning();
                    }
                } catch (Throwable e) {
                    reportWtf("Notifying CommonTimeManagementService running", e);
                }
                try {
                    if (atlasF != null) atlasF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying AssetAtlasService running", e);
                }
                try {
                    // TODO(BT) Pass parameter to input manager
                    if (inputManagerF != null) inputManagerF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying InputManagerService running", e);
                }
                try {
                    if (telephonyRegistryF != null) telephonyRegistryF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying TelephonyRegistry running", e);
                }
                try {
                    if (mediaRouterF != null) mediaRouterF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying MediaRouterService running", e);
                }

                try {
                    if (mmsServiceF != null) mmsServiceF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying MmsService running", e);
                }

                try {
                    if (networkScoreF != null) networkScoreF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying NetworkScoreService running", e);
                }
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }
        });
    }

    static final void startSystemUi(Context context) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.android.systemui",
                    "com.android.systemui.SystemUIService"));
        intent.addFlags(Intent.FLAG_DEBUG_TRIAGED_MISSING);
        //Slog.d(TAG, "Starting service: " + intent);
        context.startServiceAsUser(intent, UserHandle.SYSTEM);
    }

    private static void traceBeginAndSlog(String name) {
        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, name);
        Slog.i(TAG, name);
    }
}
