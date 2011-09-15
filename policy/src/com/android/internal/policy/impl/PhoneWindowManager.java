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
import android.app.ProgressDialog;
import android.app.UiModeManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.LocalPowerManager;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UEventObserver;
import android.os.Vibrator;
import android.provider.Settings;

import com.android.internal.R;
import com.android.internal.app.ShutdownThread;
import com.android.internal.policy.PolicyManager;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.telephony.ITelephony;
import com.android.internal.view.BaseInputHandler;
import com.android.internal.widget.PointerLocationView;

import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.IWindowManager;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputQueue;
import android.view.InputHandler;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowOrientationListener;
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
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_DRAG;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_PHONE;
import static android.view.WindowManager.LayoutParams.TYPE_PRIORITY_PHONE;
import static android.view.WindowManager.LayoutParams.TYPE_SEARCH_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManager.LayoutParams.TYPE_POINTER;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_BOOT_PROGRESS;
import android.view.WindowManagerImpl;
import android.view.WindowManagerPolicy;
import android.view.KeyCharacterMap.FallbackAction;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.media.IAudioService;
import android.media.AudioManager;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
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
    static final boolean localLOGV = false;
    static final boolean DEBUG_LAYOUT = false;
    static final boolean DEBUG_FALLBACK = false;
    static final boolean SHOW_STARTING_ANIMATIONS = true;
    static final boolean SHOW_PROCESSES_ON_ALT_MENU = false;

    static final int LONG_PRESS_POWER_NOTHING = 0;
    static final int LONG_PRESS_POWER_GLOBAL_ACTIONS = 1;
    static final int LONG_PRESS_POWER_SHUT_OFF = 2;

    // These need to match the documentation/constant in
    // core/res/res/values/config.xml
    static final int LONG_PRESS_HOME_NOTHING = 0;
    static final int LONG_PRESS_HOME_RECENT_DIALOG = 1;
    static final int LONG_PRESS_HOME_RECENT_SYSTEM_UI = 2;

    // wallpaper is at the bottom, though the window manager may move it.
    static final int WALLPAPER_LAYER = 2;
    static final int APPLICATION_LAYER = 2;
    static final int PHONE_LAYER = 3;
    static final int SEARCH_BAR_LAYER = 4;
    static final int SYSTEM_DIALOG_LAYER = 5;
    // toasts and the plugged-in battery thing
    static final int TOAST_LAYER = 6;
    // SIM errors and unlock.  Not sure if this really should be in a high layer.
    static final int PRIORITY_PHONE_LAYER = 7;
    // like the ANR / app crashed dialogs
    static final int SYSTEM_ALERT_LAYER = 8;
    // system-level error dialogs
    static final int SYSTEM_ERROR_LAYER = 9;
    // on-screen keyboards and other such input method user interfaces go here.
    static final int INPUT_METHOD_LAYER = 10;
    // on-screen keyboards and other such input method user interfaces go here.
    static final int INPUT_METHOD_DIALOG_LAYER = 11;
    // the keyguard; nothing on top of these can take focus, since they are
    // responsible for power management when displayed.
    static final int KEYGUARD_LAYER = 12;
    static final int KEYGUARD_DIALOG_LAYER = 13;
    static final int STATUS_BAR_SUB_PANEL_LAYER = 14;
    static final int STATUS_BAR_LAYER = 15;
    static final int STATUS_BAR_PANEL_LAYER = 16;
    // the on-screen volume indicator and controller shown when the user
    // changes the device volume
    static final int VOLUME_OVERLAY_LAYER = 17;
    // things in here CAN NOT take focus, but are shown on top of everything else.
    static final int SYSTEM_OVERLAY_LAYER = 18;
    // the navigation bar, if available, shows atop most things
    static final int NAVIGATION_BAR_LAYER = 19;
    // the drag layer: input for drag-and-drop is associated with this window,
    // which sits above all other focusable windows
    static final int DRAG_LAYER = 20;
    static final int SECURE_SYSTEM_OVERLAY_LAYER = 21;
    static final int BOOT_PROGRESS_LAYER = 22;
    // the (mouse) pointer layer
    static final int POINTER_LAYER = 23;

    static final int APPLICATION_MEDIA_SUBLAYER = -2;
    static final int APPLICATION_MEDIA_OVERLAY_SUBLAYER = -1;
    static final int APPLICATION_PANEL_SUBLAYER = 1;
    static final int APPLICATION_SUB_PANEL_SUBLAYER = 2;
    
    static public final String SYSTEM_DIALOG_REASON_KEY = "reason";
    static public final String SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS = "globalactions";
    static public final String SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps";
    static public final String SYSTEM_DIALOG_REASON_HOME_KEY = "homekey";

    // Useful scan codes.
    private static final int SW_LID = 0x00;
    private static final int BTN_MOUSE = 0x110;
    
    final Object mLock = new Object();
    
    Context mContext;
    IWindowManager mWindowManager;
    LocalPowerManager mPowerManager;
    IStatusBarService mStatusBarService;
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
    boolean mStatusBarCanHide;
    int mStatusBarHeight;
    final ArrayList<WindowState> mStatusBarPanels = new ArrayList<WindowState>();
    WindowState mNavigationBar = null;
    boolean mHasNavigationBar = false;
    int mNavigationBarWidth = 0, mNavigationBarHeight = 0;

    WindowState mKeyguard = null;
    KeyguardViewMediator mKeyguardMediator;
    GlobalActions mGlobalActions;
    volatile boolean mPowerKeyHandled;
    RecentApplicationsDialog mRecentAppsDialog;
    Handler mHandler;

    private static final int LID_ABSENT = -1;
    private static final int LID_CLOSED = 0;
    private static final int LID_OPEN = 1;

    int mLidOpen = LID_ABSENT;

    boolean mSystemReady;
    boolean mSystemBooted;
    boolean mHdmiPlugged;
    int mUiMode = Configuration.UI_MODE_TYPE_NORMAL;
    int mDockMode = Intent.EXTRA_DOCK_STATE_UNDOCKED;
    int mLidOpenRotation;
    int mCarDockRotation;
    int mDeskDockRotation;

    int mUserRotationMode = WindowManagerPolicy.USER_ROTATION_FREE;
    int mUserRotation = Surface.ROTATION_0;

    boolean mAllowAllRotations;
    boolean mCarDockEnablesAccelerometer;
    boolean mDeskDockEnablesAccelerometer;
    int mLidKeyboardAccessibility;
    int mLidNavigationAccessibility;
    int mLongPressOnPowerBehavior = -1;
    boolean mScreenOn = false;
    boolean mOrientationSensorEnabled = false;
    int mCurrentAppOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    static final int DEFAULT_ACCELEROMETER_ROTATION = 0;
    int mAccelerometerDefault = DEFAULT_ACCELEROMETER_ROTATION;
    boolean mHasSoftInput = false;
    
    int mPointerLocationMode = 0;
    PointerLocationView mPointerLocationView = null;
    InputChannel mPointerLocationInputChannel;

    // The last window we were told about in focusChanged.
    WindowState mFocusedWindow;

    private final InputHandler mPointerLocationInputHandler = new BaseInputHandler() {
        @Override
        public void handleMotion(MotionEvent event, InputQueue.FinishedCallback finishedCallback) {
            boolean handled = false;
            try {
                if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
                    synchronized (mLock) {
                        if (mPointerLocationView != null) {
                            mPointerLocationView.addPointerEvent(event);
                            handled = true;
                        }
                    }
                }
            } finally {
                finishedCallback.finished(handled);
            }
        }
    };
    
    // The current size of the screen; really; (ir)regardless of whether the status
    // bar can be hidden or not
    int mUnrestrictedScreenLeft, mUnrestrictedScreenTop;
    int mUnrestrictedScreenWidth, mUnrestrictedScreenHeight;
    // The current size of the screen; these may be different than (0,0)-(dw,dh)
    // if the status bar can't be hidden; in that case it effectively carves out
    // that area of the display from all other windows.
    int mRestrictedScreenLeft, mRestrictedScreenTop;
    int mRestrictedScreenWidth, mRestrictedScreenHeight;
    // During layout, the current screen borders with all outer decoration
    // (status bar, input method dock) accounted for.
    int mCurLeft, mCurTop, mCurRight, mCurBottom;
    // During layout, the frame in which content should be displayed
    // to the user, accounting for all screen decoration except for any
    // space they deem as available for other content.  This is usually
    // the same as mCur*, but may be larger if the screen decor has supplied
    // content insets.
    int mContentLeft, mContentTop, mContentRight, mContentBottom;
    // During layout, the current screen borders along which input method
    // windows are placed.
    int mDockLeft, mDockTop, mDockRight, mDockBottom;
    // During layout, the layer at which the doc window is placed.
    int mDockLayer;
    
    static final Rect mTmpParentFrame = new Rect();
    static final Rect mTmpDisplayFrame = new Rect();
    static final Rect mTmpContentFrame = new Rect();
    static final Rect mTmpVisibleFrame = new Rect();
    static final Rect mTmpNavigationFrame = new Rect();
    
    WindowState mTopFullscreenOpaqueWindowState;
    WindowState mTopAppWindowState;
    WindowState mLastTopAppWindowState;
    boolean mTopIsFullscreen;
    boolean mForceStatusBar;
    boolean mHideLockScreen;
    boolean mDismissKeyguard;
    boolean mHomePressed;
    Intent mHomeIntent;
    Intent mCarDockIntent;
    Intent mDeskDockIntent;
    int mShortcutKeyPressed = -1;
    boolean mConsumeShortcutKeyUp;

    // support for activating the lock screen while the screen is on
    boolean mAllowLockscreenWhenOn;
    int mLockScreenTimeout;
    boolean mLockScreenTimerActive;

    // visual screen saver support
    int mScreenSaverTimeout;
    boolean mScreenSaverEnabled = false;

    // Behavior of ENDCALL Button.  (See Settings.System.END_BUTTON_BEHAVIOR.)
    int mEndcallBehavior;

    // Behavior of POWER button while in-call and screen on.
    // (See Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR.)
    int mIncallPowerBehavior;

    int mLandscapeRotation = 0;  // default landscape rotation
    int mSeascapeRotation = 0;   // "other" landscape rotation, 180 degrees from mLandscapeRotation
    int mPortraitRotation = 0;   // default portrait rotation
    int mUpsideDownRotation = 0; // "other" portrait rotation

    // Nothing to see here, move along...
    int mFancyRotationAnimation;
    
    // What we do when the user long presses on home
    private int mLongPressOnHomeBehavior = -1;

    // Screenshot trigger states
    private boolean mVolumeDownTriggered;
    private boolean mPowerDownTriggered;

    ShortcutManager mShortcutManager;
    PowerManager.WakeLock mBroadcastWakeLock;

    final KeyCharacterMap.FallbackAction mFallbackAction = new KeyCharacterMap.FallbackAction();

    private UEventObserver mHDMIObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEventObserver.UEvent event) {
            setHdmiPlugged("1".equals(event.get("SWITCH_STATE")));
        }
    };

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
                    Settings.System.USER_ROTATION), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_OFF_TIMEOUT), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.WINDOW_ORIENTATION_LISTENER_LOG), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.POINTER_LOCATION), false, this);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.DEFAULT_INPUT_METHOD), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    "fancy_rotation_anim"), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.Secure.DREAM_TIMEOUT), false, this);
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
        if (appOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR
                || appOrientation == ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                || appOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                || appOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT) {
            return true;
        }
        // The user preference says we can rotate, and the app is willing to rotate.
        if (mAccelerometerDefault != 0 &&
                (appOrientation == ActivityInfo.SCREEN_ORIENTATION_USER
                 || appOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)) {
            return true;
        }
        // We're in a dock that has a rotation affinity, and the app is willing to rotate.
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
        if (mCurrentAppOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR
                || mCurrentAppOrientation == ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                || mCurrentAppOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                || mCurrentAppOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
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

    private void interceptPowerKeyDown(boolean handled) {
        mPowerKeyHandled = handled;
        if (!handled) {
            mHandler.postDelayed(mPowerLongPress, ViewConfiguration.getGlobalActionKeyTimeout());
        }
    }

    private boolean interceptPowerKeyUp(boolean canceled) {
        if (!mPowerKeyHandled) {
            mHandler.removeCallbacks(mPowerLongPress);
            return !canceled;
        } else {
            mPowerKeyHandled = true;
            return false;
        }
    }

    private final Runnable mPowerLongPress = new Runnable() {
        public void run() {
            if (!mPowerKeyHandled) {
                // The context isn't read
                if (mLongPressOnPowerBehavior < 0) {
                    mLongPressOnPowerBehavior = mContext.getResources().getInteger(
                            com.android.internal.R.integer.config_longPressOnPowerBehavior);
                }
                switch (mLongPressOnPowerBehavior) {
                case LONG_PRESS_POWER_NOTHING:
                    break;
                case LONG_PRESS_POWER_GLOBAL_ACTIONS:
                    mPowerKeyHandled = true;
                    performHapticFeedbackLw(null, HapticFeedbackConstants.LONG_PRESS, false);
                    sendCloseSystemWindows(SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS);
                    showGlobalActionsDialog();
                    break;
                case LONG_PRESS_POWER_SHUT_OFF:
                    mPowerKeyHandled = true;
                    performHapticFeedbackLw(null, HapticFeedbackConstants.LONG_PRESS, false);
                    sendCloseSystemWindows(SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS);
                    ShutdownThread.shutdown(mContext, true);
                    break;
                }
            }
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

    private void handleLongPressOnHome() {
        // We can't initialize this in init() since the configuration hasn't been loaded yet.
        if (mLongPressOnHomeBehavior < 0) {
            mLongPressOnHomeBehavior
                    = mContext.getResources().getInteger(R.integer.config_longPressOnHomeBehavior);
            if (mLongPressOnHomeBehavior < LONG_PRESS_HOME_NOTHING ||
                    mLongPressOnHomeBehavior > LONG_PRESS_HOME_RECENT_SYSTEM_UI) {
                mLongPressOnHomeBehavior = LONG_PRESS_HOME_NOTHING;
            }
        }

        if (mLongPressOnHomeBehavior != LONG_PRESS_HOME_NOTHING) {
            performHapticFeedbackLw(null, HapticFeedbackConstants.LONG_PRESS, false);
            sendCloseSystemWindows(SYSTEM_DIALOG_REASON_RECENT_APPS);

            // Eat the longpress so it won't dismiss the recent apps dialog when
            // the user lets go of the home key
            mHomePressed = false;
        }

        if (mLongPressOnHomeBehavior == LONG_PRESS_HOME_RECENT_DIALOG) {
            showOrHideRecentAppsDialog(0, true /*dismissIfShown*/);
        } else if (mLongPressOnHomeBehavior == LONG_PRESS_HOME_RECENT_SYSTEM_UI) {
            try {
                mStatusBarService.toggleRecentApps();
            } catch (RemoteException e) {
                Slog.e(TAG, "RemoteException when showing recent apps", e);
            }
        }
    }

    /**
     * Create (if necessary) and launch the recent apps dialog, or hide it if it is
     * already shown.
     */
    void showOrHideRecentAppsDialog(final int heldModifiers, final boolean dismissIfShown) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mRecentAppsDialog == null) {
                    mRecentAppsDialog = new RecentApplicationsDialog(mContext);
                }
                if (mRecentAppsDialog.isShowing()) {
                    if (dismissIfShown) {
                        mRecentAppsDialog.dismiss();
                    }
                } else {
                    mRecentAppsDialog.setHeldModifiers(heldModifiers);
                    mRecentAppsDialog.show();
                }
            }
        });
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
        mAllowAllRotations = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_allowAllRotations);
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

        // Controls rotation and the like.
        initializeHdmiState();
    }

    public void setInitialDisplaySize(int width, int height) {
        int shortSize;
        if (width > height) {
            shortSize = height;
            mLandscapeRotation = Surface.ROTATION_0;
            mSeascapeRotation = Surface.ROTATION_180;
            if (mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_reverseDefaultRotation)) {
                mPortraitRotation = Surface.ROTATION_90;
                mUpsideDownRotation = Surface.ROTATION_270;
            } else {
                mPortraitRotation = Surface.ROTATION_270;
                mUpsideDownRotation = Surface.ROTATION_90;
            }
        } else {
            shortSize = width;
            mPortraitRotation = Surface.ROTATION_0;
            mUpsideDownRotation = Surface.ROTATION_180;
            if (mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_reverseDefaultRotation)) {
                mLandscapeRotation = Surface.ROTATION_270;
                mSeascapeRotation = Surface.ROTATION_90;
            } else {
                mLandscapeRotation = Surface.ROTATION_90;
                mSeascapeRotation = Surface.ROTATION_270;
            }
        }

        // Determine whether the status bar can hide based on the size
        // of the screen.  We assume sizes > 600dp are tablets where we
        // will use the system bar.
        int shortSizeDp = shortSize
                * DisplayMetrics.DENSITY_DEFAULT
                / DisplayMetrics.DENSITY_DEVICE;
        mStatusBarCanHide = shortSizeDp < 600;
        mStatusBarHeight = mContext.getResources().getDimensionPixelSize(
                mStatusBarCanHide
                ? com.android.internal.R.dimen.status_bar_height
                : com.android.internal.R.dimen.system_bar_height);

        mHasNavigationBar = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_showNavigationBar);
        mNavigationBarHeight = mHasNavigationBar
                ? mContext.getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.navigation_bar_height)
                : 0;
        mNavigationBarWidth = mHasNavigationBar
                ? mContext.getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.navigation_bar_width)
                : 0;
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
            
            // set up rotation lock state
            mUserRotationMode = (accelerometerDefault == 0)
                ? WindowManagerPolicy.USER_ROTATION_LOCKED
                : WindowManagerPolicy.USER_ROTATION_FREE;
            mUserRotation = Settings.System.getInt(resolver,
                    Settings.System.USER_ROTATION,
                    Surface.ROTATION_0);

            if (mAccelerometerDefault != accelerometerDefault) {
                mAccelerometerDefault = accelerometerDefault;
                updateOrientationListenerLp();
            }

            mOrientationListener.setLogEnabled(
                    Settings.System.getInt(resolver,
                            Settings.System.WINDOW_ORIENTATION_LISTENER_LOG, 0) != 0);

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

            mScreenSaverTimeout = Settings.System.getInt(resolver,
                    Settings.Secure.DREAM_TIMEOUT, 0);
            mScreenSaverEnabled = true;
            updateScreenSaverTimeoutLocked();
        }
        if (updateRotation) {
            updateRotation(0);
        }
        if (addView != null) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            lp.type = WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
            lp.flags = WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            lp.format = PixelFormat.TRANSLUCENT;
            lp.setTitle("PointerLocation");
            WindowManager wm = (WindowManager)
                    mContext.getSystemService(Context.WINDOW_SERVICE);
            lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;
            wm.addView(addView, lp);
            
            if (mPointerLocationInputChannel == null) {
                try {
                    mPointerLocationInputChannel =
                        mWindowManager.monitorInput("PointerLocationView");
                    InputQueue.registerInputChannel(mPointerLocationInputChannel,
                            mPointerLocationInputHandler, mHandler.getLooper().getQueue());
                } catch (RemoteException ex) {
                    Slog.e(TAG, "Could not set up input monitoring channel for PointerLocation.",
                            ex);
                }
            }
        }
        if (removeView != null) {
            if (mPointerLocationInputChannel != null) {
                InputQueue.unregisterInputChannel(mPointerLocationInputChannel);
                mPointerLocationInputChannel.dispose();
                mPointerLocationInputChannel = null;
            }
            
            WindowManager wm = (WindowManager)
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
            case TYPE_SECURE_SYSTEM_OVERLAY:
            case TYPE_TOAST:
                // These types of windows can't receive input events.
                attrs.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                attrs.flags &= ~WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
                break;
        }
    }
    
    void readLidState() {
        try {
            int sw = mWindowManager.getSwitchState(SW_LID);
            if (sw > 0) {
                mLidOpen = LID_OPEN;
            } else if (sw == 0) {
                mLidOpen = LID_CLOSED;
            } else {
                mLidOpen = LID_ABSENT;
            }
        } catch (RemoteException e) {
            // Ignore
        }
    }
    
    private int determineHiddenState(int mode, int hiddenValue, int visibleValue) {
        if (mLidOpen != LID_ABSENT) {
            switch (mode) {
                case 1:
                    return mLidOpen == LID_OPEN ? visibleValue : hiddenValue;
                case 2:
                    return mLidOpen == LID_OPEN ? hiddenValue : visibleValue;
            }
        }
        return visibleValue;
    }

    /** {@inheritDoc} */
    public void adjustConfigurationLw(Configuration config) {
        readLidState();

        mPowerManager.setKeyboardVisibility(mLidOpen == LID_OPEN);

        if (config.keyboard == Configuration.KEYBOARD_NOKEYS) {
            config.hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_YES;
        } else {
            config.hardKeyboardHidden = determineHiddenState(mLidKeyboardAccessibility,
                    Configuration.HARDKEYBOARDHIDDEN_YES, Configuration.HARDKEYBOARDHIDDEN_NO);
        }

        if (config.navigation == Configuration.NAVIGATION_NONAV) {
            config.navigationHidden = Configuration.NAVIGATIONHIDDEN_YES;
        } else {
            config.navigationHidden = determineHiddenState(mLidNavigationAccessibility,
                    Configuration.NAVIGATIONHIDDEN_YES, Configuration.NAVIGATIONHIDDEN_NO);
        }

        if (mHasSoftInput || config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO) {
            config.keyboardHidden = Configuration.KEYBOARDHIDDEN_NO;
        } else {
            config.keyboardHidden = Configuration.KEYBOARDHIDDEN_YES;
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
        case TYPE_STATUS_BAR_SUB_PANEL:
            return STATUS_BAR_SUB_PANEL_LAYER;
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
        case TYPE_VOLUME_OVERLAY:
            return VOLUME_OVERLAY_LAYER;
        case TYPE_SYSTEM_OVERLAY:
            return SYSTEM_OVERLAY_LAYER;
        case TYPE_SECURE_SYSTEM_OVERLAY:
            return SECURE_SYSTEM_OVERLAY_LAYER;
        case TYPE_PRIORITY_PHONE:
            return PRIORITY_PHONE_LAYER;
        case TYPE_TOAST:
            return TOAST_LAYER;
        case TYPE_WALLPAPER:
            return WALLPAPER_LAYER;
        case TYPE_DRAG:
            return DRAG_LAYER;
        case TYPE_POINTER:
            return POINTER_LAYER;
        case TYPE_NAVIGATION_BAR:
            return NAVIGATION_BAR_LAYER;
        case TYPE_BOOT_PROGRESS:
            return BOOT_PROGRESS_LAYER;
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

    public boolean canStatusBarHide() {
        return mStatusBarCanHide;
    }

    public int getNonDecorDisplayWidth(int fullWidth, int fullHeight, int rotation) {
        // Assumes that the navigation bar appears on the side of the display in landscape.
        if (fullWidth > fullHeight) {
            return fullWidth - mNavigationBarWidth;
        }
        return fullWidth;
    }

    public int getNonDecorDisplayHeight(int fullWidth, int fullHeight, int rotation) {
        // Assumes the navigation bar appears on the bottom of the display in portrait.
        return fullHeight
            - (mStatusBarCanHide ? 0 : mStatusBarHeight)
            - ((fullWidth > fullHeight) ? 0 : mNavigationBarHeight);
    }

    public int getConfigDisplayWidth(int fullWidth, int fullHeight, int rotation) {
        return getNonDecorDisplayWidth(fullWidth, fullHeight, rotation);
    }

    public int getConfigDisplayHeight(int fullWidth, int fullHeight, int rotation) {
        return getNonDecorDisplayHeight(fullWidth, fullHeight, rotation);
    }

    public boolean doesForceHide(WindowState win, WindowManager.LayoutParams attrs) {
        return attrs.type == WindowManager.LayoutParams.TYPE_KEYGUARD;
    }
    
    public boolean canBeForceHidden(WindowState win, WindowManager.LayoutParams attrs) {
        return attrs.type != WindowManager.LayoutParams.TYPE_STATUS_BAR
                && attrs.type != WindowManager.LayoutParams.TYPE_WALLPAPER;
    }
    
    /** {@inheritDoc} */
    public View addStartingWindow(IBinder appToken, String packageName, int theme,
            CompatibilityInfo compatInfo, CharSequence nonLocalizedLabel, int labelRes,
            int icon, int windowFlags) {
        if (!SHOW_STARTING_ANIMATIONS) {
            return null;
        }
        if (packageName == null) {
            return null;
        }
        
        try {
            Context context = mContext;
            //Log.i(TAG, "addStartingWindow " + packageName + ": nonLocalizedLabel="
            //        + nonLocalizedLabel + " theme=" + Integer.toHexString(theme));
            if (theme != context.getThemeResId() || labelRes != 0) {
                try {
                    context = context.createPackageContext(packageName, 0);
                    context.setTheme(theme);
                } catch (PackageManager.NameNotFoundException e) {
                    // Ignore
                }
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
                windowFlags|
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                windowFlags|
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
    
            if (!compatInfo.supportsScreen()) {
                win.addFlags(WindowManager.LayoutParams.FLAG_COMPATIBLE_WINDOW);
            }

            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
    
            final WindowManager.LayoutParams params = win.getAttributes();
            params.token = appToken;
            params.packageName = packageName;
            params.windowAnimations = win.getWindowStyle().getResourceId(
                    com.android.internal.R.styleable.Window_windowAnimationStyle, 0);
            params.privateFlags |=
                    WindowManager.LayoutParams.PRIVATE_FLAG_FAKE_HARDWARE_ACCELERATED;
            params.setTitle("Starting " + packageName);

            WindowManager wm = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
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
            WindowManager wm = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
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
            case TYPE_NAVIGATION_BAR:
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.STATUS_BAR_SERVICE,
                        "PhoneWindowManager");
                mNavigationBar = win;
                if (DEBUG_LAYOUT) Log.i(TAG, "NAVIGATION BAR: " + mNavigationBar);
                break;
            case TYPE_STATUS_BAR_PANEL:
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.STATUS_BAR_SERVICE,
                        "PhoneWindowManager");
                mStatusBarPanels.add(win);
                break;
            case TYPE_STATUS_BAR_SUB_PANEL:
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
        } else if (mKeyguard == win) {
            mKeyguard = null;
        } else if (mNavigationBar == win) {
            mNavigationBar = null;
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
    
    static ITelephony getTelephonyService() {
        ITelephony telephonyService = ITelephony.Stub.asInterface(
                ServiceManager.checkService(Context.TELEPHONY_SERVICE));
        if (telephonyService == null) {
            Log.w(TAG, "Unable to find ITelephony interface.");
        }
        return telephonyService;
    }

    static IAudioService getAudioService() {
        IAudioService audioService = IAudioService.Stub.asInterface(
                ServiceManager.checkService(Context.AUDIO_SERVICE));
        if (audioService == null) {
            Log.w(TAG, "Unable to find IAudioService interface.");
        }
        return audioService;
    }

    boolean keyguardOn() {
        return keyguardIsShowingTq() || inKeyguardRestrictedKeyInputMode();
    }

    private static final int[] WINDOW_TYPES_WHERE_HOME_DOESNT_WORK = {
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
        };

    /** {@inheritDoc} */
    @Override
    public boolean interceptKeyBeforeDispatching(WindowState win, KeyEvent event, int policyFlags) {
        final boolean keyguardOn = keyguardOn();
        final int keyCode = event.getKeyCode();
        final int repeatCount = event.getRepeatCount();
        final int metaState = event.getMetaState();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final boolean canceled = event.isCanceled();

        if (false) {
            Log.d(TAG, "interceptKeyTi keyCode=" + keyCode + " down=" + down + " repeatCount="
                    + repeatCount + " keyguardOn=" + keyguardOn + " mHomePressed=" + mHomePressed);
        }

        // First we always handle the home key here, so applications
        // can never break it, although if keyguard is on, we do let
        // it handle it, because that gives us the correct 5 second
        // timeout.
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            // If we have released the home key, and didn't do anything else
            // while it was pressed, then it is time to go home!
            if (mHomePressed && !down) {
                mHomePressed = false;
                if (!canceled) {
                    // If an incoming call is ringing, HOME is totally disabled.
                    // (The user is already on the InCallScreen at this point,
                    // and his ONLY options are to answer or reject the call.)
                    boolean incomingRinging = false;
                    try {
                        ITelephony telephonyService = getTelephonyService();
                        if (telephonyService != null) {
                            incomingRinging = telephonyService.isRinging();
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
                return true;
            }

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

            if (down) {
                if (repeatCount == 0) {
                    mHomePressed = true;
                } else if ((event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0) {
                    if (!keyguardOn) {
                        handleLongPressOnHome();
                    }
                }
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_MENU) {
            // Hijack modified menu keys for debugging features
            final int chordBug = KeyEvent.META_SHIFT_ON;

            if (down && repeatCount == 0) {
                if (mEnableShiftMenuBugReports && (metaState & chordBug) == chordBug) {
                    Intent intent = new Intent(Intent.ACTION_BUG_REPORT);
                    mContext.sendOrderedBroadcast(intent, null);
                    return true;
                } else if (SHOW_PROCESSES_ON_ALT_MENU &&
                        (metaState & KeyEvent.META_ALT_ON) == KeyEvent.META_ALT_ON) {
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
        } else if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (down) {
                if (repeatCount == 0) {
                    mShortcutKeyPressed = keyCode;
                    mConsumeShortcutKeyUp = false;
                }
            } else if (keyCode == mShortcutKeyPressed) {
                mShortcutKeyPressed = -1;
                if (mConsumeShortcutKeyUp) {
                    mConsumeShortcutKeyUp = false;
                    return true;
                }
            }
            return false;
        } else if (keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            if (down && repeatCount == 0) {
                showOrHideRecentAppsDialog(0, true /*dismissIfShown*/);
            }
            return true;
        }

        // Shortcuts are invoked through Search+key, so intercept those here
        // Any printing key that is chorded with Search should be consumed
        // even if no shortcut was invoked.  This prevents text from being
        // inadvertently inserted when using a keyboard that has built-in macro
        // shortcut keys (that emit Search+x) and some of them are not registered.
        if (mShortcutKeyPressed != -1) {
            final KeyCharacterMap kcm = event.getKeyCharacterMap();
            if (kcm.isPrintingKey(keyCode)) {
                mConsumeShortcutKeyUp = true;
                if (down && repeatCount == 0 && !keyguardOn) {
                    Intent shortcutIntent = mShortcutManager.getIntent(kcm, keyCode, metaState);
                    if (shortcutIntent != null) {
                        shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            mContext.startActivity(shortcutIntent);
                        } catch (ActivityNotFoundException ex) {
                            Slog.w(TAG, "Dropping shortcut key combination because "
                                    + "the activity to which it is registered was not found: "
                                    + KeyEvent.keyCodeToString(mShortcutKeyPressed)
                                    + "+" + KeyEvent.keyCodeToString(keyCode), ex);
                        }
                    } else {
                        Slog.i(TAG, "Dropping unregistered shortcut key combination: "
                                + KeyEvent.keyCodeToString(mShortcutKeyPressed)
                                + "+" + KeyEvent.keyCodeToString(keyCode));
                    }
                }
                return true;
            }
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override
    public KeyEvent dispatchUnhandledKey(WindowState win, KeyEvent event, int policyFlags) {
        // Note: This method is only called if the initial down was unhandled.
        if (DEBUG_FALLBACK) {
            Slog.d(TAG, "Unhandled key: win=" + win + ", action=" + event.getAction()
                    + ", flags=" + event.getFlags()
                    + ", keyCode=" + event.getKeyCode()
                    + ", scanCode=" + event.getScanCode()
                    + ", metaState=" + event.getMetaState()
                    + ", repeatCount=" + event.getRepeatCount()
                    + ", policyFlags=" + policyFlags);
        }

        if ((event.getFlags() & KeyEvent.FLAG_FALLBACK) == 0) {
            final KeyCharacterMap kcm = event.getKeyCharacterMap();
            final int keyCode = event.getKeyCode();
            final int metaState = event.getMetaState();
            final boolean initialDown = event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0;

            if (initialDown) {
                // Invoke shortcuts using Meta as a fallback.
                if ((metaState & KeyEvent.META_META_ON) != 0) {
                    Intent shortcutIntent = mShortcutManager.getIntent(kcm, keyCode,
                            metaState & ~(KeyEvent.META_META_ON
                                    | KeyEvent.META_META_LEFT_ON | KeyEvent.META_META_RIGHT_ON));
                    if (shortcutIntent != null) {
                        shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            mContext.startActivity(shortcutIntent);
                        } catch (ActivityNotFoundException ex) {
                            Slog.w(TAG, "Dropping shortcut key combination because "
                                    + "the activity to which it is registered was not found: "
                                    + "META+" + KeyEvent.keyCodeToString(keyCode), ex);
                        }
                        return null;
                    }
                }

                // Display task switcher for ALT-TAB or Meta-TAB.
                if (keyCode == KeyEvent.KEYCODE_TAB) {
                    final int shiftlessModifiers = event.getModifiers() & ~KeyEvent.META_SHIFT_MASK;
                    if (KeyEvent.metaStateHasModifiers(shiftlessModifiers, KeyEvent.META_ALT_ON)
                            || KeyEvent.metaStateHasModifiers(
                                    shiftlessModifiers, KeyEvent.META_META_ON)) {
                        showOrHideRecentAppsDialog(shiftlessModifiers, false /*dismissIfShown*/);
                        return null;
                    }
                }
            }

            // Check for fallback actions specified by the key character map.
            if (getFallbackAction(kcm, keyCode, metaState, mFallbackAction)) {
                if (DEBUG_FALLBACK) {
                    Slog.d(TAG, "Fallback: keyCode=" + mFallbackAction.keyCode
                            + " metaState=" + Integer.toHexString(mFallbackAction.metaState));
                }

                int flags = event.getFlags() | KeyEvent.FLAG_FALLBACK;
                KeyEvent fallbackEvent = KeyEvent.obtain(
                        event.getDownTime(), event.getEventTime(),
                        event.getAction(), mFallbackAction.keyCode,
                        event.getRepeatCount(), mFallbackAction.metaState,
                        event.getDeviceId(), event.getScanCode(),
                        flags, event.getSource(), null);
                int actions = interceptKeyBeforeQueueing(fallbackEvent, policyFlags, true);
                if ((actions & ACTION_PASS_TO_USER) != 0) {
                    if (!interceptKeyBeforeDispatching(win, fallbackEvent, policyFlags)) {
                        if (DEBUG_FALLBACK) {
                            Slog.d(TAG, "Performing fallback.");
                        }
                        return fallbackEvent;
                    }
                }
                fallbackEvent.recycle();
            }
        }

        if (DEBUG_FALLBACK) {
            Slog.d(TAG, "No fallback.");
        }
        return null;
    }

    private boolean getFallbackAction(KeyCharacterMap kcm, int keyCode, int metaState,
            FallbackAction outFallbackAction) {
        // Consult the key character map for specific fallback actions.
        // For example, map NUMPAD_1 to MOVE_HOME when NUMLOCK is not pressed.
        return kcm.getFallbackAction(keyCode, metaState, outFallbackAction);
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
        
        if ((fl & (FLAG_LAYOUT_IN_SCREEN | FLAG_FULLSCREEN | FLAG_LAYOUT_INSET_DECOR))
                == (FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR)) {
            contentInset.set(mCurLeft, mCurTop,
                    (mRestrictedScreenLeft+mRestrictedScreenWidth) - mCurRight,
                    (mRestrictedScreenTop+mRestrictedScreenHeight) - mCurBottom);
        } else {
            contentInset.setEmpty();
        }
    }
    
    /** {@inheritDoc} */
    public void beginLayoutLw(int displayWidth, int displayHeight, int displayRotation) {
        mUnrestrictedScreenLeft = mUnrestrictedScreenTop = 0;
        mUnrestrictedScreenWidth = displayWidth;
        mUnrestrictedScreenHeight = displayHeight;
        mRestrictedScreenLeft = mRestrictedScreenTop = 0;
        mRestrictedScreenWidth = displayWidth;
        mRestrictedScreenHeight = displayHeight;
        mDockLeft = mContentLeft = mCurLeft = 0;
        mDockTop = mContentTop = mCurTop = 0;
        mDockRight = mContentRight = mCurRight = displayWidth;
        mDockBottom = mContentBottom = mCurBottom = displayHeight;
        mDockLayer = 0x10000000;

        // start with the current dock rect, which will be (0,0,displayWidth,displayHeight)
        final Rect pf = mTmpParentFrame;
        final Rect df = mTmpDisplayFrame;
        final Rect vf = mTmpVisibleFrame;
        pf.left = df.left = vf.left = mDockLeft;
        pf.top = df.top = vf.top = mDockTop;
        pf.right = df.right = vf.right = mDockRight;
        pf.bottom = df.bottom = vf.bottom = mDockBottom;

        // decide where the status bar goes ahead of time
        if (mStatusBar != null) {
            Rect navr = null;
            if (mNavigationBar != null) {
                // Force the navigation bar to its appropriate place and
                // size.  We need to do this directly, instead of relying on
                // it to bubble up from the nav bar, because this needs to
                // change atomically with screen rotations.
                if (displayWidth < displayHeight) {
                    // Portrait screen; nav bar goes on bottom.
                    mTmpNavigationFrame.set(0, displayHeight-mNavigationBarHeight,
                            displayWidth, displayHeight);
                    if (mNavigationBar.isVisibleLw()) {
                        mDockBottom = mTmpNavigationFrame.top;
                        mRestrictedScreenHeight = mDockBottom - mDockTop;
                    }
                } else {
                    // Landscape screen; nav bar goes to the right.
                    mTmpNavigationFrame.set(displayWidth-mNavigationBarWidth, 0,
                            displayWidth, displayHeight);
                    if (mNavigationBar.isVisibleLw()) {
                        mDockRight = mTmpNavigationFrame.left;
                        mRestrictedScreenWidth = mDockRight - mDockLeft;
                    }
                }
                mNavigationBar.computeFrameLw(mTmpNavigationFrame, mTmpNavigationFrame,
                        mTmpNavigationFrame, mTmpNavigationFrame);
                if (DEBUG_LAYOUT) Log.i(TAG, "mNavigationBar frame: " + mTmpNavigationFrame);
            }
            if (DEBUG_LAYOUT) Log.i(TAG, String.format("mDock rect: (%d,%d - %d,%d)",
                    mDockLeft, mDockTop, mDockRight, mDockBottom));

            // apply navigation bar insets
            pf.left = df.left = vf.left = mDockLeft;
            pf.top = df.top = vf.top = mDockTop;
            pf.right = df.right = vf.right = mDockRight;
            pf.bottom = df.bottom = vf.bottom = mDockBottom;

            mStatusBar.computeFrameLw(pf, df, vf, vf);

            if (mStatusBar.isVisibleLw()) {
                // If the status bar is hidden, we don't want to cause
                // windows behind it to scroll.
                final Rect r = mStatusBar.getFrameLw();
                if (mStatusBarCanHide) {
                    // Status bar may go away, so the screen area it occupies
                    // is available to apps but just covering them when the
                    // status bar is visible.
                    if (mDockTop == r.top) mDockTop = r.bottom;
                    else if (mDockBottom == r.bottom) mDockBottom = r.top;
                    
                    mContentTop = mCurTop = mDockTop;
                    mContentBottom = mCurBottom = mDockBottom;
                    mContentLeft = mCurLeft = mDockLeft;
                    mContentRight = mCurRight = mDockRight;

                    if (DEBUG_LAYOUT) Log.v(TAG, "Status bar: " +
                        String.format(
                            "dock=[%d,%d][%d,%d] content=[%d,%d][%d,%d] cur=[%d,%d][%d,%d]",
                            mDockLeft, mDockTop, mDockRight, mDockBottom,
                            mContentLeft, mContentTop, mContentRight, mContentBottom,
                            mCurLeft, mCurTop, mCurRight, mCurBottom));
                } else {
                    // Status bar can't go away; the part of the screen it
                    // covers does not exist for anything behind it.
                    if (mRestrictedScreenTop == r.top) {
                        mRestrictedScreenTop = r.bottom;
                        mRestrictedScreenHeight -= (r.bottom-r.top);
                    } else if ((mRestrictedScreenHeight-mRestrictedScreenTop) == r.bottom) {
                        mRestrictedScreenHeight -= (r.bottom-r.top);
                    }

                    mContentTop = mCurTop = mDockTop = mRestrictedScreenTop;
                    mContentBottom = mCurBottom = mDockBottom
                            = mRestrictedScreenTop + mRestrictedScreenHeight;
                    if (DEBUG_LAYOUT) Log.v(TAG, "Status bar: restricted screen area: ("
                            + mRestrictedScreenLeft + ","
                            + mRestrictedScreenTop + ","
                            + (mRestrictedScreenLeft + mRestrictedScreenWidth) + ","
                            + (mRestrictedScreenTop + mRestrictedScreenHeight) + ")");
                }
            }
        }
    }

    void setAttachedWindowFrames(WindowState win, int fl, int adjust,
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
            if (adjust != SOFT_INPUT_ADJUST_RESIZE) {
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
        if (win == mStatusBar || win == mNavigationBar) {
            return;
        }

        final int fl = attrs.flags;
        final int sim = attrs.softInputMode;
        
        final Rect pf = mTmpParentFrame;
        final Rect df = mTmpDisplayFrame;
        final Rect cf = mTmpContentFrame;
        final Rect vf = mTmpVisibleFrame;
        
        final boolean hasNavBar = (mHasNavigationBar 
                && mNavigationBar != null && mNavigationBar.isVisibleLw());

        if (attrs.type == TYPE_INPUT_METHOD) {
            pf.left = df.left = cf.left = vf.left = mDockLeft;
            pf.top = df.top = cf.top = vf.top = mDockTop;
            pf.right = df.right = cf.right = vf.right = mDockRight;
            pf.bottom = df.bottom = cf.bottom = vf.bottom = mDockBottom;
            // IM dock windows always go to the bottom of the screen.
            attrs.gravity = Gravity.BOTTOM;
            mDockLayer = win.getSurfaceLayer();
        } else {
            final int adjust = sim & SOFT_INPUT_MASK_ADJUST;

            if ((fl & (FLAG_LAYOUT_IN_SCREEN | FLAG_FULLSCREEN | FLAG_LAYOUT_INSET_DECOR))
                    == (FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR)) {
                if (DEBUG_LAYOUT)
                    Log.v(TAG, "layoutWindowLw(" + attrs.getTitle() 
                            + "): IN_SCREEN, INSET_DECOR, !FULLSCREEN");
                // This is the case for a normal activity window: we want it
                // to cover all of the screen space, and it can take care of
                // moving its contents to account for screen decorations that
                // intrude into that space.
                if (attached != null) {
                    // If this window is attached to another, our display
                    // frame is the same as the one we are attached to.
                    setAttachedWindowFrames(win, fl, sim, attached, true, pf, df, cf, vf);
                } else {
                    if (attrs.type == TYPE_STATUS_BAR_PANEL
                            || attrs.type == TYPE_STATUS_BAR_SUB_PANEL) {
                        // Status bar panels are the only windows who can go on top of
                        // the status bar.  They are protected by the STATUS_BAR_SERVICE
                        // permission, so they have the same privileges as the status
                        // bar itself.
                        //
                        // However, they should still dodge the navigation bar if it exists.

                        pf.left = df.left = hasNavBar ? mDockLeft : mUnrestrictedScreenLeft;
                        pf.top = df.top = mUnrestrictedScreenTop;
                        pf.right = df.right = hasNavBar
                                            ? mRestrictedScreenLeft+mRestrictedScreenWidth
                                            : mUnrestrictedScreenLeft+mUnrestrictedScreenWidth;
                        pf.bottom = df.bottom = hasNavBar
                                              ? mRestrictedScreenTop+mRestrictedScreenHeight
                                              : mUnrestrictedScreenTop+mUnrestrictedScreenHeight;

                        if (DEBUG_LAYOUT) {
                            Log.v(TAG, String.format(
                                        "Laying out status bar window: (%d,%d - %d,%d)",
                                        pf.left, pf.top, pf.right, pf.bottom));
                        }
                    } else {
                        pf.left = df.left = mRestrictedScreenLeft;
                        pf.top = df.top = mRestrictedScreenTop;
                        pf.right = df.right = mRestrictedScreenLeft+mRestrictedScreenWidth;
                        pf.bottom = df.bottom = mRestrictedScreenTop+mRestrictedScreenHeight;
                    }
                    if (adjust != SOFT_INPUT_ADJUST_RESIZE) {
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
                    if (adjust != SOFT_INPUT_ADJUST_NOTHING) {
                        vf.left = mCurLeft;
                        vf.top = mCurTop;
                        vf.right = mCurRight;
                        vf.bottom = mCurBottom;
                    } else {
                        vf.set(cf);
                    }
                }
            } else if ((fl & FLAG_LAYOUT_IN_SCREEN) != 0) {
                if (DEBUG_LAYOUT)
                    Log.v(TAG, "layoutWindowLw(" + attrs.getTitle() + "): IN_SCREEN");
                // A window that has requested to fill the entire screen just
                // gets everything, period.
                if (attrs.type == TYPE_STATUS_BAR_PANEL
                        || attrs.type == TYPE_STATUS_BAR_SUB_PANEL) {
                    pf.left = df.left = cf.left = hasNavBar ? mDockLeft : mUnrestrictedScreenLeft;
                    pf.top = df.top = cf.top = mUnrestrictedScreenTop;
                    pf.right = df.right = cf.right = hasNavBar
                                        ? mRestrictedScreenLeft+mRestrictedScreenWidth
                                        : mUnrestrictedScreenLeft+mUnrestrictedScreenWidth;
                    pf.bottom = df.bottom = cf.bottom = hasNavBar
                                          ? mRestrictedScreenTop+mRestrictedScreenHeight
                                          : mUnrestrictedScreenTop+mUnrestrictedScreenHeight;

                    if (DEBUG_LAYOUT) {
                        Log.v(TAG, String.format(
                                    "Laying out IN_SCREEN status bar window: (%d,%d - %d,%d)",
                                    pf.left, pf.top, pf.right, pf.bottom));
                    }
                } else if (attrs.type == TYPE_NAVIGATION_BAR) {
                    // The navigation bar has Real Ultimate Power.
                    pf.left = df.left = mUnrestrictedScreenLeft;
                    pf.top = df.top = mUnrestrictedScreenTop;
                    pf.right = df.right = mUnrestrictedScreenLeft+mUnrestrictedScreenWidth;
                    pf.bottom = df.bottom = mUnrestrictedScreenTop+mUnrestrictedScreenHeight;
                    if (DEBUG_LAYOUT) {
                        Log.v(TAG, String.format(
                                    "Laying out navigation bar window: (%d,%d - %d,%d)",
                                    pf.left, pf.top, pf.right, pf.bottom));
                    }
                } else if (attrs.type == TYPE_SECURE_SYSTEM_OVERLAY
                        && ((fl & FLAG_FULLSCREEN) != 0)) {
                    // Fullscreen secure system overlays get what they ask for.
                    pf.left = df.left = mUnrestrictedScreenLeft;
                    pf.top = df.top = mUnrestrictedScreenTop;
                    pf.right = df.right = mUnrestrictedScreenLeft+mUnrestrictedScreenWidth;
                    pf.bottom = df.bottom = mUnrestrictedScreenTop+mUnrestrictedScreenHeight;
                } else {
                    pf.left = df.left = cf.left = mRestrictedScreenLeft;
                    pf.top = df.top = cf.top = mRestrictedScreenTop;
                    pf.right = df.right = cf.right = mRestrictedScreenLeft+mRestrictedScreenWidth;
                    pf.bottom = df.bottom = cf.bottom
                            = mRestrictedScreenTop+mRestrictedScreenHeight;
                }
                if (adjust != SOFT_INPUT_ADJUST_NOTHING) {
                    vf.left = mCurLeft;
                    vf.top = mCurTop;
                    vf.right = mCurRight;
                    vf.bottom = mCurBottom;
                } else {
                    vf.set(cf);
                }
            } else if (attached != null) {
                if (DEBUG_LAYOUT)
                    Log.v(TAG, "layoutWindowLw(" + attrs.getTitle() + "): attached to " + attached);
                // A child window should be placed inside of the same visible
                // frame that its parent had.
                setAttachedWindowFrames(win, fl, adjust, attached, false, pf, df, cf, vf);
            } else {
                if (DEBUG_LAYOUT)
                    Log.v(TAG, "layoutWindowLw(" + attrs.getTitle() + "): normal window");
                // Otherwise, a normal window must be placed inside the content
                // of all screen decorations.
                if (attrs.type == TYPE_STATUS_BAR_PANEL) {
                    // Status bar panels are the only windows who can go on top of
                    // the status bar.  They are protected by the STATUS_BAR_SERVICE
                    // permission, so they have the same privileges as the status
                    // bar itself.
                    pf.left = df.left = cf.left = mRestrictedScreenLeft;
                    pf.top = df.top = cf.top = mRestrictedScreenTop;
                    pf.right = df.right = cf.right = mRestrictedScreenLeft+mRestrictedScreenWidth;
                    pf.bottom = df.bottom = cf.bottom
                            = mRestrictedScreenTop+mRestrictedScreenHeight;
                } else {
                    pf.left = mContentLeft;
                    pf.top = mContentTop;
                    pf.right = mContentRight;
                    pf.bottom = mContentBottom;
                    if (adjust != SOFT_INPUT_ADJUST_RESIZE) {
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
                    if (adjust != SOFT_INPUT_ADJUST_NOTHING) {
                        vf.left = mCurLeft;
                        vf.top = mCurTop;
                        vf.right = mCurRight;
                        vf.bottom = mCurBottom;
                    } else {
                        vf.set(cf);
                    }
                }
            }
        }
        
        if ((fl & FLAG_LAYOUT_NO_LIMITS) != 0) {
            df.left = df.top = cf.left = cf.top = vf.left = vf.top = -10000;
            df.right = df.bottom = cf.right = cf.bottom = vf.right = vf.bottom = 10000;
        }

        if (DEBUG_LAYOUT) Log.v(TAG, "Compute frame " + attrs.getTitle()
                + ": sim=#" + Integer.toHexString(sim)
                + " attach=" + attached + " type=" + attrs.type 
                + String.format(" flags=0x%08x", fl)
                + " pf=" + pf.toShortString() + " df=" + df.toShortString()
                + " cf=" + cf.toShortString() + " vf=" + vf.toShortString());
        
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
        mTopAppWindowState = null;
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
                    && attrs.x == 0 && attrs.y == 0
                    && attrs.width == WindowManager.LayoutParams.MATCH_PARENT
                    && attrs.height == WindowManager.LayoutParams.MATCH_PARENT) {
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
        if (mTopAppWindowState == null && win.isVisibleOrBehindKeyguardLw()) {
            if (attrs.type >= FIRST_APPLICATION_WINDOW
                    && attrs.type <= LAST_APPLICATION_WINDOW) {
                mTopAppWindowState = win;
            }
        }
    }

    /** {@inheritDoc} */
    public int finishAnimationLw() {
        int changes = 0;
        boolean topIsFullscreen = false;

        final WindowManager.LayoutParams lp = (mTopFullscreenOpaqueWindowState != null)
                ? mTopFullscreenOpaqueWindowState.getAttrs()
                : null;

        if (mStatusBar != null) {
            if (localLOGV) Log.i(TAG, "force=" + mForceStatusBar
                    + " top=" + mTopFullscreenOpaqueWindowState);
            if (mForceStatusBar) {
                if (DEBUG_LAYOUT) Log.v(TAG, "Showing status bar");
                if (mStatusBar.showLw(true)) changes |= FINISH_LAYOUT_REDO_LAYOUT;
            } else if (mTopFullscreenOpaqueWindowState != null) {
                if (localLOGV) {
                    Log.d(TAG, "frame: " + mTopFullscreenOpaqueWindowState.getFrameLw()
                            + " shown frame: " + mTopFullscreenOpaqueWindowState.getShownFrameLw());
                    Log.d(TAG, "attr: " + mTopFullscreenOpaqueWindowState.getAttrs()
                            + " lp.flags=0x" + Integer.toHexString(lp.flags));
                }
                topIsFullscreen = (lp.flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0;
                // The subtle difference between the window for mTopFullscreenOpaqueWindowState
                // and mTopIsFullscreen is that that mTopIsFullscreen is set only if the window
                // has the FLAG_FULLSCREEN set.  Not sure if there is another way that to be the
                // case though.
                if (topIsFullscreen) {
                    if (mStatusBarCanHide) {
                        if (DEBUG_LAYOUT) Log.v(TAG, "Hiding status bar");
                        if (mStatusBar.hideLw(true)) {
                            changes |= FINISH_LAYOUT_REDO_LAYOUT;

                            mHandler.post(new Runnable() { public void run() {
                                if (mStatusBarService != null) {
                                    try {
                                        mStatusBarService.collapse();
                                    } catch (RemoteException ex) {}
                                }
                            }});
                        }
                    } else if (localLOGV) {
                        Log.v(TAG, "Preventing status bar from hiding by policy");
                    }
                } else {
                    if (DEBUG_LAYOUT) Log.v(TAG, "Showing status bar");
                    if (mStatusBar.showLw(true)) changes |= FINISH_LAYOUT_REDO_LAYOUT;
                }
            }
        }

        mTopIsFullscreen = topIsFullscreen;

        if (mTopAppWindowState != null && mTopAppWindowState != mLastTopAppWindowState) {
            mLastTopAppWindowState = mTopAppWindowState;

            final boolean topNeedsMenu = (mTopAppWindowState.getAttrs().flags
                    & WindowManager.LayoutParams.FLAG_NEEDS_MENU_KEY) != 0;

            mHandler.post(new Runnable() {
                    public void run() {
                        if (mStatusBarService == null) {
                            // This is the one that can not go away, but it doesn't come up
                            // before the window manager does, so don't fail if it doesn't
                            // exist. This works as long as no fullscreen windows come up
                            // before the status bar service does.
                            mStatusBarService = IStatusBarService.Stub.asInterface(
                                    ServiceManager.getService("statusbar"));
                        }
                        final IStatusBarService sbs = mStatusBarService;
                        if (mStatusBarService != null) {
                            try {
                                sbs.topAppWindowChanged(topNeedsMenu);
                            } catch (RemoteException e) {
                                // This should be impossible because we're in the same process.
                                mStatusBarService = null;
                            }
                        }
                    }
                });
        }

        // Hide the key guard if a visible window explicitly specifies that it wants to be displayed
        // when the screen is locked
        if (mKeyguard != null) {
            if (localLOGV) Log.v(TAG, "finishAnimationLw::mHideKeyguard="+mHideLockScreen);
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

        updateSystemUiVisibility();

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
        if (false) {
            // Don't do this on the tablet, since the system bar never completely
            // covers the screen, and with all its transparency this will
            // incorrectly think it does cover it when it doesn't.  We'll revisit
            // this later when we re-do the phone status bar.
            if (mStatusBar != null && mStatusBar.isVisibleLw()) {
                RectF rect = new RectF(mStatusBar.getShownFrameLw());
                for (int i=mStatusBarPanels.size()-1; i>=0; i--) {
                    WindowState w = mStatusBarPanels.get(i);
                    if (w.isVisibleLw()) {
                        rect.union(w.getShownFrameLw());
                    }
                }
                final int insetw = mRestrictedScreenWidth/10;
                final int inseth = mRestrictedScreenHeight/10;
                if (rect.contains(insetw, inseth, mRestrictedScreenWidth-insetw,
                            mRestrictedScreenHeight-inseth)) {
                    // All of the status bar windows put together cover the
                    // screen, so the app can't be seen.  (Note this test doesn't
                    // work if the rects of these windows are at off offsets or
                    // sizes, causing gaps in the rect union we have computed.)
                    return false;
                }
            }
        }
        return true;
    }

    public void focusChanged(WindowState lastFocus, WindowState newFocus) {
        mFocusedWindow = newFocus;
        updateSystemUiVisibility();
    }

    /** {@inheritDoc} */
    public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen) {
        // lid changed state
        mLidOpen = lidOpen ? LID_OPEN : LID_CLOSED;
        boolean awakeNow = mKeyguardMediator.doLidChangeTq(lidOpen);
        updateRotation(Surface.FLAGS_ORIENTATION_ANIMATION_DISABLE);
        if (awakeNow) {
            // If the lid is opening and we don't have to keep the
            // keyguard up, then we can turn on the screen
            // immediately.
            mKeyguardMediator.pokeWakelock();
        } else if (keyguardIsShowingTq()) {
            if (lidOpen) {
                // If we are opening the lid and not hiding the
                // keyguard, then we need to have it turn on the
                // screen once it is shown.
                mKeyguardMediator.onWakeKeyWhenKeyguardShowingTq(
                        KeyEvent.KEYCODE_POWER);
            }
        } else {
            // Light up the keyboard if we are sliding up.
            if (lidOpen) {
                mPowerManager.userActivity(SystemClock.uptimeMillis(), false,
                        LocalPowerManager.BUTTON_EVENT);
            } else {
                mPowerManager.userActivity(SystemClock.uptimeMillis(), false,
                        LocalPowerManager.OTHER_EVENT);
            }
        }
    }

    void setHdmiPlugged(boolean plugged) {
        if (mHdmiPlugged != plugged) {
            mHdmiPlugged = plugged;
            updateRotation(Surface.FLAGS_ORIENTATION_ANIMATION_DISABLE);
            Intent intent = new Intent(ACTION_HDMI_PLUGGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(EXTRA_HDMI_PLUGGED_STATE, plugged);
            mContext.sendStickyBroadcast(intent);
        }
    }

    void initializeHdmiState() {
        boolean plugged = false;
        // watch for HDMI plug messages if the hdmi switch exists
        if (new File("/sys/devices/virtual/switch/hdmi/state").exists()) {
            mHDMIObserver.startObserving("DEVPATH=/devices/virtual/switch/hdmi");

            final String filename = "/sys/class/switch/hdmi/state";
            FileReader reader = null;
            try {
                reader = new FileReader(filename);
                char[] buf = new char[15];
                int n = reader.read(buf);
                if (n > 1) {
                    plugged = 0 != Integer.parseInt(new String(buf, 0, n-1));
                }
            } catch (IOException ex) {
                Slog.w(TAG, "Couldn't read hdmi state from " + filename + ": " + ex);
            } catch (NumberFormatException ex) {
                Slog.w(TAG, "Couldn't read hdmi state from " + filename + ": " + ex);
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException ex) {
                    }
                }
            }
        }
        // This dance forces the code in setHdmiPlugged to run.
        // Always do this so the sticky intent is stuck (to false) if there is no hdmi.
        mHdmiPlugged = !plugged;
        setHdmiPlugged(!mHdmiPlugged);
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
        IAudioService audioService = getAudioService();
        if (audioService == null) {
            return;
        }
        try {
            // since audio is playing, we shouldn't have to hold a wake lock
            // during the call, but we do it as a precaution for the rare possibility
            // that the music stops right before we call this
            // TODO: Actually handle MUTE.
            mBroadcastWakeLock.acquire();
            audioService.adjustStreamVolume(stream,
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

    final Object mScreenshotLock = new Object();
    ServiceConnection mScreenshotConnection = null;
    Runnable mScreenshotTimeout = null;

    void finishScreenshotLSS(ServiceConnection conn) {
        if (mScreenshotConnection == conn) {
            mContext.unbindService(conn);
            mScreenshotConnection = null;
            if (mScreenshotTimeout != null) {
                mHandler.removeCallbacks(mScreenshotTimeout);
                mScreenshotTimeout = null;
            }
        }
    }

    private void takeScreenshot() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (mScreenshotLock) {
                    if (mScreenshotConnection != null) {
                        return;
                    }
                    ComponentName cn = new ComponentName("com.android.systemui",
                            "com.android.systemui.screenshot.TakeScreenshotService");
                    Intent intent = new Intent();
                    intent.setComponent(cn);
                    ServiceConnection conn = new ServiceConnection() {
                        @Override
                        public void onServiceConnected(ComponentName name, IBinder service) {
                            synchronized (mScreenshotLock) {
                                if (mScreenshotConnection != this) {
                                    return;
                                }
                                Messenger messenger = new Messenger(service);
                                Message msg = Message.obtain(null, 1);
                                final ServiceConnection myConn = this;
                                Handler h = new Handler(mHandler.getLooper()) {
                                    @Override
                                    public void handleMessage(Message msg) {
                                        synchronized (mScreenshotLock) {
                                            finishScreenshotLSS(myConn);
                                        }
                                    }
                                };
                                msg.replyTo = new Messenger(h);
                                try {
                                    messenger.send(msg);
                                } catch (RemoteException e) {
                                }
                            }
                        }
                        @Override
                        public void onServiceDisconnected(ComponentName name) {}
                    };
                    if (mContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
                        mScreenshotConnection = conn;
                        mScreenshotTimeout = new Runnable() {
                            @Override public void run() {
                                synchronized (mScreenshotLock) {
                                    if (mScreenshotConnection != null) {
                                        finishScreenshotLSS(mScreenshotConnection);
                                    }
                                }
                            }
    
                        };
                        mHandler.postDelayed(mScreenshotTimeout, 10000);
                    }
                }
            }
        });
    }

    /** {@inheritDoc} */
    @Override
    public int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags, boolean isScreenOn) {
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final boolean canceled = event.isCanceled();
        final int keyCode = event.getKeyCode();

        final boolean isInjected = (policyFlags & WindowManagerPolicy.FLAG_INJECTED) != 0;

        // If screen is off then we treat the case where the keyguard is open but hidden
        // the same as if it were open and in front.
        // This will prevent any keys other than the power button from waking the screen
        // when the keyguard is hidden by another activity.
        final boolean keyguardActive = (isScreenOn ?
                                        mKeyguardMediator.isShowingAndNotHidden() :
                                        mKeyguardMediator.isShowing());

        if (!mSystemBooted) {
            // If we have not yet booted, don't let key events do anything.
            return 0;
        }

        if (false) {
            Log.d(TAG, "interceptKeyTq keycode=" + keyCode
                  + " screenIsOn=" + isScreenOn + " keyguardActive=" + keyguardActive);
        }

        if (down && (policyFlags & WindowManagerPolicy.FLAG_VIRTUAL) != 0
                && event.getRepeatCount() == 0) {
            performHapticFeedbackLw(null, HapticFeedbackConstants.VIRTUAL_KEY, false);
        }

        // Basic policy based on screen state and keyguard.
        // FIXME: This policy isn't quite correct.  We shouldn't care whether the screen
        //        is on or off, really.  We should care about whether the device is in an
        //        interactive state or is in suspend pretending to be "off".
        //        The primary screen might be turned off due to proximity sensor or
        //        because we are presenting media on an auxiliary screen or remotely controlling
        //        the device some other way (which is why we have an exemption here for injected
        //        events).
        int result;
        if (isScreenOn || isInjected) {
            // When the screen is on or if the key is injected pass the key to the application.
            result = ACTION_PASS_TO_USER;
        } else {
            // When the screen is off and the key is not injected, determine whether
            // to wake the device but don't pass the key to the application.
            result = 0;

            final boolean isWakeKey = (policyFlags
                    & (WindowManagerPolicy.FLAG_WAKE | WindowManagerPolicy.FLAG_WAKE_DROPPED)) != 0;
            if (down && isWakeKey) {
                if (keyguardActive) {
                    // If the keyguard is showing, let it decide what to do with the wake key.
                    mKeyguardMediator.onWakeKeyWhenKeyguardShowingTq(keyCode);
                } else {
                    // Otherwise, wake the device ourselves.
                    result |= ACTION_POKE_USER_ACTIVITY;
                }
            }
        }

        // Handle special keys.
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (down) {
                    if (isScreenOn) {
                        // If the power key down was already triggered, take the screenshot
                        if (mPowerDownTriggered) {
                            // Dismiss the power-key longpress
                            mHandler.removeCallbacks(mPowerLongPress);
                            mPowerKeyHandled = true;

                            // Take the screenshot
                            takeScreenshot();

                            // Prevent the event from being passed through to the current activity
                            result &= ~ACTION_PASS_TO_USER;
                            break;
                        }
                        mVolumeDownTriggered = true;
                    }
                } else {
                    mVolumeDownTriggered = false;
                }
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_MUTE: {
                if (down) {
                    ITelephony telephonyService = getTelephonyService();
                    if (telephonyService != null) {
                        try {
                            if (telephonyService.isRinging()) {
                                // If an incoming call is ringing, either VOLUME key means
                                // "silence ringer".  We handle these keys here, rather than
                                // in the InCallScreen, to make sure we'll respond to them
                                // even if the InCallScreen hasn't come to the foreground yet.
                                // Look for the DOWN event here, to agree with the "fallback"
                                // behavior in the InCallScreen.
                                Log.i(TAG, "interceptKeyBeforeQueueing:"
                                      + " VOLUME key-down while ringing: Silence ringer!");

                                // Silence the ringer.  (It's safe to call this
                                // even if the ringer has already been silenced.)
                                telephonyService.silenceRinger();

                                // And *don't* pass this key thru to the current activity
                                // (which is probably the InCallScreen.)
                                result &= ~ACTION_PASS_TO_USER;
                                break;
                            }
                            if (telephonyService.isOffhook()
                                    && (result & ACTION_PASS_TO_USER) == 0) {
                                // If we are in call but we decided not to pass the key to
                                // the application, handle the volume change here.
                                handleVolumeKey(AudioManager.STREAM_VOICE_CALL, keyCode);
                                break;
                            }
                        } catch (RemoteException ex) {
                            Log.w(TAG, "ITelephony threw RemoteException", ex);
                        }
                    }

                    if (isMusicActive() && (result & ACTION_PASS_TO_USER) == 0) {
                        // If music is playing but we decided not to pass the key to the
                        // application, handle the volume change here.
                        handleVolumeKey(AudioManager.STREAM_MUSIC, keyCode);
                        break;
                    }
                }
                break;
            }

            case KeyEvent.KEYCODE_ENDCALL: {
                result &= ~ACTION_PASS_TO_USER;
                if (down) {
                    ITelephony telephonyService = getTelephonyService();
                    boolean hungUp = false;
                    if (telephonyService != null) {
                        try {
                            hungUp = telephonyService.endCall();
                        } catch (RemoteException ex) {
                            Log.w(TAG, "ITelephony threw RemoteException", ex);
                        }
                    }
                    interceptPowerKeyDown(!isScreenOn || hungUp);
                } else {
                    if (interceptPowerKeyUp(canceled)) {
                        if ((mEndcallBehavior
                                & Settings.System.END_BUTTON_BEHAVIOR_HOME) != 0) {
                            if (goHome()) {
                                break;
                            }
                        }
                        if ((mEndcallBehavior
                                & Settings.System.END_BUTTON_BEHAVIOR_SLEEP) != 0) {
                            result = (result & ~ACTION_POKE_USER_ACTIVITY) | ACTION_GO_TO_SLEEP;
                        }
                    }
                }
                break;
            }

            case KeyEvent.KEYCODE_POWER: {
                result &= ~ACTION_PASS_TO_USER;
                if (down) {
                    if (isScreenOn) {
                        // If the volume down key has been triggered, then just take the screenshot
                        if (mVolumeDownTriggered) {
                            // Take the screenshot
                            takeScreenshot();
                            mPowerKeyHandled = true;

                            // Prevent the event from being passed through to the current activity
                            break;
                        }
                        mPowerDownTriggered = true;
                    }

                    ITelephony telephonyService = getTelephonyService();
                    boolean hungUp = false;
                    if (telephonyService != null) {
                        try {
                            if (telephonyService.isRinging()) {
                                // Pressing Power while there's a ringing incoming
                                // call should silence the ringer.
                                telephonyService.silenceRinger();
                            } else if ((mIncallPowerBehavior
                                    & Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR_HANGUP) != 0
                                    && telephonyService.isOffhook()) {
                                // Otherwise, if "Power button ends call" is enabled,
                                // the Power button will hang up any current active call.
                                hungUp = telephonyService.endCall();
                            }
                        } catch (RemoteException ex) {
                            Log.w(TAG, "ITelephony threw RemoteException", ex);
                        }
                    }
                    interceptPowerKeyDown(!isScreenOn || hungUp);
                } else {
                    mPowerDownTriggered = false;
                    if (interceptPowerKeyUp(canceled)) {
                        result = (result & ~ACTION_POKE_USER_ACTIVITY) | ACTION_GO_TO_SLEEP;
                    }
                }
                break;
            }

            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (down) {
                    ITelephony telephonyService = getTelephonyService();
                    if (telephonyService != null) {
                        try {
                            if (!telephonyService.isIdle()) {
                                // Suppress PLAY/PAUSE toggle when phone is ringing or in-call
                                // to avoid music playback.
                                break;
                            }
                        } catch (RemoteException ex) {
                            Log.w(TAG, "ITelephony threw RemoteException", ex);
                        }
                    }
                }
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: {
                if ((result & ACTION_PASS_TO_USER) == 0) {
                    // Only do this if we would otherwise not pass it to the user. In that
                    // case, the PhoneWindow class will do the same thing, except it will
                    // only do it if the showing app doesn't process the key on its own.
                    mBroadcastWakeLock.acquire();
                    mHandler.post(new PassHeadsetKey(new KeyEvent(event)));
                }
                break;
            }

            case KeyEvent.KEYCODE_CALL: {
                if (down) {
                    ITelephony telephonyService = getTelephonyService();
                    if (telephonyService != null) {
                        try {
                            if (telephonyService.isRinging()) {
                                Log.i(TAG, "interceptKeyBeforeQueueing:"
                                      + " CALL key-down while ringing: Answer the call!");
                                telephonyService.answerRingingCall();

                                // And *don't* pass this key thru to the current activity
                                // (which is presumably the InCallScreen.)
                                result &= ~ACTION_PASS_TO_USER;
                            }
                        } catch (RemoteException ex) {
                            Log.w(TAG, "ITelephony threw RemoteException", ex);
                        }
                    }
                }
                break;
            }
        }
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public int interceptMotionBeforeQueueingWhenScreenOff(int policyFlags) {
        int result = 0;

        final boolean isWakeMotion = (policyFlags
                & (WindowManagerPolicy.FLAG_WAKE | WindowManagerPolicy.FLAG_WAKE_DROPPED)) != 0;
        if (isWakeMotion) {
            if (mKeyguardMediator.isShowing()) {
                // If the keyguard is showing, let it decide what to do with the wake motion.
                mKeyguardMediator.onWakeMotionWhenKeyguardShowingTq();
            } else {
                // Otherwise, wake the device ourselves.
                result |= ACTION_POKE_USER_ACTIVITY;
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
    public void screenTurnedOff(int why) {
        EventLog.writeEvent(70000, 0);
        synchronized (mLock) {
            mScreenOn = false;
        }
        mKeyguardMediator.onScreenTurnedOff(why);
        synchronized (mLock) {
            updateOrientationListenerLp();
            updateLockScreenTimeout();
            updateScreenSaverTimeoutLocked();
        }
        try {
            mWindowManager.waitForAllDrawn();
        } catch (RemoteException e) {
        }
        // Wait for one frame to give surface flinger time to do its
        // compositing.  Yes this is a hack, but I am really not up right now for
        // implementing some mechanism to block until SF is done. :p
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
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
            updateScreenSaverTimeoutLocked();
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
    public boolean isKeyguardLocked() {
        return keyguardOn();
    }

    /** {@inheritDoc} */
    public boolean isKeyguardSecure() {
        return mKeyguardMediator.isSecure();
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

        if (false) {
            Slog.v(TAG, "rotationForOrientationLw(orient="
                        + orientation + ", last=" + lastRotation
                        + "); user=" + mUserRotation + " "
                        + ((mUserRotationMode == WindowManagerPolicy.USER_ROTATION_LOCKED)
                            ? "USER_ROTATION_LOCKED" : "")
                        );
        }

        synchronized (mLock) {
            switch (orientation) {
                case ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
                    //always return portrait if orientation set to portrait
                    return mPortraitRotation;
                case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
                    //always return landscape if orientation set to landscape
                    return mLandscapeRotation;
                case ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT:
                    //always return portrait if orientation set to portrait
                    return mUpsideDownRotation;
                case ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE:
                    //always return seascape if orientation set to reverse landscape
                    return mSeascapeRotation;
                case ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:
                    //return either landscape rotation based on the sensor
                    return getCurrentLandscapeRotation(lastRotation);
                case ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:
                    return getCurrentPortraitRotation(lastRotation);
            }

            // case for nosensor meaning ignore sensor and consider only lid
            // or orientation sensor disabled
            //or case.unspecified
            if (mHdmiPlugged) {
                return mLandscapeRotation;
            } else if (mLidOpen == LID_OPEN) {
                return mLidOpenRotation;
            } else if (mDockMode == Intent.EXTRA_DOCK_STATE_CAR && mCarDockRotation >= 0) {
                return mCarDockRotation;
            } else if (mDockMode == Intent.EXTRA_DOCK_STATE_DESK && mDeskDockRotation >= 0) {
                return mDeskDockRotation;
            } else if (mUserRotationMode == WindowManagerPolicy.USER_ROTATION_LOCKED) {
                return mUserRotation;
            } else {
                if (useSensorForOrientationLp(orientation)) {
                    // Disable 180 degree rotation unless allowed by default for the device
                    // or explicitly requested by the application.
                    int rotation = mOrientationListener.getCurrentRotation(lastRotation);
                    if (rotation == Surface.ROTATION_180
                            && !mAllowAllRotations
                            && orientation != ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR) {
                        return lastRotation;
                    }
                    return rotation;
                }
                return Surface.ROTATION_0;
            }
        }
    }

    public int getLockedRotationLw() {
        synchronized (mLock) {
            if (false) {
                // Not yet working.
                if (mHdmiPlugged) {
                    return Surface.ROTATION_0;
                } else if (mLidOpen == LID_OPEN) {
                    return mLidOpenRotation;
                } else if (mDockMode == Intent.EXTRA_DOCK_STATE_CAR && mCarDockRotation >= 0) {
                    return mCarDockRotation;
                } else if (mDockMode == Intent.EXTRA_DOCK_STATE_DESK && mDeskDockRotation >= 0) {
                    return mDeskDockRotation;
                } else if (mUserRotationMode == WindowManagerPolicy.USER_ROTATION_LOCKED) {
                    return mUserRotation;
                }
            }
            return -1;
        }
    }

    private int getCurrentLandscapeRotation(int lastRotation) {
        // if the user has locked rotation, we ignore the sensor 
        if (mUserRotationMode == WindowManagerPolicy.USER_ROTATION_LOCKED) {
            if (isLandscapeOrSeascape(mUserRotation)) {
                return mUserRotation;
            } else {
                // it seems odd to obey the sensor at all if rotation lock is enabled
                return mLandscapeRotation;
            }
        }

        int sensorRotation = mOrientationListener.getCurrentRotation(lastRotation);
        if (isLandscapeOrSeascape(sensorRotation)) {
            return sensorRotation;
        }
        // try to preserve the old rotation if it was landscape
        if (isLandscapeOrSeascape(lastRotation)) {
            return lastRotation;
        }
        // default to one of the primary landscape rotation
        return mLandscapeRotation;
    }

    private boolean isLandscapeOrSeascape(int sensorRotation) {
        return sensorRotation == mLandscapeRotation || sensorRotation == mSeascapeRotation;
    }

    private int getCurrentPortraitRotation(int lastRotation) {
        // if the user has locked rotation, we ignore the sensor 
        if (mUserRotationMode == WindowManagerPolicy.USER_ROTATION_LOCKED) {
            if (isAnyPortrait(mUserRotation)) {
                return mUserRotation;
            } else {
                // it seems odd to obey the sensor at all if rotation lock is enabled
                return mPortraitRotation;
            }
        }

        int sensorRotation = mOrientationListener.getCurrentRotation(lastRotation);
        if (isAnyPortrait(sensorRotation)) {
            return sensorRotation;
        }
        // try to preserve the old rotation if it was portrait
        if (isAnyPortrait(lastRotation)) {
            return lastRotation;
        }
        // default to one of the primary portrait rotations
        return mPortraitRotation;
    }

    private boolean isAnyPortrait(int sensorRotation) {
        return sensorRotation == mPortraitRotation || sensorRotation == mUpsideDownRotation;
    }


    // User rotation: to be used when all else fails in assigning an orientation to the device
    public void setUserRotationMode(int mode, int rot) {
        ContentResolver res = mContext.getContentResolver();

        // mUserRotationMode and mUserRotation will be assigned by the content observer
        if (mode == WindowManagerPolicy.USER_ROTATION_LOCKED) {
            Settings.System.putInt(res,
                    Settings.System.USER_ROTATION,
                    rot);
            Settings.System.putInt(res,
                    Settings.System.ACCELEROMETER_ROTATION,
                    0);
        } else {
            Settings.System.putInt(res,
                    Settings.System.ACCELEROMETER_ROTATION,
                    1);
        }
    }

    public boolean detectSafeMode() {
        try {
            int menuState = mWindowManager.getKeycodeState(KeyEvent.KEYCODE_MENU);
            int sState = mWindowManager.getKeycodeState(KeyEvent.KEYCODE_S);
            int dpadState = mWindowManager.getDPadKeycodeState(KeyEvent.KEYCODE_DPAD_CENTER);
            int trackballState = mWindowManager.getTrackballScancodeState(BTN_MOUSE);
            int volumeDownState = mWindowManager.getKeycodeState(KeyEvent.KEYCODE_VOLUME_DOWN);
            mSafeMode = menuState > 0 || sState > 0 || dpadState > 0 || trackballState > 0
                    || volumeDownState > 0;
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
    public void systemBooted() {
        synchronized (mLock) {
            mSystemBooted = true;
        }
    }

    ProgressDialog mBootMsgDialog = null;

    /** {@inheritDoc} */
    public void showBootMessage(final CharSequence msg, final boolean always) {
        mHandler.post(new Runnable() {
            @Override public void run() {
                if (mBootMsgDialog == null) {
                    mBootMsgDialog = new ProgressDialog(mContext) {
                        // This dialog will consume all events coming in to
                        // it, to avoid it trying to do things too early in boot.
                        @Override public boolean dispatchKeyEvent(KeyEvent event) {
                            return true;
                        }
                        @Override public boolean dispatchKeyShortcutEvent(KeyEvent event) {
                            return true;
                        }
                        @Override public boolean dispatchTouchEvent(MotionEvent ev) {
                            return true;
                        }
                        @Override public boolean dispatchTrackballEvent(MotionEvent ev) {
                            return true;
                        }
                        @Override public boolean dispatchGenericMotionEvent(MotionEvent ev) {
                            return true;
                        }
                        @Override public boolean dispatchPopulateAccessibilityEvent(
                                AccessibilityEvent event) {
                            return true;
                        }
                    };
                    mBootMsgDialog.setTitle(R.string.android_upgrading_title);
                    mBootMsgDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                    mBootMsgDialog.setIndeterminate(true);
                    mBootMsgDialog.getWindow().setType(
                            WindowManager.LayoutParams.TYPE_BOOT_PROGRESS);
                    mBootMsgDialog.getWindow().addFlags(
                            WindowManager.LayoutParams.FLAG_DIM_BEHIND
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
                    mBootMsgDialog.getWindow().setDimAmount(1);
                    mBootMsgDialog.setCancelable(false);
                    mBootMsgDialog.show();
                }
                mBootMsgDialog.setMessage(msg);
            }
        });
    }

    /** {@inheritDoc} */
    public void hideBootMessages() {
        mHandler.post(new Runnable() {
            @Override public void run() {
                if (mBootMsgDialog != null) {
                    mBootMsgDialog.dismiss();
                    mBootMsgDialog = null;
                }
            }
        });
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

        if (mStatusBarService != null) {
            try {
                mStatusBarService.userActivity();
            } catch (RemoteException ex) {}
        }

        synchronized (mLock) {
            updateScreenSaverTimeoutLocked();
        }
    }

    Runnable mScreenSaverActivator = null;
    /*new Runnable() {
        public void run() {
            synchronized (this) {
                if (!(mScreenSaverEnabled && mScreenOn)) {
                    Log.w(TAG, "mScreenSaverActivator ran, but the screensaver should not be showing. Who's driving this thing?");
                    return;
                }

                if (localLOGV) Log.v(TAG, "mScreenSaverActivator entering dreamland");
                try {
                    String component = Settings.System.getString(
                            mContext.getContentResolver(), Settings.Secure.DREAM_COMPONENT);
                    if (component != null) {
                        ComponentName cn = ComponentName.unflattenFromString(component);
                        Intent intent = new Intent(Intent.ACTION_MAIN)
                            .setComponent(cn)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                                | Intent.FLAG_ACTIVITY_NO_USER_ACTION
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        mContext.startActivity(intent);
                    } else {
                        Log.e(TAG, "Couldn't start screen saver: none selected");
                    }
                } catch (android.content.ActivityNotFoundException exc) {
                    // no screensaver? give up
                    Log.e(TAG, "Couldn't start screen saver: none installed");
                }
            }
        }
    };
    */

    // Must call while holding mLock
    private void updateScreenSaverTimeoutLocked() {
        if (mScreenSaverActivator == null) return;

        synchronized (mScreenSaverActivator) {
            mHandler.removeCallbacks(mScreenSaverActivator);
            if (mScreenSaverEnabled && mScreenOn && mScreenSaverTimeout > 0) {
                if (localLOGV)
                    Log.v(TAG, "scheduling screensaver for " + mScreenSaverTimeout + "ms from now");
                mHandler.postDelayed(mScreenSaverActivator, mScreenSaverTimeout);
            } else {
                if (localLOGV) {
                    if (mScreenSaverTimeout == 0)
                        Log.v(TAG, "screen saver disabled by user");
                    else if (!mScreenOn)
                        Log.v(TAG, "screen saver disabled while screen off");
                    else
                        Log.v(TAG, "screen saver disabled by wakelock");
                }
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
        mPowerManager.setKeyboardVisibility(mLidOpen == LID_OPEN);
        int rotation = Surface.ROTATION_0;
        if (mHdmiPlugged) {
            rotation = Surface.ROTATION_0;
        } else if (mLidOpen == LID_OPEN) {
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
                                        null, 0, null, null, 0, true /* onlyIfNeeded*/, false,
                                        null, null, false);
                        if (result == IActivityManager.START_RETURN_INTENT_TO_CALLER) {
                            return false;
                        }
                    }
                }
                int result = ActivityManagerNative.getDefault()
                        .startActivity(null, mHomeIntent,
                                mHomeIntent.resolveTypeIfNeeded(mContext.getContentResolver()),
                                null, 0, null, null, 0, true /* onlyIfNeeded*/, false,
                                null, null, false);
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
    
    public void screenOnStartedLw() {
        // The window manager has just grabbed a wake lock. This is our cue to disable the screen
        // saver.
        synchronized (mLock) {
            mScreenSaverEnabled = false;
        }
    }

    public void screenOnStoppedLw() {
        if (mPowerManager.isScreenOn()) {
            if (!mKeyguardMediator.isShowingAndNotHidden()) {
                long curTime = SystemClock.uptimeMillis();
                mPowerManager.userActivity(curTime, false, LocalPowerManager.OTHER_EVENT);
            }

            synchronized (mLock) {
                // even if the keyguard is up, now that all the wakelocks have been released, we
                // should re-enable the screen saver
                mScreenSaverEnabled = true;
                updateScreenSaverTimeoutLocked();
            }
        }
    }

    public boolean allowKeyRepeat() {
        // disable key repeat when screen is off
        return mScreenOn;
    }

    private void updateSystemUiVisibility() {
        // If there is no window focused, there will be nobody to handle the events
        // anyway, so just hang on in whatever state we're in until things settle down.
        if (mFocusedWindow != null) {
            final WindowManager.LayoutParams params = mFocusedWindow.getAttrs();
            final int visibility = params.systemUiVisibility | params.subtreeSystemUiVisibility;
            mHandler.post(new Runnable() {
                    public void run() {
                        if (mStatusBarService == null) {
                            mStatusBarService = IStatusBarService.Stub.asInterface(
                                    ServiceManager.getService("statusbar"));
                        }
                        if (mStatusBarService != null) {
                            // need to assume status bar privileges to invoke lights on
                            long origId = Binder.clearCallingIdentity();
                            try {
                                mStatusBarService.setSystemUiVisibility(visibility);
                            } catch (RemoteException e) {
                                // not much to be done
                                mStatusBarService = null;
                            } finally {
                                Binder.restoreCallingIdentity(origId);
                            }
                        }
                    }
                });
        }
    }

    public void dump(String prefix, FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print(prefix); pw.print("mSafeMode="); pw.print(mSafeMode);
                pw.print(" mSystemReady="); pw.print(mSystemReady);
                pw.print(" mSystemBooted="); pw.println(mSystemBooted);
        pw.print(prefix); pw.print("mLidOpen="); pw.print(mLidOpen);
                pw.print(" mLidOpenRotation="); pw.print(mLidOpenRotation);
                pw.print(" mHdmiPlugged="); pw.println(mHdmiPlugged);
        pw.print(prefix); pw.print("mUiMode="); pw.print(mUiMode);
                pw.print(" mDockMode="); pw.print(mDockMode);
                pw.print(" mCarDockRotation="); pw.print(mCarDockRotation);
                pw.print(" mDeskDockRotation="); pw.println(mDeskDockRotation);
        pw.print(prefix); pw.print("mUserRotationMode="); pw.print(mUserRotationMode);
                pw.print(" mUserRotation="); pw.print(mUserRotation);
                pw.print(" mAllowAllRotations="); pw.println(mAllowAllRotations);
        pw.print(prefix); pw.print("mAccelerometerDefault="); pw.print(mAccelerometerDefault);
                pw.print(" mCurrentAppOrientation="); pw.println(mCurrentAppOrientation);
        pw.print(prefix); pw.print("mCarDockEnablesAccelerometer=");
                pw.print(mCarDockEnablesAccelerometer);
                pw.print(" mDeskDockEnablesAccelerometer=");
                pw.println(mDeskDockEnablesAccelerometer);
        pw.print(prefix); pw.print("mLidKeyboardAccessibility=");
                pw.print(mLidKeyboardAccessibility);
                pw.print(" mLidNavigationAccessibility="); pw.print(mLidNavigationAccessibility);
                pw.print(" mLongPressOnPowerBehavior="); pw.println(mLongPressOnPowerBehavior);
        pw.print(prefix); pw.print("mScreenOn="); pw.print(mScreenOn);
                pw.print(" mOrientationSensorEnabled="); pw.print(mOrientationSensorEnabled);
                pw.print(" mHasSoftInput="); pw.println(mHasSoftInput);
        pw.print(prefix); pw.print("mUnrestrictedScreen=("); pw.print(mUnrestrictedScreenLeft);
                pw.print(","); pw.print(mUnrestrictedScreenTop);
                pw.print(") "); pw.print(mUnrestrictedScreenWidth);
                pw.print("x"); pw.println(mUnrestrictedScreenHeight);
        pw.print(prefix); pw.print("mRestrictedScreen=("); pw.print(mRestrictedScreenLeft);
                pw.print(","); pw.print(mRestrictedScreenTop);
                pw.print(") "); pw.print(mRestrictedScreenWidth);
                pw.print("x"); pw.println(mRestrictedScreenHeight);
        pw.print(prefix); pw.print("mCur=("); pw.print(mCurLeft);
                pw.print(","); pw.print(mCurTop);
                pw.print(")-("); pw.print(mCurRight);
                pw.print(","); pw.print(mCurBottom); pw.println(")");
        pw.print(prefix); pw.print("mContent=("); pw.print(mContentLeft);
                pw.print(","); pw.print(mContentTop);
                pw.print(")-("); pw.print(mContentRight);
                pw.print(","); pw.print(mContentBottom); pw.println(")");
        pw.print(prefix); pw.print("mDock=("); pw.print(mDockLeft);
                pw.print(","); pw.print(mDockTop);
                pw.print(")-("); pw.print(mDockRight);
                pw.print(","); pw.print(mDockBottom); pw.println(")");
        pw.print(prefix); pw.print("mDockLayer="); pw.println(mDockLayer);
        pw.print(prefix); pw.print("mTopFullscreenOpaqueWindowState=");
                pw.println(mTopFullscreenOpaqueWindowState);
        pw.print(prefix); pw.print("mTopIsFullscreen="); pw.print(mTopIsFullscreen);
                pw.print(" mForceStatusBar="); pw.print(mForceStatusBar);
                pw.print(" mHideLockScreen="); pw.println(mHideLockScreen);
        pw.print(prefix); pw.print("mDismissKeyguard="); pw.print(mDismissKeyguard);
                pw.print(" mHomePressed="); pw.println(mHomePressed);
        pw.print(prefix); pw.print("mAllowLockscreenWhenOn="); pw.print(mAllowLockscreenWhenOn);
                pw.print(" mLockScreenTimeout="); pw.print(mLockScreenTimeout);
                pw.print(" mLockScreenTimerActive="); pw.println(mLockScreenTimerActive);
        pw.print(prefix); pw.print("mEndcallBehavior="); pw.print(mEndcallBehavior);
                pw.print(" mIncallPowerBehavior="); pw.print(mIncallPowerBehavior);
                pw.print(" mLongPressOnHomeBehavior="); pw.println(mLongPressOnHomeBehavior);
        pw.print(prefix); pw.print("mLandscapeRotation="); pw.print(mLandscapeRotation);
                pw.print(" mSeascapeRotation="); pw.println(mSeascapeRotation);
        pw.print(prefix); pw.print("mPortraitRotation="); pw.print(mPortraitRotation);
                pw.print(" mUpsideDownRotation="); pw.println(mUpsideDownRotation);
    }
}
