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

package com.android.internal.policy.impl;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.IUiModeManager;
import android.app.UiModeManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.LocalPowerManager;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.provider.Settings;

import com.android.internal.policy.PolicyManager;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.telephony.ITelephony;
import com.android.internal.widget.PointerLocationView;

import android.util.Config;
import android.util.EventLog;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowOrientationListener;
import android.view.RawInputEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static android.view.WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_PHONE;
import static android.view.WindowManager.LayoutParams.TYPE_PRIORITY_PHONE;
import static android.view.WindowManager.LayoutParams.TYPE_SEARCH_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import android.view.WindowManagerImpl;
import android.view.WindowManagerPolicy;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.media.IAudioService;
import android.media.AudioManager;

import java.util.ArrayList;

/**
 * WindowManagerPolicy implementation for the Android phone UI.  This
 * introduces a new method suffix, Lp, for an internal lock of the
 * PhoneWindowManager.  This is used to protect some internal state, and
 * can be acquired with either thw Lw and Li lock held, so has the restrictions
 * of both of those when held.
 */
public class PhoneWindowManager implements WindowManagerPolicy {
    static final String TAG = "WindowManager";
    static final boolean DEBUG = false;
    static final boolean localLOGV = DEBUG ? Config.LOGD : Config.LOGV;
    static final boolean DEBUG_LAYOUT = false;
    static final boolean SHOW_STARTING_ANIMATIONS = true;
    static final boolean SHOW_PROCESSES_ON_ALT_MENU = false;
    
    // wallpaper is at the bottom, though the window manager may move it.
    static final int WALLPAPER_LAYER = 2;
    static final int APPLICATION_LAYER = 2;
    static final int PHONE_LAYER = 3;
    static final int SEARCH_BAR_LAYER = 4;
    static final int STATUS_BAR_PANEL_LAYER = 5;
    static final int SYSTEM_DIALOG_LAYER = 6;
    // toasts and the plugged-in battery thing
    static final int TOAST_LAYER = 7;
    static final int STATUS_BAR_LAYER = 8;
    // SIM errors and unlock.  Not sure if this really should be in a high layer.
    static final int PRIORITY_PHONE_LAYER = 9;
    // like the ANR / app crashed dialogs
    static final int SYSTEM_ALERT_LAYER = 10;
    // system-level error dialogs
    static final int SYSTEM_ERROR_LAYER = 11;
    // on-screen keyboards and other such input method user interfaces go here.
    static final int INPUT_METHOD_LAYER = 12;
    // on-screen keyboards and other such input method user interfaces go here.
    static final int INPUT_METHOD_DIALOG_LAYER = 13;
    // the keyguard; nothing on top of these can take focus, since they are
    // responsible for power management when displayed.
    static final int KEYGUARD_LAYER = 14;
    static final int KEYGUARD_DIALOG_LAYER = 15;
    // things in here CAN NOT take focus, but are shown on top of everything else.
    static final int SYSTEM_OVERLAY_LAYER = 16;

    static final int APPLICATION_MEDIA_SUBLAYER = -2;
    static final int APPLICATION_MEDIA_OVERLAY_SUBLAYER = -1;
    static final int APPLICATION_PANEL_SUBLAYER = 1;
    static final int APPLICATION_SUB_PANEL_SUBLAYER = 2;

    static final float SLIDE_TOUCH_EVENT_SIZE_LIMIT = 0.6f;
    
    // Debugging: set this to have the system act like there is no hard keyboard.
    static final boolean KEYBOARD_ALWAYS_HIDDEN = false;
    
    static public final String SYSTEM_DIALOG_REASON_KEY = "reason";
    static public final String SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS = "globalactions";
    static public final String SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps";
    static public final String SYSTEM_DIALOG_REASON_HOME_KEY = "homekey";

    final Object mLock = new Object();
    
    Context mContext;
    IWindowManager mWindowManager;
    LocalPowerManager mPowerManager;
    Vibrator mVibrator; // Vibrator for giving feedback of orientation changes

    // Vibrator pattern for haptic feedback of a long press.
    long[] mLongPressVibePattern;
    
    // Vibrator pattern for haptic feedback of virtual key press.
    long[] mVirtualKeyVibePattern;
    
    // Vibrator pattern for a short vibration.
    long[] mKeyboardTapVibePattern;

    // Vibrator pattern for haptic feedback during boot when safe mode is disabled.
    long[] mSafeModeDisabledVibePattern;
    
    // Vibrator pattern for haptic feedback during boot when safe mode is enabled.
    long[] mSafeModeEnabledVibePattern;

    /** If true, hitting shift & menu will broadcast Intent.ACTION_BUG_REPORT */
    boolean mEnableShiftMenuBugReports = false;
    
    boolean mSafeMode;
    WindowState mStatusBar = null;
    final ArrayList<WindowState> mStatusBarPanels = new ArrayList<WindowState>();
    WindowState mKeyguard = null;
    KeyguardViewMediator mKeyguardMediator;
    GlobalActions mGlobalActions;
    boolean mShouldTurnOffOnKeyUp;
    RecentApplicationsDialog mRecentAppsDialog;
    Handler mHandler;
    
    boolean mSystemReady;
    boolean mLidOpen;
    int mUiMode = Configuration.UI_MODE_TYPE_NORMAL;
    int mDockMode = Intent.EXTRA_DOCK_STATE_UNDOCKED;
    int mLidOpenRotation;
    int mCarDockRotation;
    int mDeskDockRotation;
    boolean mCarDockEnablesAccelerometer;
    boolean mDeskDockEnablesAccelerometer;
    int mLidKeyboardAccessibility;
    int mLidNavigationAccessibility;
    boolean mScreenOn = false;
    boolean mOrientationSensorEnabled = false;
    int mCurrentAppOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    static final int DEFAULT_ACCELEROMETER_ROTATION = 0;
    int mAccelerometerDefault = DEFAULT_ACCELEROMETER_ROTATION;
    boolean mHasSoftInput = false;
    
    int mPointerLocationMode = 0;
    PointerLocationView mPointerLocationView = null;
    
    // The current size of the screen.
    int mW, mH;
    // During layout, the current screen borders with all outer decoration
    // (status bar, input method dock) accounted for.
    int mCurLeft, mCurTop, mCurRight, mCurBottom;
    // During layout, the frame in which content should be displayed
    // to the user, accounting for all screen decoration except for any
    // space they deem as available for other content.  This is usually
    // the same as mCur*, but may be larger if the screen decor has supplied
    // content insets.
    int mContentLeft, mContentTop, mContentRight, mContentBottom;
    // During layout, the current screen borders along with input method
    // windows are placed.
    int mDockLeft, mDockTop, mDockRight, mDockBottom;
    // During layout, the layer at which the doc window is placed.
    int mDockLayer;
    
    static final Rect mTmpParentFrame = new Rect();
    static final Rect mTmpDisplayFrame = new Rect();
    static final Rect mTmpContentFrame = new Rect();
    static final Rect mTmpVisibleFrame = new Rect();
    
    WindowState mTopFullscreenOpaqueWindowState;
    boolean mForceStatusBar;
    boolean mHideLockScreen;
    boolean mDismissKeyguard;
    boolean mHomePressed;
    Intent mHomeIntent;
    Intent mCarDockIntent;
    Intent mDeskDockIntent;
    boolean mSearchKeyPressed;
    boolean mConsumeSearchKeyUp;

    // support for activating the lock screen while the screen is on
    boolean mAllowLockscreenWhenOn;
    int mLockScreenTimeout;
    boolean mLockScreenTimerActive;

    // Behavior of ENDCALL Button.  (See Settings.System.END_BUTTON_BEHAVIOR.)
    int mEndcallBehavior;

    // Behavior of POWER button while in-call and screen on.
    // (See Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR.)
    int mIncallPowerBehavior;

    int mLandscapeRotation = -1;
    int mPortraitRotation = -1;

    // Nothing to see here, move along...
    int mFancyRotationAnimation;

    ShortcutManager mShortcutManager;
    PowerManager.WakeLock mBroadcastWakeLock;

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.END_BUTTON_BEHAVIOR), false, this);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.ACCELEROMETER_ROTATION), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_OFF_TIMEOUT), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.POINTER_LOCATION), false, this);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.DEFAULT_INPUT_METHOD), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    "fancy_rotation_anim"), false, this);
            updateSettings();
        }

        @Override public void onChange(boolean selfChange) {
            updateSettings();
            try {
                mWindowManager.setRotation(USE_LAST_ROTATION, false,
                        mFancyRotationAnimation);
            } catch (RemoteException e) {
                // Ignore
            }
        }
    }
    
    class MyOrientationListener extends WindowOrientationListener {
        MyOrientationListener(Context context) {
            super(context);
        }
        
        @Override
        public void onOrientationChanged(int rotation) {
            // Send updates based on orientation value
            if (localLOGV) Log.v(TAG, "onOrientationChanged, rotation changed to " +rotation);
            try {
                mWindowManager.setRotation(rotation, false,
                        mFancyRotationAnimation);
            } catch (RemoteException e) {
                // Ignore

            }
        }                                      
    }
    MyOrientationListener mOrientationListener;

    boolean useSensorForOrientationLp(int appOrientation) {
        // The app says use the sensor.
        if (appOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR) {
            return true;
        }
        // The user preference says we can rotate, and the app is willing to rotate.
        if (mAccelerometerDefault != 0 &&
                (appOrientation == ActivityInfo.SCREEN_ORIENTATION_USER
                 || appOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)) {
            return true;
        }
        // We're in a dock that has a rotation affinity, an the app is willing to rotate.
        if ((mCarDockEnablesAccelerometer && mDockMode == Intent.EXTRA_DOCK_STATE_CAR)
                || (mDeskDockEnablesAccelerometer && mDockMode == Intent.EXTRA_DOCK_STATE_DESK)) {
            // Note we override the nosensor flag here.
            if (appOrientation == ActivityInfo.SCREEN_ORIENTATION_USER
                    || appOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    || appOrientation == ActivityInfo.SCREEN_ORIENTATION_NOSENSOR) {
                return true;
            }
        }
        // Else, don't use the sensor.
        return false;
    }
    
    /*
     * We always let the sensor be switched on by default except when
     * the user has explicitly disabled sensor based rotation or when the
     * screen is switched off.
     */
    boolean needSensorRunningLp() {
        if (mCurrentAppOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR) {
            // If the application has explicitly requested to follow the
            // orientation, then we need to turn the sensor or.
            return true;
        }
        if ((mCarDockEnablesAccelerometer && mDockMode == Intent.EXTRA_DOCK_STATE_CAR) ||
                (mDeskDockEnablesAccelerometer && mDockMode == Intent.EXTRA_DOCK_STATE_DESK)) {
            // enable accelerometer if we are docked in a dock that enables accelerometer
            // orientation management,
            return true;
        }
        if (mAccelerometerDefault == 0) {
            // If the setting for using the sensor by default is enabled, then
            // we will always leave it on.  Note that the user could go to
            // a window that forces an orientation that does not use the
            // sensor and in theory we could turn it off... however, when next
            // turning it on we won't have a good value for the current
            // orientation for a little bit, which can cause orientation
            // changes to lag, so we'd like to keep it always on.  (It will
            // still be turned off when the screen is off.)
            return false;
        }
        return true;
    }
    
    /*
     * Various use cases for invoking this function
     * screen turning off, should always disable listeners if already enabled
     * screen turned on and current app has sensor based orientation, enable listeners 
     * if not already enabled
     * screen turned on and current app does not have sensor orientation, disable listeners if
     * already enabled
     * screen turning on and current app has sensor based orientation, enable listeners if needed
     * screen turning on and current app has nosensor based orientation, do nothing
     */
    void updateOrientationListenerLp() {
        if (!mOrientationListener.canDetectOrientation()) {
            // If sensor is turned off or nonexistent for some reason
            return;
        }
        //Could have been invoked due to screen turning on or off or
        //change of the currently visible window's orientation
        if (localLOGV) Log.v(TAG, "Screen status="+mScreenOn+
                ", current orientation="+mCurrentAppOrientation+
                ", SensorEnabled="+mOrientationSensorEnabled);
        boolean disable = true;
        if (mScreenOn) {
            if (needSensorRunningLp()) {
                disable = false;
                //enable listener if not already enabled
                if (!mOrientationSensorEnabled) {
                    mOrientationListener.enable();
                    if(localLOGV) Log.v(TAG, "Enabling listeners");
                    mOrientationSensorEnabled = true;
                }
            } 
        } 
        //check if sensors need to be disabled
        if (disable && mOrientationSensorEnabled) {
            mOrientationListener.disable();
            if(localLOGV) Log.v(TAG, "Disabling listeners");
            mOrientationSensorEnabled = false;
        }
    }

    Runnable mPowerLongPress = new Runnable() {
        public void run() {
            mShouldTurnOffOnKeyUp = false;
            performHapticFeedbackLw(null, HapticFeedbackConstants.LONG_PRESS, false);
            sendCloseSystemWindows(SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS);
            showGlobalActionsDialog();
        }
    };

    void showGlobalActionsDialog() {
        if (mGlobalActions == null) {
            mGlobalActions = new GlobalActions(mContext);
        }
        final boolean keyguardShowing = mKeyguardMediator.isShowingAndNotHidden();
        mGlobalActions.showDialog(keyguardShowing, isDeviceProvisioned());
        if (keyguardShowing) {
            // since it took two seconds of long press to bring this up,
            // poke the wake lock so they have some time to see the dialog.
            mKeyguardMediator.pokeWakelock();
        }
    }

    boolean isDeviceProvisioned() {
        return Settings.Secure.getInt(
                mContext.getContentResolver(), Settings.Secure.DEVICE_PROVISIONED, 0) != 0;
    }

    /**
     * When a home-key longpress expires, close other system windows and launch the recent apps
     */
    Runnable mHomeLongPress = new Runnable() {
        public void run() {
            /*
             * Eat the longpress so it won't dismiss the recent apps dialog when
             * the user lets go of the home key
             */
            mHomePressed = false;
            performHapticFeedbackLw(null, HapticFeedbackConstants.LONG_PRESS, false);
            sendCloseSystemWindows(SYSTEM_DIALOG_REASON_RECENT_APPS);
            showRecentAppsDialog();
        }
    };

    /**
     * Create (if necessary) and launch the recent apps dialog
     */
    void showRecentAppsDialog() {
        if (mRecentAppsDialog == null) {
            mRecentAppsDialog = new RecentApplicationsDialog(mContext);
        }
        mRecentAppsDialog.show();
    }
    
    /** {@inheritDoc} */
    public void init(Context context, IWindowManager windowManager,
            LocalPowerManager powerManager) {
        mContext = context;
        mWindowManager = windowManager;
        mPowerManager = powerManager;
        mKeyguardMediator = new KeyguardViewMediator(context, this, powerManager);
        mHandler = new Handler();
        mOrientationListener = new MyOrientationListener(mContext);
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();
        mShortcutManager = new ShortcutManager(context, mHandler);
        mShortcutManager.observe();
        mHomeIntent =  new Intent(Intent.ACTION_MAIN, null);
        mHomeIntent.addCategory(Intent.CATEGORY_HOME);
        mHomeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        mCarDockIntent =  new Intent(Intent.ACTION_MAIN, null);
        mCarDockIntent.addCategory(Intent.CATEGORY_CAR_DOCK);
        mCarDockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        mDeskDockIntent =  new Intent(Intent.ACTION_MAIN, null);
        mDeskDockIntent.addCategory(Intent.CATEGORY_DESK_DOCK);
        mDeskDockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mBroadcastWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "PhoneWindowManager.mBroadcastWakeLock");
        mEnableShiftMenuBugReports = "1".equals(SystemProperties.get("ro.debuggable"));
        mLidOpenRotation = readRotation(
                com.android.internal.R.integer.config_lidOpenRotation);
        mCarDockRotation = readRotation(
                com.android.internal.R.integer.config_carDockRotation);
        mDeskDockRotation = readRotation(
                com.android.internal.R.integer.config_deskDockRotation);
        mCarDockEnablesAccelerometer = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_carDockEnablesAccelerometer);
        mDeskDockEnablesAccelerometer = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_deskDockEnablesAccelerometer);
        mLidKeyboardAccessibility = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lidKeyboardAccessibility);
        mLidNavigationAccessibility = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lidNavigationAccessibility);
        // register for dock events
        IntentFilter filter = new IntentFilter();
        filter.addAction(UiModeManager.ACTION_ENTER_CAR_MODE);
        filter.addAction(UiModeManager.ACTION_EXIT_CAR_MODE);
        filter.addAction(UiModeManager.ACTION_ENTER_DESK_MODE);
        filter.addAction(UiModeManager.ACTION_EXIT_DESK_MODE);
        filter.addAction(Intent.ACTION_DOCK_EVENT);
        Intent intent = context.registerReceiver(mDockReceiver, filter);
        if (intent != null) {
            // Retrieve current sticky dock event broadcast.
            mDockMode = intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                    Intent.EXTRA_DOCK_STATE_UNDOCKED);
        }
        mVibrator = new Vibrator();
        mLongPressVibePattern = getLongIntArray(mContext.getResources(),
                com.android.internal.R.array.config_longPressVibePattern);
        mVirtualKeyVibePattern = getLongIntArray(mContext.getResources(),
                com.android.internal.R.array.config_virtualKeyVibePattern);
        mKeyboardTapVibePattern = getLongIntArray(mContext.getResources(),
                com.android.internal.R.array.config_keyboardTapVibePattern);
        mSafeModeDisabledVibePattern = getLongIntArray(mContext.getResources(),
                com.android.internal.R.array.config_safeModeDisabledVibePattern);
        mSafeModeEnabledVibePattern = getLongIntArray(mContext.getResources(),
                com.android.internal.R.array.config_safeModeEnabledVibePattern);
    }

    public void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();
        boolean updateRotation = false;
        View addView = null;
        View removeView = null;
        synchronized (mLock) {
            mEndcallBehavior = Settings.System.getInt(resolver,
                    Settings.System.END_BUTTON_BEHAVIOR,
                    Settings.System.END_BUTTON_BEHAVIOR_DEFAULT);
            mIncallPowerBehavior = Settings.Secure.getInt(resolver,
                    Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR,
                    Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR_DEFAULT);
            mFancyRotationAnimation = Settings.System.getInt(resolver,
                    "fancy_rotation_anim", 0) != 0 ? 0x80 : 0;
            int accelerometerDefault = Settings.System.getInt(resolver,
                    Settings.System.ACCELEROMETER_ROTATION, DEFAULT_ACCELEROMETER_ROTATION);
            if (mAccelerometerDefault != accelerometerDefault) {
                mAccelerometerDefault = accelerometerDefault;
                updateOrientationListenerLp();
            }
            if (mSystemReady) {
                int pointerLocation = Settings.System.getInt(resolver,
                        Settings.System.POINTER_LOCATION, 0);
                if (mPointerLocationMode != pointerLocation) {
                    mPointerLocationMode = pointerLocation;
                    if (pointerLocation != 0) {
                        if (mPointerLocationView == null) {
                            mPointerLocationView = new PointerLocationView(mContext);
                            mPointerLocationView.setPrintCoords(false);
                            addView = mPointerLocationView;
                        }
                    } else {
                        removeView = mPointerLocationView;
                        mPointerLocationView = null;
                    }
                }
            }
            // use screen off timeout setting as the timeout for the lockscreen
            mLockScreenTimeout = Settings.System.getInt(resolver,
                    Settings.System.SCREEN_OFF_TIMEOUT, 0);
            String imId = Settings.Secure.getString(resolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD);
            boolean hasSoftInput = imId != null && imId.length() > 0;
            if (mHasSoftInput != hasSoftInput) {
                mHasSoftInput = hasSoftInput;
                updateRotation = true;
            }
        }
        if (updateRotation) {
            updateRotation(0);
        }
        if (addView != null) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            lp.type = WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
            lp.flags = 
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            lp.format = PixelFormat.TRANSLUCENT;
            lp.setTitle("PointerLocation");
            WindowManagerImpl wm = (WindowManagerImpl)
                    mContext.getSystemService(Context.WINDOW_SERVICE);
            wm.addView(addView, lp);
        }
        if (removeView != null) {
            WindowManagerImpl wm = (WindowManagerImpl)
                    mContext.getSystemService(Context.WINDOW_SERVICE);
            wm.removeView(removeView);
        }
    }
    
    private int readRotation(int resID) {
        try {
            int rotation = mContext.getResources().getInteger(resID);
            switch (rotation) {
                case 0:
                    return Surface.ROTATION_0;
                case 90:
                    return Surface.ROTATION_90;
                case 180:
                    return Surface.ROTATION_180;
                case 270:
                    return Surface.ROTATION_270;
            }
        } catch (Resources.NotFoundException e) {
            // fall through
        }
        return -1;
    }

    /** {@inheritDoc} */
    public int checkAddPermission(WindowManager.LayoutParams attrs) {
        int type = attrs.type;
        
        if (type < WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW
                || type > WindowManager.LayoutParams.LAST_SYSTEM_WINDOW) {
            return WindowManagerImpl.ADD_OKAY;
        }
        String permission = null;
        switch (type) {
            case TYPE_TOAST:
                // XXX right now the app process has complete control over
                // this...  should introduce a token to let the system
                // monitor/control what they are doing.
                break;
            case TYPE_INPUT_METHOD:
            case TYPE_WALLPAPER:
                // The window manager will check these.
                break;
            case TYPE_PHONE:
            case TYPE_PRIORITY_PHONE:
            case TYPE_SYSTEM_ALERT:
            case TYPE_SYSTEM_ERROR:
            case TYPE_SYSTEM_OVERLAY:
                permission = android.Manifest.permission.SYSTEM_ALERT_WINDOW;
                break;
            default:
                permission = android.Manifest.permission.INTERNAL_SYSTEM_WINDOW;
        }
        if (permission != null) {
            if (mContext.checkCallingOrSelfPermission(permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return WindowManagerImpl.ADD_PERMISSION_DENIED;
            }
        }
        return WindowManagerImpl.ADD_OKAY;
    }
    
    public void adjustWindowParamsLw(WindowManager.LayoutParams attrs) {
        switch (attrs.type) {
            case TYPE_SYSTEM_OVERLAY:
            case TYPE_TOAST:
                // These types of windows can't receive input events.
                attrs.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                break;
        }
    }
    
    void readLidState() {
        try {
            int sw = mWindowManager.getSwitchState(RawInputEvent.SW_LID);
            if (sw >= 0) {
                mLidOpen = sw == 0;
            }
        } catch (RemoteException e) {
            // Ignore
        }
    }
    
    private int determineHiddenState(boolean lidOpen,
            int mode, int hiddenValue, int visibleValue) {
        switch (mode) {
            case 1:
                return lidOpen ? visibleValue : hiddenValue;
            case 2:
                return lidOpen ? hiddenValue : visibleValue;
        }
        return visibleValue;
    }
    
    /** {@inheritDoc} */
    public void adjustConfigurationLw(Configuration config) {
        readLidState();
        final boolean lidOpen = !KEYBOARD_ALWAYS_HIDDEN && mLidOpen;
        mPowerManager.setKeyboardVisibility(lidOpen);
        config.hardKeyboardHidden = determineHiddenState(lidOpen,
                mLidKeyboardAccessibility, Configuration.HARDKEYBOARDHIDDEN_YES,
                Configuration.HARDKEYBOARDHIDDEN_NO);
        config.navigationHidden = determineHiddenState(lidOpen,
                mLidNavigationAccessibility, Configuration.NAVIGATIONHIDDEN_YES,
                Configuration.NAVIGATIONHIDDEN_NO);
        config.keyboardHidden = (config.hardKeyboardHidden
                        == Configuration.HARDKEYBOARDHIDDEN_NO || mHasSoftInput)
                ? Configuration.KEYBOARDHIDDEN_NO
                : Configuration.KEYBOARDHIDDEN_YES;
    }
    
    public boolean isCheekPressedAgainstScreen(MotionEvent ev) {
        if(ev.getSize() > SLIDE_TOUCH_EVENT_SIZE_LIMIT) {
            return true;
        }
        int size = ev.getHistorySize();
        for(int i = 0; i < size; i++) {
            if(ev.getHistoricalSize(i) > SLIDE_TOUCH_EVENT_SIZE_LIMIT) {
                return true;
            }
        }
        return false;
    }
    
    public void dispatchedPointerEventLw(MotionEvent ev, int targetX, int targetY) {
        if (mPointerLocationView == null) {
            return;
        }
        synchronized (mLock) {
            if (mPointerLocationView == null) {
                return;
            }
            ev.offsetLocation(targetX, targetY);
            mPointerLocationView.addTouchEvent(ev);
            ev.offsetLocation(-targetX, -targetY);
        }
    }
    
    /** {@inheritDoc} */
    public int windowTypeToLayerLw(int type) {
        if (type >= FIRST_APPLICATION_WINDOW && type <= LAST_APPLICATION_WINDOW) {
            return APPLICATION_LAYER;
        }
        switch (type) {
        case TYPE_STATUS_BAR:
            return STATUS_BAR_LAYER;
        case TYPE_STATUS_BAR_PANEL:
            return STATUS_BAR_PANEL_LAYER;
        case TYPE_SYSTEM_DIALOG:
            return SYSTEM_DIALOG_LAYER;
        case TYPE_SEARCH_BAR:
            return SEARCH_BAR_LAYER;
        case TYPE_PHONE:
            return PHONE_LAYER;
        case TYPE_KEYGUARD:
            return KEYGUARD_LAYER;
        case TYPE_KEYGUARD_DIALOG:
            return KEYGUARD_DIALOG_LAYER;
        case TYPE_SYSTEM_ALERT:
            return SYSTEM_ALERT_LAYER;
        case TYPE_SYSTEM_ERROR:
            return SYSTEM_ERROR_LAYER;
        case TYPE_INPUT_METHOD:
            return INPUT_METHOD_LAYER;
        case TYPE_INPUT_METHOD_DIALOG:
            return INPUT_METHOD_DIALOG_LAYER;
        case TYPE_SYSTEM_OVERLAY:
            return SYSTEM_OVERLAY_LAYER;
        case TYPE_PRIORITY_PHONE:
            return PRIORITY_PHONE_LAYER;
        case TYPE_TOAST:
            return TOAST_LAYER;
        case TYPE_WALLPAPER:
            return WALLPAPER_LAYER;
        }
        Log.e(TAG, "Unknown window type: " + type);
        return APPLICATION_LAYER;
    }

    /** {@inheritDoc} */
    public int subWindowTypeToLayerLw(int type) {
        switch (type) {
        case TYPE_APPLICATION_PANEL:
        case TYPE_APPLICATION_ATTACHED_DIALOG:
            return APPLICATION_PANEL_SUBLAYER;
        case TYPE_APPLICATION_MEDIA:
            return APPLICATION_MEDIA_SUBLAYER;
        case TYPE_APPLICATION_MEDIA_OVERLAY:
            return APPLICATION_MEDIA_OVERLAY_SUBLAYER;
        case TYPE_APPLICATION_SUB_PANEL:
            return APPLICATION_SUB_PANEL_SUBLAYER;
        }
        Log.e(TAG, "Unknown sub-window type: " + type);
        return 0;
    }

    public int getMaxWallpaperLayer() {
        return STATUS_BAR_LAYER;
    }

    public boolean doesForceHide(WindowState win, WindowManager.LayoutParams attrs) {
        return attrs.type == WindowManager.LayoutParams.TYPE_KEYGUARD;
    }
    
    public boolean canBeForceHidden(WindowState win, WindowManager.LayoutParams attrs) {
        return attrs.type != WindowManager.LayoutParams.TYPE_STATUS_BAR
                && attrs.type != WindowManager.LayoutParams.TYPE_WALLPAPER;
    }
    
    /** {@inheritDoc} */
    public View addStartingWindow(IBinder appToken, String packageName,
                                  int theme, CharSequence nonLocalizedLabel,
                                  int labelRes, int icon) {
        if (!SHOW_STARTING_ANIMATIONS) {
            return null;
        }
        if (packageName == null) {
            return null;
        }
        
        try {
            Context context = mContext;
            boolean setTheme = false;
            //Log.i(TAG, "addStartingWindow " + packageName + ": nonLocalizedLabel="
            //        + nonLocalizedLabel + " theme=" + Integer.toHexString(theme));
            if (theme != 0 || labelRes != 0) {
                try {
                    context = context.createPackageContext(packageName, 0);
                    if (theme != 0) {
                        context.setTheme(theme);
                        setTheme = true;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    // Ignore
                }
            }
            if (!setTheme) {
                context.setTheme(com.android.internal.R.style.Theme);
            }
            
            Window win = PolicyManager.makeNewWindow(context);
            if (win.getWindowStyle().getBoolean(
                    com.android.internal.R.styleable.Window_windowDisablePreview, false)) {
                return null;
            }
            
            Resources r = context.getResources();
            win.setTitle(r.getText(labelRes, nonLocalizedLabel));
    
            win.setType(
                WindowManager.LayoutParams.TYPE_APPLICATION_STARTING);
            // Force the window flags: this is a fake window, so it is not really
            // touchable or focusable by the user.  We also add in the ALT_FOCUSABLE_IM
            // flag because we do know that the next window will take input
            // focus, so we want to get the IME window up on top of us right away.
            win.setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
    
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                                WindowManager.LayoutParams.MATCH_PARENT);
    
            final WindowManager.LayoutParams params = win.getAttributes();
            params.token = appToken;
            params.packageName = packageName;
            params.windowAnimations = win.getWindowStyle().getResourceId(
                    com.android.internal.R.styleable.Window_windowAnimationStyle, 0);
            params.setTitle("Starting " + packageName);

            WindowManagerImpl wm = (WindowManagerImpl)
                    context.getSystemService(Context.WINDOW_SERVICE);
            View view = win.getDecorView();

            if (win.isFloating()) {
                // Whoops, there is no way to display an animation/preview
                // of such a thing!  After all that work...  let's skip it.
                // (Note that we must do this here because it is in
                // getDecorView() where the theme is evaluated...  maybe
                // we should peek the floating attribute from the theme
                // earlier.)
                return null;
            }
            
            if (localLOGV) Log.v(
                TAG, "Adding starting window for " + packageName
                + " / " + appToken + ": "
                + (view.getParent() != null ? view : null));

            wm.addView(view, params);

            // Only return the view if it was successfully added to the
            // window manager... which we can tell by it having a parent.
            return view.getParent() != null ? view : null;
        } catch (WindowManagerImpl.BadTokenException e) {
            // ignore
            Log.w(TAG, appToken + " already running, starting window not displayed");
        } catch (RuntimeException e) {
            // don't crash if something else bad happens, for example a
            // failure loading resources because we are loading from an app
            // on external storage that has been unmounted.
            Log.w(TAG, appToken + " failed creating starting window", e);
        }

        return null;
    }

    /** {@inheritDoc} */
    public void removeStartingWindow(IBinder appToken, View window) {
        // RuntimeException e = new RuntimeException();
        // Log.i(TAG, "remove " + appToken + " " + window, e);

        if (localLOGV) Log.v(
            TAG, "Removing starting window for " + appToken + ": " + window);

        if (window != null) {
            WindowManagerImpl wm = (WindowManagerImpl) mContext.getSystemService(Context.WINDOW_SERVICE);
            wm.removeView(window);
        }
    }

    /**
     * Preflight adding a window to the system.
     * 
     * Currently enforces that three window types are singletons:
     * <ul>
     * <li>STATUS_BAR_TYPE</li>
     * <li>KEYGUARD_TYPE</li>
     * </ul>
     * 
     * @param win The window to be added
     * @param attrs Information about the window to be added
     * 
     * @return If ok, WindowManagerImpl.ADD_OKAY.  If too many singletons, WindowManagerImpl.ADD_MULTIPLE_SINGLETON
     */
    public int prepareAddWindowLw(WindowState win, WindowManager.LayoutParams attrs) {
        switch (attrs.type) {
            case TYPE_STATUS_BAR:
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.STATUS_BAR_SERVICE,
                        "PhoneWindowManager");
                // TODO: Need to handle the race condition of the status bar proc
                // dying and coming back before the removeWindowLw cleanup has happened.
                if (mStatusBar != null) {
                    return WindowManagerImpl.ADD_MULTIPLE_SINGLETON;
                }
                mStatusBar = win;
                break;
            case TYPE_STATUS_BAR_PANEL:
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.STATUS_BAR_SERVICE,
                        "PhoneWindowManager");
                mStatusBarPanels.add(win);
                break;
            case TYPE_KEYGUARD:
                if (mKeyguard != null) {
                    return WindowManagerImpl.ADD_MULTIPLE_SINGLETON;
                }
                mKeyguard = win;
                break;
        }
        return WindowManagerImpl.ADD_OKAY;
    }

    /** {@inheritDoc} */
    public void removeWindowLw(WindowState win) {
        if (mStatusBar == win) {
            mStatusBar = null;
        }
        else if (mKeyguard == win) {
            mKeyguard = null;
        } else {
            mStatusBarPanels.remove(win);
        }
    }

    static final boolean PRINT_ANIM = false;
    
    /** {@inheritDoc} */
    public int selectAnimationLw(WindowState win, int transit) {
        if (PRINT_ANIM) Log.i(TAG, "selectAnimation in " + win
              + ": transit=" + transit);
        if (transit == TRANSIT_PREVIEW_DONE) {
            if (win.hasAppShownWindows()) {
                if (PRINT_ANIM) Log.i(TAG, "**** STARTING EXIT");
                return com.android.internal.R.anim.app_starting_exit;
            }
        }

        return 0;
    }

    public Animation createForceHideEnterAnimation() {
        return AnimationUtils.loadAnimation(mContext,
                com.android.internal.R.anim.lock_screen_behind_enter);
    }
    
    static ITelephony getPhoneInterface() {
        return ITelephony.Stub.asInterface(ServiceManager.checkService(Context.TELEPHONY_SERVICE));
    }

    static IAudioService getAudioInterface() {
        return IAudioService.Stub.asInterface(ServiceManager.checkService(Context.AUDIO_SERVICE));
    }

    boolean keyguardOn() {
        return keyguardIsShowingTq() || inKeyguardRestrictedKeyInputMode();
    }

    private static final int[] WINDOW_TYPES_WHERE_HOME_DOESNT_WORK = {
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
        };

    /** {@inheritDoc} */
    public boolean interceptKeyTi(WindowState win, int code, int metaKeys, boolean down, 
            int repeatCount, int flags) {
        boolean keyguardOn = keyguardOn();

        if (false) {
            Log.d(TAG, "interceptKeyTi code=" + code + " down=" + down + " repeatCount="
                    + repeatCount + " keyguardOn=" + keyguardOn + " mHomePressed=" + mHomePressed);
        }

        // Clear a pending HOME longpress if the user releases Home
        // TODO: This could probably be inside the next bit of logic, but that code
        // turned out to be a bit fragile so I'm doing it here explicitly, for now.
        if ((code == KeyEvent.KEYCODE_HOME) && !down) {
            mHandler.removeCallbacks(mHomeLongPress);
        }

        // If the HOME button is currently being held, then we do special
        // chording with it.
        if (mHomePressed) {
            
            // If we have released the home key, and didn't do anything else
            // while it was pressed, then it is time to go home!
            if (code == KeyEvent.KEYCODE_HOME) {
                if (!down) {
                    mHomePressed = false;
                    
                    if ((flags&KeyEvent.FLAG_CANCELED) == 0) {
                        // If an incoming call is ringing, HOME is totally disabled.
                        // (The user is already on the InCallScreen at this point,
                        // and his ONLY options are to answer or reject the call.)
                        boolean incomingRinging = false;
                        try {
                            ITelephony phoneServ = getPhoneInterface();
                            if (phoneServ != null) {
                                incomingRinging = phoneServ.isRinging();
                            } else {
                                Log.w(TAG, "Unable to find ITelephony interface");
                            }
                        } catch (RemoteException ex) {
                            Log.w(TAG, "RemoteException from getPhoneInterface()", ex);
                        }
        
                        if (incomingRinging) {
                            Log.i(TAG, "Ignoring HOME; there's a ringing incoming call.");
                        } else {
                            launchHomeFromHotKey();
                        }
                    } else {
                        Log.i(TAG, "Ignoring HOME; event canceled.");
                    }
                }
            }
            
            return true;
        }
        
        // First we always handle the home key here, so applications
        // can never break it, although if keyguard is on, we do let
        // it handle it, because that gives us the correct 5 second
        // timeout.
        if (code == KeyEvent.KEYCODE_HOME) {

            // If a system window has focus, then it doesn't make sense
            // right now to interact with applications.
            WindowManager.LayoutParams attrs = win != null ? win.getAttrs() : null;
            if (attrs != null) {
                final int type = attrs.type;
                if (type == WindowManager.LayoutParams.TYPE_KEYGUARD
                        || type == WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG) {
                    // the "app" is keyguard, so give it the key
                    return false;
                }
                final int typeCount = WINDOW_TYPES_WHERE_HOME_DOESNT_WORK.length;
                for (int i=0; i<typeCount; i++) {
                    if (type == WINDOW_TYPES_WHERE_HOME_DOESNT_WORK[i]) {
                        // don't do anything, but also don't pass it to the app
                        return true;
                    }
                }
            }
            
            if (down && repeatCount == 0) {
                if (!keyguardOn) {
                    mHandler.postDelayed(mHomeLongPress, ViewConfiguration.getGlobalActionKeyTimeout());
                }
                mHomePressed = true;
            }
            return true;
        } else if (code == KeyEvent.KEYCODE_MENU) {
            // Hijack modified menu keys for debugging features
            final int chordBug = KeyEvent.META_SHIFT_ON;

            if (down && repeatCount == 0) {
                if (mEnableShiftMenuBugReports && (metaKeys & chordBug) == chordBug) {
                    Intent intent = new Intent(Intent.ACTION_BUG_REPORT);
                    mContext.sendOrderedBroadcast(intent, null);
                    return true;
                } else if (SHOW_PROCESSES_ON_ALT_MENU &&
                        (metaKeys & KeyEvent.META_ALT_ON) == KeyEvent.META_ALT_ON) {
                    Intent service = new Intent();
                    service.setClassName(mContext, "com.android.server.LoadAverageService");
                    ContentResolver res = mContext.getContentResolver();
                    boolean shown = Settings.System.getInt(
                            res, Settings.System.SHOW_PROCESSES, 0) != 0;
                    if (!shown) {
                        mContext.startService(service);
                    } else {
                        mContext.stopService(service);
                    }
                    Settings.System.putInt(
                            res, Settings.System.SHOW_PROCESSES, shown ? 0 : 1);
                    return true;
                }
            }
        } else if (code == KeyEvent.KEYCODE_SEARCH) {
            if (down) {
                if (repeatCount == 0) {
                    mSearchKeyPressed = true;
                }
            } else {
                mSearchKeyPressed = false;
                
                if (mConsumeSearchKeyUp) {
                    // Consume the up-event
                    mConsumeSearchKeyUp = false;
                    return true;
                }
            }
        }
        
        // Shortcuts are invoked through Search+key, so intercept those here
        if (mSearchKeyPressed) {
            if (down && repeatCount == 0 && !keyguardOn) {
                Intent shortcutIntent = mShortcutManager.getIntent(code, metaKeys);
                if (shortcutIntent != null) {
                    shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(shortcutIntent);
                    
                    /*
                     * We launched an app, so the up-event of the search key
                     * should be consumed
                     */
                    mConsumeSearchKeyUp = true;
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * A home key -> launch home action was detected.  Take the appropriate action
     * given the situation with the keyguard.
     */
    void launchHomeFromHotKey() {
        if (mKeyguardMediator.isShowingAndNotHidden()) {
            // don't launch home if keyguard showing
        } else if (!mHideLockScreen && mKeyguardMediator.isInputRestricted()) {
            // when in keyguard restricted mode, must first verify unlock
            // before launching home
            mKeyguardMediator.verifyUnlock(new OnKeyguardExitResult() {
                public void onKeyguardExitResult(boolean success) {
                    if (success) {
                        try {
                            ActivityManagerNative.getDefault().stopAppSwitches();
                        } catch (RemoteException e) {
                        }
                        sendCloseSystemWindows(SYSTEM_DIALOG_REASON_HOME_KEY);
                        startDockOrHome();
                    }
                }
            });
        } else {
            // no keyguard stuff to worry about, just launch home!
            try {
                ActivityManagerNative.getDefault().stopAppSwitches();
            } catch (RemoteException e) {
            }
            sendCloseSystemWindows(SYSTEM_DIALOG_REASON_HOME_KEY);
            startDockOrHome();
        }
    }

    public void getContentInsetHintLw(WindowManager.LayoutParams attrs, Rect contentInset) {
        final int fl = attrs.flags;
        
        if ((fl &
                (FLAG_LAYOUT_IN_SCREEN | FLAG_FULLSCREEN | FLAG_LAYOUT_INSET_DECOR))
                == (FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR)) {
            contentInset.set(mCurLeft, mCurTop, mW - mCurRight, mH - mCurBottom);
        } else {
            contentInset.setEmpty();
        }
    }
    
    /** {@inheritDoc} */
    public void beginLayoutLw(int displayWidth, int displayHeight) {
        mW = displayWidth;
        mH = displayHeight;
        mDockLeft = mContentLeft = mCurLeft = 0;
        mDockTop = mContentTop = mCurTop = 0;
        mDockRight = mContentRight = mCurRight = displayWidth;
        mDockBottom = mContentBottom = mCurBottom = displayHeight;
        mDockLayer = 0x10000000;

        // decide where the status bar goes ahead of time
        if (mStatusBar != null) {
            final Rect pf = mTmpParentFrame;
            final Rect df = mTmpDisplayFrame;
            final Rect vf = mTmpVisibleFrame;
            pf.left = df.left = vf.left = 0;
            pf.top = df.top = vf.top = 0;
            pf.right = df.right = vf.right = displayWidth;
            pf.bottom = df.bottom = vf.bottom = displayHeight;
            
            mStatusBar.computeFrameLw(pf, df, vf, vf);
            if (mStatusBar.isVisibleLw()) {
                // If the status bar is hidden, we don't want to cause
                // windows behind it to scroll.
                final Rect r = mStatusBar.getFrameLw();
                if (mDockTop == r.top) mDockTop = r.bottom;
                else if (mDockBottom == r.bottom) mDockBottom = r.top;
                mContentTop = mCurTop = mDockTop;
                mContentBottom = mCurBottom = mDockBottom;
                if (DEBUG_LAYOUT) Log.v(TAG, "Status bar: mDockTop=" + mDockTop
                        + " mContentTop=" + mContentTop
                        + " mCurTop=" + mCurTop
                        + " mDockBottom=" + mDockBottom
                        + " mContentBottom=" + mContentBottom
                        + " mCurBottom=" + mCurBottom);
            }
        }
    }

    void setAttachedWindowFrames(WindowState win, int fl, int sim,
            WindowState attached, boolean insetDecors, Rect pf, Rect df, Rect cf, Rect vf) {
        if (win.getSurfaceLayer() > mDockLayer && attached.getSurfaceLayer() < mDockLayer) {
            // Here's a special case: if this attached window is a panel that is
            // above the dock window, and the window it is attached to is below
            // the dock window, then the frames we computed for the window it is
            // attached to can not be used because the dock is effectively part
            // of the underlying window and the attached window is floating on top
            // of the whole thing.  So, we ignore the attached window and explicitly
            // compute the frames that would be appropriate without the dock.
            df.left = cf.left = vf.left = mDockLeft;
            df.top = cf.top = vf.top = mDockTop;
            df.right = cf.right = vf.right = mDockRight;
            df.bottom = cf.bottom = vf.bottom = mDockBottom;
        } else {
            // The effective display frame of the attached window depends on
            // whether it is taking care of insetting its content.  If not,
            // we need to use the parent's content frame so that the entire
            // window is positioned within that content.  Otherwise we can use
            // the display frame and let the attached window take care of
            // positioning its content appropriately.
            if ((sim & SOFT_INPUT_MASK_ADJUST) != SOFT_INPUT_ADJUST_RESIZE) {
                cf.set(attached.getDisplayFrameLw());
            } else {
                // If the window is resizing, then we want to base the content
                // frame on our attached content frame to resize...  however,
                // things can be tricky if the attached window is NOT in resize
                // mode, in which case its content frame will be larger.
                // Ungh.  So to deal with that, make sure the content frame
                // we end up using is not covering the IM dock.
                cf.set(attached.getContentFrameLw());
                if (attached.getSurfaceLayer() < mDockLayer) {
                    if (cf.left < mContentLeft) cf.left = mContentLeft;
                    if (cf.top < mContentTop) cf.top = mContentTop;
                    if (cf.right > mContentRight) cf.right = mContentRight;
                    if (cf.bottom > mContentBottom) cf.bottom = mContentBottom;
                }
            }
            df.set(insetDecors ? attached.getDisplayFrameLw() : cf);
            vf.set(attached.getVisibleFrameLw());
        }
        // The LAYOUT_IN_SCREEN flag is used to determine whether the attached
        // window should be positioned relative to its parent or the entire
        // screen.
        pf.set((fl & FLAG_LAYOUT_IN_SCREEN) == 0
                ? attached.getFrameLw() : df);
    }
    
    /** {@inheritDoc} */
    public void layoutWindowLw(WindowState win, WindowManager.LayoutParams attrs,
            WindowState attached) {
        // we've already done the status bar
        if (win == mStatusBar) {
            return;
        }

        if (false) {
            if ("com.google.android.youtube".equals(attrs.packageName)
                    && attrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_PANEL) {
                Log.i(TAG, "GOTCHA!");
            }
        }
        
        final int fl = attrs.flags;
        final int sim = attrs.softInputMode;
        
        final Rect pf = mTmpParentFrame;
        final Rect df = mTmpDisplayFrame;
        final Rect cf = mTmpContentFrame;
        final Rect vf = mTmpVisibleFrame;
        
        if (attrs.type == TYPE_INPUT_METHOD) {
            pf.left = df.left = cf.left = vf.left = mDockLeft;
            pf.top = df.top = cf.top = vf.top = mDockTop;
            pf.right = df.right = cf.right = vf.right = mDockRight;
            pf.bottom = df.bottom = cf.bottom = vf.bottom = mDockBottom;
            // IM dock windows always go to the bottom of the screen.
            attrs.gravity = Gravity.BOTTOM;
            mDockLayer = win.getSurfaceLayer();
        } else {
            if ((fl &
                    (FLAG_LAYOUT_IN_SCREEN | FLAG_FULLSCREEN | FLAG_LAYOUT_INSET_DECOR))
                    == (FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR)) {
                // This is the case for a normal activity window: we want it
                // to cover all of the screen space, and it can take care of
                // moving its contents to account for screen decorations that
                // intrude into that space.
                if (attached != null) {
                    // If this window is attached to another, our display
                    // frame is the same as the one we are attached to.
                    setAttachedWindowFrames(win, fl, sim, attached, true, pf, df, cf, vf);
                } else {
                    pf.left = df.left = 0;
                    pf.top = df.top = 0;
                    pf.right = df.right = mW;
                    pf.bottom = df.bottom = mH;
                    if ((sim & SOFT_INPUT_MASK_ADJUST) != SOFT_INPUT_ADJUST_RESIZE) {
                        cf.left = mDockLeft;
                        cf.top = mDockTop;
                        cf.right = mDockRight;
                        cf.bottom = mDockBottom;
                    } else {
                        cf.left = mContentLeft;
                        cf.top = mContentTop;
                        cf.right = mContentRight;
                        cf.bottom = mContentBottom;
                    }
                    vf.left = mCurLeft;
                    vf.top = mCurTop;
                    vf.right = mCurRight;
                    vf.bottom = mCurBottom;
                }
            } else if ((fl & FLAG_LAYOUT_IN_SCREEN) != 0) {
                // A window that has requested to fill the entire screen just
                // gets everything, period.
                pf.left = df.left = cf.left = 0;
                pf.top = df.top = cf.top = 0;
                pf.right = df.right = cf.right = mW;
                pf.bottom = df.bottom = cf.bottom = mH;
                vf.left = mCurLeft;
                vf.top = mCurTop;
                vf.right = mCurRight;
                vf.bottom = mCurBottom;
            } else if (attached != null) {
                // A child window should be placed inside of the same visible
                // frame that its parent had.
                setAttachedWindowFrames(win, fl, sim, attached, false, pf, df, cf, vf);
            } else {
                // Otherwise, a normal window must be placed inside the content
                // of all screen decorations.
                pf.left = mContentLeft;
                pf.top = mContentTop;
                pf.right = mContentRight;
                pf.bottom = mContentBottom;
                if ((sim & SOFT_INPUT_MASK_ADJUST) != SOFT_INPUT_ADJUST_RESIZE) {
                    df.left = cf.left = mDockLeft;
                    df.top = cf.top = mDockTop;
                    df.right = cf.right = mDockRight;
                    df.bottom = cf.bottom = mDockBottom;
                } else {
                    df.left = cf.left = mContentLeft;
                    df.top = cf.top = mContentTop;
                    df.right = cf.right = mContentRight;
                    df.bottom = cf.bottom = mContentBottom;
                }
                vf.left = mCurLeft;
                vf.top = mCurTop;
                vf.right = mCurRight;
                vf.bottom = mCurBottom;
            }
        }
        
        if ((fl & FLAG_LAYOUT_NO_LIMITS) != 0) {
            df.left = df.top = cf.left = cf.top = vf.left = vf.top = -10000;
            df.right = df.bottom = cf.right = cf.bottom = vf.right = vf.bottom = 10000;
        }

        if (DEBUG_LAYOUT) Log.v(TAG, "Compute frame " + attrs.getTitle()
                + ": sim=#" + Integer.toHexString(sim)
                + " pf=" + pf.toShortString() + " df=" + df.toShortString()
                + " cf=" + cf.toShortString() + " vf=" + vf.toShortString());
        
        if (false) {
            if ("com.google.android.youtube".equals(attrs.packageName)
                    && attrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_PANEL) {
                if (true || localLOGV) Log.v(TAG, "Computing frame of " + win +
                        ": sim=#" + Integer.toHexString(sim)
                        + " pf=" + pf.toShortString() + " df=" + df.toShortString()
                        + " cf=" + cf.toShortString() + " vf=" + vf.toShortString());
            }
        }
        
        win.computeFrameLw(pf, df, cf, vf);
        
        // Dock windows carve out the bottom of the screen, so normal windows
        // can't appear underneath them.
        if (attrs.type == TYPE_INPUT_METHOD && !win.getGivenInsetsPendingLw()) {
            int top = win.getContentFrameLw().top;
            top += win.getGivenContentInsetsLw().top;
            if (mContentBottom > top) {
                mContentBottom = top;
            }
            top = win.getVisibleFrameLw().top;
            top += win.getGivenVisibleInsetsLw().top;
            if (mCurBottom > top) {
                mCurBottom = top;
            }
            if (DEBUG_LAYOUT) Log.v(TAG, "Input method: mDockBottom="
                    + mDockBottom + " mContentBottom="
                    + mContentBottom + " mCurBottom=" + mCurBottom);
        }
    }

    /** {@inheritDoc} */
    public int finishLayoutLw() {
        return 0;
    }

    /** {@inheritDoc} */
    public void beginAnimationLw(int displayWidth, int displayHeight) {
        mTopFullscreenOpaqueWindowState = null;
        mForceStatusBar = false;
        
        mHideLockScreen = false;
        mAllowLockscreenWhenOn = false;
        mDismissKeyguard = false;
    }

    /** {@inheritDoc} */
    public void animatingWindowLw(WindowState win,
                                WindowManager.LayoutParams attrs) {
        if (mTopFullscreenOpaqueWindowState == null &&
                win.isVisibleOrBehindKeyguardLw()) {
            if ((attrs.flags & FLAG_FORCE_NOT_FULLSCREEN) != 0) {
                mForceStatusBar = true;
            } 
            if (attrs.type >= FIRST_APPLICATION_WINDOW
                    && attrs.type <= LAST_APPLICATION_WINDOW
                    && win.fillsScreenLw(mW, mH, false, false)) {
                if (DEBUG_LAYOUT) Log.v(TAG, "Fullscreen window: " + win);
                mTopFullscreenOpaqueWindowState = win;
                if ((attrs.flags & FLAG_SHOW_WHEN_LOCKED) != 0) {
                    if (localLOGV) Log.v(TAG, "Setting mHideLockScreen to true by win " + win);
                    mHideLockScreen = true;
                }
                if ((attrs.flags & FLAG_DISMISS_KEYGUARD) != 0) {
                    if (localLOGV) Log.v(TAG, "Setting mDismissKeyguard to true by win " + win);
                    mDismissKeyguard = true;
                }
                if ((attrs.flags & FLAG_ALLOW_LOCK_WHILE_SCREEN_ON) != 0) {
                    mAllowLockscreenWhenOn = true;
                }
            }
        }
    }

    /** {@inheritDoc} */
    public int finishAnimationLw() {
        int changes = 0;
        
        boolean hiding = false;
        if (mStatusBar != null) {
            if (localLOGV) Log.i(TAG, "force=" + mForceStatusBar
                    + " top=" + mTopFullscreenOpaqueWindowState);
            if (mForceStatusBar) {
                if (DEBUG_LAYOUT) Log.v(TAG, "Showing status bar");
                if (mStatusBar.showLw(true)) changes |= FINISH_LAYOUT_REDO_LAYOUT;
            } else if (mTopFullscreenOpaqueWindowState != null) {
                //Log.i(TAG, "frame: " + mTopFullscreenOpaqueWindowState.getFrameLw()
                //        + " shown frame: " + mTopFullscreenOpaqueWindowState.getShownFrameLw());
                //Log.i(TAG, "attr: " + mTopFullscreenOpaqueWindowState.getAttrs());
                WindowManager.LayoutParams lp =
                    mTopFullscreenOpaqueWindowState.getAttrs();
                boolean hideStatusBar =
                    (lp.flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0;
                if (hideStatusBar) {
                    if (DEBUG_LAYOUT) Log.v(TAG, "Hiding status bar");
                    if (mStatusBar.hideLw(true)) changes |= FINISH_LAYOUT_REDO_LAYOUT;
                    hiding = true;
                } else {
                    if (DEBUG_LAYOUT) Log.v(TAG, "Showing status bar");
                    if (mStatusBar.showLw(true)) changes |= FINISH_LAYOUT_REDO_LAYOUT;
                }
            }
        }
        
        if (changes != 0 && hiding) {
            IStatusBarService sbs = IStatusBarService.Stub.asInterface(ServiceManager.getService("statusbar"));
            if (sbs != null) {
                try {
                    // Make sure the window shade is hidden.
                    sbs.collapse();
                } catch (RemoteException e) {
                }
            }
        }

        // Hide the key guard if a visible window explicitly specifies that it wants to be displayed
        // when the screen is locked
        if (mKeyguard != null) {
            if (localLOGV) Log.v(TAG, "finishLayoutLw::mHideKeyguard="+mHideLockScreen);
            if (mDismissKeyguard && !mKeyguardMediator.isSecure()) {
                if (mKeyguard.hideLw(true)) {
                    changes |= FINISH_LAYOUT_REDO_LAYOUT
                            | FINISH_LAYOUT_REDO_CONFIG
                            | FINISH_LAYOUT_REDO_WALLPAPER;
                }
                if (mKeyguardMediator.isShowing()) {
                    mHandler.post(new Runnable() {
                        public void run() {
                            mKeyguardMediator.keyguardDone(false, false);
                        }
                    });
                }
            } else if (mHideLockScreen) {
                if (mKeyguard.hideLw(true)) {
                    changes |= FINISH_LAYOUT_REDO_LAYOUT
                            | FINISH_LAYOUT_REDO_CONFIG
                            | FINISH_LAYOUT_REDO_WALLPAPER;
                }
                mKeyguardMediator.setHidden(true);
            } else {
                if (mKeyguard.showLw(true)) {
                    changes |= FINISH_LAYOUT_REDO_LAYOUT
                            | FINISH_LAYOUT_REDO_CONFIG
                            | FINISH_LAYOUT_REDO_WALLPAPER;
                }
                mKeyguardMediator.setHidden(false);
            }
        }
        
        // update since mAllowLockscreenWhenOn might have changed
        updateLockScreenTimeout();
        return changes;
    }

    public boolean allowAppAnimationsLw() {
        if (mKeyguard != null && mKeyguard.isVisibleLw()) {
            // If keyguard is currently visible, no reason to animate
            // behind it.
            return false;
        }
        if (mStatusBar != null && mStatusBar.isVisibleLw()) {
            Rect rect = new Rect(mStatusBar.getShownFrameLw());
            for (int i=mStatusBarPanels.size()-1; i>=0; i--) {
                WindowState w = mStatusBarPanels.get(i);
                if (w.isVisibleLw()) {
                    rect.union(w.getShownFrameLw());
                }
            }
            final int insetw = mW/10;
            final int inseth = mH/10;
            if (rect.contains(insetw, inseth, mW-insetw, mH-inseth)) {
                // All of the status bar windows put together cover the
                // screen, so the app can't be seen.  (Note this test doesn't
                // work if the rects of these windows are at off offsets or
                // sizes, causing gaps in the rect union we have computed.)
                return false;
            }
        }
        return true;
    }

    /** {@inheritDoc} */
    public boolean preprocessInputEventTq(RawInputEvent event) {
        switch (event.type) {
            case RawInputEvent.EV_SW:
                if (event.keycode == RawInputEvent.SW_LID) {
                    // lid changed state
                    mLidOpen = event.value == 0;
                    boolean awakeNow = mKeyguardMediator.doLidChangeTq(mLidOpen);
                    updateRotation(Surface.FLAGS_ORIENTATION_ANIMATION_DISABLE);
                    if (awakeNow) {
                        // If the lid opening and we don't have to keep the
                        // keyguard up, then we can turn on the screen
                        // immediately.
                        mKeyguardMediator.pokeWakelock();
                    } else if (keyguardIsShowingTq()) {
                        if (mLidOpen) {
                            // If we are opening the lid and not hiding the
                            // keyguard, then we need to have it turn on the
                            // screen once it is shown.
                            mKeyguardMediator.onWakeKeyWhenKeyguardShowingTq(
                                    KeyEvent.KEYCODE_POWER);
                        }
                    } else {
                        // Light up the keyboard if we are sliding up.
                        if (mLidOpen) {
                            mPowerManager.userActivity(SystemClock.uptimeMillis(), false,
                                    LocalPowerManager.BUTTON_EVENT);
                        } else {
                            mPowerManager.userActivity(SystemClock.uptimeMillis(), false,
                                    LocalPowerManager.OTHER_EVENT);
                        }
                    }
                }
        }
        return false;
    }
    
    public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen) {
        // lid changed state
        mLidOpen = lidOpen;
        boolean awakeNow = mKeyguardMediator.doLidChangeTq(mLidOpen);
        updateRotation(Surface.FLAGS_ORIENTATION_ANIMATION_DISABLE);
        if (awakeNow) {
            // If the lid opening and we don't have to keep the
            // keyguard up, then we can turn on the screen
            // immediately.
            mKeyguardMediator.pokeWakelock();
        } else if (keyguardIsShowingTq()) {
            if (mLidOpen) {
                // If we are opening the lid and not hiding the
                // keyguard, then we need to have it turn on the
                // screen once it is shown.
                mKeyguardMediator.onWakeKeyWhenKeyguardShowingTq(
                        KeyEvent.KEYCODE_POWER);
            }
        } else {
            // Light up the keyboard if we are sliding up.
            if (mLidOpen) {
                mPowerManager.userActivity(SystemClock.uptimeMillis(), false,
                        LocalPowerManager.BUTTON_EVENT);
            } else {
                mPowerManager.userActivity(SystemClock.uptimeMillis(), false,
                        LocalPowerManager.OTHER_EVENT);
            }
        }
    }

    
    /** {@inheritDoc} */
    public boolean isAppSwitchKeyTqTiLwLi(int keycode) {
        return keycode == KeyEvent.KEYCODE_HOME
                || keycode == KeyEvent.KEYCODE_ENDCALL;
    }
    
    /** {@inheritDoc} */
    public boolean isMovementKeyTi(int keycode) {
        switch (keycode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
        }
        return false;
    }


    /**
     * @return Whether a telephone call is in progress right now.
     */
    boolean isInCall() {
        final ITelephony phone = getPhoneInterface();
        if (phone == null) {
            Log.w(TAG, "couldn't get ITelephony reference");
            return false;
        }
        try {
            return phone.isOffhook();
        } catch (RemoteException e) {
            Log.w(TAG, "ITelephony.isOffhhook threw RemoteException " + e);
            return false;
        }
    }

    /**
     * @return Whether music is being played right now.
     */
    boolean isMusicActive() {
        final AudioManager am = (AudioManager)mContext.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            Log.w(TAG, "isMusicActive: couldn't get AudioManager reference");
            return false;
        }
        return am.isMusicActive();
    }

    /**
     * Tell the audio service to adjust the volume appropriate to the event.
     * @param keycode
     */
    void handleVolumeKey(int stream, int keycode) {
        final IAudioService audio = getAudioInterface();
        if (audio == null) {
            Log.w(TAG, "handleVolumeKey: couldn't get IAudioService reference");
            return;
        }
        try {
            // since audio is playing, we shouldn't have to hold a wake lock
            // during the call, but we do it as a precaution for the rare possibility
            // that the music stops right before we call this
            mBroadcastWakeLock.acquire();
            audio.adjustStreamVolume(stream,
                keycode == KeyEvent.KEYCODE_VOLUME_UP
                            ? AudioManager.ADJUST_RAISE
                            : AudioManager.ADJUST_LOWER,
                    0);
        } catch (RemoteException e) {
            Log.w(TAG, "IAudioService.adjustStreamVolume() threw RemoteException " + e);
        } finally {
            mBroadcastWakeLock.release();
        }
    }
    
    static boolean isMediaKey(int code) {
        if (code == KeyEvent.KEYCODE_HEADSETHOOK || 
                code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                code == KeyEvent.KEYCODE_MEDIA_STOP || 
                code == KeyEvent.KEYCODE_MEDIA_NEXT ||
                code == KeyEvent.KEYCODE_MEDIA_PREVIOUS || 
                code == KeyEvent.KEYCODE_MEDIA_REWIND ||
                code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            return true;
        }
        return false;    
    }
 
    /** {@inheritDoc} */
    public int interceptKeyTq(RawInputEvent event, boolean screenIsOn) {
        int result = ACTION_PASS_TO_USER;
        final boolean isWakeKey = isWakeKeyTq(event);
        // If screen is off then we treat the case where the keyguard is open but hidden
        // the same as if it were open and in front.
        // This will prevent any keys other than the power button from waking the screen
        // when the keyguard is hidden by another activity.
        final boolean keyguardActive = (screenIsOn ?
                                        mKeyguardMediator.isShowingAndNotHidden() :
                                        mKeyguardMediator.isShowing());

        if (false) {
            Log.d(TAG, "interceptKeyTq event=" + event + " keycode=" + event.keycode
                  + " screenIsOn=" + screenIsOn + " keyguardActive=" + keyguardActive);
        }

        if (keyguardActive) {
            if (screenIsOn) {
                // when the screen is on, always give the event to the keyguard
                result |= ACTION_PASS_TO_USER;
            } else {
                // otherwise, don't pass it to the user
                result &= ~ACTION_PASS_TO_USER;

                final boolean isKeyDown =
                        (event.type == RawInputEvent.EV_KEY) && (event.value != 0);
                if (isWakeKey && isKeyDown) {

                    // tell the mediator about a wake key, it may decide to
                    // turn on the screen depending on whether the key is
                    // appropriate.
                    if (!mKeyguardMediator.onWakeKeyWhenKeyguardShowingTq(event.keycode)
                            && (event.keycode == KeyEvent.KEYCODE_VOLUME_DOWN
                                || event.keycode == KeyEvent.KEYCODE_VOLUME_UP)) {
                        // when keyguard is showing and screen off, we need
                        // to handle the volume key for calls and  music here
                        if (isInCall()) {
                            handleVolumeKey(AudioManager.STREAM_VOICE_CALL, event.keycode);
                        } else if (isMusicActive()) {
                            handleVolumeKey(AudioManager.STREAM_MUSIC, event.keycode);
                        }
                    }
                }
            }
        } else if (!screenIsOn) {
            // If we are in-call with screen off and keyguard is not showing,
            // then handle the volume key ourselves.
            // This is necessary because the phone app will disable the keyguard
            // when the proximity sensor is in use.
            if (isInCall() && event.type == RawInputEvent.EV_KEY &&
                     (event.keycode == KeyEvent.KEYCODE_VOLUME_DOWN
                                || event.keycode == KeyEvent.KEYCODE_VOLUME_UP)) {
                result &= ~ACTION_PASS_TO_USER;
                handleVolumeKey(AudioManager.STREAM_VOICE_CALL, event.keycode);
            }
            if (isWakeKey) {
                // a wake key has a sole purpose of waking the device; don't pass
                // it to the user
                result |= ACTION_POKE_USER_ACTIVITY;
                result &= ~ACTION_PASS_TO_USER;
            }
        }

        int type = event.type;
        int code = event.keycode;
        boolean down = event.value != 0;

        if (type == RawInputEvent.EV_KEY) {
            if (code == KeyEvent.KEYCODE_ENDCALL
                    || code == KeyEvent.KEYCODE_POWER) {
                if (down) {
                    boolean handled = false;
                    boolean hungUp = false;
                    // key repeats are generated by the window manager, and we don't see them
                    // here, so unless the driver is doing something it shouldn't be, we know
                    // this is the real press event.
                    ITelephony phoneServ = getPhoneInterface();
                    if (phoneServ != null) {
                        try {
                            if (code == KeyEvent.KEYCODE_ENDCALL) {
                                handled = hungUp = phoneServ.endCall();
                            } else if (code == KeyEvent.KEYCODE_POWER) {
                                if (phoneServ.isRinging()) {
                                    // Pressing Power while there's a ringing incoming
                                    // call should silence the ringer.
                                    phoneServ.silenceRinger();
                                    handled = true;
                                } else if (phoneServ.isOffhook() &&
                                           ((mIncallPowerBehavior
                                             & Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR_HANGUP)
                                            != 0)) {
                                    // Otherwise, if "Power button ends call" is enabled,
                                    // the Power button will hang up any current active call.
                                    handled = hungUp = phoneServ.endCall();
                                }
                            }
                        } catch (RemoteException ex) {
                            Log.w(TAG, "ITelephony threw RemoteException" + ex);
                        }
                    } else {
                        Log.w(TAG, "!!! Unable to find ITelephony interface !!!");
                    }

                    if (!screenIsOn
                            || (handled && code != KeyEvent.KEYCODE_POWER)
                            || (handled && hungUp && code == KeyEvent.KEYCODE_POWER)) {
                        mShouldTurnOffOnKeyUp = false;
                    } else {
                        // only try to turn off the screen if we didn't already hang up
                        mShouldTurnOffOnKeyUp = true;
                        mHandler.postDelayed(mPowerLongPress,
                                ViewConfiguration.getGlobalActionKeyTimeout());
                        result &= ~ACTION_PASS_TO_USER;
                    }
                } else {
                    mHandler.removeCallbacks(mPowerLongPress);
                    if (mShouldTurnOffOnKeyUp) {
                        mShouldTurnOffOnKeyUp = false;
                        boolean gohome, sleeps;
                        if (code == KeyEvent.KEYCODE_ENDCALL) {
                            gohome = (mEndcallBehavior
                                      & Settings.System.END_BUTTON_BEHAVIOR_HOME) != 0;
                            sleeps = (mEndcallBehavior
                                      & Settings.System.END_BUTTON_BEHAVIOR_SLEEP) != 0;
                        } else {
                            gohome = false;
                            sleeps = true;
                        }
                        if (keyguardActive
                                || (sleeps && !gohome)
                                || (gohome && !goHome() && sleeps)) {
                            // they must already be on the keyguad or home screen,
                            // go to sleep instead
                            Log.d(TAG, "I'm tired mEndcallBehavior=0x"
                                    + Integer.toHexString(mEndcallBehavior));
                            result &= ~ACTION_POKE_USER_ACTIVITY;
                            result |= ACTION_GO_TO_SLEEP;
                        }
                        result &= ~ACTION_PASS_TO_USER;
                    }
                }
            } else if (isMediaKey(code)) {
                // This key needs to be handled even if the screen is off.
                // If others need to be handled while it's off, this is a reasonable
                // pattern to follow.
                if ((result & ACTION_PASS_TO_USER) == 0) {
                    // Only do this if we would otherwise not pass it to the user. In that
                    // case, the PhoneWindow class will do the same thing, except it will
                    // only do it if the showing app doesn't process the key on its own.
                    KeyEvent keyEvent = new KeyEvent(event.when, event.when,
                            down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                            code, 0);
                    mBroadcastWakeLock.acquire();
                    mHandler.post(new PassHeadsetKey(keyEvent));
                }
            } else if (code == KeyEvent.KEYCODE_CALL) {
                // If an incoming call is ringing, answer it!
                // (We handle this key here, rather than in the InCallScreen, to make
                // sure we'll respond to the key even if the InCallScreen hasn't come to
                // the foreground yet.)

                // We answer the call on the DOWN event, to agree with
                // the "fallback" behavior in the InCallScreen.
                if (down) {
                    try {
                        ITelephony phoneServ = getPhoneInterface();
                        if (phoneServ != null) {
                            if (phoneServ.isRinging()) {
                                Log.i(TAG, "interceptKeyTq:"
                                      + " CALL key-down while ringing: Answer the call!");
                                phoneServ.answerRingingCall();

                                // And *don't* pass this key thru to the current activity
                                // (which is presumably the InCallScreen.)
                                result &= ~ACTION_PASS_TO_USER;
                            }
                        } else {
                            Log.w(TAG, "CALL button: Unable to find ITelephony interface");
                        }
                    } catch (RemoteException ex) {
                        Log.w(TAG, "CALL button: RemoteException from getPhoneInterface()", ex);
                    }
                }
            } else if ((code == KeyEvent.KEYCODE_VOLUME_UP)
                       || (code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
                // If an incoming call is ringing, either VOLUME key means
                // "silence ringer".  We handle these keys here, rather than
                // in the InCallScreen, to make sure we'll respond to them
                // even if the InCallScreen hasn't come to the foreground yet.

                // Look for the DOWN event here, to agree with the "fallback"
                // behavior in the InCallScreen.
                if (down) {
                    try {
                        ITelephony phoneServ = getPhoneInterface();
                        if (phoneServ != null) {
                            if (phoneServ.isRinging()) {
                                Log.i(TAG, "interceptKeyTq:"
                                      + " VOLUME key-down while ringing: Silence ringer!");
                                // Silence the ringer.  (It's safe to call this
                                // even if the ringer has already been silenced.)
                                phoneServ.silenceRinger();

                                // And *don't* pass this key thru to the current activity
                                // (which is probably the InCallScreen.)
                                result &= ~ACTION_PASS_TO_USER;
                            }
                        } else {
                            Log.w(TAG, "VOLUME button: Unable to find ITelephony interface");
                        }
                    } catch (RemoteException ex) {
                        Log.w(TAG, "VOLUME button: RemoteException from getPhoneInterface()", ex);
                    }
                }
            }
        }

        return result;
    }

    class PassHeadsetKey implements Runnable {
        KeyEvent mKeyEvent;

        PassHeadsetKey(KeyEvent keyEvent) {
            mKeyEvent = keyEvent;
        }

        public void run() {
            if (ActivityManagerNative.isSystemReady()) {
                Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
                intent.putExtra(Intent.EXTRA_KEY_EVENT, mKeyEvent);
                mContext.sendOrderedBroadcast(intent, null, mBroadcastDone,
                        mHandler, Activity.RESULT_OK, null, null);
            }
        }
    }

    BroadcastReceiver mBroadcastDone = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            mBroadcastWakeLock.release();
        }
    };

    BroadcastReceiver mDockReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_DOCK_EVENT.equals(intent.getAction())) {
                mDockMode = intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                        Intent.EXTRA_DOCK_STATE_UNDOCKED);
            } else {
                try {
                    IUiModeManager uiModeService = IUiModeManager.Stub.asInterface(
                            ServiceManager.getService(Context.UI_MODE_SERVICE));
                    mUiMode = uiModeService.getCurrentModeType();
                } catch (RemoteException e) {
                }
            }
            updateRotation(Surface.FLAGS_ORIENTATION_ANIMATION_DISABLE);
            updateOrientationListenerLp();
        }
    };

    /** {@inheritDoc} */
    public boolean isWakeRelMovementTq(int device, int classes,
            RawInputEvent event) {
        // if it's tagged with one of the wake bits, it wakes up the device
        return ((event.flags & (FLAG_WAKE | FLAG_WAKE_DROPPED)) != 0);
    }

    /** {@inheritDoc} */
    public boolean isWakeAbsMovementTq(int device, int classes,
            RawInputEvent event) {
        // if it's tagged with one of the wake bits, it wakes up the device
        return ((event.flags & (FLAG_WAKE | FLAG_WAKE_DROPPED)) != 0);
    }

    /**
     * Given the current state of the world, should this key wake up the device?
     */
    protected boolean isWakeKeyTq(RawInputEvent event) {
        // There are not key maps for trackball devices, but we'd still
        // like to have pressing it wake the device up, so force it here.
        int keycode = event.keycode;
        int flags = event.flags;
        if (keycode == RawInputEvent.BTN_MOUSE) {
            flags |= WindowManagerPolicy.FLAG_WAKE;
        }
        return (flags
                & (WindowManagerPolicy.FLAG_WAKE | WindowManagerPolicy.FLAG_WAKE_DROPPED)) != 0;
    }

    /** {@inheritDoc} */
    public void screenTurnedOff(int why) {
        EventLog.writeEvent(70000, 0);
        mKeyguardMediator.onScreenTurnedOff(why);
        synchronized (mLock) {
            mScreenOn = false;
            updateOrientationListenerLp();
            updateLockScreenTimeout();
        }
    }

    /** {@inheritDoc} */
    public void screenTurnedOn() {
        EventLog.writeEvent(70000, 1);
        mKeyguardMediator.onScreenTurnedOn();
        synchronized (mLock) {
            mScreenOn = true;
            updateOrientationListenerLp();
            updateLockScreenTimeout();
        }
    }

    /** {@inheritDoc} */
    public boolean isScreenOn() {
        return mScreenOn;
    }
    
    /** {@inheritDoc} */
    public void enableKeyguard(boolean enabled) {
        mKeyguardMediator.setKeyguardEnabled(enabled);
    }

    /** {@inheritDoc} */
    public void exitKeyguardSecurely(OnKeyguardExitResult callback) {
        mKeyguardMediator.verifyUnlock(callback);
    }

    private boolean keyguardIsShowingTq() {
        return mKeyguardMediator.isShowingAndNotHidden();
    }

    /** {@inheritDoc} */
    public boolean inKeyguardRestrictedKeyInputMode() {
        return mKeyguardMediator.isInputRestricted();
    }

    void sendCloseSystemWindows() {
        sendCloseSystemWindows(mContext, null);
    }

    void sendCloseSystemWindows(String reason) {
        sendCloseSystemWindows(mContext, reason);
    }

    static void sendCloseSystemWindows(Context context, String reason) {
        if (ActivityManagerNative.isSystemReady()) {
            try {
                ActivityManagerNative.getDefault().closeSystemDialogs(reason);
            } catch (RemoteException e) {
            }
        }
    }

    public int rotationForOrientationLw(int orientation, int lastRotation,
            boolean displayEnabled) {

        if (mPortraitRotation < 0) {
            // Initialize the rotation angles for each orientation once.
            Display d = ((WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay();
            if (d.getWidth() > d.getHeight()) {
                mPortraitRotation = Surface.ROTATION_90;
                mLandscapeRotation = Surface.ROTATION_0;
            } else {
                mPortraitRotation = Surface.ROTATION_0;
                mLandscapeRotation = Surface.ROTATION_90;
            }
        }

        synchronized (mLock) {
            switch (orientation) {
                case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
                    //always return landscape if orientation set to landscape
                    return mLandscapeRotation;
                case ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
                    //always return portrait if orientation set to portrait
                    return mPortraitRotation;
            }
            // case for nosensor meaning ignore sensor and consider only lid
            // or orientation sensor disabled
            //or case.unspecified
            if (mLidOpen) {
                return mLidOpenRotation;
            } else if (mDockMode == Intent.EXTRA_DOCK_STATE_CAR && mCarDockRotation >= 0) {
                return mCarDockRotation;
            } else if (mDockMode == Intent.EXTRA_DOCK_STATE_DESK && mDeskDockRotation >= 0) {
                return mDeskDockRotation;
            } else {
                if (useSensorForOrientationLp(orientation)) {
                    // If the user has enabled auto rotation by default, do it.
                    int curRotation = mOrientationListener.getCurrentRotation();
                    return curRotation >= 0 ? curRotation : lastRotation;
                }
                return Surface.ROTATION_0;
            }
        }
    }

    public boolean detectSafeMode() {
        try {
            int menuState = mWindowManager.getKeycodeState(KeyEvent.KEYCODE_MENU);
            int sState = mWindowManager.getKeycodeState(KeyEvent.KEYCODE_S);
            int dpadState = mWindowManager.getDPadKeycodeState(KeyEvent.KEYCODE_DPAD_CENTER);
            int trackballState = mWindowManager.getTrackballScancodeState(RawInputEvent.BTN_MOUSE);
            mSafeMode = menuState > 0 || sState > 0 || dpadState > 0 || trackballState > 0;
            performHapticFeedbackLw(null, mSafeMode
                    ? HapticFeedbackConstants.SAFE_MODE_ENABLED
                    : HapticFeedbackConstants.SAFE_MODE_DISABLED, true);
            if (mSafeMode) {
                Log.i(TAG, "SAFE MODE ENABLED (menu=" + menuState + " s=" + sState
                        + " dpad=" + dpadState + " trackball=" + trackballState + ")");
            } else {
                Log.i(TAG, "SAFE MODE not enabled");
            }
            return mSafeMode;
        } catch (RemoteException e) {
            // Doom! (it's also local)
            throw new RuntimeException("window manager dead");
        }
    }
    
    static long[] getLongIntArray(Resources r, int resid) {
        int[] ar = r.getIntArray(resid);
        if (ar == null) {
            return null;
        }
        long[] out = new long[ar.length];
        for (int i=0; i<ar.length; i++) {
            out[i] = ar[i];
        }
        return out;
    }
    
    /** {@inheritDoc} */
    public void systemReady() {
        // tell the keyguard
        mKeyguardMediator.onSystemReady();
        android.os.SystemProperties.set("dev.bootcomplete", "1"); 
        synchronized (mLock) {
            updateOrientationListenerLp();
            mSystemReady = true;
            mHandler.post(new Runnable() {
                public void run() {
                    updateSettings();
                }
            });
        }
    }
   
    /** {@inheritDoc} */
    public void userActivity() {
        synchronized (mScreenLockTimeout) {
            if (mLockScreenTimerActive) {
                // reset the timer
                mHandler.removeCallbacks(mScreenLockTimeout);
                mHandler.postDelayed(mScreenLockTimeout, mLockScreenTimeout);
            }
        }
    }

    Runnable mScreenLockTimeout = new Runnable() {
        public void run() {
            synchronized (this) {
                if (localLOGV) Log.v(TAG, "mScreenLockTimeout activating keyguard");
                mKeyguardMediator.doKeyguardTimeout();
                mLockScreenTimerActive = false;
            }
        }
    };

    private void updateLockScreenTimeout() {
        synchronized (mScreenLockTimeout) {
            boolean enable = (mAllowLockscreenWhenOn && mScreenOn && mKeyguardMediator.isSecure());
            if (mLockScreenTimerActive != enable) {
                if (enable) {
                    if (localLOGV) Log.v(TAG, "setting lockscreen timer");
                    mHandler.postDelayed(mScreenLockTimeout, mLockScreenTimeout);
                } else {
                    if (localLOGV) Log.v(TAG, "clearing lockscreen timer");
                    mHandler.removeCallbacks(mScreenLockTimeout);
                }
                mLockScreenTimerActive = enable;
            }
        }
    }

    /** {@inheritDoc} */
    public void enableScreenAfterBoot() {
        readLidState();
        updateRotation(Surface.FLAGS_ORIENTATION_ANIMATION_DISABLE);
    }

    void updateRotation(int animFlags) {
        mPowerManager.setKeyboardVisibility(mLidOpen);
        int rotation = Surface.ROTATION_0;
        if (mLidOpen) {
            rotation = mLidOpenRotation;
        } else if (mDockMode == Intent.EXTRA_DOCK_STATE_CAR && mCarDockRotation >= 0) {
            rotation = mCarDockRotation;
        } else if (mDockMode == Intent.EXTRA_DOCK_STATE_DESK && mDeskDockRotation >= 0) {
            rotation = mDeskDockRotation;
        }
        //if lid is closed orientation will be portrait
        try {
            //set orientation on WindowManager
            mWindowManager.setRotation(rotation, true,
                    mFancyRotationAnimation | animFlags);
        } catch (RemoteException e) {
            // Ignore
        }
    }

    /**
     * Return an Intent to launch the currently active dock as home.  Returns
     * null if the standard home should be launched.
     * @return
     */
    Intent createHomeDockIntent() {
        Intent intent;
        
        // What home does is based on the mode, not the dock state.  That
        // is, when in car mode you should be taken to car home regardless
        // of whether we are actually in a car dock.
        if (mUiMode == Configuration.UI_MODE_TYPE_CAR) {
            intent = mCarDockIntent;
        } else if (mUiMode == Configuration.UI_MODE_TYPE_DESK) {
            intent = mDeskDockIntent;
        } else {
            return null;
        }
        
        ActivityInfo ai = intent.resolveActivityInfo(
                mContext.getPackageManager(), PackageManager.GET_META_DATA);
        if (ai == null) {
            return null;
        }
        
        if (ai.metaData != null && ai.metaData.getBoolean(Intent.METADATA_DOCK_HOME)) {
            intent = new Intent(intent);
            intent.setClassName(ai.packageName, ai.name);
            return intent;
        }
        
        return null;
    }
    
    void startDockOrHome() {
        Intent dock = createHomeDockIntent();
        if (dock != null) {
            try {
                mContext.startActivity(dock);
                return;
            } catch (ActivityNotFoundException e) {
            }
        }
        mContext.startActivity(mHomeIntent);
    }
    
    /**
     * goes to the home screen
     * @return whether it did anything
     */
    boolean goHome() {
        if (false) {
            // This code always brings home to the front.
            try {
                ActivityManagerNative.getDefault().stopAppSwitches();
            } catch (RemoteException e) {
            }
            sendCloseSystemWindows();
            startDockOrHome();
        } else {
            // This code brings home to the front or, if it is already
            // at the front, puts the device to sleep.
            try {
                if (SystemProperties.getInt("persist.sys.uts-test-mode", 0) == 1) {
                    /// Roll back EndcallBehavior as the cupcake design to pass P1 lab entry.
                    Log.d(TAG, "UTS-TEST-MODE");
                } else {
                    ActivityManagerNative.getDefault().stopAppSwitches();
                    sendCloseSystemWindows();
                    Intent dock = createHomeDockIntent();
                    if (dock != null) {
                        int result = ActivityManagerNative.getDefault()
                                .startActivity(null, dock,
                                        dock.resolveTypeIfNeeded(mContext.getContentResolver()),
                                        null, 0, null, null, 0, true /* onlyIfNeeded*/, false);
                        if (result == IActivityManager.START_RETURN_INTENT_TO_CALLER) {
                            return false;
                        }
                    }
                }
                int result = ActivityManagerNative.getDefault()
                        .startActivity(null, mHomeIntent,
                                mHomeIntent.resolveTypeIfNeeded(mContext.getContentResolver()),
                                null, 0, null, null, 0, true /* onlyIfNeeded*/, false);
                if (result == IActivityManager.START_RETURN_INTENT_TO_CALLER) {
                    return false;
                }
            } catch (RemoteException ex) {
                // bummer, the activity manager, which is in this process, is dead
            }
        }
        return true;
    }
    
    public void setCurrentOrientationLw(int newOrientation) {
        synchronized (mLock) {
            if (newOrientation != mCurrentAppOrientation) {
                mCurrentAppOrientation = newOrientation;
                updateOrientationListenerLp();
            }
        }
    }

    public boolean performHapticFeedbackLw(WindowState win, int effectId, boolean always) {
        final boolean hapticsDisabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) == 0;
        if (!always && (hapticsDisabled || mKeyguardMediator.isShowingAndNotHidden())) {
            return false;
        }
        long[] pattern = null;
        switch (effectId) {
            case HapticFeedbackConstants.LONG_PRESS:
                pattern = mLongPressVibePattern;
                break;
            case HapticFeedbackConstants.VIRTUAL_KEY:
                pattern = mVirtualKeyVibePattern;
                break;
            case HapticFeedbackConstants.KEYBOARD_TAP:
                pattern = mKeyboardTapVibePattern;
                break;
            case HapticFeedbackConstants.SAFE_MODE_DISABLED:
                pattern = mSafeModeDisabledVibePattern;
                break;
            case HapticFeedbackConstants.SAFE_MODE_ENABLED:
                pattern = mSafeModeEnabledVibePattern;
                break;
            default:
                return false;
        }
        if (pattern.length == 1) {
            // One-shot vibration
            mVibrator.vibrate(pattern[0]);
        } else {
            // Pattern vibration
            mVibrator.vibrate(pattern, -1);
        }
        return true;
    }
    
    public void keyFeedbackFromInput(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getFlags()&KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            performHapticFeedbackLw(null, HapticFeedbackConstants.VIRTUAL_KEY, false);
        }
    }
    
    public void screenOnStoppedLw() {
        if (!mKeyguardMediator.isShowingAndNotHidden() && mPowerManager.isScreenOn()) {
            long curTime = SystemClock.uptimeMillis();
            mPowerManager.userActivity(curTime, false, LocalPowerManager.OTHER_EVENT);
        }
    }

    public boolean allowKeyRepeat() {
        // disable key repeat when screen is off
        return mScreenOn;
    }
}
