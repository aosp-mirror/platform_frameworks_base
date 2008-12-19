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
import android.content.ContentResolver;
import android.content.ContentService;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.AudioService;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.IBinder;
import android.provider.Settings;
import android.provider.Contacts.People;
import android.server.BluetoothDeviceService;
import android.server.BluetoothA2dpService;
import android.server.checkin.FallbackCheckinService;
import android.server.search.SearchManagerService;
import android.util.EventLog;
import android.util.Log;

import dalvik.system.TouchDex;
import dalvik.system.VMRuntime;

import com.android.server.am.ActivityManagerService;
import com.android.server.status.StatusBarService;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

class ServerThread extends Thread {
    private static final String TAG = "SystemServer";
    private final static boolean INCLUDE_DEMO = false;

    private static final int LOG_BOOT_PROGRESS_SYSTEM_RUN = 3010;

    private ContentResolver mContentResolver;

    private class AdbSettingsObserver extends ContentObserver {
        public AdbSettingsObserver() {
            super(null);
        }
        @Override
        public void onChange(boolean selfChange) {
            boolean enableAdb = (Settings.Secure.getInt(mContentResolver,
                Settings.Secure.ADB_ENABLED, 0) > 0);
            // setting this secure property will start or stop adbd
           SystemProperties.set("persist.service.adb.enable", enableAdb ? "1" : "0");
        }
    }

    @Override
    public void run() {
        EventLog.writeEvent(LOG_BOOT_PROGRESS_SYSTEM_RUN,
            SystemClock.uptimeMillis());

        ActivityManagerService.prepareTraceFile(false);     // create dir

        Looper.prepare();

        android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_FOREGROUND);

        String factoryTestStr = SystemProperties.get("ro.factorytest");
        int factoryTest = "".equals(factoryTestStr) ? SystemServer.FACTORY_TEST_OFF
                : Integer.parseInt(factoryTestStr);

        PowerManagerService power = null;
        IPackageManager pm = null;
        Context context = null;
        WindowManagerService wm = null;
        BluetoothDeviceService bluetooth = null;
        BluetoothA2dpService bluetoothA2dp = null;
        HeadsetObserver headset = null;

        // Critical services...
        try {
            Log.i(TAG, "Starting Power Manager.");
            power = new PowerManagerService();
            ServiceManager.addService(Context.POWER_SERVICE, power);

            Log.i(TAG, "Starting Activity Manager.");
            context = ActivityManagerService.main(factoryTest);

            Log.i(TAG, "Starting telephony registry");
            ServiceManager.addService("telephony.registry", new TelephonyRegistry(context));

            AttributeCache.init(context);

            Log.i(TAG, "Starting Package Manager.");
            pm = PackageManagerService.main(context,
                    factoryTest != SystemServer.FACTORY_TEST_OFF);

            ActivityManagerService.setSystemProcess();

            mContentResolver = context.getContentResolver();

            Log.i(TAG, "Starting Content Manager.");
            ContentService.main(context,
                    factoryTest == SystemServer.FACTORY_TEST_LOW_LEVEL);

            Log.i(TAG, "Starting System Content Providers.");
            ActivityManagerService.installSystemProviders();

            Log.i(TAG, "Starting Battery Service.");
            BatteryService battery = new BatteryService(context);
            ServiceManager.addService("battery", battery);

            // only initialize the power service after we have started the
            // content providers and the batter service.
            power.init(context, ActivityManagerService.getDefault(), battery);

            Log.i(TAG, "Starting Alarm Manager.");
            AlarmManagerService alarm = new AlarmManagerService(context);
            ServiceManager.addService(Context.ALARM_SERVICE, alarm);

            Watchdog.getInstance().init(context, battery, power, alarm,
                    ActivityManagerService.self());

            // Sensor Service is needed by Window Manager, so this goes first
            Log.i(TAG, "Starting Sensor Service.");
            ServiceManager.addService(Context.SENSOR_SERVICE, new SensorService(context));

            Log.i(TAG, "Starting Window Manager.");
            wm = WindowManagerService.main(context, power,
                    factoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL);
            ServiceManager.addService(Context.WINDOW_SERVICE, wm);

            ((ActivityManagerService)ServiceManager.getService("activity"))
                    .setWindowManager(wm);

            // Skip Bluetooth if we have an emulator kernel
            // TODO: Use a more reliable check to see if this product should
            // support Bluetooth - see bug 988521
            if (SystemProperties.get("ro.kernel.qemu").equals("1")) {
                Log.i(TAG, "Registering null Bluetooth Service (emulator)");
                ServiceManager.addService(Context.BLUETOOTH_SERVICE, null);
            } else if (factoryTest == SystemServer.FACTORY_TEST_LOW_LEVEL) {
                Log.i(TAG, "Registering null Bluetooth Service (factory test)");
                ServiceManager.addService(Context.BLUETOOTH_SERVICE, null);
            } else {
                Log.i(TAG, "Starting Bluetooth Service.");
                bluetooth = new BluetoothDeviceService(context);
                bluetooth.init();
                ServiceManager.addService(Context.BLUETOOTH_SERVICE, bluetooth);
                bluetoothA2dp = new BluetoothA2dpService(context);
                ServiceManager.addService(BluetoothA2dpService.BLUETOOTH_A2DP_SERVICE,
                                          bluetoothA2dp);

                int bluetoothOn = Settings.Secure.getInt(mContentResolver,
                    Settings.Secure.BLUETOOTH_ON, 0);
                if (bluetoothOn > 0) {
                    bluetooth.enable(null);
                }
            }

        } catch (RuntimeException e) {
            Log.e("System", "Failure starting core service", e);
        }

        StatusBarService statusBar = null;
        InputMethodManagerService imm = null;
        
        if (factoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL) {
            try {
                Log.i(TAG, "Starting Status Bar Service.");
                statusBar = new StatusBarService(context);
                ServiceManager.addService("statusbar", statusBar);
                com.android.server.status.StatusBarPolicy.installIcons(context, statusBar);
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting StatusBarService", e);
            }

            try {
                Log.i(TAG, "Starting Clipboard Service.");
                ServiceManager.addService("clipboard", new ClipboardService(context));
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Clipboard Service", e);
            }

            try {
                Log.i(TAG, "Starting Input Method Service.");
                imm = new InputMethodManagerService(context, statusBar);
                ServiceManager.addService(Context.INPUT_METHOD_SERVICE, imm);
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Input Manager Service", e);
            }
            
            try {
                Log.i(TAG, "Starting Hardware Service.");
                ServiceManager.addService("hardware", new HardwareService(context));
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Hardware Service", e);
            }

            try {
                Log.i(TAG, "Starting NetStat Service.");
                ServiceManager.addService("netstat", new NetStatService(context));
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting NetStat Service", e);
            }

            try {
                Log.i(TAG, "Starting Connectivity Service.");
                ServiceManager.addService(Context.CONNECTIVITY_SERVICE,
                        ConnectivityService.getInstance(context));
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Connectivity Service", e);
            }

            try {
                Log.i(TAG, "Starting Notification Manager.");
                ServiceManager.addService(Context.NOTIFICATION_SERVICE,
                        new NotificationManagerService(context, statusBar));
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Notification Manager", e);
            }

            try {
                // MountService must start after NotificationManagerService
                Log.i(TAG, "Starting Mount Service.");
                ServiceManager.addService("mount", new MountService(context));
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Mount Service", e);
            }

            try {
                Log.i(TAG, "Starting DeviceStorageMonitor service");
                ServiceManager.addService(DeviceStorageMonitorService.SERVICE,
                        new DeviceStorageMonitorService(context));
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting DeviceStorageMonitor service", e);
            }

            try {
                Log.i(TAG, "Starting Location Manager.");
                ServiceManager.addService(Context.LOCATION_SERVICE, new LocationManagerService(context));
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Location Manager", e);
            }

            try {
                Log.i(TAG, "Starting Search Service.");
                ServiceManager.addService( Context.SEARCH_SERVICE, new SearchManagerService(context) );
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Search Service", e);
            }

            if (INCLUDE_DEMO) {
                Log.i(TAG, "Installing demo data...");
                (new DemoThread(context)).start();
            }

            try {
                Log.i(TAG, "Starting Checkin Service");
                addService(context, "checkin", "com.google.android.server.checkin.CheckinService",
                        FallbackCheckinService.class);
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Checkin Service", e);
            }

            try {
                Log.i(TAG, "Starting Wallpaper Service");
                ServiceManager.addService(Context.WALLPAPER_SERVICE, new WallpaperService(context));
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Wallpaper Service", e);
            }

            try {
                Log.i(TAG, "Starting Audio Service");
                ServiceManager.addService(Context.AUDIO_SERVICE, new AudioService(context));
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting Volume Service", e);
            }

            try {
                Log.i(TAG, "Starting HeadsetObserver");
                // Listen for wired headset changes
                headset = new HeadsetObserver(context);
            } catch (Throwable e) {
                Log.e(TAG, "Failure starting HeadsetObserver", e);
            }
        }

        // make sure the ADB_ENABLED setting value matches the secure property value
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ADB_ENABLED,
                "1".equals(SystemProperties.get("persist.service.adb.enable")) ? 1 : 0);

        // register observer to listen for settings changes
        mContentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ADB_ENABLED),
                false, new AdbSettingsObserver());

        // It is now time to start up the app processes...
        if (statusBar != null) {
            statusBar.systemReady();
        }
        if (imm != null) {
            imm.systemReady();
        }
        wm.systemReady();
        power.systemReady();
        try {
            pm.systemReady();
        } catch (RemoteException e) {
        }

        // After making the following code, third party code may be running...
        try {
            ActivityManagerNative.getDefault().systemReady();
        } catch (RemoteException e) {
        }

        Watchdog.getInstance().start();

        Looper.loop();
        Log.d(TAG, "System ServerThread is exiting!");
    }

    private void addService(Context context, String name, String serviceClass,
            Class<? extends IBinder> fallback) {

        final IBinder service = findService(context, serviceClass, fallback);
        if (service != null) {
            ServiceManager.addService(name, service);
        } else {
            Log.e(TAG, "Failure starting service '" + name + "' with class " + serviceClass);
        }
    }

    private IBinder findService(Context context, String serviceClass,
            Class<? extends IBinder> fallback) {

        IBinder service = null;
        try {
            Class<?> klass = Class.forName(serviceClass);
            Constructor<?> c = klass.getConstructor(Context.class);
            service = (IBinder) c.newInstance(context);
        } catch (ClassNotFoundException e) {
            // Ignore
        } catch (IllegalAccessException e) {
            // Ignore
        } catch (NoSuchMethodException e) {
            // Ignore
        } catch (InvocationTargetException e) {
            // Ignore
        } catch (InstantiationException e) {
            // Ignore
        }

        if (service == null && fallback != null) {
            Log.w(TAG, "Could not find " + serviceClass + ", trying fallback");
            try {
                service = fallback.newInstance();
            } catch (IllegalAccessException e) {
                // Ignore
            } catch (InstantiationException e) {
                // Ignore
            }
        }

        return service;
    }
}

class DemoThread extends Thread
{
    DemoThread(Context context)
    {
        mContext = context;
    }

    @Override
    public void run()
    {
        try {
            Cursor c = mContext.getContentResolver().query(People.CONTENT_URI, null, null, null, null);
            boolean hasData = c != null && c.moveToFirst();
            if (c != null) {
                c.deactivate();
            }
            if (!hasData) {
                DemoDataSet dataset = new DemoDataSet();
                dataset.add(mContext);
            }
        } catch (Throwable e) {
            Log.e("SystemServer", "Failure installing demo data", e);
        }

    }

    Context mContext;
}

public class SystemServer
{
    private static final String TAG = "SystemServer";

    public static final int FACTORY_TEST_OFF = 0;
    public static final int FACTORY_TEST_LOW_LEVEL = 1;
    public static final int FACTORY_TEST_HIGH_LEVEL = 2;
    
    /** 
     * This method is called from Zygote to initialize the system. This will cause the native 
     * services (SurfaceFlinger, AudioFlinger, etc..) to be started. After that it will call back
     * up into init2() to start the Android services.
     */ 
    native public static void init1(String[] args);

    public static void main(String[] args) {
        // The system server has to run all of the time, so it needs to be
        // as efficient as possible with its memory usage.
        VMRuntime.getRuntime().setTargetHeapUtilization(0.8f);
        
        System.loadLibrary("android_servers");
        init1(args);
    }

    public static final void init2() {
        Log.i(TAG, "Entered the Android system server!");
        Thread thr = new ServerThread();
        thr.setName("android.server.ServerThread");
        thr.start();
    }
}
