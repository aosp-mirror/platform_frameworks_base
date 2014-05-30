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

import android.app.ActivityManagerNative;
import android.app.ActivityThread;
import android.app.IAlarmManager;
import android.app.INotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.AudioService;
import android.net.wifi.p2p.WifiP2pService;
import android.os.Environment;
import android.os.FactoryTest;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.service.dreams.DreamService;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.os.BinderInternal;
import com.android.internal.os.SamplingProfilerIntegration;
import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.accounts.AccountManagerService;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.BatteryStatsService;
import com.android.server.clipboard.ClipboardService;
import com.android.server.content.ContentService;
import com.android.server.devicepolicy.DevicePolicyManagerService;
import com.android.server.display.DisplayManagerService;
import com.android.server.dreams.DreamManagerService;
import com.android.server.input.InputManagerService;
import com.android.server.lights.LightsManager;
import com.android.server.lights.LightsService;
import com.android.server.media.MediaRouterService;
import com.android.server.net.NetworkPolicyManagerService;
import com.android.server.net.NetworkStatsService;
import com.android.server.notification.NotificationManagerService;
import com.android.server.os.SchedulingPolicyService;
import com.android.server.pm.Installer;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.UserManagerService;
import com.android.server.power.PowerManagerService;
import com.android.server.power.ShutdownThread;
import com.android.server.search.SearchManagerService;
import com.android.server.statusbar.StatusBarManagerService;
import com.android.server.storage.DeviceStorageMonitorService;
import com.android.server.twilight.TwilightService;
import com.android.server.usb.UsbService;
import com.android.server.wallpaper.WallpaperManagerService;
import com.android.server.wifi.WifiService;
import com.android.server.wm.WindowManagerService;

import dalvik.system.VMRuntime;
import dalvik.system.Zygote;

import java.io.File;
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
    private static final String PRINT_MANAGER_SERVICE_CLASS =
            "com.android.server.print.PrintManagerService";
    private static final String USB_SERVICE_CLASS =
            "com.android.server.usb.UsbService$Lifecycle";
    private static final String HDMI_CEC_SERVICE_CLASS =
            "com.android.server.hdmi.HdmiCecService";

    private final int mFactoryTestMode;
    private Timer mProfilerSnapshotTimer;

    private Context mSystemContext;
    private SystemServiceManager mSystemServiceManager;

    // TODO: remove all of these references by improving dependency resolution and boot phases
    private Installer mInstaller;
    private PowerManagerService mPowerManagerService;
    private ActivityManagerService mActivityManagerService;
    private DisplayManagerService mDisplayManagerService;
    private ContentResolver mContentResolver;

    /**
     * Called to initialize native system services.
     */
    private static native void nativeInit();

    /**
     * The main entry point from zygote.
     */
    public static void main(String[] args) {
        new SystemServer().run();
    }

    public SystemServer() {
        mFactoryTestMode = FactoryTest.getMode();
    }

    private void run() {
        // If a device's clock is before 1970 (before 0), a lot of
        // APIs crash dealing with negative numbers, notably
        // java.io.File#setLastModified, so instead we fake it and
        // hope that time from cell towers or NTP fixes it shortly.
        if (System.currentTimeMillis() < EARLIEST_SUPPORTED_TIME) {
            Slog.w(TAG, "System clock is before 1970; setting to 1970.");
            SystemClock.setCurrentTimeMillis(EARLIEST_SUPPORTED_TIME);
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
        SystemProperties.set("persist.sys.dalvik.vm.lib", VMRuntime.getRuntime().vmLibrary());

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

        // Within the system server, it is an error to access Environment paths without
        // explicitly specifying a user.
        Environment.setUserRequired(true);

        // Ensure binder calls into the system always run at foreground priority.
        BinderInternal.disableBackgroundScheduling(true);

        // Prepare the main looper thread (this thread).
        android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_FOREGROUND);
        android.os.Process.setCanSelfBackground(false);
        Looper.prepareMainLooper();

        // Initialize native services.
        System.loadLibrary("android_servers");
        nativeInit();

        // Check whether we failed to shut down last time we tried.
        // This call may not return.
        performPendingShutdown();

        // Initialize the system context.
        createSystemContext();

        // Create the system service manager.
        mSystemServiceManager = new SystemServiceManager(mSystemContext);
        LocalServices.addService(SystemServiceManager.class, mSystemServiceManager);

        // Start services.
        try {
            startBootstrapServices();
            startCoreServices();
            startOtherServices();
        } catch (RuntimeException ex) {
            Slog.e("System", "******************************************");
            Slog.e("System", "************ Failure starting system services", ex);
            throw ex;
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
        Log.wtf(TAG, "BOOT FAILURE " + msg, e);
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

            ShutdownThread.rebootOrShutdown(reboot, reason);
        }
    }

    private void createSystemContext() {
        ActivityThread activityThread = ActivityThread.systemMain();
        mSystemContext = activityThread.getSystemContext();
        mSystemContext.setTheme(android.R.style.Theme_Holo);
    }

    private void startBootstrapServices() {
        // Wait for installd to finish starting up so that it has a chance to
        // create critical directories such as /data/user with the appropriate
        // permissions.  We need this to complete before we initialize other services.
        mInstaller = mSystemServiceManager.startService(Installer.class);

        // Power manager needs to be started early because other services need it.
        // TODO: The conversion to the new pattern is incomplete.  We need to switch
        // the power manager's dependencies over then we can use boot phases to arrange
        // initialization order and remove the mPowerManagerService field.
        mPowerManagerService = mSystemServiceManager.startService(PowerManagerService.class);

        // Activity manager runs the show.
        mActivityManagerService = mSystemServiceManager.startService(
                ActivityManagerService.Lifecycle.class).getService();
    }

    private void startCoreServices() {
        // Display manager is needed to provide display metrics before package manager
        // starts up.
        mDisplayManagerService = mSystemServiceManager.startService(DisplayManagerService.class);
    }

    private void startOtherServices() {
        final Context context = mSystemContext;
        AccountManagerService accountManager = null;
        ContentService contentService = null;
        LightsManager lights = null;
        BatteryService battery = null;
        VibratorService vibrator = null;
        IAlarmManager alarm = null;
        MountService mountService = null;
        NetworkManagementService networkManagement = null;
        NetworkStatsService networkStats = null;
        NetworkPolicyManagerService networkPolicy = null;
        ConnectivityService connectivity = null;
        WifiP2pService wifiP2p = null;
        WifiService wifi = null;
        NsdService serviceDiscovery= null;
        IPackageManager pm = null;
        WindowManagerService wm = null;
        BluetoothManagerService bluetooth = null;
        UsbService usb = null;
        SerialService serial = null;
        RecognitionManagerService recognition = null;
        NetworkTimeUpdateService networkTimeUpdater = null;
        CommonTimeManagementService commonTimeMgmtService = null;
        InputManagerService inputManager = null;
        TelephonyRegistry telephonyRegistry = null;
        ConsumerIrService consumerIr = null;

        boolean onlyCore = false;
        boolean firstBoot = false;
        boolean disableStorage = SystemProperties.getBoolean("config.disable_storage", false);
        boolean disableMedia = SystemProperties.getBoolean("config.disable_media", false);
        boolean disableBluetooth = SystemProperties.getBoolean("config.disable_bluetooth", false);
        boolean disableTelephony = SystemProperties.getBoolean("config.disable_telephony", false);
        boolean disableLocation = SystemProperties.getBoolean("config.disable_location", false);
        boolean disableSystemUI = SystemProperties.getBoolean("config.disable_systemui", false);
        boolean disableNonCoreServices = SystemProperties.getBoolean("config.disable_noncore", false);
        boolean disableNetwork = SystemProperties.getBoolean("config.disable_network", false);

        try {
            Slog.i(TAG, "Telephony Registry");
            telephonyRegistry = new TelephonyRegistry(context);
            ServiceManager.addService("telephony.registry", telephonyRegistry);

            Slog.i(TAG, "Scheduling Policy");
            ServiceManager.addService("scheduling_policy", new SchedulingPolicyService());

            AttributeCache.init(context);

            // We need the default display before we can initialize the package manager.
            mSystemServiceManager.startBootPhase(SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);

            Slog.i(TAG, "Package Manager");
            // Only run "core" apps if we're encrypting the device.
            String cryptState = SystemProperties.get("vold.decrypt");
            if (ENCRYPTING_STATE.equals(cryptState)) {
                Slog.w(TAG, "Detected encryption in progress - only parsing core apps");
                onlyCore = true;
            } else if (ENCRYPTED_STATE.equals(cryptState)) {
                Slog.w(TAG, "Device encrypted - only parsing core apps");
                onlyCore = true;
            }

            pm = PackageManagerService.main(context, mInstaller,
                    mFactoryTestMode != FactoryTest.FACTORY_TEST_OFF,
                    onlyCore);
            try {
                firstBoot = pm.isFirstBoot();
            } catch (RemoteException e) {
            }

            mActivityManagerService.setSystemProcess();

            Slog.i(TAG, "Entropy Mixer");
            ServiceManager.addService("entropy", new EntropyMixer(context));

            Slog.i(TAG, "User Service");
            ServiceManager.addService(Context.USER_SERVICE,
                    UserManagerService.getInstance());

            mContentResolver = context.getContentResolver();

            // The AccountManager must come before the ContentService
            try {
                // TODO: seems like this should be disable-able, but req'd by ContentService
                Slog.i(TAG, "Account Manager");
                accountManager = new AccountManagerService(context);
                ServiceManager.addService(Context.ACCOUNT_SERVICE, accountManager);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting Account Manager", e);
            }

            Slog.i(TAG, "Content Manager");
            contentService = ContentService.main(context,
                    mFactoryTestMode == FactoryTest.FACTORY_TEST_LOW_LEVEL);

            Slog.i(TAG, "System Content Providers");
            mActivityManagerService.installSystemProviders();

            mSystemServiceManager.startService(LightsService.class);
            lights = LocalServices.getService(LightsManager.class);

            Slog.i(TAG, "Battery Service");
            battery = new BatteryService(context, lights);
            ServiceManager.addService("battery", battery);

            Slog.i(TAG, "Vibrator Service");
            vibrator = new VibratorService(context);
            ServiceManager.addService("vibrator", vibrator);

            // TODO: use boot phase
            // only initialize the power service after we have started the
            // lights service, content providers and the battery service.
            mPowerManagerService.init(lights, battery,
                    BatteryStatsService.getService(),
                    mActivityManagerService.getAppOpsService());

            Slog.i(TAG, "Consumer IR Service");
            consumerIr = new ConsumerIrService(context);
            ServiceManager.addService(Context.CONSUMER_IR_SERVICE, consumerIr);

            mSystemServiceManager.startService(AlarmManagerService.class);
            alarm = IAlarmManager.Stub.asInterface(
                    ServiceManager.getService(Context.ALARM_SERVICE));

            Slog.i(TAG, "Init Watchdog");
            final Watchdog watchdog = Watchdog.getInstance();
            watchdog.init(context, mActivityManagerService);

            Slog.i(TAG, "Input Manager");
            inputManager = new InputManagerService(context);

            Slog.i(TAG, "Window Manager");
            wm = WindowManagerService.main(context, inputManager,
                    mFactoryTestMode != FactoryTest.FACTORY_TEST_LOW_LEVEL,
                    !firstBoot, onlyCore);
            ServiceManager.addService(Context.WINDOW_SERVICE, wm);
            ServiceManager.addService(Context.INPUT_SERVICE, inputManager);

            mActivityManagerService.setWindowManager(wm);

            inputManager.setWindowManagerCallbacks(wm.getInputMonitor());
            inputManager.start();

            // TODO: Use service dependencies instead.
            mDisplayManagerService.windowManagerAndInputReady();

            // Skip Bluetooth if we have an emulator kernel
            // TODO: Use a more reliable check to see if this product should
            // support Bluetooth - see bug 988521
            if (SystemProperties.get("ro.kernel.qemu").equals("1")) {
                Slog.i(TAG, "No Bluetooh Service (emulator)");
            } else if (mFactoryTestMode == FactoryTest.FACTORY_TEST_LOW_LEVEL) {
                Slog.i(TAG, "No Bluetooth Service (factory test)");
            } else if (!context.getPackageManager().hasSystemFeature
                       (PackageManager.FEATURE_BLUETOOTH)) {
                Slog.i(TAG, "No Bluetooth Service (Bluetooth Hardware Not Present)");
            } else if (disableBluetooth) {
                Slog.i(TAG, "Bluetooth Service disabled by config");
            } else {
                Slog.i(TAG, "Bluetooth Manager Service");
                bluetooth = new BluetoothManagerService(context);
                ServiceManager.addService(BluetoothAdapter.BLUETOOTH_MANAGER_SERVICE, bluetooth);
            }
        } catch (RuntimeException e) {
            Slog.e("System", "******************************************");
            Slog.e("System", "************ Failure starting core service", e);
        }

        StatusBarManagerService statusBar = null;
        INotificationManager notification = null;
        InputMethodManagerService imm = null;
        WallpaperManagerService wallpaper = null;
        LocationManagerService location = null;
        CountryDetectorService countryDetector = null;
        TextServicesManagerService tsms = null;
        LockSettingsService lockSettings = null;
        AssetAtlasService atlas = null;
        MediaRouterService mediaRouter = null;

        // Bring up services needed for UI.
        if (mFactoryTestMode != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
            //if (!disableNonCoreServices) { // TODO: View depends on these; mock them?
            if (true) {
                try {
                    Slog.i(TAG, "Input Method Service");
                    imm = new InputMethodManagerService(context, wm);
                    ServiceManager.addService(Context.INPUT_METHOD_SERVICE, imm);
                } catch (Throwable e) {
                    reportWtf("starting Input Manager Service", e);
                }

                try {
                    Slog.i(TAG, "Accessibility Manager");
                    ServiceManager.addService(Context.ACCESSIBILITY_SERVICE,
                            new AccessibilityManagerService(context));
                } catch (Throwable e) {
                    reportWtf("starting Accessibility Manager", e);
                }
            }
        }

        try {
            wm.displayReady();
        } catch (Throwable e) {
            reportWtf("making display ready", e);
        }

        try {
            pm.performBootDexOpt();
        } catch (Throwable e) {
            reportWtf("performing boot dexopt", e);
        }

        try {
            ActivityManagerNative.getDefault().showBootMessage(
                    context.getResources().getText(
                            com.android.internal.R.string.android_upgrading_starting_apps),
                    false);
        } catch (RemoteException e) {
        }

        if (mFactoryTestMode != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
            if (!disableStorage &&
                !"0".equals(SystemProperties.get("system_init.startmountservice"))) {
                try {
                    /*
                     * NotificationManagerService is dependant on MountService,
                     * (for media / usb notifications) so we must start MountService first.
                     */
                    Slog.i(TAG, "Mount Service");
                    mountService = new MountService(context);
                    ServiceManager.addService("mount", mountService);
                } catch (Throwable e) {
                    reportWtf("starting Mount Service", e);
                }
            }

            if (!disableNonCoreServices) {
                try {
                    Slog.i(TAG,  "LockSettingsService");
                    lockSettings = new LockSettingsService(context);
                    ServiceManager.addService("lock_settings", lockSettings);
                } catch (Throwable e) {
                    reportWtf("starting LockSettingsService service", e);
                }

                try {
                    // Always start the Device Policy Manager, so that the API is compatible with
                    // API8.
                    mSystemServiceManager.startService(DevicePolicyManagerService.Lifecycle.class);
                } catch (Throwable e) {
                    reportWtf("starting DevicePolicyService", e);
                }
            }

            if (!disableSystemUI) {
                try {
                    Slog.i(TAG, "Status Bar");
                    statusBar = new StatusBarManagerService(context, wm);
                    ServiceManager.addService(Context.STATUS_BAR_SERVICE, statusBar);
                } catch (Throwable e) {
                    reportWtf("starting StatusBarManagerService", e);
                }
            }

            if (!disableNonCoreServices) {
                try {
                    Slog.i(TAG, "Clipboard Service");
                    ServiceManager.addService(Context.CLIPBOARD_SERVICE,
                            new ClipboardService(context));
                } catch (Throwable e) {
                    reportWtf("starting Clipboard Service", e);
                }
            }

            if (!disableNetwork) {
                try {
                    Slog.i(TAG, "NetworkManagement Service");
                    networkManagement = NetworkManagementService.create(context);
                    ServiceManager.addService(Context.NETWORKMANAGEMENT_SERVICE, networkManagement);
                } catch (Throwable e) {
                    reportWtf("starting NetworkManagement Service", e);
                }
            }

            if (!disableNonCoreServices) {
                try {
                    Slog.i(TAG, "Text Service Manager Service");
                    tsms = new TextServicesManagerService(context);
                    ServiceManager.addService(Context.TEXT_SERVICES_MANAGER_SERVICE, tsms);
                } catch (Throwable e) {
                    reportWtf("starting Text Service Manager Service", e);
                }
            }

            if (!disableNetwork) {
                try {
                    Slog.i(TAG, "NetworkStats Service");
                    networkStats = new NetworkStatsService(context, networkManagement, alarm);
                    ServiceManager.addService(Context.NETWORK_STATS_SERVICE, networkStats);
                } catch (Throwable e) {
                    reportWtf("starting NetworkStats Service", e);
                }

                try {
                    Slog.i(TAG, "NetworkPolicy Service");
                    networkPolicy = new NetworkPolicyManagerService(
                            context, mActivityManagerService,
                            (IPowerManager)ServiceManager.getService(Context.POWER_SERVICE),
                            networkStats, networkManagement);
                    ServiceManager.addService(Context.NETWORK_POLICY_SERVICE, networkPolicy);
                } catch (Throwable e) {
                    reportWtf("starting NetworkPolicy Service", e);
                }

               try {
                    Slog.i(TAG, "Wi-Fi P2pService");
                    wifiP2p = new WifiP2pService(context);
                    ServiceManager.addService(Context.WIFI_P2P_SERVICE, wifiP2p);
                } catch (Throwable e) {
                    reportWtf("starting Wi-Fi P2pService", e);
                }

               try {
                    Slog.i(TAG, "Wi-Fi Service");
                    wifi = new WifiService(context);
                    ServiceManager.addService(Context.WIFI_SERVICE, wifi);
                } catch (Throwable e) {
                    reportWtf("starting Wi-Fi Service", e);
                }

                try {
                    Slog.i(TAG, "Connectivity Service");
                    connectivity = new ConnectivityService(
                            context, networkManagement, networkStats, networkPolicy);
                    ServiceManager.addService(Context.CONNECTIVITY_SERVICE, connectivity);
                    networkStats.bindConnectivityManager(connectivity);
                    networkPolicy.bindConnectivityManager(connectivity);

                    wifiP2p.connectivityServiceReady();
                    wifi.checkAndStartWifi();
                } catch (Throwable e) {
                    reportWtf("starting Connectivity Service", e);
                }

                try {
                    Slog.i(TAG, "Network Service Discovery Service");
                    serviceDiscovery = NsdService.create(context);
                    ServiceManager.addService(
                            Context.NSD_SERVICE, serviceDiscovery);
                } catch (Throwable e) {
                    reportWtf("starting Service Discovery Service", e);
                }
            }

            if (!disableNonCoreServices) {
                try {
                    Slog.i(TAG, "UpdateLock Service");
                    ServiceManager.addService(Context.UPDATE_LOCK_SERVICE,
                            new UpdateLockService(context));
                } catch (Throwable e) {
                    reportWtf("starting UpdateLockService", e);
                }
            }

            /*
             * MountService has a few dependencies: Notification Manager and
             * AppWidget Provider. Make sure MountService is completely started
             * first before continuing.
             */
            if (mountService != null && !onlyCore) {
                mountService.waitForAsecScan();
            }

            try {
                if (accountManager != null)
                    accountManager.systemReady();
            } catch (Throwable e) {
                reportWtf("making Account Manager Service ready", e);
            }

            try {
                if (contentService != null)
                    contentService.systemReady();
            } catch (Throwable e) {
                reportWtf("making Content Service ready", e);
            }

            mSystemServiceManager.startService(NotificationManagerService.class);
            notification = INotificationManager.Stub.asInterface(
                    ServiceManager.getService(Context.NOTIFICATION_SERVICE));
            networkPolicy.bindNotificationManager(notification);

            mSystemServiceManager.startService(DeviceStorageMonitorService.class);

            if (!disableLocation) {
                try {
                    Slog.i(TAG, "Location Manager");
                    location = new LocationManagerService(context);
                    ServiceManager.addService(Context.LOCATION_SERVICE, location);
                } catch (Throwable e) {
                    reportWtf("starting Location Manager", e);
                }

                try {
                    Slog.i(TAG, "Country Detector");
                    countryDetector = new CountryDetectorService(context);
                    ServiceManager.addService(Context.COUNTRY_DETECTOR, countryDetector);
                } catch (Throwable e) {
                    reportWtf("starting Country Detector", e);
                }
            }

            if (!disableNonCoreServices) {
                try {
                    Slog.i(TAG, "Search Service");
                    ServiceManager.addService(Context.SEARCH_SERVICE,
                            new SearchManagerService(context));
                } catch (Throwable e) {
                    reportWtf("starting Search Service", e);
                }
            }

            try {
                Slog.i(TAG, "DropBox Service");
                ServiceManager.addService(Context.DROPBOX_SERVICE,
                        new DropBoxManagerService(context, new File("/data/system/dropbox")));
            } catch (Throwable e) {
                reportWtf("starting DropBoxManagerService", e);
            }

            if (!disableNonCoreServices && context.getResources().getBoolean(
                        R.bool.config_enableWallpaperService)) {
                try {
                    Slog.i(TAG, "Wallpaper Service");
                    wallpaper = new WallpaperManagerService(context);
                    ServiceManager.addService(Context.WALLPAPER_SERVICE, wallpaper);
                } catch (Throwable e) {
                    reportWtf("starting Wallpaper Service", e);
                }
            }

            if (!disableMedia && !"0".equals(SystemProperties.get("system_init.startaudioservice"))) {
                try {
                    Slog.i(TAG, "Audio Service");
                    ServiceManager.addService(Context.AUDIO_SERVICE, new AudioService(context));
                } catch (Throwable e) {
                    reportWtf("starting Audio Service", e);
                }
            }

            if (!disableNonCoreServices) {
                mSystemServiceManager.startService(DockObserver.class);
            }

            if (!disableMedia) {
                try {
                    Slog.i(TAG, "Wired Accessory Manager");
                    // Listen for wired headset changes
                    inputManager.setWiredAccessoryCallbacks(
                            new WiredAccessoryManager(context, inputManager));
                } catch (Throwable e) {
                    reportWtf("starting WiredAccessoryManager", e);
                }
            }

            if (!disableNonCoreServices) {
                try {
                    if (pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST) ||
                            pm.hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY)) {
                        // Manage USB host and device support
                        mSystemServiceManager.startService(USB_SERVICE_CLASS);
                    }
                } catch (Throwable e) {
                    reportWtf("starting UsbService", e);
                }

                try {
                    Slog.i(TAG, "Serial Service");
                    // Serial port support
                    serial = new SerialService(context);
                    ServiceManager.addService(Context.SERIAL_SERVICE, serial);
                } catch (Throwable e) {
                    Slog.e(TAG, "Failure starting SerialService", e);
                }
            }

            mSystemServiceManager.startService(TwilightService.class);

            mSystemServiceManager.startService(UiModeManagerService.class);

            if (!disableNonCoreServices) {
                try {
                    if (pm.hasSystemFeature(PackageManager.FEATURE_BACKUP)) {
                        mSystemServiceManager.startService(BACKUP_MANAGER_SERVICE_CLASS);
                    }
                } catch (Throwable e) {
                    Slog.e(TAG, "Failure starting Backup Service", e);
                }

                try {
                    if (pm.hasSystemFeature(PackageManager.FEATURE_APP_WIDGETS)) {
                        mSystemServiceManager.startService(APPWIDGET_SERVICE_CLASS);
                    }
                } catch (Throwable e) {
                    reportWtf("starting AppWidget Service", e);
                }

                try {
                    Slog.i(TAG, "Recognition Service");
                    recognition = new RecognitionManagerService(context);
                } catch (Throwable e) {
                    reportWtf("starting Recognition Service", e);
                }
            }

            try {
                Slog.i(TAG, "DiskStats Service");
                ServiceManager.addService("diskstats", new DiskStatsService(context));
            } catch (Throwable e) {
                reportWtf("starting DiskStats Service", e);
            }

            try {
                // need to add this service even if SamplingProfilerIntegration.isEnabled()
                // is false, because it is this service that detects system property change and
                // turns on SamplingProfilerIntegration. Plus, when sampling profiler doesn't work,
                // there is little overhead for running this service.
                Slog.i(TAG, "SamplingProfiler Service");
                ServiceManager.addService("samplingprofiler",
                            new SamplingProfilerService(context));
            } catch (Throwable e) {
                reportWtf("starting SamplingProfiler Service", e);
            }

            if (!disableNetwork) {
                try {
                    Slog.i(TAG, "NetworkTimeUpdateService");
                    networkTimeUpdater = new NetworkTimeUpdateService(context);
                } catch (Throwable e) {
                    reportWtf("starting NetworkTimeUpdate service", e);
                }
            }

            if (!disableMedia) {
                try {
                    Slog.i(TAG, "CommonTimeManagementService");
                    commonTimeMgmtService = new CommonTimeManagementService(context);
                    ServiceManager.addService("commontime_management", commonTimeMgmtService);
                } catch (Throwable e) {
                    reportWtf("starting CommonTimeManagementService service", e);
                }
            }

            if (!disableNetwork) {
                try {
                    Slog.i(TAG, "CertBlacklister");
                    CertBlacklister blacklister = new CertBlacklister(context);
                } catch (Throwable e) {
                    reportWtf("starting CertBlacklister", e);
                }
            }

            if (!disableNonCoreServices) {
                // Dreams (interactive idle-time views, a/k/a screen savers, and doze mode)
                mSystemServiceManager.startService(DreamManagerService.class);
            }

            if (!disableNonCoreServices) {
                try {
                    Slog.i(TAG, "Assets Atlas Service");
                    atlas = new AssetAtlasService(context);
                    ServiceManager.addService(AssetAtlasService.ASSET_ATLAS_SERVICE, atlas);
                } catch (Throwable e) {
                    reportWtf("starting AssetAtlasService", e);
                }
            }

            try {
                Slog.i(TAG, "IdleMaintenanceService");
                new IdleMaintenanceService(context, battery);
            } catch (Throwable e) {
                reportWtf("starting IdleMaintenanceService", e);
            }

            try {
                if (pm.hasSystemFeature(PackageManager.FEATURE_PRINTING)) {
                    mSystemServiceManager.startService(PRINT_MANAGER_SERVICE_CLASS);
                }
            } catch (Throwable e) {
                reportWtf("starting Print Service", e);
            }

            try {
                mSystemServiceManager.startService(HDMI_CEC_SERVICE_CLASS);
            } catch (Throwable e) {
                reportWtf("starting HdmiCec Service", e);
            }

            if (!disableNonCoreServices) {
                try {
                    Slog.i(TAG, "Media Router Service");
                    mediaRouter = new MediaRouterService(context);
                    ServiceManager.addService(Context.MEDIA_ROUTER_SERVICE, mediaRouter);
                } catch (Throwable e) {
                    reportWtf("starting MediaRouterService", e);
                }
            }
        }

        // Before things start rolling, be sure we have decided whether
        // we are in safe mode.
        final boolean safeMode = wm.detectSafeMode();
        if (safeMode) {
            mActivityManagerService.enterSafeMode();
            // Post the safe mode state in the Zygote class
            Zygote.systemInSafeMode = true;
            // Disable the JIT for the system_server process
            VMRuntime.getRuntime().disableJitCompilation();
        } else {
            // Enable the JIT for the system_server process
            VMRuntime.getRuntime().startJitCompilation();
        }

        // It is now time to start up the app processes...

        try {
            vibrator.systemReady();
        } catch (Throwable e) {
            reportWtf("making Vibrator Service ready", e);
        }

        if (lockSettings != null) {
            try {
                lockSettings.systemReady();
            } catch (Throwable e) {
                reportWtf("making Lock Settings Service ready", e);
            }
        }

        // Needed by DevicePolicyManager for initialization
        mSystemServiceManager.startBootPhase(SystemService.PHASE_LOCK_SETTINGS_READY);

        mSystemServiceManager.startBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);

        try {
            wm.systemReady();
        } catch (Throwable e) {
            reportWtf("making Window Manager Service ready", e);
        }

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

        try {
            // TODO: use boot phase
            mPowerManagerService.systemReady();
        } catch (Throwable e) {
            reportWtf("making Power Manager Service ready", e);
        }

        try {
            pm.systemReady();
        } catch (Throwable e) {
            reportWtf("making Package Manager Service ready", e);
        }

        try {
            // TODO: use boot phase and communicate these flags some other way
            mDisplayManagerService.systemReady(safeMode, onlyCore);
        } catch (Throwable e) {
            reportWtf("making Display Manager Service ready", e);
        }

        // These are needed to propagate to the runnable below.
        final MountService mountServiceF = mountService;
        final BatteryService batteryF = battery;
        final NetworkManagementService networkManagementF = networkManagement;
        final NetworkStatsService networkStatsF = networkStats;
        final NetworkPolicyManagerService networkPolicyF = networkPolicy;
        final ConnectivityService connectivityF = connectivity;
        final WallpaperManagerService wallpaperF = wallpaper;
        final InputMethodManagerService immF = imm;
        final RecognitionManagerService recognitionF = recognition;
        final LocationManagerService locationF = location;
        final CountryDetectorService countryDetectorF = countryDetector;
        final NetworkTimeUpdateService networkTimeUpdaterF = networkTimeUpdater;
        final CommonTimeManagementService commonTimeMgmtServiceF = commonTimeMgmtService;
        final TextServicesManagerService textServiceManagerServiceF = tsms;
        final StatusBarManagerService statusBarF = statusBar;
        final AssetAtlasService atlasF = atlas;
        final InputManagerService inputManagerF = inputManager;
        final TelephonyRegistry telephonyRegistryF = telephonyRegistry;
        final MediaRouterService mediaRouterF = mediaRouter;

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

                try {
                    mActivityManagerService.startObservingNativeCrashes();
                } catch (Throwable e) {
                    reportWtf("observing native crashes", e);
                }
                try {
                    startSystemUi(context);
                } catch (Throwable e) {
                    reportWtf("starting System UI", e);
                }
                try {
                    if (mountServiceF != null) mountServiceF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Mount Service ready", e);
                }
                try {
                    if (batteryF != null) batteryF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Battery Service ready", e);
                }
                try {
                    if (networkManagementF != null) networkManagementF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Network Managment Service ready", e);
                }
                try {
                    if (networkStatsF != null) networkStatsF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Network Stats Service ready", e);
                }
                try {
                    if (networkPolicyF != null) networkPolicyF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Network Policy Service ready", e);
                }
                try {
                    if (connectivityF != null) connectivityF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Connectivity Service ready", e);
                }
                try {
                    if (recognitionF != null) recognitionF.systemReady();
                } catch (Throwable e) {
                    reportWtf("making Recognition Service ready", e);
                }
                Watchdog.getInstance().start();

                // It is now okay to let the various system services start their
                // third party code...
                mSystemServiceManager.startBootPhase(
                        SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

                try {
                    if (wallpaperF != null) wallpaperF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying WallpaperService running", e);
                }
                try {
                    if (immF != null) immF.systemRunning(statusBarF);
                } catch (Throwable e) {
                    reportWtf("Notifying InputMethodService running", e);
                }
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
                    if (textServiceManagerServiceF != null)
                        textServiceManagerServiceF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying TextServicesManagerService running", e);
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

                mSystemServiceManager.startBootPhase(SystemService.PHASE_BOOT_COMPLETE);
            }
        });
    }

    static final void startSystemUi(Context context) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.android.systemui",
                    "com.android.systemui.SystemUIService"));
        //Slog.d(TAG, "Starting service: " + intent);
        context.startServiceAsUser(intent, UserHandle.OWNER);
    }
}
