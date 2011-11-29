/*
 * Copyright (C) 2007 The Android Open Source Project
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

import com.android.internal.app.IBatteryStats;
import com.android.internal.app.ShutdownThread;
import com.android.server.am.BatteryStatsService;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentQueryMap;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.LocalPowerManager;
import android.os.Power;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.WorkSource;
import android.provider.Settings.SettingNotFoundException;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.view.WindowManagerPolicy;
import static android.provider.Settings.System.DIM_SCREEN;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
import static android.provider.Settings.System.SCREEN_OFF_TIMEOUT;
import static android.provider.Settings.System.STAY_ON_WHILE_PLUGGED_IN;
import static android.provider.Settings.System.WINDOW_ANIMATION_SCALE;
import static android.provider.Settings.System.TRANSITION_ANIMATION_SCALE;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Observable;
import java.util.Observer;

public class PowerManagerService extends IPowerManager.Stub
        implements LocalPowerManager, Watchdog.Monitor {

    private static final String TAG = "PowerManagerService";
    static final String PARTIAL_NAME = "PowerManagerService";

    static final boolean DEBUG_SCREEN_ON = false;

    private static final boolean LOG_PARTIAL_WL = false;

    // Indicates whether touch-down cycles should be logged as part of the
    // LOG_POWER_SCREEN_STATE log events
    private static final boolean LOG_TOUCH_DOWNS = true;

    private static final int LOCK_MASK = PowerManager.PARTIAL_WAKE_LOCK
                                        | PowerManager.SCREEN_DIM_WAKE_LOCK
                                        | PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                        | PowerManager.FULL_WAKE_LOCK
                                        | PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK;

    //                       time since last state:               time since last event:
    // The short keylight delay comes from secure settings; this is the default.
    private static final int SHORT_KEYLIGHT_DELAY_DEFAULT = 6000; // t+6 sec
    private static final int MEDIUM_KEYLIGHT_DELAY = 15000;       // t+15 sec
    private static final int LONG_KEYLIGHT_DELAY = 6000;        // t+6 sec
    private static final int LONG_DIM_TIME = 7000;              // t+N-5 sec

    // How long to wait to debounce light sensor changes in milliseconds
    private static final int LIGHT_SENSOR_DELAY = 2000;

    // light sensor events rate in microseconds
    private static final int LIGHT_SENSOR_RATE = 1000000;

    // For debouncing the proximity sensor in milliseconds
    private static final int PROXIMITY_SENSOR_DELAY = 1000;

    // trigger proximity if distance is less than 5 cm
    private static final float PROXIMITY_THRESHOLD = 5.0f;

    // Cached secure settings; see updateSettingsValues()
    private int mShortKeylightDelay = SHORT_KEYLIGHT_DELAY_DEFAULT;

    // Default timeout for screen off, if not found in settings database = 15 seconds.
    private static final int DEFAULT_SCREEN_OFF_TIMEOUT = 15000;

    // flags for setPowerState
    private static final int SCREEN_ON_BIT          = 0x00000001;
    private static final int SCREEN_BRIGHT_BIT      = 0x00000002;
    private static final int BUTTON_BRIGHT_BIT      = 0x00000004;
    private static final int KEYBOARD_BRIGHT_BIT    = 0x00000008;
    private static final int BATTERY_LOW_BIT        = 0x00000010;

    // values for setPowerState

    // SCREEN_OFF == everything off
    private static final int SCREEN_OFF         = 0x00000000;

    // SCREEN_DIM == screen on, screen backlight dim
    private static final int SCREEN_DIM         = SCREEN_ON_BIT;

    // SCREEN_BRIGHT == screen on, screen backlight bright
    private static final int SCREEN_BRIGHT      = SCREEN_ON_BIT | SCREEN_BRIGHT_BIT;

    // SCREEN_BUTTON_BRIGHT == screen on, screen and button backlights bright
    private static final int SCREEN_BUTTON_BRIGHT  = SCREEN_BRIGHT | BUTTON_BRIGHT_BIT;

    // SCREEN_BUTTON_BRIGHT == screen on, screen, button and keyboard backlights bright
    private static final int ALL_BRIGHT         = SCREEN_BUTTON_BRIGHT | KEYBOARD_BRIGHT_BIT;

    // used for noChangeLights in setPowerState()
    private static final int LIGHTS_MASK        = SCREEN_BRIGHT_BIT | BUTTON_BRIGHT_BIT | KEYBOARD_BRIGHT_BIT;

    boolean mAnimateScreenLights = true;

    static final int ANIM_STEPS = 60/4;
    // Slower animation for autobrightness changes
    static final int AUTOBRIGHTNESS_ANIM_STEPS = 60;

    // These magic numbers are the initial state of the LEDs at boot.  Ideally
    // we should read them from the driver, but our current hardware returns 0
    // for the initial value.  Oops!
    static final int INITIAL_SCREEN_BRIGHTNESS = 255;
    static final int INITIAL_BUTTON_BRIGHTNESS = Power.BRIGHTNESS_OFF;
    static final int INITIAL_KEYBOARD_BRIGHTNESS = Power.BRIGHTNESS_OFF;

    private final int MY_UID;
    private final int MY_PID;

    private boolean mDoneBooting = false;
    private boolean mBootCompleted = false;
    private int mStayOnConditions = 0;
    private final int[] mBroadcastQueue = new int[] { -1, -1, -1 };
    private final int[] mBroadcastWhy = new int[3];
    private boolean mPreparingForScreenOn = false;
    private boolean mSkippedScreenOn = false;
    private boolean mInitialized = false;
    private int mPartialCount = 0;
    private int mPowerState;
    // mScreenOffReason can be WindowManagerPolicy.OFF_BECAUSE_OF_USER,
    // WindowManagerPolicy.OFF_BECAUSE_OF_TIMEOUT or WindowManagerPolicy.OFF_BECAUSE_OF_PROX_SENSOR
    private int mScreenOffReason;
    private int mUserState;
    private boolean mKeyboardVisible = false;
    private boolean mUserActivityAllowed = true;
    private int mProximityWakeLockCount = 0;
    private boolean mProximitySensorEnabled = false;
    private boolean mProximitySensorActive = false;
    private int mProximityPendingValue = -1; // -1 == nothing, 0 == inactive, 1 == active
    private long mLastProximityEventTime;
    private int mScreenOffTimeoutSetting;
    private int mMaximumScreenOffTimeout = Integer.MAX_VALUE;
    private int mKeylightDelay;
    private int mDimDelay;
    private int mScreenOffDelay;
    private int mWakeLockState;
    private long mLastEventTime = 0;
    private long mScreenOffTime;
    private volatile WindowManagerPolicy mPolicy;
    private final LockList mLocks = new LockList();
    private Intent mScreenOffIntent;
    private Intent mScreenOnIntent;
    private LightsService mLightsService;
    private Context mContext;
    private LightsService.Light mLcdLight;
    private LightsService.Light mButtonLight;
    private LightsService.Light mKeyboardLight;
    private LightsService.Light mAttentionLight;
    private UnsynchronizedWakeLock mBroadcastWakeLock;
    private UnsynchronizedWakeLock mStayOnWhilePluggedInScreenDimLock;
    private UnsynchronizedWakeLock mStayOnWhilePluggedInPartialLock;
    private UnsynchronizedWakeLock mPreventScreenOnPartialLock;
    private UnsynchronizedWakeLock mProximityPartialLock;
    private HandlerThread mHandlerThread;
    private HandlerThread mScreenOffThread;
    private Handler mScreenOffHandler;
    private Handler mHandler;
    private final TimeoutTask mTimeoutTask = new TimeoutTask();
    private final BrightnessState mScreenBrightness
            = new BrightnessState(SCREEN_BRIGHT_BIT);
    private boolean mStillNeedSleepNotification;
    private boolean mIsPowered = false;
    private IActivityManager mActivityService;
    private IBatteryStats mBatteryStats;
    private BatteryService mBatteryService;
    private SensorManager mSensorManager;
    private Sensor mProximitySensor;
    private Sensor mLightSensor;
    private boolean mLightSensorEnabled;
    private float mLightSensorValue = -1;
    private boolean mProxIgnoredBecauseScreenTurnedOff = false;
    private int mHighestLightSensorValue = -1;
    private boolean mLightSensorPendingDecrease = false;
    private boolean mLightSensorPendingIncrease = false;
    private float mLightSensorPendingValue = -1;
    private int mLightSensorScreenBrightness = -1;
    private int mLightSensorButtonBrightness = -1;
    private int mLightSensorKeyboardBrightness = -1;
    private boolean mDimScreen = true;
    private boolean mIsDocked = false;
    private long mNextTimeout;
    private volatile int mPokey = 0;
    private volatile boolean mPokeAwakeOnSet = false;
    private volatile boolean mInitComplete = false;
    private final HashMap<IBinder,PokeLock> mPokeLocks = new HashMap<IBinder,PokeLock>();
    // mLastScreenOnTime is the time the screen was last turned on
    private long mLastScreenOnTime;
    private boolean mPreventScreenOn;
    private int mScreenBrightnessOverride = -1;
    private int mButtonBrightnessOverride = -1;
    private int mScreenBrightnessDim;
    private boolean mUseSoftwareAutoBrightness;
    private boolean mAutoBrightessEnabled;
    private int[] mAutoBrightnessLevels;
    private int[] mLcdBacklightValues;
    private int[] mButtonBacklightValues;
    private int[] mKeyboardBacklightValues;
    private int mLightSensorWarmupTime;
    boolean mUnplugTurnsOnScreen;
    private int mWarningSpewThrottleCount;
    private long mWarningSpewThrottleTime;
    private int mAnimationSetting = ANIM_SETTING_OFF;

    // Must match with the ISurfaceComposer constants in C++.
    private static final int ANIM_SETTING_ON = 0x01;
    private static final int ANIM_SETTING_OFF = 0x10;

    // Used when logging number and duration of touch-down cycles
    private long mTotalTouchDownTime;
    private long mLastTouchDown;
    private int mTouchCycles;

    // could be either static or controllable at runtime
    private static final boolean mSpew = false;
    private static final boolean mDebugProximitySensor = (false || mSpew);
    private static final boolean mDebugLightSensor = (false || mSpew);
    
    private native void nativeInit();
    private native void nativeSetPowerState(boolean screenOn, boolean screenBright);
    private native void nativeStartSurfaceFlingerAnimation(int mode);

    /*
    static PrintStream mLog;
    static {
        try {
            mLog = new PrintStream("/data/power.log");
        }
        catch (FileNotFoundException e) {
            android.util.Slog.e(TAG, "Life is hard", e);
        }
    }
    static class Log {
        static void d(String tag, String s) {
            mLog.println(s);
            android.util.Slog.d(tag, s);
        }
        static void i(String tag, String s) {
            mLog.println(s);
            android.util.Slog.i(tag, s);
        }
        static void w(String tag, String s) {
            mLog.println(s);
            android.util.Slog.w(tag, s);
        }
        static void e(String tag, String s) {
            mLog.println(s);
            android.util.Slog.e(tag, s);
        }
    }
    */

    /**
     * This class works around a deadlock between the lock in PowerManager.WakeLock
     * and our synchronizing on mLocks.  PowerManager.WakeLock synchronizes on its
     * mToken object so it can be accessed from any thread, but it calls into here
     * with its lock held.  This class is essentially a reimplementation of
     * PowerManager.WakeLock, but without that extra synchronized block, because we'll
     * only call it with our own locks held.
     */
    private class UnsynchronizedWakeLock {
        int mFlags;
        String mTag;
        IBinder mToken;
        int mCount = 0;
        boolean mRefCounted;
        boolean mHeld;

        UnsynchronizedWakeLock(int flags, String tag, boolean refCounted) {
            mFlags = flags;
            mTag = tag;
            mToken = new Binder();
            mRefCounted = refCounted;
        }

        public void acquire() {
            if (!mRefCounted || mCount++ == 0) {
                long ident = Binder.clearCallingIdentity();
                try {
                    PowerManagerService.this.acquireWakeLockLocked(mFlags, mToken,
                            MY_UID, MY_PID, mTag, null);
                    mHeld = true;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        public void release() {
            if (!mRefCounted || --mCount == 0) {
                PowerManagerService.this.releaseWakeLockLocked(mToken, 0, false);
                mHeld = false;
            }
            if (mCount < 0) {
                throw new RuntimeException("WakeLock under-locked " + mTag);
            }
        }

        public boolean isHeld()
        {
            return mHeld;
        }

        public String toString() {
            return "UnsynchronizedWakeLock(mFlags=0x" + Integer.toHexString(mFlags)
                    + " mCount=" + mCount + " mHeld=" + mHeld + ")";
        }
    }

    private final class BatteryReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLocks) {
                boolean wasPowered = mIsPowered;
                mIsPowered = mBatteryService.isPowered();

                if (mIsPowered != wasPowered) {
                    // update mStayOnWhilePluggedIn wake lock
                    updateWakeLockLocked();

                    // treat plugging and unplugging the devices as a user activity.
                    // users find it disconcerting when they unplug the device
                    // and it shuts off right away.
                    // to avoid turning on the screen when unplugging, we only trigger
                    // user activity when screen was already on.
                    // temporarily set mUserActivityAllowed to true so this will work
                    // even when the keyguard is on.
                    // However, you can also set config_unplugTurnsOnScreen to have it
                    // turn on.  Some devices want this because they don't have a
                    // charging LED.
                    synchronized (mLocks) {
                        if (!wasPowered || (mPowerState & SCREEN_ON_BIT) != 0 ||
                                mUnplugTurnsOnScreen) {
                            forceUserActivityLocked();
                        }
                    }
                }
            }
        }
    }

    private final class BootCompletedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            bootCompleted();
        }
    }

    private final class DockReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                    Intent.EXTRA_DOCK_STATE_UNDOCKED);
            dockStateChanged(state);
        }
    }

    /**
     * Set the setting that determines whether the device stays on when plugged in.
     * The argument is a bit string, with each bit specifying a power source that,
     * when the device is connected to that source, causes the device to stay on.
     * See {@link android.os.BatteryManager} for the list of power sources that
     * can be specified. Current values include {@link android.os.BatteryManager#BATTERY_PLUGGED_AC}
     * and {@link android.os.BatteryManager#BATTERY_PLUGGED_USB}
     * @param val an {@code int} containing the bits that specify which power sources
     * should cause the device to stay on.
     */
    public void setStayOnSetting(int val) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WRITE_SETTINGS, null);
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.STAY_ON_WHILE_PLUGGED_IN, val);
    }

    public void setMaximumScreenOffTimeount(int timeMs) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS, null);
        synchronized (mLocks) {
            mMaximumScreenOffTimeout = timeMs;
            // recalculate everything
            setScreenOffTimeoutsLocked();
        }
    }

    private class SettingsObserver implements Observer {
        private int getInt(String name, int defValue) {
            ContentValues values = mSettings.getValues(name);
            Integer iVal = values != null ? values.getAsInteger(Settings.System.VALUE) : null;
            return iVal != null ? iVal : defValue;
        }

        private float getFloat(String name, float defValue) {
            ContentValues values = mSettings.getValues(name);
            Float fVal = values != null ? values.getAsFloat(Settings.System.VALUE) : null;
            return fVal != null ? fVal : defValue;
        }

        public void update(Observable o, Object arg) {
            synchronized (mLocks) {
                // STAY_ON_WHILE_PLUGGED_IN, default to when plugged into AC
                mStayOnConditions = getInt(STAY_ON_WHILE_PLUGGED_IN,
                        BatteryManager.BATTERY_PLUGGED_AC);
                updateWakeLockLocked();

                // SCREEN_OFF_TIMEOUT, default to 15 seconds
                mScreenOffTimeoutSetting = getInt(SCREEN_OFF_TIMEOUT, DEFAULT_SCREEN_OFF_TIMEOUT);

                // DIM_SCREEN
                //mDimScreen = getInt(DIM_SCREEN) != 0;

                // SCREEN_BRIGHTNESS_MODE, default to manual
                setScreenBrightnessMode(getInt(SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL));

                // recalculate everything
                setScreenOffTimeoutsLocked();

                final float windowScale = getFloat(WINDOW_ANIMATION_SCALE, 1.0f);
                final float transitionScale = getFloat(TRANSITION_ANIMATION_SCALE, 1.0f);
                mAnimationSetting = 0;
                if (windowScale > 0.5f) {
                    mAnimationSetting |= ANIM_SETTING_OFF;
                }
                if (transitionScale > 0.5f) {
                    // Uncomment this if you want the screen-on animation.
                    // mAnimationSetting |= ANIM_SETTING_ON;
                }
            }
        }
    }

    PowerManagerService() {
        // Hack to get our uid...  should have a func for this.
        long token = Binder.clearCallingIdentity();
        MY_UID = Process.myUid();
        MY_PID = Process.myPid();
        Binder.restoreCallingIdentity(token);

        // XXX remove this when the kernel doesn't timeout wake locks
        Power.setLastUserActivityTimeout(7*24*3600*1000); // one week

        // assume nothing is on yet
        mUserState = mPowerState = 0;

        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);
    }

    private ContentQueryMap mSettings;

    void init(Context context, LightsService lights, IActivityManager activity,
            BatteryService battery) {
        mLightsService = lights;
        mContext = context;
        mActivityService = activity;
        mBatteryStats = BatteryStatsService.getService();
        mBatteryService = battery;

        mLcdLight = lights.getLight(LightsService.LIGHT_ID_BACKLIGHT);
        mButtonLight = lights.getLight(LightsService.LIGHT_ID_BUTTONS);
        mKeyboardLight = lights.getLight(LightsService.LIGHT_ID_KEYBOARD);
        mAttentionLight = lights.getLight(LightsService.LIGHT_ID_ATTENTION);

        nativeInit();
        synchronized (mLocks) {
            updateNativePowerStateLocked();
        }

        mInitComplete = false;
        mScreenOffThread = new HandlerThread("PowerManagerService.mScreenOffThread") {
            @Override
            protected void onLooperPrepared() {
                mScreenOffHandler = new Handler();
                synchronized (mScreenOffThread) {
                    mInitComplete = true;
                    mScreenOffThread.notifyAll();
                }
            }
        };
        mScreenOffThread.start();

        synchronized (mScreenOffThread) {
            while (!mInitComplete) {
                try {
                    mScreenOffThread.wait();
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
        }
        
        mInitComplete = false;
        mHandlerThread = new HandlerThread("PowerManagerService") {
            @Override
            protected void onLooperPrepared() {
                super.onLooperPrepared();
                initInThread();
            }
        };
        mHandlerThread.start();

        synchronized (mHandlerThread) {
            while (!mInitComplete) {
                try {
                    mHandlerThread.wait();
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
        }
        
        nativeInit();
        synchronized (mLocks) {
            updateNativePowerStateLocked();
            // We make sure to start out with the screen on due to user activity.
            // (They did just boot their device, after all.)
            forceUserActivityLocked();
            mInitialized = true;
        }
    }

    void initInThread() {
        mHandler = new Handler();

        mBroadcastWakeLock = new UnsynchronizedWakeLock(
                                PowerManager.PARTIAL_WAKE_LOCK, "sleep_broadcast", true);
        mStayOnWhilePluggedInScreenDimLock = new UnsynchronizedWakeLock(
                                PowerManager.SCREEN_DIM_WAKE_LOCK, "StayOnWhilePluggedIn Screen Dim", false);
        mStayOnWhilePluggedInPartialLock = new UnsynchronizedWakeLock(
                                PowerManager.PARTIAL_WAKE_LOCK, "StayOnWhilePluggedIn Partial", false);
        mPreventScreenOnPartialLock = new UnsynchronizedWakeLock(
                                PowerManager.PARTIAL_WAKE_LOCK, "PreventScreenOn Partial", false);
        mProximityPartialLock = new UnsynchronizedWakeLock(
                                PowerManager.PARTIAL_WAKE_LOCK, "Proximity Partial", false);

        mScreenOnIntent = new Intent(Intent.ACTION_SCREEN_ON);
        mScreenOnIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mScreenOffIntent = new Intent(Intent.ACTION_SCREEN_OFF);
        mScreenOffIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);

        Resources resources = mContext.getResources();

        mAnimateScreenLights = resources.getBoolean(
                com.android.internal.R.bool.config_animateScreenLights);

        mUnplugTurnsOnScreen = resources.getBoolean(
                com.android.internal.R.bool.config_unplugTurnsOnScreen);

        mScreenBrightnessDim = resources.getInteger(
                com.android.internal.R.integer.config_screenBrightnessDim);

        // read settings for auto-brightness
        mUseSoftwareAutoBrightness = resources.getBoolean(
                com.android.internal.R.bool.config_automatic_brightness_available);
        if (mUseSoftwareAutoBrightness) {
            mAutoBrightnessLevels = resources.getIntArray(
                    com.android.internal.R.array.config_autoBrightnessLevels);
            mLcdBacklightValues = resources.getIntArray(
                    com.android.internal.R.array.config_autoBrightnessLcdBacklightValues);
            mButtonBacklightValues = resources.getIntArray(
                    com.android.internal.R.array.config_autoBrightnessButtonBacklightValues);
            mKeyboardBacklightValues = resources.getIntArray(
                    com.android.internal.R.array.config_autoBrightnessKeyboardBacklightValues);
            mLightSensorWarmupTime = resources.getInteger(
                    com.android.internal.R.integer.config_lightSensorWarmupTime);
        }

       ContentResolver resolver = mContext.getContentResolver();
        Cursor settingsCursor = resolver.query(Settings.System.CONTENT_URI, null,
                "(" + Settings.System.NAME + "=?) or ("
                        + Settings.System.NAME + "=?) or ("
                        + Settings.System.NAME + "=?) or ("
                        + Settings.System.NAME + "=?) or ("
                        + Settings.System.NAME + "=?) or ("
                        + Settings.System.NAME + "=?)",
                new String[]{STAY_ON_WHILE_PLUGGED_IN, SCREEN_OFF_TIMEOUT, DIM_SCREEN,
                        SCREEN_BRIGHTNESS_MODE, WINDOW_ANIMATION_SCALE, TRANSITION_ANIMATION_SCALE},
                null);
        mSettings = new ContentQueryMap(settingsCursor, Settings.System.NAME, true, mHandler);
        SettingsObserver settingsObserver = new SettingsObserver();
        mSettings.addObserver(settingsObserver);

        // pretend that the settings changed so we will get their initial state
        settingsObserver.update(mSettings, null);

        // register for the battery changed notifications
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        mContext.registerReceiver(new BatteryReceiver(), filter);
        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        mContext.registerReceiver(new BootCompletedReceiver(), filter);
        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_DOCK_EVENT);
        mContext.registerReceiver(new DockReceiver(), filter);

        // Listen for secure settings changes
        mContext.getContentResolver().registerContentObserver(
            Settings.Secure.CONTENT_URI, true,
            new ContentObserver(new Handler()) {
                public void onChange(boolean selfChange) {
                    updateSettingsValues();
                }
            });
        updateSettingsValues();

        synchronized (mHandlerThread) {
            mInitComplete = true;
            mHandlerThread.notifyAll();
        }
    }

    private class WakeLock implements IBinder.DeathRecipient
    {
        WakeLock(int f, IBinder b, String t, int u, int p) {
            super();
            flags = f;
            binder = b;
            tag = t;
            uid = u == MY_UID ? Process.SYSTEM_UID : u;
            pid = p;
            if (u != MY_UID || (
                    !"KEEP_SCREEN_ON_FLAG".equals(tag)
                    && !"KeyInputQueue".equals(tag))) {
                monitorType = (f & LOCK_MASK) == PowerManager.PARTIAL_WAKE_LOCK
                        ? BatteryStats.WAKE_TYPE_PARTIAL
                        : BatteryStats.WAKE_TYPE_FULL;
            } else {
                monitorType = -1;
            }
            try {
                b.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }
        public void binderDied() {
            synchronized (mLocks) {
                releaseWakeLockLocked(this.binder, 0, true);
            }
        }
        final int flags;
        final IBinder binder;
        final String tag;
        final int uid;
        final int pid;
        final int monitorType;
        WorkSource ws;
        boolean activated = true;
        int minState;
    }

    private void updateWakeLockLocked() {
        if (mStayOnConditions != 0 && mBatteryService.isPowered(mStayOnConditions)) {
            // keep the device on if we're plugged in and mStayOnWhilePluggedIn is set.
            mStayOnWhilePluggedInScreenDimLock.acquire();
            mStayOnWhilePluggedInPartialLock.acquire();
        } else {
            mStayOnWhilePluggedInScreenDimLock.release();
            mStayOnWhilePluggedInPartialLock.release();
        }
    }

    private boolean isScreenLock(int flags)
    {
        int n = flags & LOCK_MASK;
        return n == PowerManager.FULL_WAKE_LOCK
                || n == PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                || n == PowerManager.SCREEN_DIM_WAKE_LOCK
                || n == PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK;
    }

    void enforceWakeSourcePermission(int uid, int pid) {
        if (uid == Process.myUid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_DEVICE_STATS,
                pid, uid, null);
    }

    public void acquireWakeLock(int flags, IBinder lock, String tag, WorkSource ws) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        if (uid != Process.myUid()) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        }
        if (ws != null) {
            enforceWakeSourcePermission(uid, pid);
        }
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mLocks) {
                acquireWakeLockLocked(flags, lock, uid, pid, tag, ws);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    void noteStartWakeLocked(WakeLock wl, WorkSource ws) {
        if (wl.monitorType >= 0) {
            long origId = Binder.clearCallingIdentity();
            try {
                if (ws != null) {
                    mBatteryStats.noteStartWakelockFromSource(ws, wl.pid, wl.tag,
                            wl.monitorType);
                } else {
                    mBatteryStats.noteStartWakelock(wl.uid, wl.pid, wl.tag, wl.monitorType);
                }
            } catch (RemoteException e) {
                // Ignore
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    void noteStopWakeLocked(WakeLock wl, WorkSource ws) {
        if (wl.monitorType >= 0) {
            long origId = Binder.clearCallingIdentity();
            try {
                if (ws != null) {
                    mBatteryStats.noteStopWakelockFromSource(ws, wl.pid, wl.tag,
                            wl.monitorType);
                } else {
                    mBatteryStats.noteStopWakelock(wl.uid, wl.pid, wl.tag, wl.monitorType);
                }
            } catch (RemoteException e) {
                // Ignore
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    public void acquireWakeLockLocked(int flags, IBinder lock, int uid, int pid, String tag,
            WorkSource ws) {
        if (mSpew) {
            Slog.d(TAG, "acquireWakeLock flags=0x" + Integer.toHexString(flags) + " tag=" + tag);
        }

        if (ws != null && ws.size() == 0) {
            ws = null;
        }

        int index = mLocks.getIndex(lock);
        WakeLock wl;
        boolean newlock;
        boolean diffsource;
        WorkSource oldsource;
        if (index < 0) {
            wl = new WakeLock(flags, lock, tag, uid, pid);
            switch (wl.flags & LOCK_MASK)
            {
                case PowerManager.FULL_WAKE_LOCK:
                    if (mUseSoftwareAutoBrightness) {
                        wl.minState = SCREEN_BRIGHT;
                    } else {
                        wl.minState = (mKeyboardVisible ? ALL_BRIGHT : SCREEN_BUTTON_BRIGHT);
                    }
                    break;
                case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                    wl.minState = SCREEN_BRIGHT;
                    break;
                case PowerManager.SCREEN_DIM_WAKE_LOCK:
                    wl.minState = SCREEN_DIM;
                    break;
                case PowerManager.PARTIAL_WAKE_LOCK:
                case PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                    break;
                default:
                    // just log and bail.  we're in the server, so don't
                    // throw an exception.
                    Slog.e(TAG, "bad wakelock type for lock '" + tag + "' "
                            + " flags=" + flags);
                    return;
            }
            mLocks.addLock(wl);
            if (ws != null) {
                wl.ws = new WorkSource(ws);
            }
            newlock = true;
            diffsource = false;
            oldsource = null;
        } else {
            wl = mLocks.get(index);
            newlock = false;
            oldsource = wl.ws;
            if (oldsource != null) {
                if (ws == null) {
                    wl.ws = null;
                    diffsource = true;
                } else {
                    diffsource = oldsource.diff(ws);
                }
            } else if (ws != null) {
                diffsource = true;
            } else {
                diffsource = false;
            }
            if (diffsource) {
                wl.ws = new WorkSource(ws);
            }
        }
        if (isScreenLock(flags)) {
            // if this causes a wakeup, we reactivate all of the locks and
            // set it to whatever they want.  otherwise, we modulate that
            // by the current state so we never turn it more on than
            // it already is.
            if ((flags & LOCK_MASK) == PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) {
                mProximityWakeLockCount++;
                if (mProximityWakeLockCount == 1) {
                    enableProximityLockLocked();
                }
            } else {
                if ((wl.flags & PowerManager.ACQUIRE_CAUSES_WAKEUP) != 0) {
                    int oldWakeLockState = mWakeLockState;
                    mWakeLockState = mLocks.reactivateScreenLocksLocked();

                    // Disable proximity sensor if if user presses power key while we are in the
                    // "waiting for proximity sensor to go negative" state.
                    if ((mWakeLockState & SCREEN_ON_BIT) != 0
                            && mProximitySensorActive && mProximityWakeLockCount == 0) {
                        mProximitySensorActive = false;
                    }

                    if (mSpew) {
                        Slog.d(TAG, "wakeup here mUserState=0x" + Integer.toHexString(mUserState)
                                + " mWakeLockState=0x"
                                + Integer.toHexString(mWakeLockState)
                                + " previous wakeLockState=0x"
                                + Integer.toHexString(oldWakeLockState));
                    }
                } else {
                    if (mSpew) {
                        Slog.d(TAG, "here mUserState=0x" + Integer.toHexString(mUserState)
                                + " mLocks.gatherState()=0x"
                                + Integer.toHexString(mLocks.gatherState())
                                + " mWakeLockState=0x" + Integer.toHexString(mWakeLockState));
                    }
                    mWakeLockState = (mUserState | mWakeLockState) & mLocks.gatherState();
                }
                setPowerState(mWakeLockState | mUserState);
            }
        }
        else if ((flags & LOCK_MASK) == PowerManager.PARTIAL_WAKE_LOCK) {
            if (newlock) {
                mPartialCount++;
                if (mPartialCount == 1) {
                    if (LOG_PARTIAL_WL) EventLog.writeEvent(EventLogTags.POWER_PARTIAL_WAKE_STATE, 1, tag);
                }
            }
            Power.acquireWakeLock(Power.PARTIAL_WAKE_LOCK,PARTIAL_NAME);
        }

        if (diffsource) {
            // If the lock sources have changed, need to first release the
            // old ones.
            noteStopWakeLocked(wl, oldsource);
        }
        if (newlock || diffsource) {
            noteStartWakeLocked(wl, ws);
        }
    }

    public void updateWakeLockWorkSource(IBinder lock, WorkSource ws) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        if (ws != null && ws.size() == 0) {
            ws = null;
        }
        if (ws != null) {
            enforceWakeSourcePermission(uid, pid);
        }
        synchronized (mLocks) {
            int index = mLocks.getIndex(lock);
            if (index < 0) {
                throw new IllegalArgumentException("Wake lock not active");
            }
            WakeLock wl = mLocks.get(index);
            WorkSource oldsource = wl.ws;
            wl.ws = ws != null ? new WorkSource(ws) : null;
            noteStopWakeLocked(wl, oldsource);
            noteStartWakeLocked(wl, ws);
        }
    }

    public void releaseWakeLock(IBinder lock, int flags) {
        int uid = Binder.getCallingUid();
        if (uid != Process.myUid()) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        }

        synchronized (mLocks) {
            releaseWakeLockLocked(lock, flags, false);
        }
    }

    private void releaseWakeLockLocked(IBinder lock, int flags, boolean death) {
        WakeLock wl = mLocks.removeLock(lock);
        if (wl == null) {
            return;
        }

        if (mSpew) {
            Slog.d(TAG, "releaseWakeLock flags=0x"
                    + Integer.toHexString(wl.flags) + " tag=" + wl.tag);
        }

        if (isScreenLock(wl.flags)) {
            if ((wl.flags & LOCK_MASK) == PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) {
                mProximityWakeLockCount--;
                if (mProximityWakeLockCount == 0) {
                    if (mProximitySensorActive &&
                            ((flags & PowerManager.WAIT_FOR_PROXIMITY_NEGATIVE) != 0)) {
                        // wait for proximity sensor to go negative before disabling sensor
                        if (mDebugProximitySensor) {
                            Slog.d(TAG, "waiting for proximity sensor to go negative");
                        }
                    } else {
                        disableProximityLockLocked();
                    }
                }
            } else {
                mWakeLockState = mLocks.gatherState();
                // goes in the middle to reduce flicker
                if ((wl.flags & PowerManager.ON_AFTER_RELEASE) != 0) {
                    userActivity(SystemClock.uptimeMillis(), -1, false, OTHER_EVENT, false);
                }
                setPowerState(mWakeLockState | mUserState);
            }
        }
        else if ((wl.flags & LOCK_MASK) == PowerManager.PARTIAL_WAKE_LOCK) {
            mPartialCount--;
            if (mPartialCount == 0) {
                if (LOG_PARTIAL_WL) EventLog.writeEvent(EventLogTags.POWER_PARTIAL_WAKE_STATE, 0, wl.tag);
                Power.releaseWakeLock(PARTIAL_NAME);
            }
        }
        // Unlink the lock from the binder.
        wl.binder.unlinkToDeath(wl, 0);

        noteStopWakeLocked(wl, wl.ws);
    }

    private class PokeLock implements IBinder.DeathRecipient
    {
        PokeLock(int p, IBinder b, String t) {
            super();
            this.pokey = p;
            this.binder = b;
            this.tag = t;
            try {
                b.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }
        public void binderDied() {
            setPokeLock(0, this.binder, this.tag);
        }
        int pokey;
        IBinder binder;
        String tag;
        boolean awakeOnSet;
    }

    public void setPokeLock(int pokey, IBinder token, String tag) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
        if (token == null) {
            Slog.e(TAG, "setPokeLock got null token for tag='" + tag + "'");
            return;
        }

        if ((pokey & POKE_LOCK_TIMEOUT_MASK) == POKE_LOCK_TIMEOUT_MASK) {
            throw new IllegalArgumentException("setPokeLock can't have both POKE_LOCK_SHORT_TIMEOUT"
                    + " and POKE_LOCK_MEDIUM_TIMEOUT");
        }

        synchronized (mLocks) {
            if (pokey != 0) {
                PokeLock p = mPokeLocks.get(token);
                int oldPokey = 0;
                if (p != null) {
                    oldPokey = p.pokey;
                    p.pokey = pokey;
                } else {
                    p = new PokeLock(pokey, token, tag);
                    mPokeLocks.put(token, p);
                }
                int oldTimeout = oldPokey & POKE_LOCK_TIMEOUT_MASK;
                int newTimeout = pokey & POKE_LOCK_TIMEOUT_MASK;
                if (((mPowerState & SCREEN_ON_BIT) == 0) && (oldTimeout != newTimeout)) {
                    p.awakeOnSet = true;
                }
            } else {
                PokeLock rLock = mPokeLocks.remove(token);
                if (rLock != null) {
                    token.unlinkToDeath(rLock, 0);
                }
            }

            int oldPokey = mPokey;
            int cumulative = 0;
            boolean oldAwakeOnSet = mPokeAwakeOnSet;
            boolean awakeOnSet = false;
            for (PokeLock p: mPokeLocks.values()) {
                cumulative |= p.pokey;
                if (p.awakeOnSet) {
                    awakeOnSet = true;
                }
            }
            mPokey = cumulative;
            mPokeAwakeOnSet = awakeOnSet;

            int oldCumulativeTimeout = oldPokey & POKE_LOCK_TIMEOUT_MASK;
            int newCumulativeTimeout = pokey & POKE_LOCK_TIMEOUT_MASK;

            if (oldCumulativeTimeout != newCumulativeTimeout) {
                setScreenOffTimeoutsLocked();
                // reset the countdown timer, but use the existing nextState so it doesn't
                // change anything
                setTimeoutLocked(SystemClock.uptimeMillis(), mTimeoutTask.nextState);
            }
        }
    }

    private static String lockType(int type)
    {
        switch (type)
        {
            case PowerManager.FULL_WAKE_LOCK:
                return "FULL_WAKE_LOCK                ";
            case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                return "SCREEN_BRIGHT_WAKE_LOCK       ";
            case PowerManager.SCREEN_DIM_WAKE_LOCK:
                return "SCREEN_DIM_WAKE_LOCK          ";
            case PowerManager.PARTIAL_WAKE_LOCK:
                return "PARTIAL_WAKE_LOCK             ";
            case PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                return "PROXIMITY_SCREEN_OFF_WAKE_LOCK";
            default:
                return "???                           ";
        }
    }

    private static String dumpPowerState(int state) {
        return (((state & KEYBOARD_BRIGHT_BIT) != 0)
                        ? "KEYBOARD_BRIGHT_BIT " : "")
                + (((state & SCREEN_BRIGHT_BIT) != 0)
                        ? "SCREEN_BRIGHT_BIT " : "")
                + (((state & SCREEN_ON_BIT) != 0)
                        ? "SCREEN_ON_BIT " : "")
                + (((state & BATTERY_LOW_BIT) != 0)
                        ? "BATTERY_LOW_BIT " : "");
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump PowerManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        long now = SystemClock.uptimeMillis();

        synchronized (mLocks) {
            pw.println("Power Manager State:");
            pw.println("  mIsPowered=" + mIsPowered
                    + " mPowerState=" + mPowerState
                    + " mScreenOffTime=" + (SystemClock.elapsedRealtime()-mScreenOffTime)
                    + " ms");
            pw.println("  mPartialCount=" + mPartialCount);
            pw.println("  mWakeLockState=" + dumpPowerState(mWakeLockState));
            pw.println("  mUserState=" + dumpPowerState(mUserState));
            pw.println("  mPowerState=" + dumpPowerState(mPowerState));
            pw.println("  mLocks.gather=" + dumpPowerState(mLocks.gatherState()));
            pw.println("  mNextTimeout=" + mNextTimeout + " now=" + now
                    + " " + ((mNextTimeout-now)/1000) + "s from now");
            pw.println("  mDimScreen=" + mDimScreen
                    + " mStayOnConditions=" + mStayOnConditions
                    + " mPreparingForScreenOn=" + mPreparingForScreenOn
                    + " mSkippedScreenOn=" + mSkippedScreenOn);
            pw.println("  mScreenOffReason=" + mScreenOffReason
                    + " mUserState=" + mUserState);
            pw.println("  mBroadcastQueue={" + mBroadcastQueue[0] + ',' + mBroadcastQueue[1]
                    + ',' + mBroadcastQueue[2] + "}");
            pw.println("  mBroadcastWhy={" + mBroadcastWhy[0] + ',' + mBroadcastWhy[1]
                    + ',' + mBroadcastWhy[2] + "}");
            pw.println("  mPokey=" + mPokey + " mPokeAwakeonSet=" + mPokeAwakeOnSet);
            pw.println("  mKeyboardVisible=" + mKeyboardVisible
                    + " mUserActivityAllowed=" + mUserActivityAllowed);
            pw.println("  mKeylightDelay=" + mKeylightDelay + " mDimDelay=" + mDimDelay
                    + " mScreenOffDelay=" + mScreenOffDelay);
            pw.println("  mPreventScreenOn=" + mPreventScreenOn
                    + "  mScreenBrightnessOverride=" + mScreenBrightnessOverride
                    + "  mButtonBrightnessOverride=" + mButtonBrightnessOverride);
            pw.println("  mScreenOffTimeoutSetting=" + mScreenOffTimeoutSetting
                    + " mMaximumScreenOffTimeout=" + mMaximumScreenOffTimeout);
            pw.println("  mLastScreenOnTime=" + mLastScreenOnTime);
            pw.println("  mBroadcastWakeLock=" + mBroadcastWakeLock);
            pw.println("  mStayOnWhilePluggedInScreenDimLock=" + mStayOnWhilePluggedInScreenDimLock);
            pw.println("  mStayOnWhilePluggedInPartialLock=" + mStayOnWhilePluggedInPartialLock);
            pw.println("  mPreventScreenOnPartialLock=" + mPreventScreenOnPartialLock);
            pw.println("  mProximityPartialLock=" + mProximityPartialLock);
            pw.println("  mProximityWakeLockCount=" + mProximityWakeLockCount);
            pw.println("  mProximitySensorEnabled=" + mProximitySensorEnabled);
            pw.println("  mProximitySensorActive=" + mProximitySensorActive);
            pw.println("  mProximityPendingValue=" + mProximityPendingValue);
            pw.println("  mLastProximityEventTime=" + mLastProximityEventTime);
            pw.println("  mLightSensorEnabled=" + mLightSensorEnabled);
            pw.println("  mLightSensorValue=" + mLightSensorValue
                    + " mLightSensorPendingValue=" + mLightSensorPendingValue);
            pw.println("  mLightSensorPendingDecrease=" + mLightSensorPendingDecrease
                    + " mLightSensorPendingIncrease=" + mLightSensorPendingIncrease);
            pw.println("  mLightSensorScreenBrightness=" + mLightSensorScreenBrightness
                    + " mLightSensorButtonBrightness=" + mLightSensorButtonBrightness
                    + " mLightSensorKeyboardBrightness=" + mLightSensorKeyboardBrightness);
            pw.println("  mUseSoftwareAutoBrightness=" + mUseSoftwareAutoBrightness);
            pw.println("  mAutoBrightessEnabled=" + mAutoBrightessEnabled);
            mScreenBrightness.dump(pw, "  mScreenBrightness: ");

            int N = mLocks.size();
            pw.println();
            pw.println("mLocks.size=" + N + ":");
            for (int i=0; i<N; i++) {
                WakeLock wl = mLocks.get(i);
                String type = lockType(wl.flags & LOCK_MASK);
                String acquireCausesWakeup = "";
                if ((wl.flags & PowerManager.ACQUIRE_CAUSES_WAKEUP) != 0) {
                    acquireCausesWakeup = "ACQUIRE_CAUSES_WAKEUP ";
                }
                String activated = "";
                if (wl.activated) {
                   activated = " activated";
                }
                pw.println("  " + type + " '" + wl.tag + "'" + acquireCausesWakeup
                        + activated + " (minState=" + wl.minState + ", uid=" + wl.uid
                        + ", pid=" + wl.pid + ")");
            }

            pw.println();
            pw.println("mPokeLocks.size=" + mPokeLocks.size() + ":");
            for (PokeLock p: mPokeLocks.values()) {
                pw.println("    poke lock '" + p.tag + "':"
                        + ((p.pokey & POKE_LOCK_IGNORE_TOUCH_EVENTS) != 0
                                ? " POKE_LOCK_IGNORE_TOUCH_EVENTS" : "")
                        + ((p.pokey & POKE_LOCK_SHORT_TIMEOUT) != 0
                                ? " POKE_LOCK_SHORT_TIMEOUT" : "")
                        + ((p.pokey & POKE_LOCK_MEDIUM_TIMEOUT) != 0
                                ? " POKE_LOCK_MEDIUM_TIMEOUT" : ""));
            }

            pw.println();
        }
    }

    private void setTimeoutLocked(long now, int nextState) {
        setTimeoutLocked(now, -1, nextState);
    }

    // If they gave a timeoutOverride it is the number of seconds
    // to screen-off.  Figure out where in the countdown cycle we
    // should jump to.
    private void setTimeoutLocked(long now, final long originalTimeoutOverride, int nextState) {
        long timeoutOverride = originalTimeoutOverride;
        if (mBootCompleted) {
            synchronized (mLocks) {
                long when = 0;
                if (timeoutOverride <= 0) {
                    switch (nextState)
                    {
                        case SCREEN_BRIGHT:
                            when = now + mKeylightDelay;
                            break;
                        case SCREEN_DIM:
                            if (mDimDelay >= 0) {
                                when = now + mDimDelay;
                                break;
                            } else {
                                Slog.w(TAG, "mDimDelay=" + mDimDelay + " while trying to dim");
                            }
                       case SCREEN_OFF:
                            synchronized (mLocks) {
                                when = now + mScreenOffDelay;
                            }
                            break;
                        default:
                            when = now;
                            break;
                    }
                } else {
                    override: {
                        if (timeoutOverride <= mScreenOffDelay) {
                            when = now + timeoutOverride;
                            nextState = SCREEN_OFF;
                            break override;
                        }
                        timeoutOverride -= mScreenOffDelay;

                        if (mDimDelay >= 0) {
                             if (timeoutOverride <= mDimDelay) {
                                when = now + timeoutOverride;
                                nextState = SCREEN_DIM;
                                break override;
                            }
                            timeoutOverride -= mDimDelay;
                        }

                        when = now + timeoutOverride;
                        nextState = SCREEN_BRIGHT;
                    }
                }
                if (mSpew) {
                    Slog.d(TAG, "setTimeoutLocked now=" + now
                            + " timeoutOverride=" + timeoutOverride
                            + " nextState=" + nextState + " when=" + when);
                }

                mHandler.removeCallbacks(mTimeoutTask);
                mTimeoutTask.nextState = nextState;
                mTimeoutTask.remainingTimeoutOverride = timeoutOverride > 0
                        ? (originalTimeoutOverride - timeoutOverride)
                        : -1;
                mHandler.postAtTime(mTimeoutTask, when);
                mNextTimeout = when; // for debugging
            }
        }
    }

    private void cancelTimerLocked()
    {
        mHandler.removeCallbacks(mTimeoutTask);
        mTimeoutTask.nextState = -1;
    }

    private class TimeoutTask implements Runnable
    {
        int nextState; // access should be synchronized on mLocks
        long remainingTimeoutOverride;
        public void run()
        {
            synchronized (mLocks) {
                if (mSpew) {
                    Slog.d(TAG, "user activity timeout timed out nextState=" + this.nextState);
                }

                if (nextState == -1) {
                    return;
                }

                mUserState = this.nextState;
                setPowerState(this.nextState | mWakeLockState);

                long now = SystemClock.uptimeMillis();

                switch (this.nextState)
                {
                    case SCREEN_BRIGHT:
                        if (mDimDelay >= 0) {
                            setTimeoutLocked(now, remainingTimeoutOverride, SCREEN_DIM);
                            break;
                        }
                    case SCREEN_DIM:
                        setTimeoutLocked(now, remainingTimeoutOverride, SCREEN_OFF);
                        break;
                }
            }
        }
    }

    private void sendNotificationLocked(boolean on, int why) {
        if (!mInitialized) {
            // No notifications sent until first initialization is done.
            // This is so that when we are moving from our initial state
            // which looks like the screen was off to it being on, we do not
            // go through the process of waiting for the higher-level user
            // space to be ready before turning up the display brightness.
            // (And also do not send needless broadcasts about the screen.)
            return;
        }

        if (DEBUG_SCREEN_ON) {
            RuntimeException here = new RuntimeException("here");
            here.fillInStackTrace();
            Slog.i(TAG, "sendNotificationLocked: " + on, here);
        }

        if (!on) {
            mStillNeedSleepNotification = false;
        }

        // Add to the queue.
        int index = 0;
        while (mBroadcastQueue[index] != -1) {
            index++;
        }
        mBroadcastQueue[index] = on ? 1 : 0;
        mBroadcastWhy[index] = why;

        // If we added it position 2, then there is a pair that can be stripped.
        // If we added it position 1 and we're turning the screen off, we can strip
        // the pair and do nothing, because the screen is already off, and therefore
        // keyguard has already been enabled.
        // However, if we added it at position 1 and we're turning it on, then position
        // 0 was to turn it off, and we can't strip that, because keyguard needs to come
        // on, so have to run the queue then.
        if (index == 2) {
            // While we're collapsing them, if it's going off, and the new reason
            // is more significant than the first, then use the new one.
            if (!on && mBroadcastWhy[0] > why) {
                mBroadcastWhy[0] = why;
            }
            mBroadcastQueue[0] = on ? 1 : 0;
            mBroadcastQueue[1] = -1;
            mBroadcastQueue[2] = -1;
            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_STOP, 1, mBroadcastWakeLock.mCount);
            mBroadcastWakeLock.release();
            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_STOP, 1, mBroadcastWakeLock.mCount);
            mBroadcastWakeLock.release();
            index = 0;
        }
        if (index == 1 && !on) {
            mBroadcastQueue[0] = -1;
            mBroadcastQueue[1] = -1;
            index = -1;
            // The wake lock was being held, but we're not actually going to do any
            // broadcasts, so release the wake lock.
            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_STOP, 1, mBroadcastWakeLock.mCount);
            mBroadcastWakeLock.release();
        }

        // The broadcast queue has changed; make sure the screen is on if it
        // is now possible for it to be.
        if (mSkippedScreenOn) {
            updateLightsLocked(mPowerState, SCREEN_ON_BIT);
        }

        // Now send the message.
        if (index >= 0) {
            // Acquire the broadcast wake lock before changing the power
            // state. It will be release after the broadcast is sent.
            // We always increment the ref count for each notification in the queue
            // and always decrement when that notification is handled.
            mBroadcastWakeLock.acquire();
            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_SEND, mBroadcastWakeLock.mCount);
            mHandler.post(mNotificationTask);
        }
    }

    private WindowManagerPolicy.ScreenOnListener mScreenOnListener =
            new WindowManagerPolicy.ScreenOnListener() {
                @Override public void onScreenOn() {
                    synchronized (mLocks) {
                        if (mPreparingForScreenOn) {
                            mPreparingForScreenOn = false;
                            updateLightsLocked(mPowerState, SCREEN_ON_BIT);
                            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_STOP,
                                    4, mBroadcastWakeLock.mCount);
                            mBroadcastWakeLock.release();
                        }
                    }
                }
    };

    private Runnable mNotificationTask = new Runnable()
    {
        public void run()
        {
            while (true) {
                int value;
                int why;
                WindowManagerPolicy policy;
                synchronized (mLocks) {
                    value = mBroadcastQueue[0];
                    why = mBroadcastWhy[0];
                    for (int i=0; i<2; i++) {
                        mBroadcastQueue[i] = mBroadcastQueue[i+1];
                        mBroadcastWhy[i] = mBroadcastWhy[i+1];
                    }
                    policy = getPolicyLocked();
                    if (value == 1 && !mPreparingForScreenOn) {
                        mPreparingForScreenOn = true;
                        mBroadcastWakeLock.acquire();
                        EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_SEND,
                                mBroadcastWakeLock.mCount);
                    }
                }
                if (value == 1) {
                    mScreenOnStart = SystemClock.uptimeMillis();

                    policy.screenTurningOn(mScreenOnListener);
                    try {
                        ActivityManagerNative.getDefault().wakingUp();
                    } catch (RemoteException e) {
                        // ignore it
                    }

                    if (mSpew) {
                        Slog.d(TAG, "mBroadcastWakeLock=" + mBroadcastWakeLock);
                    }
                    if (mContext != null && ActivityManagerNative.isSystemReady()) {
                        mContext.sendOrderedBroadcast(mScreenOnIntent, null,
                                mScreenOnBroadcastDone, mHandler, 0, null, null);
                    } else {
                        synchronized (mLocks) {
                            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_STOP, 2,
                                    mBroadcastWakeLock.mCount);
                            mBroadcastWakeLock.release();
                        }
                    }
                }
                else if (value == 0) {
                    mScreenOffStart = SystemClock.uptimeMillis();

                    policy.screenTurnedOff(why);
                    try {
                        ActivityManagerNative.getDefault().goingToSleep();
                    } catch (RemoteException e) {
                        // ignore it.
                    }

                    if (mContext != null && ActivityManagerNative.isSystemReady()) {
                        mContext.sendOrderedBroadcast(mScreenOffIntent, null,
                                mScreenOffBroadcastDone, mHandler, 0, null, null);
                    } else {
                        synchronized (mLocks) {
                            EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_STOP, 3,
                                    mBroadcastWakeLock.mCount);
                            updateLightsLocked(mPowerState, SCREEN_ON_BIT);
                            mBroadcastWakeLock.release();
                        }
                    }
                }
                else {
                    // If we're in this case, then this handler is running for a previous
                    // paired transaction.  mBroadcastWakeLock will already have been released.
                    break;
                }
            }
        }
    };

    long mScreenOnStart;
    private BroadcastReceiver mScreenOnBroadcastDone = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            synchronized (mLocks) {
                EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_DONE, 1,
                        SystemClock.uptimeMillis() - mScreenOnStart, mBroadcastWakeLock.mCount);
                mBroadcastWakeLock.release();
            }
        }
    };

    long mScreenOffStart;
    private BroadcastReceiver mScreenOffBroadcastDone = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            synchronized (mLocks) {
                EventLog.writeEvent(EventLogTags.POWER_SCREEN_BROADCAST_DONE, 0,
                        SystemClock.uptimeMillis() - mScreenOffStart, mBroadcastWakeLock.mCount);
                mBroadcastWakeLock.release();
            }
        }
    };

    void logPointerUpEvent() {
        if (LOG_TOUCH_DOWNS) {
            mTotalTouchDownTime += SystemClock.elapsedRealtime() - mLastTouchDown;
            mLastTouchDown = 0;
        }
    }

    void logPointerDownEvent() {
        if (LOG_TOUCH_DOWNS) {
            // If we are not already timing a down/up sequence
            if (mLastTouchDown == 0) {
                mLastTouchDown = SystemClock.elapsedRealtime();
                mTouchCycles++;
            }
        }
    }

    /**
     * Prevents the screen from turning on even if it *should* turn on due
     * to a subsequent full wake lock being acquired.
     * <p>
     * This is a temporary hack that allows an activity to "cover up" any
     * display glitches that happen during the activity's startup
     * sequence.  (Specifically, this API was added to work around a
     * cosmetic bug in the "incoming call" sequence, where the lock screen
     * would flicker briefly before the incoming call UI became visible.)
     * TODO: There ought to be a more elegant way of doing this,
     * probably by having the PowerManager and ActivityManager
     * work together to let apps specify that the screen on/off
     * state should be synchronized with the Activity lifecycle.
     * <p>
     * Note that calling preventScreenOn(true) will NOT turn the screen
     * off if it's currently on.  (This API only affects *future*
     * acquisitions of full wake locks.)
     * But calling preventScreenOn(false) WILL turn the screen on if
     * it's currently off because of a prior preventScreenOn(true) call.
     * <p>
     * Any call to preventScreenOn(true) MUST be followed promptly by a call
     * to preventScreenOn(false).  In fact, if the preventScreenOn(false)
     * call doesn't occur within 5 seconds, we'll turn the screen back on
     * ourselves (and log a warning about it); this prevents a buggy app
     * from disabling the screen forever.)
     * <p>
     * TODO: this feature should really be controlled by a new type of poke
     * lock (rather than an IPowerManager call).
     */
    public void preventScreenOn(boolean prevent) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);

        synchronized (mLocks) {
            if (prevent) {
                // First of all, grab a partial wake lock to
                // make sure the CPU stays on during the entire
                // preventScreenOn(true) -> preventScreenOn(false) sequence.
                mPreventScreenOnPartialLock.acquire();

                // Post a forceReenableScreen() call (for 5 seconds in the
                // future) to make sure the matching preventScreenOn(false) call
                // has happened by then.
                mHandler.removeCallbacks(mForceReenableScreenTask);
                mHandler.postDelayed(mForceReenableScreenTask, 5000);

                // Finally, set the flag that prevents the screen from turning on.
                // (Below, in setPowerState(), we'll check mPreventScreenOn and
                // we *won't* call setScreenStateLocked(true) if it's set.)
                mPreventScreenOn = true;
            } else {
                // (Re)enable the screen.
                mPreventScreenOn = false;

                // We're "undoing" a the prior preventScreenOn(true) call, so we
                // no longer need the 5-second safeguard.
                mHandler.removeCallbacks(mForceReenableScreenTask);

                // Forcibly turn on the screen if it's supposed to be on.  (This
                // handles the case where the screen is currently off because of
                // a prior preventScreenOn(true) call.)
                if (!mProximitySensorActive && (mPowerState & SCREEN_ON_BIT) != 0) {
                    if (mSpew) {
                        Slog.d(TAG,
                              "preventScreenOn: turning on after a prior preventScreenOn(true)!");
                    }
                    int err = setScreenStateLocked(true);
                    if (err != 0) {
                        Slog.w(TAG, "preventScreenOn: error from setScreenStateLocked(): " + err);
                    }
                }

                // Release the partial wake lock that we held during the
                // preventScreenOn(true) -> preventScreenOn(false) sequence.
                mPreventScreenOnPartialLock.release();
            }
        }
    }

    public void setScreenBrightnessOverride(int brightness) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);

        if (mSpew) Slog.d(TAG, "setScreenBrightnessOverride " + brightness);
        synchronized (mLocks) {
            if (mScreenBrightnessOverride != brightness) {
                mScreenBrightnessOverride = brightness;
                if (isScreenOn()) {
                    updateLightsLocked(mPowerState, SCREEN_ON_BIT);
                }
            }
        }
    }

    public void setButtonBrightnessOverride(int brightness) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);

        if (mSpew) Slog.d(TAG, "setButtonBrightnessOverride " + brightness);
         synchronized (mLocks) {
           if (mButtonBrightnessOverride != brightness) {
                mButtonBrightnessOverride = brightness;
                if (isScreenOn()) {
                    updateLightsLocked(mPowerState, BUTTON_BRIGHT_BIT | KEYBOARD_BRIGHT_BIT);
                }
            }
        }
    }

    /**
     * Sanity-check that gets called 5 seconds after any call to
     * preventScreenOn(true).  This ensures that the original call
     * is followed promptly by a call to preventScreenOn(false).
     */
    private void forceReenableScreen() {
        // We shouldn't get here at all if mPreventScreenOn is false, since
        // we should have already removed any existing
        // mForceReenableScreenTask messages...
        if (!mPreventScreenOn) {
            Slog.w(TAG, "forceReenableScreen: mPreventScreenOn is false, nothing to do");
            return;
        }

        // Uh oh.  It's been 5 seconds since a call to
        // preventScreenOn(true) and we haven't re-enabled the screen yet.
        // This means the app that called preventScreenOn(true) is either
        // slow (i.e. it took more than 5 seconds to call preventScreenOn(false)),
        // or buggy (i.e. it forgot to call preventScreenOn(false), or
        // crashed before doing so.)

        // Log a warning, and forcibly turn the screen back on.
        Slog.w(TAG, "App called preventScreenOn(true) but didn't promptly reenable the screen! "
              + "Forcing the screen back on...");
        preventScreenOn(false);
    }

    private Runnable mForceReenableScreenTask = new Runnable() {
            public void run() {
                forceReenableScreen();
            }
        };

    private int setScreenStateLocked(boolean on) {
        if (DEBUG_SCREEN_ON) {
            RuntimeException e = new RuntimeException("here");
            e.fillInStackTrace();
            Slog.i(TAG, "Set screen state: " + on, e);
        }
        if (on) {
            if ((mPowerState & SCREEN_ON_BIT) == 0 || mSkippedScreenOn) {
                // If we are turning the screen state on, but the screen
                // light is currently off, then make sure that we set the
                // light at this point to 0.  This is the case where we are
                // turning on the screen and waiting for the UI to be drawn
                // before showing it to the user.  We want the light off
                // until it is ready to be shown to the user, not it using
                // whatever the last value it had.
                if (DEBUG_SCREEN_ON) {
                    Slog.i(TAG, "Forcing brightness 0: mPowerState=0x"
                            + Integer.toHexString(mPowerState)
                            + " mSkippedScreenOn=" + mSkippedScreenOn);
                }
                mScreenBrightness.forceValueLocked(Power.BRIGHTNESS_OFF);
            }
        }
        int err = Power.setScreenState(on);
        if (err == 0) {
            mLastScreenOnTime = (on ? SystemClock.elapsedRealtime() : 0);
            if (mUseSoftwareAutoBrightness) {
                enableLightSensorLocked(on);
                if (!on) {
                    // make sure button and key backlights are off too
                    mButtonLight.turnOff();
                    mKeyboardLight.turnOff();
                }
            }
        }
        return err;
    }

    private void setPowerState(int state)
    {
        setPowerState(state, false, WindowManagerPolicy.OFF_BECAUSE_OF_TIMEOUT);
    }

    private void setPowerState(int newState, boolean noChangeLights, int reason)
    {
        synchronized (mLocks) {
            int err;

            if (mSpew) {
                Slog.d(TAG, "setPowerState: mPowerState=0x" + Integer.toHexString(mPowerState)
                        + " newState=0x" + Integer.toHexString(newState)
                        + " noChangeLights=" + noChangeLights
                        + " reason=" + reason);
            }

            if (noChangeLights) {
                newState = (newState & ~LIGHTS_MASK) | (mPowerState & LIGHTS_MASK);
            }
            if (mProximitySensorActive) {
                // don't turn on the screen when the proximity sensor lock is held
                newState = (newState & ~SCREEN_BRIGHT);
            }

            if (batteryIsLow()) {
                newState |= BATTERY_LOW_BIT;
            } else {
                newState &= ~BATTERY_LOW_BIT;
            }
            if (newState == mPowerState && mInitialized) {
                return;
            }

            if (!mBootCompleted && !mUseSoftwareAutoBrightness) {
                newState |= ALL_BRIGHT;
            }

            boolean oldScreenOn = (mPowerState & SCREEN_ON_BIT) != 0;
            boolean newScreenOn = (newState & SCREEN_ON_BIT) != 0;

            if (mSpew) {
                Slog.d(TAG, "setPowerState: mPowerState=" + mPowerState
                        + " newState=" + newState + " noChangeLights=" + noChangeLights);
                Slog.d(TAG, "  oldKeyboardBright=" + ((mPowerState & KEYBOARD_BRIGHT_BIT) != 0)
                         + " newKeyboardBright=" + ((newState & KEYBOARD_BRIGHT_BIT) != 0));
                Slog.d(TAG, "  oldScreenBright=" + ((mPowerState & SCREEN_BRIGHT_BIT) != 0)
                         + " newScreenBright=" + ((newState & SCREEN_BRIGHT_BIT) != 0));
                Slog.d(TAG, "  oldButtonBright=" + ((mPowerState & BUTTON_BRIGHT_BIT) != 0)
                         + " newButtonBright=" + ((newState & BUTTON_BRIGHT_BIT) != 0));
                Slog.d(TAG, "  oldScreenOn=" + oldScreenOn
                         + " newScreenOn=" + newScreenOn);
                Slog.d(TAG, "  oldBatteryLow=" + ((mPowerState & BATTERY_LOW_BIT) != 0)
                         + " newBatteryLow=" + ((newState & BATTERY_LOW_BIT) != 0));
            }

            final boolean stateChanged = mPowerState != newState;

            if (oldScreenOn != newScreenOn) {
                if (newScreenOn) {
                    // When the user presses the power button, we need to always send out the
                    // notification that it's going to sleep so the keyguard goes on.  But
                    // we can't do that until the screen fades out, so we don't show the keyguard
                    // too early.
                    if (mStillNeedSleepNotification) {
                        sendNotificationLocked(false, WindowManagerPolicy.OFF_BECAUSE_OF_USER);
                    }

                    // Turn on the screen UNLESS there was a prior
                    // preventScreenOn(true) request.  (Note that the lifetime
                    // of a single preventScreenOn() request is limited to 5
                    // seconds to prevent a buggy app from disabling the
                    // screen forever; see forceReenableScreen().)
                    boolean reallyTurnScreenOn = true;
                    if (mSpew) {
                        Slog.d(TAG, "- turning screen on...  mPreventScreenOn = "
                              + mPreventScreenOn);
                    }

                    if (mPreventScreenOn) {
                        if (mSpew) {
                            Slog.d(TAG, "- PREVENTING screen from really turning on!");
                        }
                        reallyTurnScreenOn = false;
                    }
                    if (reallyTurnScreenOn) {
                        err = setScreenStateLocked(true);
                        long identity = Binder.clearCallingIdentity();
                        try {
                            mBatteryStats.noteScreenBrightness(getPreferredBrightness());
                            mBatteryStats.noteScreenOn();
                        } catch (RemoteException e) {
                            Slog.w(TAG, "RemoteException calling noteScreenOn on BatteryStatsService", e);
                        } finally {
                            Binder.restoreCallingIdentity(identity);
                        }
                    } else {
                        setScreenStateLocked(false);
                        // But continue as if we really did turn the screen on...
                        err = 0;
                    }

                    mLastTouchDown = 0;
                    mTotalTouchDownTime = 0;
                    mTouchCycles = 0;
                    EventLog.writeEvent(EventLogTags.POWER_SCREEN_STATE, 1, reason,
                            mTotalTouchDownTime, mTouchCycles);
                    if (err == 0) {
                        sendNotificationLocked(true, -1);
                        // Update the lights *after* taking care of turning the
                        // screen on, so we do this after our notifications are
                        // enqueued and thus will delay turning on the screen light
                        // until the windows are correctly displayed.
                        if (stateChanged) {
                            updateLightsLocked(newState, 0);
                        }
                        mPowerState |= SCREEN_ON_BIT;
                    }

                } else {
                    // Update the lights *before* taking care of turning the
                    // screen off, so we can initiate any animations that are desired.
                    if (stateChanged) {
                        updateLightsLocked(newState, 0);
                    }

                    // cancel light sensor task
                    mHandler.removeCallbacks(mAutoBrightnessTask);
                    mLightSensorPendingDecrease = false;
                    mLightSensorPendingIncrease = false;
                    mScreenOffTime = SystemClock.elapsedRealtime();
                    long identity = Binder.clearCallingIdentity();
                    try {
                        mBatteryStats.noteScreenOff();
                    } catch (RemoteException e) {
                        Slog.w(TAG, "RemoteException calling noteScreenOff on BatteryStatsService", e);
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                    mPowerState &= ~SCREEN_ON_BIT;
                    mScreenOffReason = reason;
                    if (!mScreenBrightness.animating) {
                        err = screenOffFinishedAnimatingLocked(reason);
                    } else {
                        err = 0;
                        mLastTouchDown = 0;
                    }
                }
            } else if (stateChanged) {
                // Screen on/off didn't change, but lights may have.
                updateLightsLocked(newState, 0);
            }

            mPowerState = (mPowerState & ~LIGHTS_MASK) | (newState & LIGHTS_MASK);

            updateNativePowerStateLocked();
        }
    }

    private void updateNativePowerStateLocked() {
        nativeSetPowerState(
                (mPowerState & SCREEN_ON_BIT) != 0,
                (mPowerState & SCREEN_BRIGHT) == SCREEN_BRIGHT);
    }

    private int screenOffFinishedAnimatingLocked(int reason) {
        // I don't think we need to check the current state here because all of these
        // Power.setScreenState and sendNotificationLocked can both handle being
        // called multiple times in the same state. -joeo
        EventLog.writeEvent(EventLogTags.POWER_SCREEN_STATE, 0, reason, mTotalTouchDownTime,
                mTouchCycles);
        mLastTouchDown = 0;
        int err = setScreenStateLocked(false);
        if (err == 0) {
            mScreenOffReason = reason;
            sendNotificationLocked(false, reason);
        }
        return err;
    }

    private boolean batteryIsLow() {
        return (!mIsPowered &&
                mBatteryService.getBatteryLevel() <= Power.LOW_BATTERY_THRESHOLD);
    }

    private boolean shouldDeferScreenOnLocked() {
        if (mPreparingForScreenOn) {
            // Currently waiting for confirmation from the policy that it
            // is okay to turn on the screen.  Don't allow the screen to go
            // on until that is done.
            if (DEBUG_SCREEN_ON) Slog.i(TAG,
                    "updateLights: delaying screen on due to mPreparingForScreenOn");
            return true;
        } else {
            // If there is a screen-on command in the notification queue, we
            // can't turn the screen on until it has been processed (and we
            // have set mPreparingForScreenOn) or it has been dropped.
            for (int i=0; i<mBroadcastQueue.length; i++) {
                if (mBroadcastQueue[i] == 1) {
                    if (DEBUG_SCREEN_ON) Slog.i(TAG,
                            "updateLights: delaying screen on due to notification queue");
                    return true;
                }
            }
        }
        return false;
    }

    private void updateLightsLocked(int newState, int forceState) {
        final int oldState = mPowerState;

        // If the screen is not currently on, we will want to delay actually
        // turning the lights on if we are still getting the UI put up.
        if ((oldState&SCREEN_ON_BIT) == 0 || mSkippedScreenOn) {
            // Don't turn screen on until we know we are really ready to.
            // This is to avoid letting the screen go on before things like the
            // lock screen have been displayed.
            if ((mSkippedScreenOn=shouldDeferScreenOnLocked())) {
                newState &= ~(SCREEN_ON_BIT|SCREEN_BRIGHT_BIT);
            }
        }

        if ((newState & SCREEN_ON_BIT) != 0) {
            // Only turn on the buttons or keyboard if the screen is also on.
            // We should never see the buttons on but not the screen.
            newState = applyButtonState(newState);
            newState = applyKeyboardState(newState);
        }
        final int realDifference = (newState ^ oldState);
        final int difference = realDifference | forceState;
        if (difference == 0) {
            return;
        }

        int offMask = 0;
        int dimMask = 0;
        int onMask = 0;

        int preferredBrightness = getPreferredBrightness();

        if ((difference & KEYBOARD_BRIGHT_BIT) != 0) {
            if ((newState & KEYBOARD_BRIGHT_BIT) == 0) {
                offMask |= KEYBOARD_BRIGHT_BIT;
            } else {
                onMask |= KEYBOARD_BRIGHT_BIT;
            }
        }

        if ((difference & BUTTON_BRIGHT_BIT) != 0) {
            if ((newState & BUTTON_BRIGHT_BIT) == 0) {
                offMask |= BUTTON_BRIGHT_BIT;
            } else {
                onMask |= BUTTON_BRIGHT_BIT;
            }
        }

        if ((difference & (SCREEN_ON_BIT | SCREEN_BRIGHT_BIT)) != 0) {
            int nominalCurrentValue = -1;
            // If there was an actual difference in the light state, then
            // figure out the "ideal" current value based on the previous
            // state.  Otherwise, this is a change due to the brightness
            // override, so we want to animate from whatever the current
            // value is.
            if ((realDifference & (SCREEN_ON_BIT | SCREEN_BRIGHT_BIT)) != 0) {
                switch (oldState & (SCREEN_BRIGHT_BIT|SCREEN_ON_BIT)) {
                    case SCREEN_BRIGHT_BIT | SCREEN_ON_BIT:
                        nominalCurrentValue = preferredBrightness;
                        break;
                    case SCREEN_ON_BIT:
                        nominalCurrentValue = mScreenBrightnessDim;
                        break;
                    case 0:
                        nominalCurrentValue = Power.BRIGHTNESS_OFF;
                        break;
                    case SCREEN_BRIGHT_BIT:
                    default:
                        // not possible
                        nominalCurrentValue = (int)mScreenBrightness.curValue;
                        break;
                }
            }
            int brightness = preferredBrightness;
            int steps = ANIM_STEPS;
            if ((newState & SCREEN_BRIGHT_BIT) == 0) {
                // dim or turn off backlight, depending on if the screen is on
                // the scale is because the brightness ramp isn't linear and this biases
                // it so the later parts take longer.
                final float scale = 1.5f;
                float ratio = (((float)mScreenBrightnessDim)/preferredBrightness);
                if (ratio > 1.0f) ratio = 1.0f;
                if ((newState & SCREEN_ON_BIT) == 0) {
                    if ((oldState & SCREEN_BRIGHT_BIT) != 0) {
                        // was bright
                        steps = ANIM_STEPS;
                    } else {
                        // was dim
                        steps = (int)(ANIM_STEPS*ratio*scale);
                    }
                    brightness = Power.BRIGHTNESS_OFF;
                } else {
                    if ((oldState & SCREEN_ON_BIT) != 0) {
                        // was bright
                        steps = (int)(ANIM_STEPS*(1.0f-ratio)*scale);
                    } else {
                        // was dim
                        steps = (int)(ANIM_STEPS*ratio);
                    }
                    if (mStayOnConditions != 0 && mBatteryService.isPowered(mStayOnConditions)) {
                        // If the "stay on while plugged in" option is
                        // turned on, then the screen will often not
                        // automatically turn off while plugged in.  To
                        // still have a sense of when it is inactive, we
                        // will then count going dim as turning off.
                        mScreenOffTime = SystemClock.elapsedRealtime();
                    }
                    brightness = mScreenBrightnessDim;
                }
            }
            long identity = Binder.clearCallingIdentity();
            try {
                mBatteryStats.noteScreenBrightness(brightness);
            } catch (RemoteException e) {
                // Nothing interesting to do.
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            if (!mSkippedScreenOn) {
                mScreenBrightness.setTargetLocked(brightness, steps,
                        INITIAL_SCREEN_BRIGHTNESS, nominalCurrentValue);
                if (DEBUG_SCREEN_ON) {
                    RuntimeException e = new RuntimeException("here");
                    e.fillInStackTrace();
                    Slog.i(TAG, "Setting screen brightness: " + brightness, e);
                }
            }
        }

        if (mSpew) {
            Slog.d(TAG, "offMask=0x" + Integer.toHexString(offMask)
                    + " dimMask=0x" + Integer.toHexString(dimMask)
                    + " onMask=0x" + Integer.toHexString(onMask)
                    + " difference=0x" + Integer.toHexString(difference)
                    + " realDifference=0x" + Integer.toHexString(realDifference)
                    + " forceState=0x" + Integer.toHexString(forceState)
                    );
        }

        if (offMask != 0) {
            if (mSpew) Slog.i(TAG, "Setting brightess off: " + offMask);
            setLightBrightness(offMask, Power.BRIGHTNESS_OFF);
        }
        if (dimMask != 0) {
            int brightness = mScreenBrightnessDim;
            if ((newState & BATTERY_LOW_BIT) != 0 &&
                    brightness > Power.BRIGHTNESS_LOW_BATTERY) {
                brightness = Power.BRIGHTNESS_LOW_BATTERY;
            }
            if (mSpew) Slog.i(TAG, "Setting brightess dim " + brightness + ": " + dimMask);
            setLightBrightness(dimMask, brightness);
        }
        if (onMask != 0) {
            int brightness = getPreferredBrightness();
            if ((newState & BATTERY_LOW_BIT) != 0 &&
                    brightness > Power.BRIGHTNESS_LOW_BATTERY) {
                brightness = Power.BRIGHTNESS_LOW_BATTERY;
            }
            if (mSpew) Slog.i(TAG, "Setting brightess on " + brightness + ": " + onMask);
            setLightBrightness(onMask, brightness);
        }
    }

    private void setLightBrightness(int mask, int value) {
        int brightnessMode = (mAutoBrightessEnabled
                            ? LightsService.BRIGHTNESS_MODE_SENSOR
                            : LightsService.BRIGHTNESS_MODE_USER);
        if ((mask & SCREEN_BRIGHT_BIT) != 0) {
            if (DEBUG_SCREEN_ON) {
                RuntimeException e = new RuntimeException("here");
                e.fillInStackTrace();
                Slog.i(TAG, "Set LCD brightness: " + value, e);
            }
            mLcdLight.setBrightness(value, brightnessMode);
        }
        if ((mask & BUTTON_BRIGHT_BIT) != 0) {
            mButtonLight.setBrightness(value);
        }
        if ((mask & KEYBOARD_BRIGHT_BIT) != 0) {
            mKeyboardLight.setBrightness(value);
        }
    }

    class BrightnessState implements Runnable {
        final int mask;

        boolean initialized;
        int targetValue;
        float curValue;
        float delta;
        boolean animating;

        BrightnessState(int m) {
            mask = m;
        }

        public void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + "animating=" + animating
                    + " targetValue=" + targetValue
                    + " curValue=" + curValue
                    + " delta=" + delta);
        }

        void forceValueLocked(int value) {
            targetValue = -1;
            curValue = value;
            setLightBrightness(mask, value);
            if (animating) {
                finishAnimationLocked(false, value);
            }
        }

        void setTargetLocked(int target, int stepsToTarget, int initialValue,
                int nominalCurrentValue) {
            if (!initialized) {
                initialized = true;
                curValue = (float)initialValue;
            } else if (targetValue == target) {
                return;
            }
            targetValue = target;
            delta = (targetValue -
                    (nominalCurrentValue >= 0 ? nominalCurrentValue : curValue))
                    / stepsToTarget;
            if (mSpew || DEBUG_SCREEN_ON) {
                String noticeMe = nominalCurrentValue == curValue ? "" : "  ******************";
                Slog.i(TAG, "setTargetLocked mask=" + mask + " curValue=" + curValue
                        + " target=" + target + " targetValue=" + targetValue + " delta=" + delta
                        + " nominalCurrentValue=" + nominalCurrentValue
                        + noticeMe);
            }
            animating = true;

            if (mSpew) {
                Slog.i(TAG, "scheduling light animator");
            }
            mScreenOffHandler.removeCallbacks(this);
            mScreenOffHandler.post(this);
        }

        boolean stepLocked() {
            if (!animating) return false;
            if (false && mSpew) {
                Slog.i(TAG, "Step target " + mask + ": cur=" + curValue
                        + " target=" + targetValue + " delta=" + delta);
            }
            curValue += delta;
            int curIntValue = (int)curValue;
            boolean more = true;
            if (delta == 0) {
                curValue = curIntValue = targetValue;
                more = false;
            } else if (delta > 0) {
                if (curIntValue >= targetValue) {
                    curValue = curIntValue = targetValue;
                    more = false;
                }
            } else {
                if (curIntValue <= targetValue) {
                    curValue = curIntValue = targetValue;
                    more = false;
                }
            }
            if (mSpew) Slog.d(TAG, "Animating curIntValue=" + curIntValue + ": " + mask);
            setLightBrightness(mask, curIntValue);
            finishAnimationLocked(more, curIntValue);
            return more;
        }

        void jumpToTargetLocked() {
            if (mSpew) Slog.d(TAG, "jumpToTargetLocked targetValue=" + targetValue + ": " + mask);
            setLightBrightness(mask, targetValue);
            final int tv = targetValue;
            curValue = tv;
            targetValue = -1;
            finishAnimationLocked(false, tv);
        }

        private void finishAnimationLocked(boolean more, int curIntValue) {
            animating = more;
            if (!more) {
                if (mask == SCREEN_BRIGHT_BIT && curIntValue == Power.BRIGHTNESS_OFF) {
                    screenOffFinishedAnimatingLocked(mScreenOffReason);
                }
            }
        }

        public void run() {
            synchronized (mLocks) {
                // we're turning off
                final boolean turningOff = animating && targetValue == Power.BRIGHTNESS_OFF;
                if (mAnimateScreenLights || !turningOff) {
                    long now = SystemClock.uptimeMillis();
                    boolean more = mScreenBrightness.stepLocked();
                    if (more) {
                        mScreenOffHandler.postAtTime(this, now+(1000/60));
                    }
                } else {
                    // It's pretty scary to hold mLocks for this long, and we should
                    // redesign this, but it works for now.
                    nativeStartSurfaceFlingerAnimation(
                            mScreenOffReason == WindowManagerPolicy.OFF_BECAUSE_OF_PROX_SENSOR
                            ? 0 : mAnimationSetting);
                    mScreenBrightness.jumpToTargetLocked();
                }
            }
        }
    }

    private int getPreferredBrightness() {
        try {
            if (mScreenBrightnessOverride >= 0) {
                return mScreenBrightnessOverride;
            } else if (mLightSensorScreenBrightness >= 0 && mUseSoftwareAutoBrightness
                    && mAutoBrightessEnabled) {
                return mLightSensorScreenBrightness;
            }
            final int brightness = Settings.System.getInt(mContext.getContentResolver(),
                                                          SCREEN_BRIGHTNESS);
             // Don't let applications turn the screen all the way off
            return Math.max(brightness, mScreenBrightnessDim);
        } catch (SettingNotFoundException snfe) {
            return Power.BRIGHTNESS_ON;
        }
    }

    private int applyButtonState(int state) {
        int brightness = -1;
        if ((state & BATTERY_LOW_BIT) != 0) {
            // do not override brightness if the battery is low
            return state;
        }
        if (mButtonBrightnessOverride >= 0) {
            brightness = mButtonBrightnessOverride;
        } else if (mLightSensorButtonBrightness >= 0 && mUseSoftwareAutoBrightness) {
            brightness = mLightSensorButtonBrightness;
        }
        if (brightness > 0) {
            return state | BUTTON_BRIGHT_BIT;
        } else if (brightness == 0) {
            return state & ~BUTTON_BRIGHT_BIT;
        } else {
            return state;
        }
    }

    private int applyKeyboardState(int state) {
        int brightness = -1;
        if ((state & BATTERY_LOW_BIT) != 0) {
            // do not override brightness if the battery is low
            return state;
        }
        if (!mKeyboardVisible) {
            brightness = 0;
        } else if (mButtonBrightnessOverride >= 0) {
            brightness = mButtonBrightnessOverride;
        } else if (mLightSensorKeyboardBrightness >= 0 && mUseSoftwareAutoBrightness) {
            brightness =  mLightSensorKeyboardBrightness;
        }
        if (brightness > 0) {
            return state | KEYBOARD_BRIGHT_BIT;
        } else if (brightness == 0) {
            return state & ~KEYBOARD_BRIGHT_BIT;
        } else {
            return state;
        }
    }

    public boolean isScreenOn() {
        synchronized (mLocks) {
            return (mPowerState & SCREEN_ON_BIT) != 0;
        }
    }

    boolean isScreenBright() {
        synchronized (mLocks) {
            return (mPowerState & SCREEN_BRIGHT) == SCREEN_BRIGHT;
        }
    }

    private boolean isScreenTurningOffLocked() {
        return (mScreenBrightness.animating && mScreenBrightness.targetValue == 0);
    }

    private boolean shouldLog(long time) {
        synchronized (mLocks) {
            if (time > (mWarningSpewThrottleTime + (60*60*1000))) {
                mWarningSpewThrottleTime = time;
                mWarningSpewThrottleCount = 0;
                return true;
            } else if (mWarningSpewThrottleCount < 30) {
                mWarningSpewThrottleCount++;
                return true;
            } else {
                return false;
            }
        }
    }

    private void forceUserActivityLocked() {
        if (isScreenTurningOffLocked()) {
            // cancel animation so userActivity will succeed
            mScreenBrightness.animating = false;
        }
        boolean savedActivityAllowed = mUserActivityAllowed;
        mUserActivityAllowed = true;
        userActivity(SystemClock.uptimeMillis(), false);
        mUserActivityAllowed = savedActivityAllowed;
    }

    public void userActivityWithForce(long time, boolean noChangeLights, boolean force) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
        userActivity(time, -1, noChangeLights, OTHER_EVENT, force);
    }

    public void userActivity(long time, boolean noChangeLights) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER)
                != PackageManager.PERMISSION_GRANTED) {
            if (shouldLog(time)) {
                Slog.w(TAG, "Caller does not have DEVICE_POWER permission.  pid="
                        + Binder.getCallingPid() + " uid=" + Binder.getCallingUid());
            }
            return;
        }

        userActivity(time, -1, noChangeLights, OTHER_EVENT, false);
    }

    public void userActivity(long time, boolean noChangeLights, int eventType) {
        userActivity(time, -1, noChangeLights, eventType, false);
    }

    public void userActivity(long time, boolean noChangeLights, int eventType, boolean force) {
        userActivity(time, -1, noChangeLights, eventType, force);
    }

    /*
     * Reset the user activity timeout to now + timeout.  This overrides whatever else is going
     * on with user activity.  Don't use this function.
     */
    public void clearUserActivityTimeout(long now, long timeout) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
        Slog.i(TAG, "clearUserActivity for " + timeout + "ms from now");
        userActivity(now, timeout, false, OTHER_EVENT, false);
    }

    private void userActivity(long time, long timeoutOverride, boolean noChangeLights,
            int eventType, boolean force) {

        if (((mPokey & POKE_LOCK_IGNORE_TOUCH_EVENTS) != 0) && (eventType == TOUCH_EVENT)) {
            if (false) {
                Slog.d(TAG, "dropping touch mPokey=0x" + Integer.toHexString(mPokey));
            }
            return;
        }

        synchronized (mLocks) {
            if (mSpew) {
                Slog.d(TAG, "userActivity mLastEventTime=" + mLastEventTime + " time=" + time
                        + " mUserActivityAllowed=" + mUserActivityAllowed
                        + " mUserState=0x" + Integer.toHexString(mUserState)
                        + " mWakeLockState=0x" + Integer.toHexString(mWakeLockState)
                        + " mProximitySensorActive=" + mProximitySensorActive
                        + " timeoutOverride=" + timeoutOverride
                        + " force=" + force);
            }
            // ignore user activity if we are in the process of turning off the screen
            if (isScreenTurningOffLocked()) {
                Slog.d(TAG, "ignoring user activity while turning off screen");
                return;
            }
            // Disable proximity sensor if if user presses power key while we are in the
            // "waiting for proximity sensor to go negative" state.
            if (mProximitySensorActive && mProximityWakeLockCount == 0) {
                mProximitySensorActive = false;
            }
            if (mLastEventTime <= time || force) {
                mLastEventTime = time;
                if ((mUserActivityAllowed && !mProximitySensorActive) || force) {
                    // Only turn on button backlights if a button was pressed
                    // and auto brightness is disabled
                    if (eventType == BUTTON_EVENT && !mUseSoftwareAutoBrightness) {
                        mUserState = (mKeyboardVisible ? ALL_BRIGHT : SCREEN_BUTTON_BRIGHT);
                    } else {
                        // don't clear button/keyboard backlights when the screen is touched.
                        mUserState |= SCREEN_BRIGHT;
                    }

                    int uid = Binder.getCallingUid();
                    long ident = Binder.clearCallingIdentity();
                    try {
                        mBatteryStats.noteUserActivity(uid, eventType);
                    } catch (RemoteException e) {
                        // Ignore
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }

                    mWakeLockState = mLocks.reactivateScreenLocksLocked();
                    setPowerState(mUserState | mWakeLockState, noChangeLights,
                            WindowManagerPolicy.OFF_BECAUSE_OF_USER);
                    setTimeoutLocked(time, timeoutOverride, SCREEN_BRIGHT);
                }
            }
        }

        if (mPolicy != null) {
            mPolicy.userActivity();
        }
    }

    private int getAutoBrightnessValue(int sensorValue, int[] values) {
        try {
            int i;
            for (i = 0; i < mAutoBrightnessLevels.length; i++) {
                if (sensorValue < mAutoBrightnessLevels[i]) {
                    break;
                }
            }
            return values[i];
        } catch (Exception e) {
            // guard against null pointer or index out of bounds errors
            Slog.e(TAG, "getAutoBrightnessValue", e);
            return 255;
        }
    }

    private Runnable mProximityTask = new Runnable() {
        public void run() {
            synchronized (mLocks) {
                if (mProximityPendingValue != -1) {
                    proximityChangedLocked(mProximityPendingValue == 1);
                    mProximityPendingValue = -1;
                }
                if (mProximityPartialLock.isHeld()) {
                    mProximityPartialLock.release();
                }
            }
        }
    };

    private Runnable mAutoBrightnessTask = new Runnable() {
        public void run() {
            synchronized (mLocks) {
                if (mLightSensorPendingDecrease || mLightSensorPendingIncrease) {
                    int value = (int)mLightSensorPendingValue;
                    mLightSensorPendingDecrease = false;
                    mLightSensorPendingIncrease = false;
                    lightSensorChangedLocked(value);
                }
            }
        }
    };

    private void dockStateChanged(int state) {
        synchronized (mLocks) {
            mIsDocked = (state != Intent.EXTRA_DOCK_STATE_UNDOCKED);
            if (mIsDocked) {
                // allow brightness to decrease when docked
                mHighestLightSensorValue = -1;
            }
            if ((mPowerState & SCREEN_ON_BIT) != 0) {
                // force lights recalculation
                int value = (int)mLightSensorValue;
                mLightSensorValue = -1;
                lightSensorChangedLocked(value);
            }
        }
    }

    private void lightSensorChangedLocked(int value) {
        if (mDebugLightSensor) {
            Slog.d(TAG, "lightSensorChangedLocked " + value);
        }

        // Don't do anything if the screen is off.
        if ((mPowerState & SCREEN_ON_BIT) == 0) {
            if (mDebugLightSensor) {
                Slog.d(TAG, "dropping lightSensorChangedLocked because screen is off");
            }
            return;
        }

        // do not allow light sensor value to decrease
        if (mHighestLightSensorValue < value) {
            mHighestLightSensorValue = value;
        }

        if (mLightSensorValue != value) {
            mLightSensorValue = value;
            if ((mPowerState & BATTERY_LOW_BIT) == 0) {
                // use maximum light sensor value seen since screen went on for LCD to avoid flicker
                // we only do this if we are undocked, since lighting should be stable when
                // stationary in a dock.
                int lcdValue = getAutoBrightnessValue(
                        (mIsDocked ? value : mHighestLightSensorValue),
                        mLcdBacklightValues);
                int buttonValue = getAutoBrightnessValue(value, mButtonBacklightValues);
                int keyboardValue;
                if (mKeyboardVisible) {
                    keyboardValue = getAutoBrightnessValue(value, mKeyboardBacklightValues);
                } else {
                    keyboardValue = 0;
                }
                mLightSensorScreenBrightness = lcdValue;
                mLightSensorButtonBrightness = buttonValue;
                mLightSensorKeyboardBrightness = keyboardValue;

                if (mDebugLightSensor) {
                    Slog.d(TAG, "lcdValue " + lcdValue);
                    Slog.d(TAG, "buttonValue " + buttonValue);
                    Slog.d(TAG, "keyboardValue " + keyboardValue);
                }

                if (mAutoBrightessEnabled && mScreenBrightnessOverride < 0) {
                    if (!mSkippedScreenOn) {
                        mScreenBrightness.setTargetLocked(lcdValue, AUTOBRIGHTNESS_ANIM_STEPS,
                                INITIAL_SCREEN_BRIGHTNESS, (int)mScreenBrightness.curValue);
                    }
                }
                if (mButtonBrightnessOverride < 0) {
                    mButtonLight.setBrightness(buttonValue);
                }
                if (mButtonBrightnessOverride < 0 || !mKeyboardVisible) {
                    mKeyboardLight.setBrightness(keyboardValue);
                }
            }
        }
    }

    /**
     * The user requested that we go to sleep (probably with the power button).
     * This overrides all wake locks that are held.
     */
    public void goToSleep(long time)
    {
        goToSleepWithReason(time, WindowManagerPolicy.OFF_BECAUSE_OF_USER);
    }

    /**
     * The user requested that we go to sleep (probably with the power button).
     * This overrides all wake locks that are held.
     */
    public void goToSleepWithReason(long time, int reason)
    {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
        synchronized (mLocks) {
            goToSleepLocked(time, reason);
        }
    }

    /**
     * Reboot the device immediately, passing 'reason' (may be null)
     * to the underlying __reboot system call.  Should not return.
     */
    public void reboot(String reason)
    {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.REBOOT, null);

        if (mHandler == null || !ActivityManagerNative.isSystemReady()) {
            throw new IllegalStateException("Too early to call reboot()");
        }

        final String finalReason = reason;
        Runnable runnable = new Runnable() {
            public void run() {
                synchronized (this) {
                    ShutdownThread.reboot(mContext, finalReason, false);
                }
                
            }
        };
        // ShutdownThread must run on a looper capable of displaying the UI.
        mHandler.post(runnable);

        // PowerManager.reboot() is documented not to return so just wait for the inevitable.
        synchronized (runnable) {
            while (true) {
                try {
                    runnable.wait();
                } catch (InterruptedException e) {
                }
            }
        }
    }

    /**
     * Crash the runtime (causing a complete restart of the Android framework).
     * Requires REBOOT permission.  Mostly for testing.  Should not return.
     */
    public void crash(final String message)
    {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.REBOOT, null);
        Thread t = new Thread("PowerManagerService.crash()") {
            public void run() { throw new RuntimeException(message); }
        };
        try {
            t.start();
            t.join();
        } catch (InterruptedException e) {
            Log.wtf(TAG, e);
        }
    }

    private void goToSleepLocked(long time, int reason) {

        if (mLastEventTime <= time) {
            mLastEventTime = time;
            // cancel all of the wake locks
            mWakeLockState = SCREEN_OFF;
            int N = mLocks.size();
            int numCleared = 0;
            boolean proxLock = false;
            for (int i=0; i<N; i++) {
                WakeLock wl = mLocks.get(i);
                if (isScreenLock(wl.flags)) {
                    if (((wl.flags & LOCK_MASK) == PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
                            && reason == WindowManagerPolicy.OFF_BECAUSE_OF_PROX_SENSOR) {
                        proxLock = true;
                    } else {
                        mLocks.get(i).activated = false;
                        numCleared++;
                    }
                }
            }
            if (!proxLock) {
                mProxIgnoredBecauseScreenTurnedOff = true;
                if (mDebugProximitySensor) {
                    Slog.d(TAG, "setting mProxIgnoredBecauseScreenTurnedOff");
                }
            }
            EventLog.writeEvent(EventLogTags.POWER_SLEEP_REQUESTED, numCleared);
            mStillNeedSleepNotification = true;
            mUserState = SCREEN_OFF;
            setPowerState(SCREEN_OFF, false, reason);
            cancelTimerLocked();
        }
    }

    public long timeSinceScreenOn() {
        synchronized (mLocks) {
            if ((mPowerState & SCREEN_ON_BIT) != 0) {
                return 0;
            }
            return SystemClock.elapsedRealtime() - mScreenOffTime;
        }
    }

    public void setKeyboardVisibility(boolean visible) {
        synchronized (mLocks) {
            if (mSpew) {
                Slog.d(TAG, "setKeyboardVisibility: " + visible);
            }
            if (mKeyboardVisible != visible) {
                mKeyboardVisible = visible;
                // don't signal user activity if the screen is off; other code
                // will take care of turning on due to a true change to the lid
                // switch and synchronized with the lock screen.
                if ((mPowerState & SCREEN_ON_BIT) != 0) {
                    if (mUseSoftwareAutoBrightness) {
                        // force recompute of backlight values
                        if (mLightSensorValue >= 0) {
                            int value = (int)mLightSensorValue;
                            mLightSensorValue = -1;
                            lightSensorChangedLocked(value);
                        }
                    }
                    userActivity(SystemClock.uptimeMillis(), false, BUTTON_EVENT, true);
                }
            }
        }
    }

    /**
     * When the keyguard is up, it manages the power state, and userActivity doesn't do anything.
     * When disabling user activity we also reset user power state so the keyguard can reset its
     * short screen timeout when keyguard is unhidden.
     */
    public void enableUserActivity(boolean enabled) {
        if (mSpew) {
            Slog.d(TAG, "enableUserActivity " + enabled);
        }
        synchronized (mLocks) {
            mUserActivityAllowed = enabled;
            if (!enabled) {
                // cancel timeout and clear mUserState so the keyguard can set a short timeout
                setTimeoutLocked(SystemClock.uptimeMillis(), 0);
            }
        }
    }

    private void setScreenBrightnessMode(int mode) {
        synchronized (mLocks) {
            boolean enabled = (mode == SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            if (mUseSoftwareAutoBrightness && mAutoBrightessEnabled != enabled) {
                mAutoBrightessEnabled = enabled;
                // This will get us a new value
                enableLightSensorLocked(mAutoBrightessEnabled && isScreenOn());
            }
        }
    }

    /** Sets the screen off timeouts:
     *      mKeylightDelay
     *      mDimDelay
     *      mScreenOffDelay
     * */
    private void setScreenOffTimeoutsLocked() {
        if ((mPokey & POKE_LOCK_SHORT_TIMEOUT) != 0) {
            mKeylightDelay = mShortKeylightDelay;  // Configurable via secure settings
            mDimDelay = -1;
            mScreenOffDelay = 0;
        } else if ((mPokey & POKE_LOCK_MEDIUM_TIMEOUT) != 0) {
            mKeylightDelay = MEDIUM_KEYLIGHT_DELAY;
            mDimDelay = -1;
            mScreenOffDelay = 0;
        } else {
            int totalDelay = mScreenOffTimeoutSetting;
            if (totalDelay > mMaximumScreenOffTimeout) {
                totalDelay = mMaximumScreenOffTimeout;
            }
            mKeylightDelay = LONG_KEYLIGHT_DELAY;
            if (totalDelay < 0) {
                // negative number means stay on as long as possible.
                mScreenOffDelay = mMaximumScreenOffTimeout;
            } else if (mKeylightDelay < totalDelay) {
                // subtract the time that the keylight delay. This will give us the
                // remainder of the time that we need to sleep to get the accurate
                // screen off timeout.
                mScreenOffDelay = totalDelay - mKeylightDelay;
            } else {
                mScreenOffDelay = 0;
            }
            if (mDimScreen && totalDelay >= (LONG_KEYLIGHT_DELAY + LONG_DIM_TIME)) {
                mDimDelay = mScreenOffDelay - LONG_DIM_TIME;
                mScreenOffDelay = LONG_DIM_TIME;
            } else {
                mDimDelay = -1;
            }
        }
        if (mSpew) {
            Slog.d(TAG, "setScreenOffTimeouts mKeylightDelay=" + mKeylightDelay
                    + " mDimDelay=" + mDimDelay + " mScreenOffDelay=" + mScreenOffDelay
                    + " mDimScreen=" + mDimScreen);
        }
    }

    /**
     * Refreshes cached secure settings.  Called once on startup, and
     * on subsequent changes to secure settings.
     */
    private void updateSettingsValues() {
        mShortKeylightDelay = Settings.Secure.getInt(
                mContext.getContentResolver(),
                Settings.Secure.SHORT_KEYLIGHT_DELAY_MS,
                SHORT_KEYLIGHT_DELAY_DEFAULT);
        // Slog.i(TAG, "updateSettingsValues(): mShortKeylightDelay now " + mShortKeylightDelay);
    }

    private class LockList extends ArrayList<WakeLock>
    {
        void addLock(WakeLock wl)
        {
            int index = getIndex(wl.binder);
            if (index < 0) {
                this.add(wl);
            }
        }

        WakeLock removeLock(IBinder binder)
        {
            int index = getIndex(binder);
            if (index >= 0) {
                return this.remove(index);
            } else {
                return null;
            }
        }

        int getIndex(IBinder binder)
        {
            int N = this.size();
            for (int i=0; i<N; i++) {
                if (this.get(i).binder == binder) {
                    return i;
                }
            }
            return -1;
        }

        int gatherState()
        {
            int result = 0;
            int N = this.size();
            for (int i=0; i<N; i++) {
                WakeLock wl = this.get(i);
                if (wl.activated) {
                    if (isScreenLock(wl.flags)) {
                        result |= wl.minState;
                    }
                }
            }
            return result;
        }

        int reactivateScreenLocksLocked()
        {
            int result = 0;
            int N = this.size();
            for (int i=0; i<N; i++) {
                WakeLock wl = this.get(i);
                if (isScreenLock(wl.flags)) {
                    wl.activated = true;
                    result |= wl.minState;
                }
            }
            if (mDebugProximitySensor) {
                Slog.d(TAG, "reactivateScreenLocksLocked mProxIgnoredBecauseScreenTurnedOff="
                        + mProxIgnoredBecauseScreenTurnedOff);
            }
            mProxIgnoredBecauseScreenTurnedOff = false;
            return result;
        }
    }

    public void setPolicy(WindowManagerPolicy p) {
        synchronized (mLocks) {
            mPolicy = p;
            mLocks.notifyAll();
        }
    }

    WindowManagerPolicy getPolicyLocked() {
        while (mPolicy == null || !mDoneBooting) {
            try {
                mLocks.wait();
            } catch (InterruptedException e) {
                // Ignore
            }
        }
        return mPolicy;
    }

    void systemReady() {
        mSensorManager = new SensorManager(mHandlerThread.getLooper());
        mProximitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        // don't bother with the light sensor if auto brightness is handled in hardware
        if (mUseSoftwareAutoBrightness) {
            mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        }

        // wait until sensors are enabled before turning on screen.
        // some devices will not activate the light sensor properly on boot
        // unless we do this.
        if (mUseSoftwareAutoBrightness) {
            // turn the screen on
            setPowerState(SCREEN_BRIGHT);
        } else {
            // turn everything on
            setPowerState(ALL_BRIGHT);
        }

        synchronized (mLocks) {
            Slog.d(TAG, "system ready!");
            mDoneBooting = true;

            enableLightSensorLocked(mUseSoftwareAutoBrightness && mAutoBrightessEnabled);

            long identity = Binder.clearCallingIdentity();
            try {
                mBatteryStats.noteScreenBrightness(getPreferredBrightness());
                mBatteryStats.noteScreenOn();
            } catch (RemoteException e) {
                // Nothing interesting to do.
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    void bootCompleted() {
        Slog.d(TAG, "bootCompleted");
        synchronized (mLocks) {
            mBootCompleted = true;
            userActivity(SystemClock.uptimeMillis(), false, BUTTON_EVENT, true);
            updateWakeLockLocked();
            mLocks.notifyAll();
        }
    }

    // for watchdog
    public void monitor() {
        synchronized (mLocks) { }
    }

    public int getSupportedWakeLockFlags() {
        int result = PowerManager.PARTIAL_WAKE_LOCK
                   | PowerManager.FULL_WAKE_LOCK
                   | PowerManager.SCREEN_DIM_WAKE_LOCK;

        if (mProximitySensor != null) {
            result |= PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK;
        }

        return result;
    }

    public void setBacklightBrightness(int brightness) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
        // Don't let applications turn the screen all the way off
        synchronized (mLocks) {
            brightness = Math.max(brightness, mScreenBrightnessDim);
            mLcdLight.setBrightness(brightness);
            mKeyboardLight.setBrightness(mKeyboardVisible ? brightness : 0);
            mButtonLight.setBrightness(brightness);
            long identity = Binder.clearCallingIdentity();
            try {
                mBatteryStats.noteScreenBrightness(brightness);
            } catch (RemoteException e) {
                Slog.w(TAG, "RemoteException calling noteScreenBrightness on BatteryStatsService", e);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

            // update our animation state
            synchronized (mLocks) {
                mScreenBrightness.targetValue = brightness;
                mScreenBrightness.jumpToTargetLocked();
            }
        }
    }

    public void setAttentionLight(boolean on, int color) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
        mAttentionLight.setFlashing(color, LightsService.LIGHT_FLASH_HARDWARE, (on ? 3 : 0), 0);
    }

    private void enableProximityLockLocked() {
        if (mDebugProximitySensor) {
            Slog.d(TAG, "enableProximityLockLocked");
        }
        if (!mProximitySensorEnabled) {
            // clear calling identity so sensor manager battery stats are accurate
            long identity = Binder.clearCallingIdentity();
            try {
                mSensorManager.registerListener(mProximityListener, mProximitySensor,
                        SensorManager.SENSOR_DELAY_NORMAL);
                mProximitySensorEnabled = true;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private void disableProximityLockLocked() {
        if (mDebugProximitySensor) {
            Slog.d(TAG, "disableProximityLockLocked");
        }
        if (mProximitySensorEnabled) {
            // clear calling identity so sensor manager battery stats are accurate
            long identity = Binder.clearCallingIdentity();
            try {
                mSensorManager.unregisterListener(mProximityListener);
                mHandler.removeCallbacks(mProximityTask);
                if (mProximityPartialLock.isHeld()) {
                    mProximityPartialLock.release();
                }
                mProximitySensorEnabled = false;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            if (mProximitySensorActive) {
                mProximitySensorActive = false;
                if (mDebugProximitySensor) {
                    Slog.d(TAG, "disableProximityLockLocked mProxIgnoredBecauseScreenTurnedOff="
                            + mProxIgnoredBecauseScreenTurnedOff);
                }
                if (!mProxIgnoredBecauseScreenTurnedOff) {
                    forceUserActivityLocked();
                }
            }
        }
    }

    private void proximityChangedLocked(boolean active) {
        if (mDebugProximitySensor) {
            Slog.d(TAG, "proximityChangedLocked, active: " + active);
        }
        if (!mProximitySensorEnabled) {
            Slog.d(TAG, "Ignoring proximity change after sensor is disabled");
            return;
        }
        if (active) {
            if (mDebugProximitySensor) {
                Slog.d(TAG, "b mProxIgnoredBecauseScreenTurnedOff="
                        + mProxIgnoredBecauseScreenTurnedOff);
            }
            if (!mProxIgnoredBecauseScreenTurnedOff) {
                goToSleepLocked(SystemClock.uptimeMillis(),
                        WindowManagerPolicy.OFF_BECAUSE_OF_PROX_SENSOR);
            }
            mProximitySensorActive = true;
        } else {
            // proximity sensor negative events trigger as user activity.
            // temporarily set mUserActivityAllowed to true so this will work
            // even when the keyguard is on.
            mProximitySensorActive = false;
            if (mDebugProximitySensor) {
                Slog.d(TAG, "b mProxIgnoredBecauseScreenTurnedOff="
                        + mProxIgnoredBecauseScreenTurnedOff);
            }
            if (!mProxIgnoredBecauseScreenTurnedOff) {
                forceUserActivityLocked();
            }

            if (mProximityWakeLockCount == 0) {
                // disable sensor if we have no listeners left after proximity negative
                disableProximityLockLocked();
            }
        }
    }

    private void enableLightSensorLocked(boolean enable) {
        if (mDebugLightSensor) {
            Slog.d(TAG, "enableLightSensorLocked enable=" + enable
                    + " mAutoBrightessEnabled=" + mAutoBrightessEnabled);
        }
        if (!mAutoBrightessEnabled) {
            enable = false;
        }
        if (mSensorManager != null && mLightSensorEnabled != enable) {
            mLightSensorEnabled = enable;
            // clear calling identity so sensor manager battery stats are accurate
            long identity = Binder.clearCallingIdentity();
            try {
                if (enable) {
                    // reset our highest value when reenabling
                    mHighestLightSensorValue = -1;
                    // force recompute of backlight values
                    if (mLightSensorValue >= 0) {
                        int value = (int)mLightSensorValue;
                        mLightSensorValue = -1;
                        handleLightSensorValue(value);
                    }
                    mSensorManager.registerListener(mLightListener, mLightSensor,
                            LIGHT_SENSOR_RATE);
                } else {
                    mSensorManager.unregisterListener(mLightListener);
                    mHandler.removeCallbacks(mAutoBrightnessTask);
                    mLightSensorPendingDecrease = false;
                    mLightSensorPendingIncrease = false;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    SensorEventListener mProximityListener = new SensorEventListener() {
        public void onSensorChanged(SensorEvent event) {
            long milliseconds = SystemClock.elapsedRealtime();
            synchronized (mLocks) {
                float distance = event.values[0];
                long timeSinceLastEvent = milliseconds - mLastProximityEventTime;
                mLastProximityEventTime = milliseconds;
                mHandler.removeCallbacks(mProximityTask);
                boolean proximityTaskQueued = false;

                // compare against getMaximumRange to support sensors that only return 0 or 1
                boolean active = (distance >= 0.0 && distance < PROXIMITY_THRESHOLD &&
                        distance < mProximitySensor.getMaximumRange());

                if (mDebugProximitySensor) {
                    Slog.d(TAG, "mProximityListener.onSensorChanged active: " + active);
                }
                if (timeSinceLastEvent < PROXIMITY_SENSOR_DELAY) {
                    // enforce delaying atleast PROXIMITY_SENSOR_DELAY before processing
                    mProximityPendingValue = (active ? 1 : 0);
                    mHandler.postDelayed(mProximityTask, PROXIMITY_SENSOR_DELAY - timeSinceLastEvent);
                    proximityTaskQueued = true;
                } else {
                    // process the value immediately
                    mProximityPendingValue = -1;
                    proximityChangedLocked(active);
                }

                // update mProximityPartialLock state
                boolean held = mProximityPartialLock.isHeld();
                if (!held && proximityTaskQueued) {
                    // hold wakelock until mProximityTask runs
                    mProximityPartialLock.acquire();
                } else if (held && !proximityTaskQueued) {
                    mProximityPartialLock.release();
                }
            }
        }

        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // ignore
        }
    };

    private void handleLightSensorValue(int value) {
        long milliseconds = SystemClock.elapsedRealtime();
        if (mLightSensorValue == -1 ||
                milliseconds < mLastScreenOnTime + mLightSensorWarmupTime) {
            // process the value immediately if screen has just turned on
            mHandler.removeCallbacks(mAutoBrightnessTask);
            mLightSensorPendingDecrease = false;
            mLightSensorPendingIncrease = false;
            lightSensorChangedLocked(value);
        } else {
            if ((value > mLightSensorValue && mLightSensorPendingDecrease) ||
                    (value < mLightSensorValue && mLightSensorPendingIncrease) ||
                    (value == mLightSensorValue) ||
                    (!mLightSensorPendingDecrease && !mLightSensorPendingIncrease)) {
                // delay processing to debounce the sensor
                mHandler.removeCallbacks(mAutoBrightnessTask);
                mLightSensorPendingDecrease = (value < mLightSensorValue);
                mLightSensorPendingIncrease = (value > mLightSensorValue);
                if (mLightSensorPendingDecrease || mLightSensorPendingIncrease) {
                    mLightSensorPendingValue = value;
                    mHandler.postDelayed(mAutoBrightnessTask, LIGHT_SENSOR_DELAY);
                }
            } else {
                mLightSensorPendingValue = value;
            }
        }
    }

    SensorEventListener mLightListener = new SensorEventListener() {
        public void onSensorChanged(SensorEvent event) {
            if (mDebugLightSensor) {
                Slog.d(TAG, "onSensorChanged: light value: " + event.values[0]);
            }
            synchronized (mLocks) {
                // ignore light sensor while screen is turning off
                if (isScreenTurningOffLocked()) {
                    return;
                }
                handleLightSensorValue((int)event.values[0]);
            }
        }

        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // ignore
        }
    };
}
