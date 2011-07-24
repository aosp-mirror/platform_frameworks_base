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

import android.accounts.AccountManagerService;
import android.app.ActivityManagerNative;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.res.Configuration;
import android.media.AudioService;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.server.BluetoothA2dpService;
import android.server.BluetoothService;
import android.server.search.SearchManagerService;
import android.server.WifiP2pService;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Slog;
import android.view.WindowManager;

import com.android.internal.app.ShutdownThread;
import com.android.internal.os.BinderInternal;
import com.android.internal.os.SamplingProfilerIntegration;
import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.am.ActivityManagerService;
import com.android.server.net.NetworkPolicyManagerService;
import com.android.server.net.NetworkStatsService;
import com.android.server.pm.PackageManagerService;
import com.android.server.usb.UsbService;
import com.android.server.wm.WindowManagerService;

import dalvik.system.VMRuntime;
import dalvik.system.Zygote;

import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

class ServerThread extends Thread {
    private static final String TAG = "SystemServer";

    ContentResolver mContentResolver;

    @Override
    public void run() {
        EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_SYSTEM_RUN,
            SystemClock.uptimeMillis());

        Looper.prepare();

        android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_FOREGROUND);

        BinderInternal.disableBackgroundScheduling(true);
        android.os.Process.setCanSelfBackground(false);

        // Check whether we failed to shut down last time we tried.
        {
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

        String factoryTestStr = SystemProperties.get("ro.factorytest");
        int factoryTest = "".equals(factoryTestStr) ? SystemServer.FACTORY_TEST_OFF
                : Integer.parseInt(factoryTestStr);

        LightsService lights = null;
        PowerManagerService power = null;
        BatteryService battery = null;
        AlarmManagerService alarm = null;
        NetworkManagementService networkManagement = null;
        NetworkStatsService networkStats = null;
        NetworkPolicyManagerService networkPolicy = null;
        ConnectivityService connectivity = null;
        WifiP2pService wifiP2p = null;
        IPackageManager pm = null;
        Context context = null;
        WindowManagerService wm = null;
        BluetoothService bluetooth = null;
        BluetoothA2dpService bluetoothA2dp = null;
        WiredAccessoryObserver wiredAccessory = null;
        DockObserver dock = null;
        UsbService usb = null;
        UiModeManagerService uiMode = null;
        RecognitionManagerService recognition = null;
        ThrottleService throttle = null;
        NetworkTimeUpdateService networkTimeUpdater = null;

        // Critical services...
        try {
            Slog.i(TAG, "Entropy Service");
            ServiceManager.addService("entropy", new EntropyService());

            Slog.i(TAG, "Power Manager");
            power = new PowerManagerService();
            ServiceManager.addService(Context.POWER_SERVICE, power);

            Slog.i(TAG, "Activity Manager");
            context = ActivityManagerService.main(factoryTest);

            Slog.i(TAG, "Telephony Registry");
            ServiceManager.addService("telephony.registry", new TelephonyRegistry(context));

            AttributeCache.init(context);

            Slog.i(TAG, "Package Manager");
            pm = PackageManagerService.main(context,
                    factoryTest != SystemServer.FACTORY_TEST_OFF);

            ActivityManagerService.setSystemProcess();

            mContentResolver = context.getContentResolver();

            // The AccountManager must come before the ContentService
            try {
                Slog.i(TAG, "Account Manager");
                ServiceManager.addService(Context.ACCOUNT_SERVICE,
                        new AccountManagerService(context));
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting Account Manager", e);
            }

            Slog.i(TAG, "Content Manager");
            ContentService.main(context,
                    factoryTest == SystemServer.FACTORY_TEST_LOW_LEVEL);

            Slog.i(TAG, "System Content Providers");
            ActivityManagerService.installSystemProviders();

            Slog.i(TAG, "Lights Service");
            lights = new LightsService(context);

            Slog.i(TAG, "Battery Service");
            battery = new BatteryService(context, lights);
            ServiceManager.addService("battery", battery);

            Slog.i(TAG, "Vibrator Service");
            ServiceManager.addService("vibrator", new VibratorService(context));

            // only initialize the power service after we have started the
            // lights service, content providers and the battery service.
            power.init(context, lights, ActivityManagerService.getDefault(), battery);

            Slog.i(TAG, "Alarm Manager");
            alarm = new AlarmManagerService(context);
            ServiceManager.addService(Context.ALARM_SERVICE, alarm);

            Slog.i(TAG, "Init Watchdog");
            Watchdog.getInstance().init(context, battery, power, alarm,
                    ActivityManagerService.self());

            Slog.i(TAG, "Window Manager");
            wm = WindowManagerService.main(context, power,
                    factoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL);
            ServiceManager.addService(Context.WINDOW_SERVICE, wm);

            ((ActivityManagerService)ServiceManager.getService("activity"))
                    .setWindowManager(wm);

            // Skip Bluetooth if we have an emulator kernel
            // TODO: Use a more reliable check to see if this product should
            // support Bluetooth - see bug 988521
            if (SystemProperties.get("ro.kernel.qemu").equals("1")) {
                Slog.i(TAG, "No Bluetooh Service (emulator)");
            } else if (factoryTest == SystemServer.FACTORY_TEST_LOW_LEVEL) {
                Slog.i(TAG, "No Bluetooth Service (factory test)");
            } else {
                Slog.i(TAG, "Bluetooth Service");
                bluetooth = new BluetoothService(context);
                ServiceManager.addService(BluetoothAdapter.BLUETOOTH_SERVICE, bluetooth);
                bluetooth.initAfterRegistration();
                bluetoothA2dp = new BluetoothA2dpService(context, bluetooth);
                ServiceManager.addService(BluetoothA2dpService.BLUETOOTH_A2DP_SERVICE,
                                          bluetoothA2dp);
                bluetooth.initAfterA2dpRegistration();

                int airplaneModeOn = Settings.System.getInt(mContentResolver,
                        Settings.System.AIRPLANE_MODE_ON, 0);
                int bluetoothOn = Settings.Secure.getInt(mContentResolver,
                    Settings.Secure.BLUETOOTH_ON, 0);
                if (airplaneModeOn == 0 && bluetoothOn != 0) {
                    bluetooth.enable();
                }
            }

        } catch (RuntimeException e) {
            Slog.e("System", "Failure starting core service", e);
        }

        DevicePolicyManagerService devicePolicy = null;
        StatusBarManagerService statusBar = null;
        InputMethodManagerService imm = null;
        AppWidgetService appWidget = null;
        NotificationManagerService notification = null;
        WallpaperManagerService wallpaper = null;
        LocationManagerService location = null;
        CountryDetectorService countryDetector = null;
        TextServicesManagerService tsms = null;

        if (factoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL) {
            try {
                Slog.i(TAG, "Device Policy");
                devicePolicy = new DevicePolicyManagerService(context);
                ServiceManager.addService(Context.DEVICE_POLICY_SERVICE, devicePolicy);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting DevicePolicyService", e);
            }

            try {
                Slog.i(TAG, "Status Bar");
                statusBar = new StatusBarManagerService(context, wm);
                ServiceManager.addService(Context.STATUS_BAR_SERVICE, statusBar);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting StatusBarManagerService", e);
            }

            try {
                Slog.i(TAG, "Clipboard Service");
                ServiceManager.addService(Context.CLIPBOARD_SERVICE,
                        new ClipboardService(context));
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting Clipboard Service", e);
            }

            try {
                Slog.i(TAG, "Input Method Service");
                imm = new InputMethodManagerService(context, statusBar);
                ServiceManager.addService(Context.INPUT_METHOD_SERVICE, imm);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting Input Manager Service", e);
            }

            try {
                Slog.i(TAG, "NetworkManagement Service");
                networkManagement = NetworkManagementService.create(context);
                ServiceManager.addService(Context.NETWORKMANAGEMENT_SERVICE, networkManagement);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting NetworkManagement Service", e);
            }

            try {
                Slog.i(TAG, "Text Service Manager Service");
                tsms = new TextServicesManagerService(context);
                ServiceManager.addService(Context.TEXT_SERVICES_MANAGER_SERVICE, tsms);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting Text Service Manager Service", e);
            }

            try {
                Slog.i(TAG, "NetworkStats Service");
                networkStats = new NetworkStatsService(context, networkManagement, alarm);
                ServiceManager.addService(Context.NETWORK_STATS_SERVICE, networkStats);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting NetworkStats Service", e);
            }

            try {
                Slog.i(TAG, "NetworkPolicy Service");
                networkPolicy = new NetworkPolicyManagerService(
                        context, ActivityManagerService.self(), power,
                        networkStats, networkManagement);
                ServiceManager.addService(Context.NETWORK_POLICY_SERVICE, networkPolicy);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting NetworkPolicy Service", e);
            }

           try {
                Slog.i(TAG, "Wi-Fi P2pService");
                wifiP2p = new WifiP2pService(context);
                ServiceManager.addService(Context.WIFI_P2P_SERVICE, wifiP2p);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting Wi-Fi P2pService", e);
            }

            try {
                Slog.i(TAG, "Connectivity Service");
                connectivity = new ConnectivityService(context, networkManagement, networkPolicy);
                ServiceManager.addService(Context.CONNECTIVITY_SERVICE, connectivity);
                networkStats.bindConnectivityManager(connectivity);
                networkPolicy.bindConnectivityManager(connectivity);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting Connectivity Service", e);
            }

            try {
                Slog.i(TAG, "Throttle Service");
                throttle = new ThrottleService(context);
                ServiceManager.addService(
                        Context.THROTTLE_SERVICE, throttle);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting ThrottleService", e);
            }

            try {
              Slog.i(TAG, "Accessibility Manager");
              ServiceManager.addService(Context.ACCESSIBILITY_SERVICE,
                      new AccessibilityManagerService(context));
            } catch (Throwable e) {
              Slog.e(TAG, "Failure starting Accessibility Manager", e);
            }

            try {
                /*
                 * NotificationManagerService is dependant on MountService,
                 * (for media / usb notifications) so we must start MountService first.
                 */
                Slog.i(TAG, "Mount Service");
                ServiceManager.addService("mount", new MountService(context));
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting Mount Service", e);
            }

            try {
                Slog.i(TAG, "Notification Manager");
                notification = new NotificationManagerService(context, statusBar, lights);
                ServiceManager.addService(Context.NOTIFICATION_SERVICE, notification);
                networkPolicy.bindNotificationManager(notification);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting Notification Manager", e);
            }

            try {
                Slog.i(TAG, "Device Storage Monitor");
                ServiceManager.addService(DeviceStorageMonitorService.SERVICE,
                        new DeviceStorageMonitorService(context));
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting DeviceStorageMonitor service", e);
            }

            try {
                Slog.i(TAG, "Location Manager");
                location = new LocationManagerService(context);
                ServiceManager.addService(Context.LOCATION_SERVICE, location);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting Location Manager", e);
            }

            try {
                Slog.i(TAG, "Country Detector");
                countryDetector = new CountryDetectorService(context);
                ServiceManager.addService(Context.COUNTRY_DETECTOR, countryDetector);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting Country Detector", e);
            }

            try {
                Slog.i(TAG, "Search Service");
                ServiceManager.addService(Context.SEARCH_SERVICE,
                        new SearchManagerService(context));
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting Search Service", e);
            }

            try {
                Slog.i(TAG, "DropBox Service");
                ServiceManager.addService(Context.DROPBOX_SERVICE,
                        new DropBoxManagerService(context, new File("/data/system/dropbox")));
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting DropBoxManagerService", e);
            }

            try {
                Slog.i(TAG, "Wallpaper Service");
                wallpaper = new WallpaperManagerService(context);
                ServiceManager.addService(Context.WALLPAPER_SERVICE, wallpaper);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting Wallpaper Service", e);
            }

            try {
                Slog.i(TAG, "Audio Service");
                ServiceManager.addService(Context.AUDIO_SERVICE, new AudioService(context));
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting Audio Service", e);
            }

            try {
                Slog.i(TAG, "Dock Observer");
                // Listen for dock station changes
                dock = new DockObserver(context, power);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting DockObserver", e);
            }

            try {
                Slog.i(TAG, "Wired Accessory Observer");
                // Listen for wired headset changes
                wiredAccessory = new WiredAccessoryObserver(context);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting WiredAccessoryObserver", e);
            }

            try {
                Slog.i(TAG, "USB Service");
                // Manage USB host and device support
                usb = new UsbService(context);
                ServiceManager.addService(Context.USB_SERVICE, usb);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting UsbService", e);
            }

            try {
                Slog.i(TAG, "UI Mode Manager Service");
                // Listen for UI mode changes
                uiMode = new UiModeManagerService(context);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting UiModeManagerService", e);
            }

            try {
                Slog.i(TAG, "Backup Service");
                ServiceManager.addService(Context.BACKUP_SERVICE,
                        new BackupManagerService(context));
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting Backup Service", e);
            }

            try {
                Slog.i(TAG, "AppWidget Service");
                appWidget = new AppWidgetService(context);
                ServiceManager.addService(Context.APPWIDGET_SERVICE, appWidget);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting AppWidget Service", e);
            }

            try {
                Slog.i(TAG, "Recognition Service");
                recognition = new RecognitionManagerService(context);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting Recognition Service", e);
            }

            try {
                Slog.i(TAG, "DiskStats Service");
                ServiceManager.addService("diskstats", new DiskStatsService(context));
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting DiskStats Service", e);
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
                Slog.e(TAG, "Failure starting SamplingProfiler Service", e);
            }

            try {
                Slog.i(TAG, "NetworkTimeUpdateService");
                networkTimeUpdater = new NetworkTimeUpdateService(context);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting NetworkTimeUpdate service");
            }
        }

        // Before things start rolling, be sure we have decided whether
        // we are in safe mode.
        final boolean safeMode = wm.detectSafeMode();
        if (safeMode) {
            ActivityManagerService.self().enterSafeMode();
            // Post the safe mode state in the Zygote class
            Zygote.systemInSafeMode = true;
            // Disable the JIT for the system_server process
            VMRuntime.getRuntime().disableJitCompilation();
        } else {
            // Enable the JIT for the system_server process
            VMRuntime.getRuntime().startJitCompilation();
        }

        // It is now time to start up the app processes...

        if (devicePolicy != null) {
            devicePolicy.systemReady();
        }

        if (notification != null) {
            notification.systemReady();
        }

        wm.systemReady();

        if (safeMode) {
            ActivityManagerService.self().showSafeModeOverlay();
        }

        // Update the configuration for this context by hand, because we're going
        // to start using it before the config change done in wm.systemReady() will
        // propagate to it.
        Configuration config = wm.computeNewConfiguration();
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager w = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        w.getDefaultDisplay().getMetrics(metrics);
        context.getResources().updateConfiguration(config, metrics);

        power.systemReady();
        try {
            pm.systemReady();
        } catch (RemoteException e) {
        }

        // These are needed to propagate to the runnable below.
        final Context contextF = context;
        final BatteryService batteryF = battery;
        final NetworkManagementService networkManagementF = networkManagement;
        final NetworkStatsService networkStatsF = networkStats;
        final NetworkPolicyManagerService networkPolicyF = networkPolicy;
        final ConnectivityService connectivityF = connectivity;
        final DockObserver dockF = dock;
        final UsbService usbF = usb;
        final ThrottleService throttleF = throttle;
        final UiModeManagerService uiModeF = uiMode;
        final AppWidgetService appWidgetF = appWidget;
        final WallpaperManagerService wallpaperF = wallpaper;
        final InputMethodManagerService immF = imm;
        final RecognitionManagerService recognitionF = recognition;
        final LocationManagerService locationF = location;
        final CountryDetectorService countryDetectorF = countryDetector;
        final NetworkTimeUpdateService networkTimeUpdaterF = networkTimeUpdater;
        final TextServicesManagerService textServiceManagerServiceF = tsms;

        // We now tell the activity manager it is okay to run third party
        // code.  It will call back into us once it has gotten to the state
        // where third party code can really run (but before it has actually
        // started launching the initial applications), for us to complete our
        // initialization.
        ((ActivityManagerService)ActivityManagerNative.getDefault())
                .systemReady(new Runnable() {
            public void run() {
                Slog.i(TAG, "Making services ready");

                startSystemUi(contextF);
                if (batteryF != null) batteryF.systemReady();
                if (networkManagementF != null) networkManagementF.systemReady();
                if (networkStatsF != null) networkStatsF.systemReady();
                if (networkPolicyF != null) networkPolicyF.systemReady();
                if (connectivityF != null) connectivityF.systemReady();
                if (dockF != null) dockF.systemReady();
                if (usbF != null) usbF.systemReady();
                if (uiModeF != null) uiModeF.systemReady();
                if (recognitionF != null) recognitionF.systemReady();
                Watchdog.getInstance().start();

                // It is now okay to let the various system services start their
                // third party code...

                if (appWidgetF != null) appWidgetF.systemReady(safeMode);
                if (wallpaperF != null) wallpaperF.systemReady();
                if (immF != null) immF.systemReady();
                if (locationF != null) locationF.systemReady();
                if (countryDetectorF != null) countryDetectorF.systemReady();
                if (throttleF != null) throttleF.systemReady();
                if (networkTimeUpdaterF != null) networkTimeUpdaterF.systemReady();
                if (textServiceManagerServiceF != null) textServiceManagerServiceF.systemReady();
            }
        });

        // For debug builds, log event loop stalls to dropbox for analysis.
        if (StrictMode.conditionallyEnableDebugLogging()) {
            Slog.i(TAG, "Enabled StrictMode for system server main thread.");
        }

        Looper.loop();
        Slog.d(TAG, "System ServerThread is exiting!");
    }

    static final void startSystemUi(Context context) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.android.systemui",
                    "com.android.systemui.SystemUIService"));
        Slog.d(TAG, "Starting service: " + intent);
        context.startService(intent);
    }
}

public class SystemServer {
    private static final String TAG = "SystemServer";

    public static final int FACTORY_TEST_OFF = 0;
    public static final int FACTORY_TEST_LOW_LEVEL = 1;
    public static final int FACTORY_TEST_HIGH_LEVEL = 2;

    static Timer timer;
    static final long SNAPSHOT_INTERVAL = 60 * 60 * 1000; // 1hr

    // The earliest supported time.  We pick one day into 1970, to
    // give any timezone code room without going into negative time.
    private static final long EARLIEST_SUPPORTED_TIME = 86400 * 1000;

    /**
     * This method is called from Zygote to initialize the system. This will cause the native
     * services (SurfaceFlinger, AudioFlinger, etc..) to be started. After that it will call back
     * up into init2() to start the Android services.
     */
    native public static void init1(String[] args);

    public static void main(String[] args) {
        if (System.currentTimeMillis() < EARLIEST_SUPPORTED_TIME) {
            // If a device's clock is before 1970 (before 0), a lot of
            // APIs crash dealing with negative numbers, notably
            // java.io.File#setLastModified, so instead we fake it and
            // hope that time from cell towers or NTP fixes it
            // shortly.
            Slog.w(TAG, "System clock is before 1970; setting to 1970.");
            SystemClock.setCurrentTimeMillis(EARLIEST_SUPPORTED_TIME);
        }

        if (SamplingProfilerIntegration.isEnabled()) {
            SamplingProfilerIntegration.start();
            timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    SamplingProfilerIntegration.writeSnapshot("system_server", null);
                }
            }, SNAPSHOT_INTERVAL, SNAPSHOT_INTERVAL);
        }

        // Mmmmmm... more memory!
        dalvik.system.VMRuntime.getRuntime().clearGrowthLimit();

        // The system server has to run all of the time, so it needs to be
        // as efficient as possible with its memory usage.
        VMRuntime.getRuntime().setTargetHeapUtilization(0.8f);

        System.loadLibrary("android_servers");
        init1(args);
    }

    public static final void init2() {
        Slog.i(TAG, "Entered the Android system server!");
        Thread thr = new ServerThread();
        thr.setName("android.server.ServerThread");
        thr.start();
    }
}
