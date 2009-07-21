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
import com.android.server.am.BatteryStatsService;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentQueryMap;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
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
import android.provider.Settings.SettingNotFoundException;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Log;
import android.view.WindowManagerPolicy;
import static android.provider.Settings.System.DIM_SCREEN;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS;
import static android.provider.Settings.System.SCREEN_OFF_TIMEOUT;
import static android.provider.Settings.System.STAY_ON_WHILE_PLUGGED_IN;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Observable;
import java.util.Observer;

class PowerManagerService extends IPowerManager.Stub implements LocalPowerManager, Watchdog.Monitor {

    private static final String TAG = "PowerManagerService";
    static final String PARTIAL_NAME = "PowerManagerService";

    private static final boolean LOG_PARTIAL_WL = false;

    // Indicates whether touch-down cycles should be logged as part of the
    // LOG_POWER_SCREEN_STATE log events
    private static final boolean LOG_TOUCH_DOWNS = true;

    private static final int LOCK_MASK = PowerManager.PARTIAL_WAKE_LOCK
                                        | PowerManager.SCREEN_DIM_WAKE_LOCK
                                        | PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                        | PowerManager.FULL_WAKE_LOCK;

    //                       time since last state:               time since last event:
    // The short keylight delay comes from Gservices; this is the default.
    private static final int SHORT_KEYLIGHT_DELAY_DEFAULT = 6000; // t+6 sec
    private static final int MEDIUM_KEYLIGHT_DELAY = 15000;       // t+15 sec
    private static final int LONG_KEYLIGHT_DELAY = 6000;        // t+6 sec
    private static final int LONG_DIM_TIME = 7000;              // t+N-5 sec

    // Cached Gservices settings; see updateGservicesValues()
    private int mShortKeylightDelay = SHORT_KEYLIGHT_DELAY_DEFAULT;

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

    static final boolean ANIMATE_SCREEN_LIGHTS = true;
    static final boolean ANIMATE_BUTTON_LIGHTS = false;
    static final boolean ANIMATE_KEYBOARD_LIGHTS = false;
    
    static final int ANIM_STEPS = 60/4;

    // These magic numbers are the initial state of the LEDs at boot.  Ideally
    // we should read them from the driver, but our current hardware returns 0
    // for the initial value.  Oops!
    static final int INITIAL_SCREEN_BRIGHTNESS = 255;
    static final int INITIAL_BUTTON_BRIGHTNESS = Power.BRIGHTNESS_OFF;
    static final int INITIAL_KEYBOARD_BRIGHTNESS = Power.BRIGHTNESS_OFF;
    
    static final int LOG_POWER_SLEEP_REQUESTED = 2724;
    static final int LOG_POWER_SCREEN_BROADCAST_SEND = 2725;
    static final int LOG_POWER_SCREEN_BROADCAST_DONE = 2726;
    static final int LOG_POWER_SCREEN_BROADCAST_STOP = 2727;
    static final int LOG_POWER_SCREEN_STATE = 2728;
    static final int LOG_POWER_PARTIAL_WAKE_STATE = 2729;
    
    private final int MY_UID;

    private boolean mDoneBooting = false;
    private int mStayOnConditions = 0;
    private int[] mBroadcastQueue = new int[] { -1, -1, -1 };
    private int[] mBroadcastWhy = new int[3];
    private int mPartialCount = 0;
    private int mPowerState;
    private boolean mOffBecauseOfUser;
    private int mUserState;
    private boolean mKeyboardVisible = false;
    private boolean mUserActivityAllowed = true;
    private int mTotalDelaySetting;
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
    private HardwareService mHardware;
    private Context mContext;
    private UnsynchronizedWakeLock mBroadcastWakeLock;
    private UnsynchronizedWakeLock mStayOnWhilePluggedInScreenDimLock;
    private UnsynchronizedWakeLock mStayOnWhilePluggedInPartialLock;
    private UnsynchronizedWakeLock mPreventScreenOnPartialLock;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private TimeoutTask mTimeoutTask = new TimeoutTask();
    private LightAnimator mLightAnimator = new LightAnimator();
    private final BrightnessState mScreenBrightness
            = new BrightnessState(SCREEN_BRIGHT_BIT);
    private final BrightnessState mKeyboardBrightness
            = new BrightnessState(KEYBOARD_BRIGHT_BIT);
    private final BrightnessState mButtonBrightness
            = new BrightnessState(BUTTON_BRIGHT_BIT);
    private boolean mStillNeedSleepNotification;
    private boolean mIsPowered = false;
    private IActivityManager mActivityService;
    private IBatteryStats mBatteryStats;
    private BatteryService mBatteryService;
    private boolean mDimScreen = true;
    private long mNextTimeout;
    private volatile int mPokey = 0;
    private volatile boolean mPokeAwakeOnSet = false;
    private volatile boolean mInitComplete = false;
    private HashMap<IBinder,PokeLock> mPokeLocks = new HashMap<IBinder,PokeLock>();
    private long mScreenOnTime;
    private long mScreenOnStartTime;
    private boolean mPreventScreenOn;
    private int mScreenBrightnessOverride = -1;

    // Used when logging number and duration of touch-down cycles
    private long mTotalTouchDownTime;
    private long mLastTouchDown;
    private int mTouchCycles;

    // could be either static or controllable at runtime
    private static final boolean mSpew = false;

    /*
    static PrintStream mLog;
    static {
        try {
            mLog = new PrintStream("/data/power.log");
        }
        catch (FileNotFoundException e) {
            android.util.Log.e(TAG, "Life is hard", e);
        }
    }
    static class Log {
        static void d(String tag, String s) {
            mLog.println(s);
            android.util.Log.d(tag, s);
        }
        static void i(String tag, String s) {
            mLog.println(s);
            android.util.Log.i(tag, s);
        }
        static void w(String tag, String s) {
            mLog.println(s);
            android.util.Log.w(tag, s);
        }
        static void e(String tag, String s) {
            mLog.println(s);
            android.util.Log.e(tag, s);
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
                            MY_UID, mTag);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        public void release() {
            if (!mRefCounted || --mCount == 0) {
                PowerManagerService.this.releaseWakeLockLocked(mToken, false);
            }
            if (mCount < 0) {
                throw new RuntimeException("WakeLock under-locked " + mTag);
            }
        }

        public String toString() {
            return "UnsynchronizedWakeLock(mFlags=0x" + Integer.toHexString(mFlags)
                    + " mCount=" + mCount + ")";
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
                    // temporarily set mUserActivityAllowed to true so this will work
                    // even when the keyguard is on.
                    synchronized (mLocks) {
                        boolean savedActivityAllowed = mUserActivityAllowed;
                        mUserActivityAllowed = true;
                        userActivity(SystemClock.uptimeMillis(), false);
                        mUserActivityAllowed = savedActivityAllowed;
                    }
                }
            }
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

    private class SettingsObserver implements Observer {
        private int getInt(String name) {
            return mSettings.getValues(name).getAsInteger(Settings.System.VALUE);
        }

        public void update(Observable o, Object arg) {
            synchronized (mLocks) {
                // STAY_ON_WHILE_PLUGGED_IN
                mStayOnConditions = getInt(STAY_ON_WHILE_PLUGGED_IN);
                updateWakeLockLocked();

                // SCREEN_OFF_TIMEOUT
                mTotalDelaySetting = getInt(SCREEN_OFF_TIMEOUT);

                 // DIM_SCREEN
                //mDimScreen = getInt(DIM_SCREEN) != 0;

                // recalculate everything
                setScreenOffTimeoutsLocked();
            }
        }
    }

    PowerManagerService()
    {
        // Hack to get our uid...  should have a func for this.
        long token = Binder.clearCallingIdentity();
        MY_UID = Binder.getCallingUid();
        Binder.restoreCallingIdentity(token);

        // XXX remove this when the kernel doesn't timeout wake locks
        Power.setLastUserActivityTimeout(7*24*3600*1000); // one week

        // assume nothing is on yet
        mUserState = mPowerState = 0;
        
        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);
        mScreenOnStartTime = SystemClock.elapsedRealtime();
    }

    private ContentQueryMap mSettings;

    void init(Context context, HardwareService hardware, IActivityManager activity,
            BatteryService battery) {
        mHardware = hardware;
        mContext = context;
        mActivityService = activity;
        mBatteryStats = BatteryStatsService.getService();
        mBatteryService = battery;

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

        mScreenOnIntent = new Intent(Intent.ACTION_SCREEN_ON);
        mScreenOnIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mScreenOffIntent = new Intent(Intent.ACTION_SCREEN_OFF);
        mScreenOffIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);

        ContentResolver resolver = mContext.getContentResolver();
        Cursor settingsCursor = resolver.query(Settings.System.CONTENT_URI, null,
                "(" + Settings.System.NAME + "=?) or ("
                        + Settings.System.NAME + "=?) or ("
                        + Settings.System.NAME + "=?)",
                new String[]{STAY_ON_WHILE_PLUGGED_IN, SCREEN_OFF_TIMEOUT, DIM_SCREEN},
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

        // Listen for Gservices changes
        IntentFilter gservicesChangedFilter =
                new IntentFilter(Settings.Gservices.CHANGED_ACTION);
        mContext.registerReceiver(new GservicesChangedReceiver(), gservicesChangedFilter);
        // And explicitly do the initial update of our cached settings
        updateGservicesValues();

        // turn everything on
        setPowerState(ALL_BRIGHT);
        
        synchronized (mHandlerThread) {
            mInitComplete = true;
            mHandlerThread.notifyAll();
        }
    }

    private class WakeLock implements IBinder.DeathRecipient
    {
        WakeLock(int f, IBinder b, String t, int u) {
            super();
            flags = f;
            binder = b;
            tag = t;
            uid = u == MY_UID ? Process.SYSTEM_UID : u;
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
                releaseWakeLockLocked(this.binder, true);
            }
        }
        final int flags;
        final IBinder binder;
        final String tag;
        final int uid;
        final int monitorType;
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
                || n == PowerManager.SCREEN_DIM_WAKE_LOCK;
    }

    public void acquireWakeLock(int flags, IBinder lock, String tag) {
        int uid = Binder.getCallingUid();
        if (uid != Process.myUid()) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        }
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mLocks) {
                acquireWakeLockLocked(flags, lock, uid, tag);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void acquireWakeLockLocked(int flags, IBinder lock, int uid, String tag) {
        int acquireUid = -1;
        String acquireName = null;
        int acquireType = -1;

        if (mSpew) {
            Log.d(TAG, "acquireWakeLock flags=0x" + Integer.toHexString(flags) + " tag=" + tag);
        }

        int index = mLocks.getIndex(lock);
        WakeLock wl;
        boolean newlock;
        if (index < 0) {
            wl = new WakeLock(flags, lock, tag, uid);
            switch (wl.flags & LOCK_MASK)
            {
                case PowerManager.FULL_WAKE_LOCK:
                    wl.minState = (mKeyboardVisible ? ALL_BRIGHT : SCREEN_BUTTON_BRIGHT);
                    break;
                case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                    wl.minState = SCREEN_BRIGHT;
                    break;
                case PowerManager.SCREEN_DIM_WAKE_LOCK:
                    wl.minState = SCREEN_DIM;
                    break;
                case PowerManager.PARTIAL_WAKE_LOCK:
                    break;
                default:
                    // just log and bail.  we're in the server, so don't
                    // throw an exception.
                    Log.e(TAG, "bad wakelock type for lock '" + tag + "' "
                            + " flags=" + flags);
                    return;
            }
            mLocks.addLock(wl);
            newlock = true;
        } else {
            wl = mLocks.get(index);
            newlock = false;
        }
        if (isScreenLock(flags)) {
            // if this causes a wakeup, we reactivate all of the locks and
            // set it to whatever they want.  otherwise, we modulate that
            // by the current state so we never turn it more on than
            // it already is.
            if ((wl.flags & PowerManager.ACQUIRE_CAUSES_WAKEUP) != 0) {
                int oldWakeLockState = mWakeLockState;
                mWakeLockState = mLocks.reactivateScreenLocksLocked();
                if (mSpew) {
                    Log.d(TAG, "wakeup here mUserState=0x" + Integer.toHexString(mUserState)
                            + " mWakeLockState=0x"
                            + Integer.toHexString(mWakeLockState)
                            + " previous wakeLockState=0x" + Integer.toHexString(oldWakeLockState));
                }
            } else {
                if (mSpew) {
                    Log.d(TAG, "here mUserState=0x" + Integer.toHexString(mUserState)
                            + " mLocks.gatherState()=0x"
                            + Integer.toHexString(mLocks.gatherState())
                            + " mWakeLockState=0x" + Integer.toHexString(mWakeLockState));
                }
                mWakeLockState = (mUserState | mWakeLockState) & mLocks.gatherState();
            }
            setPowerState(mWakeLockState | mUserState);
        }
        else if ((flags & LOCK_MASK) == PowerManager.PARTIAL_WAKE_LOCK) {
            if (newlock) {
                mPartialCount++;
                if (mPartialCount == 1) {
                    if (LOG_PARTIAL_WL) EventLog.writeEvent(LOG_POWER_PARTIAL_WAKE_STATE, 1, tag);
                }
            }
            Power.acquireWakeLock(Power.PARTIAL_WAKE_LOCK,PARTIAL_NAME);
        }
        if (newlock) {
            acquireUid = wl.uid;
            acquireName = wl.tag;
            acquireType = wl.monitorType;
        }

        if (acquireType >= 0) {
            try {
                mBatteryStats.noteStartWakelock(acquireUid, acquireName, acquireType);
            } catch (RemoteException e) {
                // Ignore
            }
        }
    }

    public void releaseWakeLock(IBinder lock) {
        int uid = Binder.getCallingUid();
        if (uid != Process.myUid()) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        }

        synchronized (mLocks) {
            releaseWakeLockLocked(lock, false);
        }
    }

    private void releaseWakeLockLocked(IBinder lock, boolean death) {
        int releaseUid;
        String releaseName;
        int releaseType;

        WakeLock wl = mLocks.removeLock(lock);
        if (wl == null) {
            return;
        }
        
        if (mSpew) {
            Log.d(TAG, "releaseWakeLock flags=0x"
                    + Integer.toHexString(wl.flags) + " tag=" + wl.tag);
        }

        if (isScreenLock(wl.flags)) {
            mWakeLockState = mLocks.gatherState();
            // goes in the middle to reduce flicker
            if ((wl.flags & PowerManager.ON_AFTER_RELEASE) != 0) {
                userActivity(SystemClock.uptimeMillis(), false);
            }
            setPowerState(mWakeLockState | mUserState);
        }
        else if ((wl.flags & LOCK_MASK) == PowerManager.PARTIAL_WAKE_LOCK) {
            mPartialCount--;
            if (mPartialCount == 0) {
                if (LOG_PARTIAL_WL) EventLog.writeEvent(LOG_POWER_PARTIAL_WAKE_STATE, 0, wl.tag);
                Power.releaseWakeLock(PARTIAL_NAME);
            }
        }
        // Unlink the lock from the binder.
        wl.binder.unlinkToDeath(wl, 0);
        releaseUid = wl.uid;
        releaseName = wl.tag;
        releaseType = wl.monitorType;

        if (releaseType >= 0) {
            long origId = Binder.clearCallingIdentity();
            try {
                mBatteryStats.noteStopWakelock(releaseUid, releaseName, releaseType);
            } catch (RemoteException e) {
                // Ignore
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
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
            Log.e(TAG, "setPokeLock got null token for tag='" + tag + "'");
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
                return "FULL_WAKE_LOCK         ";
            case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                return "SCREEN_BRIGHT_WAKE_LOCK";
            case PowerManager.SCREEN_DIM_WAKE_LOCK:
                return "SCREEN_DIM_WAKE_LOCK   ";
            case PowerManager.PARTIAL_WAKE_LOCK:
                return "PARTIAL_WAKE_LOCK      ";
            default:
                return "???                    ";
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
                + " mStayOnConditions=" + mStayOnConditions);
        pw.println("  mOffBecauseOfUser=" + mOffBecauseOfUser
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
                + "  mScreenBrightnessOverride=" + mScreenBrightnessOverride);
        pw.println("  mTotalDelaySetting=" + mTotalDelaySetting);
        pw.println("  mBroadcastWakeLock=" + mBroadcastWakeLock);
        pw.println("  mStayOnWhilePluggedInScreenDimLock=" + mStayOnWhilePluggedInScreenDimLock);
        pw.println("  mStayOnWhilePluggedInPartialLock=" + mStayOnWhilePluggedInPartialLock);
        pw.println("  mPreventScreenOnPartialLock=" + mPreventScreenOnPartialLock);
        mScreenBrightness.dump(pw, "  mScreenBrightness: ");
        mKeyboardBrightness.dump(pw, "  mKeyboardBrightness: ");
        mButtonBrightness.dump(pw, "  mButtonBrightness: ");
        
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
                    + activated + " (minState=" + wl.minState + ")");
        }
        
        pw.println();
        pw.println("mPokeLocks.size=" + mPokeLocks.size() + ":");
        for (PokeLock p: mPokeLocks.values()) {
            pw.println("    poke lock '" + p.tag + "':"
                    + ((p.pokey & POKE_LOCK_IGNORE_CHEEK_EVENTS) != 0
                            ? " POKE_LOCK_IGNORE_CHEEK_EVENTS" : "")
                    + ((p.pokey & POKE_LOCK_IGNORE_TOUCH_AND_CHEEK_EVENTS) != 0
                            ? " POKE_LOCK_IGNORE_TOUCH_AND_CHEEK_EVENTS" : "")
                    + ((p.pokey & POKE_LOCK_SHORT_TIMEOUT) != 0
                            ? " POKE_LOCK_SHORT_TIMEOUT" : "")
                    + ((p.pokey & POKE_LOCK_MEDIUM_TIMEOUT) != 0
                            ? " POKE_LOCK_MEDIUM_TIMEOUT" : ""));
        }

        pw.println();
    }

    private void setTimeoutLocked(long now, int nextState)
    {
        if (mDoneBooting) {
            mHandler.removeCallbacks(mTimeoutTask);
            mTimeoutTask.nextState = nextState;
            long when = now;
            switch (nextState)
            {
                case SCREEN_BRIGHT:
                    when += mKeylightDelay;
                    break;
                case SCREEN_DIM:
                    if (mDimDelay >= 0) {
                        when += mDimDelay;
                        break;
                    } else {
                        Log.w(TAG, "mDimDelay=" + mDimDelay + " while trying to dim");
                    }
                case SCREEN_OFF:
                    synchronized (mLocks) {
                        when += mScreenOffDelay;
                    }
                    break;
            }
            if (mSpew) {
                Log.d(TAG, "setTimeoutLocked now=" + now + " nextState=" + nextState
                        + " when=" + when);
            }
            mHandler.postAtTime(mTimeoutTask, when);
            mNextTimeout = when; // for debugging
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
        public void run()
        {
            synchronized (mLocks) {
                if (mSpew) {
                    Log.d(TAG, "user activity timeout timed out nextState=" + this.nextState);
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
                            setTimeoutLocked(now, SCREEN_DIM);
                            break;
                        }
                    case SCREEN_DIM:
                        setTimeoutLocked(now, SCREEN_OFF);
                        break;
                }
            }
        }
    }

    private void sendNotificationLocked(boolean on, int why)
    {
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
            // Also, while we're collapsing them, if it's going to be an "off," and one
            // is off because of user, then use that, regardless of whether it's the first
            // or second one.
            if (!on && why == WindowManagerPolicy.OFF_BECAUSE_OF_USER) {
                mBroadcastWhy[0] = WindowManagerPolicy.OFF_BECAUSE_OF_USER;
            }
            mBroadcastQueue[0] = on ? 1 : 0;
            mBroadcastQueue[1] = -1;
            mBroadcastQueue[2] = -1;
            index = 0;
        }
        if (index == 1 && !on) {
            mBroadcastQueue[0] = -1;
            mBroadcastQueue[1] = -1;
            index = -1;
            // The wake lock was being held, but we're not actually going to do any
            // broadcasts, so release the wake lock.
            EventLog.writeEvent(LOG_POWER_SCREEN_BROADCAST_STOP, 1, mBroadcastWakeLock.mCount);
            mBroadcastWakeLock.release();
        }

        // Now send the message.
        if (index >= 0) {
            // Acquire the broadcast wake lock before changing the power
            // state. It will be release after the broadcast is sent.
            // We always increment the ref count for each notification in the queue
            // and always decrement when that notification is handled.
            mBroadcastWakeLock.acquire();
            EventLog.writeEvent(LOG_POWER_SCREEN_BROADCAST_SEND, mBroadcastWakeLock.mCount);
            mHandler.post(mNotificationTask);
        }
    }

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
                }
                if (value == 1) {
                    mScreenOnStart = SystemClock.uptimeMillis();
                    
                    policy.screenTurnedOn();
                    try {
                        ActivityManagerNative.getDefault().wakingUp();
                    } catch (RemoteException e) {
                        // ignore it
                    }

                    if (mSpew) {
                        Log.d(TAG, "mBroadcastWakeLock=" + mBroadcastWakeLock);
                    }
                    if (mContext != null && ActivityManagerNative.isSystemReady()) {
                        mContext.sendOrderedBroadcast(mScreenOnIntent, null,
                                mScreenOnBroadcastDone, mHandler, 0, null, null);
                    } else {
                        synchronized (mLocks) {
                            EventLog.writeEvent(LOG_POWER_SCREEN_BROADCAST_STOP, 2,
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
                            EventLog.writeEvent(LOG_POWER_SCREEN_BROADCAST_STOP, 3,
                                    mBroadcastWakeLock.mCount);
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
                EventLog.writeEvent(LOG_POWER_SCREEN_BROADCAST_DONE, 1,
                        SystemClock.uptimeMillis() - mScreenOnStart, mBroadcastWakeLock.mCount);
                mBroadcastWakeLock.release();
            }
        }
    };

    long mScreenOffStart;
    private BroadcastReceiver mScreenOffBroadcastDone = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            synchronized (mLocks) {
                EventLog.writeEvent(LOG_POWER_SCREEN_BROADCAST_DONE, 0,
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
                // we *won't* call Power.setScreenState(true) if it's set.)
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
                if ((mPowerState & SCREEN_ON_BIT) != 0) {
                    if (mSpew) {
                        Log.d(TAG,
                              "preventScreenOn: turning on after a prior preventScreenOn(true)!");
                    }
                    int err = Power.setScreenState(true);
                    if (err != 0) {
                        Log.w(TAG, "preventScreenOn: error from Power.setScreenState(): " + err);
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

        synchronized (mLocks) {
            if (mScreenBrightnessOverride != brightness) {
                mScreenBrightnessOverride = brightness;
                updateLightsLocked(mPowerState, SCREEN_ON_BIT);
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
            Log.w(TAG, "forceReenableScreen: mPreventScreenOn is false, nothing to do");
            return;
        }

        // Uh oh.  It's been 5 seconds since a call to
        // preventScreenOn(true) and we haven't re-enabled the screen yet.
        // This means the app that called preventScreenOn(true) is either
        // slow (i.e. it took more than 5 seconds to call preventScreenOn(false)),
        // or buggy (i.e. it forgot to call preventScreenOn(false), or
        // crashed before doing so.)

        // Log a warning, and forcibly turn the screen back on.
        Log.w(TAG, "App called preventScreenOn(true) but didn't promptly reenable the screen! "
              + "Forcing the screen back on...");
        preventScreenOn(false);
    }

    private Runnable mForceReenableScreenTask = new Runnable() {
            public void run() {
                forceReenableScreen();
            }
        };

    private void setPowerState(int state)
    {
        setPowerState(state, false, false);
    }

    private void setPowerState(int newState, boolean noChangeLights, boolean becauseOfUser)
    {
        synchronized (mLocks) {
            int err;

            if (mSpew) {
                Log.d(TAG, "setPowerState: mPowerState=0x" + Integer.toHexString(mPowerState)
                        + " newState=0x" + Integer.toHexString(newState)
                        + " noChangeLights=" + noChangeLights);
            }

            if (noChangeLights) {
                newState = (newState & ~LIGHTS_MASK) | (mPowerState & LIGHTS_MASK);
            }

            if (batteryIsLow()) {
                newState |= BATTERY_LOW_BIT;
            } else {
                newState &= ~BATTERY_LOW_BIT;
            }
            if (newState == mPowerState) {
                return;
            }
            
            if (!mDoneBooting) {
                newState |= ALL_BRIGHT;
            }

            boolean oldScreenOn = (mPowerState & SCREEN_ON_BIT) != 0;
            boolean newScreenOn = (newState & SCREEN_ON_BIT) != 0;

            if (mSpew) {
                Log.d(TAG, "setPowerState: mPowerState=" + mPowerState
                        + " newState=" + newState + " noChangeLights=" + noChangeLights);
                Log.d(TAG, "  oldKeyboardBright=" + ((mPowerState & KEYBOARD_BRIGHT_BIT) != 0)
                         + " newKeyboardBright=" + ((newState & KEYBOARD_BRIGHT_BIT) != 0));
                Log.d(TAG, "  oldScreenBright=" + ((mPowerState & SCREEN_BRIGHT_BIT) != 0)
                         + " newScreenBright=" + ((newState & SCREEN_BRIGHT_BIT) != 0));
                Log.d(TAG, "  oldButtonBright=" + ((mPowerState & BUTTON_BRIGHT_BIT) != 0)
                         + " newButtonBright=" + ((newState & BUTTON_BRIGHT_BIT) != 0));
                Log.d(TAG, "  oldScreenOn=" + oldScreenOn
                         + " newScreenOn=" + newScreenOn);
                Log.d(TAG, "  oldBatteryLow=" + ((mPowerState & BATTERY_LOW_BIT) != 0)
                         + " newBatteryLow=" + ((newState & BATTERY_LOW_BIT) != 0));
            }

            if (mPowerState != newState) {
                updateLightsLocked(newState, 0);
                mPowerState = (mPowerState & ~LIGHTS_MASK) | (newState & LIGHTS_MASK);
            }

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
                        Log.d(TAG, "- turning screen on...  mPreventScreenOn = "
                              + mPreventScreenOn);
                    }

                    if (mPreventScreenOn) {
                        if (mSpew) {
                            Log.d(TAG, "- PREVENTING screen from really turning on!");
                        }
                        reallyTurnScreenOn = false;
                    }
                    if (reallyTurnScreenOn) {
                        err = Power.setScreenState(true);
                        long identity = Binder.clearCallingIdentity();
                        try {
                            mBatteryStats.noteScreenBrightness(
                                    getPreferredBrightness());
                            mBatteryStats.noteScreenOn();
                        } catch (RemoteException e) {
                            Log.w(TAG, "RemoteException calling noteScreenOn on BatteryStatsService", e);
                        } finally {
                            Binder.restoreCallingIdentity(identity);
                        }
                    } else {
                        Power.setScreenState(false);
                        // But continue as if we really did turn the screen on...
                        err = 0;
                    }

                    mScreenOnStartTime = SystemClock.elapsedRealtime();
                    mLastTouchDown = 0;
                    mTotalTouchDownTime = 0;
                    mTouchCycles = 0;
                    EventLog.writeEvent(LOG_POWER_SCREEN_STATE, 1, becauseOfUser ? 1 : 0,
                            mTotalTouchDownTime, mTouchCycles);
                    if (err == 0) {
                        mPowerState |= SCREEN_ON_BIT;
                        sendNotificationLocked(true, -1);
                    }
                } else {
                    mScreenOffTime = SystemClock.elapsedRealtime();
                    long identity = Binder.clearCallingIdentity();
                    try {
                        mBatteryStats.noteScreenOff();
                    } catch (RemoteException e) {
                        Log.w(TAG, "RemoteException calling noteScreenOff on BatteryStatsService", e);
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                    mPowerState &= ~SCREEN_ON_BIT;
                    if (!mScreenBrightness.animating) {
                        err = screenOffFinishedAnimatingLocked(becauseOfUser);
                    } else {
                        mOffBecauseOfUser = becauseOfUser;
                        err = 0;
                        mLastTouchDown = 0;
                    }
                }
            }
        }
    }
    
    private int screenOffFinishedAnimatingLocked(boolean becauseOfUser) {
        // I don't think we need to check the current state here because all of these
        // Power.setScreenState and sendNotificationLocked can both handle being 
        // called multiple times in the same state. -joeo
        EventLog.writeEvent(LOG_POWER_SCREEN_STATE, 0, becauseOfUser ? 1 : 0,
                mTotalTouchDownTime, mTouchCycles);
        mLastTouchDown = 0;
        int err = Power.setScreenState(false);
        if (mScreenOnStartTime != 0) {
            mScreenOnTime += SystemClock.elapsedRealtime() - mScreenOnStartTime;
            mScreenOnStartTime = 0;
        }
        if (err == 0) {
            int why = becauseOfUser
                    ? WindowManagerPolicy.OFF_BECAUSE_OF_USER
                    : WindowManagerPolicy.OFF_BECAUSE_OF_TIMEOUT;
            sendNotificationLocked(false, why);
        }
        return err;
    }

    private boolean batteryIsLow() {
        return (!mIsPowered &&
                mBatteryService.getBatteryLevel() <= Power.LOW_BATTERY_THRESHOLD);
    }

    private void updateLightsLocked(int newState, int forceState) {
        final int oldState = mPowerState;
        final int realDifference = (newState ^ oldState);
        final int difference = realDifference | forceState;
        if (difference == 0) {
            return;
        }
        
        int offMask = 0;
        int dimMask = 0;
        int onMask = 0;

        int preferredBrightness = getPreferredBrightness();
        boolean startAnimation = false;
        
        if ((difference & KEYBOARD_BRIGHT_BIT) != 0) {
            if (ANIMATE_KEYBOARD_LIGHTS) {
                if ((newState & KEYBOARD_BRIGHT_BIT) == 0) {
                    mKeyboardBrightness.setTargetLocked(Power.BRIGHTNESS_OFF,
                            ANIM_STEPS, INITIAL_KEYBOARD_BRIGHTNESS,
                            preferredBrightness);
                } else {
                    mKeyboardBrightness.setTargetLocked(preferredBrightness,
                            ANIM_STEPS, INITIAL_KEYBOARD_BRIGHTNESS,
                            Power.BRIGHTNESS_OFF);
                }
                startAnimation = true;
            } else {
                if ((newState & KEYBOARD_BRIGHT_BIT) == 0) {
                    offMask |= KEYBOARD_BRIGHT_BIT;
                } else {
                    onMask |= KEYBOARD_BRIGHT_BIT;
                }
            }
        }

        if ((difference & BUTTON_BRIGHT_BIT) != 0) {
            if (ANIMATE_BUTTON_LIGHTS) {
                if ((newState & BUTTON_BRIGHT_BIT) == 0) {
                    mButtonBrightness.setTargetLocked(Power.BRIGHTNESS_OFF,
                            ANIM_STEPS, INITIAL_BUTTON_BRIGHTNESS,
                            preferredBrightness);
                } else {
                    mButtonBrightness.setTargetLocked(preferredBrightness,
                            ANIM_STEPS, INITIAL_BUTTON_BRIGHTNESS,
                            Power.BRIGHTNESS_OFF);
                }
                startAnimation = true;
            } else {
                if ((newState & BUTTON_BRIGHT_BIT) == 0) {
                    offMask |= BUTTON_BRIGHT_BIT;
                } else {
                    onMask |= BUTTON_BRIGHT_BIT;
                }
            }
        }

        if ((difference & (SCREEN_ON_BIT | SCREEN_BRIGHT_BIT)) != 0) {
            if (ANIMATE_SCREEN_LIGHTS) {
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
                            nominalCurrentValue = Power.BRIGHTNESS_DIM;
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
                    float ratio = (((float)Power.BRIGHTNESS_DIM)/preferredBrightness);
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
                        brightness = Power.BRIGHTNESS_DIM;
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
                mScreenBrightness.setTargetLocked(brightness,
                        steps, INITIAL_SCREEN_BRIGHTNESS, nominalCurrentValue);
                startAnimation = true;
            } else {
                if ((newState & SCREEN_BRIGHT_BIT) == 0) {
                    // dim or turn off backlight, depending on if the screen is on
                    if ((newState & SCREEN_ON_BIT) == 0) {
                        offMask |= SCREEN_BRIGHT_BIT;
                    } else {
                        dimMask |= SCREEN_BRIGHT_BIT;
                    }
                } else {
                    onMask |= SCREEN_BRIGHT_BIT;
                }
            }
        }

        if (startAnimation) {
            if (mSpew) {
                Log.i(TAG, "Scheduling light animator!");
            }
            mHandler.removeCallbacks(mLightAnimator);
            mHandler.post(mLightAnimator);
        }
        
        if (offMask != 0) {
            //Log.i(TAG, "Setting brightess off: " + offMask);
            setLightBrightness(offMask, Power.BRIGHTNESS_OFF);
        }
        if (dimMask != 0) {
            int brightness = Power.BRIGHTNESS_DIM;
            if ((newState & BATTERY_LOW_BIT) != 0 &&
                    brightness > Power.BRIGHTNESS_LOW_BATTERY) {
                brightness = Power.BRIGHTNESS_LOW_BATTERY;
            }
            //Log.i(TAG, "Setting brightess dim " + brightness + ": " + offMask);
            setLightBrightness(dimMask, brightness);
        }
        if (onMask != 0) {
            int brightness = getPreferredBrightness();
            if ((newState & BATTERY_LOW_BIT) != 0 &&
                    brightness > Power.BRIGHTNESS_LOW_BATTERY) {
                brightness = Power.BRIGHTNESS_LOW_BATTERY;
            }
            //Log.i(TAG, "Setting brightess on " + brightness + ": " + onMask);
            setLightBrightness(onMask, brightness);
        }
    }

    private void setLightBrightness(int mask, int value) {
        if ((mask & SCREEN_BRIGHT_BIT) != 0) {
            mHardware.setLightBrightness_UNCHECKED(HardwareService.LIGHT_ID_BACKLIGHT, value);
        }
        if ((mask & BUTTON_BRIGHT_BIT) != 0) {
            mHardware.setLightBrightness_UNCHECKED(HardwareService.LIGHT_ID_BUTTONS, value);
        }
        if ((mask & KEYBOARD_BRIGHT_BIT) != 0) {
            mHardware.setLightBrightness_UNCHECKED(HardwareService.LIGHT_ID_KEYBOARD, value);
        }
    }

    class BrightnessState {
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
        
        void setTargetLocked(int target, int stepsToTarget, int initialValue,
                int nominalCurrentValue) {
            if (!initialized) {
                initialized = true;
                curValue = (float)initialValue;
            }
            targetValue = target;
            delta = (targetValue -
                    (nominalCurrentValue >= 0 ? nominalCurrentValue : curValue))
                    / stepsToTarget;
            if (mSpew) {
                String noticeMe = nominalCurrentValue == curValue ? "" : "  ******************";
                Log.i(TAG, "Setting target " + mask + ": cur=" + curValue
                        + " target=" + targetValue + " delta=" + delta
                        + " nominalCurrentValue=" + nominalCurrentValue
                        + noticeMe);
            }
            animating = true;
        }
        
        boolean stepLocked() {
            if (!animating) return false;
            if (false && mSpew) {
                Log.i(TAG, "Step target " + mask + ": cur=" + curValue
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
            //Log.i(TAG, "Animating brightess " + curIntValue + ": " + mask);
            setLightBrightness(mask, curIntValue);
            animating = more;
            if (!more) {
                if (mask == SCREEN_BRIGHT_BIT && curIntValue == Power.BRIGHTNESS_OFF) {
                    screenOffFinishedAnimatingLocked(mOffBecauseOfUser);
                }
            }
            return more;
        }
    }
    
    private class LightAnimator implements Runnable {
        public void run() {
            synchronized (mLocks) {
                long now = SystemClock.uptimeMillis();
                boolean more = mScreenBrightness.stepLocked();
                if (mKeyboardBrightness.stepLocked()) {
                    more = true;
                }
                if (mButtonBrightness.stepLocked()) {
                    more = true;
                }
                if (more) {
                    mHandler.postAtTime(mLightAnimator, now+(1000/60));
                }
            }
        }
    }
    
    private int getPreferredBrightness() {
        try {
            if (mScreenBrightnessOverride >= 0) {
                return mScreenBrightnessOverride;
            }
            final int brightness = Settings.System.getInt(mContext.getContentResolver(),
                                                          SCREEN_BRIGHTNESS);
             // Don't let applications turn the screen all the way off
            return Math.max(brightness, Power.BRIGHTNESS_DIM);
        } catch (SettingNotFoundException snfe) {
            return Power.BRIGHTNESS_ON;
        }
    }

    boolean screenIsOn() {
        synchronized (mLocks) {
            return (mPowerState & SCREEN_ON_BIT) != 0;
        }
    }

    boolean screenIsBright() {
        synchronized (mLocks) {
            return (mPowerState & SCREEN_BRIGHT) == SCREEN_BRIGHT;
        }
    }

    public void userActivityWithForce(long time, boolean noChangeLights, boolean force) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
        userActivity(time, noChangeLights, OTHER_EVENT, force);
    }

    public void userActivity(long time, boolean noChangeLights) {
        userActivity(time, noChangeLights, OTHER_EVENT, false);
    }

    public void userActivity(long time, boolean noChangeLights, int eventType) {
        userActivity(time, noChangeLights, eventType, false);
    }

    public void userActivity(long time, boolean noChangeLights, int eventType, boolean force) {
        //mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);

        if (((mPokey & POKE_LOCK_IGNORE_CHEEK_EVENTS) != 0)
                && (eventType == CHEEK_EVENT || eventType == TOUCH_EVENT)) {
            if (false) {
                Log.d(TAG, "dropping cheek or short event mPokey=0x" + Integer.toHexString(mPokey));
            }
            return;
        }

        if (((mPokey & POKE_LOCK_IGNORE_TOUCH_AND_CHEEK_EVENTS) != 0)
                && (eventType == TOUCH_EVENT || eventType == TOUCH_UP_EVENT
                    || eventType == LONG_TOUCH_EVENT || eventType == CHEEK_EVENT)) {
            if (false) {
                Log.d(TAG, "dropping touch mPokey=0x" + Integer.toHexString(mPokey));
            }
            return;
        }


        if (false) {
            if (((mPokey & POKE_LOCK_IGNORE_CHEEK_EVENTS) != 0)) {
                Log.d(TAG, "userActivity !!!");//, new RuntimeException());
            } else {
                Log.d(TAG, "mPokey=0x" + Integer.toHexString(mPokey));
            }
        }

        synchronized (mLocks) {
            if (mSpew) {
                Log.d(TAG, "userActivity mLastEventTime=" + mLastEventTime + " time=" + time
                        + " mUserActivityAllowed=" + mUserActivityAllowed
                        + " mUserState=0x" + Integer.toHexString(mUserState)
                        + " mWakeLockState=0x" + Integer.toHexString(mWakeLockState));
            }
            if (mLastEventTime <= time || force) {
                mLastEventTime = time;
                if (mUserActivityAllowed || force) {
                    // Only turn on button backlights if a button was pressed.
                    if (eventType == BUTTON_EVENT) {
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
                    setPowerState(mUserState | mWakeLockState, noChangeLights, true);
                    setTimeoutLocked(time, SCREEN_BRIGHT);
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
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
        synchronized (mLocks) {
            goToSleepLocked(time);
        }
    }
    
    /**
     * Returns the time the screen has been on since boot, in millis.
     * @return screen on time
     */
    public long getScreenOnTime() {
        synchronized (mLocks) {
            if (mScreenOnStartTime == 0) {
                return mScreenOnTime;
            } else {
                return SystemClock.elapsedRealtime() - mScreenOnStartTime + mScreenOnTime;
            }
        }
    }

    private void goToSleepLocked(long time) {

        if (mLastEventTime <= time) {
            mLastEventTime = time;
            // cancel all of the wake locks
            mWakeLockState = SCREEN_OFF;
            int N = mLocks.size();
            int numCleared = 0;
            for (int i=0; i<N; i++) {
                WakeLock wl = mLocks.get(i);
                if (isScreenLock(wl.flags)) {
                    mLocks.get(i).activated = false;
                    numCleared++;
                }
            }
            EventLog.writeEvent(LOG_POWER_SLEEP_REQUESTED, numCleared);
            mStillNeedSleepNotification = true;
            mUserState = SCREEN_OFF;
            setPowerState(SCREEN_OFF, false, true);
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
        mKeyboardVisible = visible;
    }

    /**
     * When the keyguard is up, it manages the power state, and userActivity doesn't do anything.
     */
    public void enableUserActivity(boolean enabled) {
        synchronized (mLocks) {
            mUserActivityAllowed = enabled;
            mLastEventTime = SystemClock.uptimeMillis(); // we might need to pass this in
        }
    }

    /** Sets the screen off timeouts:
     *      mKeylightDelay
     *      mDimDelay
     *      mScreenOffDelay
     * */
    private void setScreenOffTimeoutsLocked() {
        if ((mPokey & POKE_LOCK_SHORT_TIMEOUT) != 0) {
            mKeylightDelay = mShortKeylightDelay;  // Configurable via Gservices
            mDimDelay = -1;
            mScreenOffDelay = 0;
        } else if ((mPokey & POKE_LOCK_MEDIUM_TIMEOUT) != 0) {
            mKeylightDelay = MEDIUM_KEYLIGHT_DELAY;
            mDimDelay = -1;
            mScreenOffDelay = 0;
        } else {
            int totalDelay = mTotalDelaySetting;
            mKeylightDelay = LONG_KEYLIGHT_DELAY;
            if (totalDelay < 0) {
                mScreenOffDelay = Integer.MAX_VALUE;
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
            Log.d(TAG, "setScreenOffTimeouts mKeylightDelay=" + mKeylightDelay
                    + " mDimDelay=" + mDimDelay + " mScreenOffDelay=" + mScreenOffDelay
                    + " mDimScreen=" + mDimScreen);
        }
    }

    /**
     * Refreshes cached Gservices settings.  Called once on startup, and
     * on subsequent Settings.Gservices.CHANGED_ACTION broadcasts (see
     * GservicesChangedReceiver).
     */
    private void updateGservicesValues() {
        mShortKeylightDelay = Settings.Gservices.getInt(
                mContext.getContentResolver(),
                Settings.Gservices.SHORT_KEYLIGHT_DELAY_MS,
                SHORT_KEYLIGHT_DELAY_DEFAULT);
        // Log.i(TAG, "updateGservicesValues(): mShortKeylightDelay now " + mShortKeylightDelay);
    }

    /**
     * Receiver for the Gservices.CHANGED_ACTION broadcast intent,
     * which tells us we need to refresh our cached Gservices settings.
     */
    private class GservicesChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Log.i(TAG, "GservicesChangedReceiver.onReceive(): " + intent);
            updateGservicesValues();
        }
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
            return result;
        }
    }

    void setPolicy(WindowManagerPolicy p) {
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
        synchronized (mLocks) {
            Log.d(TAG, "system ready!");
            mDoneBooting = true;
            long identity = Binder.clearCallingIdentity();
            try {
                mBatteryStats.noteScreenBrightness(getPreferredBrightness());
                mBatteryStats.noteScreenOn();
            } catch (RemoteException e) {
                // Nothing interesting to do.
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            userActivity(SystemClock.uptimeMillis(), false, BUTTON_EVENT, true);
            updateWakeLockLocked();
            mLocks.notifyAll();
        }
    }

    public void monitor() {
        synchronized (mLocks) { }
    }
}
