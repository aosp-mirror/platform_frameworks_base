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
import android.app.IStatusBar;
import android.content.BroadcastReceiver;
import android.content.ContentQueryMap;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.LocalPowerManager;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import static android.provider.Settings.System.END_BUTTON_BEHAVIOR;

import com.android.internal.policy.PolicyManager;
import com.android.internal.telephony.ITelephony;
import android.util.Config;
import android.util.EventLog;
import android.util.Log;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationListener;
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
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_PHONE;
import static android.view.WindowManager.LayoutParams.TYPE_PRIORITY_PHONE;
import static android.view.WindowManager.LayoutParams.TYPE_SEARCH_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import android.view.WindowManagerImpl;
import android.view.WindowManagerPolicy;
import android.media.IAudioService;
import android.media.AudioManager;

import java.util.Observable;
import java.util.Observer;

/**
 * WindowManagerPolicy implementation for the Android phone UI.
 */
public class PhoneWindowManager implements WindowManagerPolicy {
    private static final String TAG = "WindowManager";
    private static final boolean DEBUG = false;
    private static final boolean localLOGV = DEBUG ? Config.LOGD : Config.LOGV;
    private static final boolean SHOW_STARTING_ANIMATIONS = true;
    private static final boolean SHOW_PROCESSES_ON_ALT_MENU = false;
    
    private static final int APPLICATION_LAYER = 1;
    private static final int PHONE_LAYER = 2;
    private static final int SEARCH_BAR_LAYER = 3;
    private static final int STATUS_BAR_PANEL_LAYER = 4;
    // toasts and the plugged-in battery thing
    private static final int TOAST_LAYER = 5;
    private static final int STATUS_BAR_LAYER = 6;
    // SIM errors and unlock.  Not sure if this really should be in a high layer.
    private static final int PRIORITY_PHONE_LAYER = 7;
    // like the ANR / app crashed dialogs
    private static final int SYSTEM_ALERT_LAYER = 8;
    // system-level error dialogs
    private static final int SYSTEM_ERROR_LAYER = 9;
    // the keyguard; nothing on top of these can take focus, since they are
    // responsible for power management when displayed.
    private static final int KEYGUARD_LAYER = 10;
    private static final int KEYGUARD_DIALOG_LAYER = 11;
    // things in here CAN NOT take focus, but are shown on top of everything else.
    private static final int SYSTEM_OVERLAY_LAYER = 12;

    private static final int APPLICATION_PANEL_SUBLAYER = 1;
    private static final int APPLICATION_MEDIA_SUBLAYER = -1;
    private static final int APPLICATION_SUB_PANEL_SUBLAYER = 2;

    private static final float SLIDE_TOUCH_EVENT_SIZE_LIMIT = 0.6f;
    
    static public final String SYSTEM_DIALOG_REASON_KEY = "reason";
    static public final String SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS = "globalactions";
    static public final String SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps";

    private Context mContext;
    private IWindowManager mWindowManager;
    private LocalPowerManager mPowerManager;

    /** If true, hitting shift & menu will broadcast Intent.ACTION_BUG_REPORT */
    private boolean mEnableShiftMenuBugReports = false;
    
    private WindowState mStatusBar = null;
    private WindowState mSearchBar = null;
    private WindowState mKeyguard = null;
    private KeyguardViewMediator mKeyguardMediator;
    private GlobalActions mGlobalActions;
    private boolean mShouldTurnOffOnKeyUp;
    private RecentApplicationsDialog mRecentAppsDialog;
    private Handler mHandler;

    private boolean mLidOpen;
    private int mSensorOrientation = OrientationListener.ORIENTATION_UNKNOWN;
    private int mSensorRotation = -1;
    private boolean mScreenOn = false;
    private boolean mOrientationSensorEnabled = false;
    private int mCurrentAppOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    
    private int mW, mH;
    private int mCurLeft, mCurTop, mCurRight, mCurBottom;
    private WindowState mTopFullscreenOpaqueWindowState;
    private boolean mForceStatusBar;
    private boolean mHomePressed;
    private Intent mHomeIntent;
    private boolean mSearchKeyPressed;
    private boolean mConsumeSearchKeyUp;

    private static final int ENDCALL_HOME = 0x1;
    private static final int ENDCALL_SLEEPS = 0x2;
    private static final int DEFAULT_ENDCALL_BEHAVIOR = ENDCALL_SLEEPS;
    private int mEndcallBehavior;

    private ShortcutManager mShortcutManager;
    private PowerManager.WakeLock mBroadcastWakeLock;

    private class SettingsObserver implements Observer {
        private ContentQueryMap mSettings;

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            Cursor settingsCursor = resolver.query(Settings.System.CONTENT_URI, null,
                    Settings.System.NAME + "=?",
                    new String[] { END_BUTTON_BEHAVIOR},
                    null);
            mSettings = new ContentQueryMap(settingsCursor, Settings.System.NAME, true, mHandler);
            mSettings.addObserver(this);

            // pretend that the settings changed so we will get their initial state
            update(mSettings, null);
        }

        private int getInt(String name, int def) {
            ContentValues row = mSettings.getValues(name);
            if (row != null) {
                Integer ret = row.getAsInteger(Settings.System.VALUE);
                if(ret == null) {
                    return def;
                }
                return ret;
            } else {
                return def;
            }
        }
      
        public void update(Observable o, Object arg) {
            mEndcallBehavior = getInt(END_BUTTON_BEHAVIOR, DEFAULT_ENDCALL_BEHAVIOR);
        }
    }
    
    private class MyOrientationListener extends OrientationListener {

        MyOrientationListener(Context context) {
            super(context);
        }
        
        @Override
        public void onOrientationChanged(int orientation) {
            // ignore orientation changes unless the value is in a range that
            // matches portrait or landscape
            // portrait range is 270+45 to 359 and 0 to 45
            // landscape range is 270-45 to 270+45
            if ((orientation >= 0 && orientation <= 45) || (orientation >= 270 - 45)) {
                mSensorOrientation = orientation;
                int rotation =  (orientation >= 270 - 45
                        && orientation <= 270 + 45)
                        ? Surface.ROTATION_90 : Surface.ROTATION_0;
                if (rotation != mSensorRotation) {
                	if(localLOGV) Log.i(TAG, "onOrientationChanged, rotation changed from "+rotation+" to "+mSensorRotation);
                    // Update window manager.  The lid rotation hasn't changed,
                    // but we want it to re-evaluate the final rotation in case
                    // it needs to call back and get the sensor orientation.
                    mSensorRotation = rotation;
                    try {
                        mWindowManager.setRotation(USE_LAST_ROTATION, false);
                    } catch (RemoteException e) {
                        // Ignore
                    }
                }
            }
        }                                      
    }
    private MyOrientationListener mOrientationListener;

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
    private void updateOrientationListener() {
        //Could have been invoked due to screen turning on or off or
        //change of the currently visible window's orientation
        if(localLOGV) Log.i(TAG, "Screen status="+mScreenOn+
                ", current orientation="+mCurrentAppOrientation+
                ", SensorEnabled="+mOrientationSensorEnabled);
        boolean disable = true;
        if(mScreenOn) {
            if(mCurrentAppOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR) {
                disable = false;
                //enable listener if not already enabled
                if(!mOrientationSensorEnabled) {
                    mOrientationListener.enable();
                    if(localLOGV) Log.i(TAG, "Enabling listeners");
                    mOrientationSensorEnabled = true;
                }
            } 
        } 
        //check if sensors need to be disabled
        if(disable && mOrientationSensorEnabled) {
            mOrientationListener.disable();
            if(localLOGV) Log.i(TAG, "Disabling listeners");
            mOrientationSensorEnabled = false;
        }
    }

    private Runnable mEndCallLongPress = new Runnable() {
        public void run() {
            mShouldTurnOffOnKeyUp = false;
            sendCloseSystemWindows(SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS);
            showGlobalActionsDialog();
        }
    };

    private void showGlobalActionsDialog() {
        if (mGlobalActions == null) {
            mGlobalActions = new GlobalActions(mContext, mPowerManager);
        }
        final boolean keyguardShowing = mKeyguardMediator.isShowing();
        mGlobalActions.showDialog(keyguardShowing, isDeviceProvisioned());
        if (keyguardShowing) {
            // since it took two seconds of long press to bring this up,
            // poke the wake lock so they have some time to see the dialog.
            mKeyguardMediator.pokeWakelock();
        }
    }

    private boolean isDeviceProvisioned() {
        return Settings.System.getInt(
                mContext.getContentResolver(), Settings.System.DEVICE_PROVISIONED, 0) != 0;
    }

    /**
     * When a home-key longpress expires, close other system windows and launch the recent apps
     */
    private Runnable mHomeLongPress = new Runnable() {
        public void run() {
            /*
             * Eat the longpress so it won't dismiss the recent apps dialog when
             * the user lets go of the home key
             */
            mHomePressed = false;
            sendCloseSystemWindows(SYSTEM_DIALOG_REASON_RECENT_APPS);
            showRecentAppsDialog();
        }
    };

    /**
     * Create (if necessary) and launch the recent apps dialog
     */
    private void showRecentAppsDialog() {
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
        SettingsObserver settingsObserver = new SettingsObserver();
        settingsObserver.observe();
        mShortcutManager = new ShortcutManager(context, mHandler);
        mShortcutManager.observe();
        mHomeIntent =  new Intent(Intent.ACTION_MAIN, null);
        mHomeIntent.addCategory(Intent.CATEGORY_HOME);
        mHomeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mBroadcastWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "PhoneWindowManager.mBroadcastWakeLock");
        mEnableShiftMenuBugReports = "1".equals(SystemProperties.get("ro.debuggable"));
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
    
    private void readLidState() {
        try {
            int sw = mWindowManager.getSwitchState(0);
            if (sw >= 0) {
                mLidOpen = sw == 0;
            }
        } catch (RemoteException e) {
            // Ignore
        }
    }
    
    /** {@inheritDoc} */
    public void adjustConfigurationLw(Configuration config) {
        readLidState();
        mPowerManager.setKeyboardVisibility(mLidOpen);
        config.keyboardHidden = mLidOpen
            ? Configuration.KEYBOARDHIDDEN_NO
            : Configuration.KEYBOARDHIDDEN_YES;
        if (keyguardIsShowingTq()) {
            if (mLidOpen) {
                // only do this if it's opening -- closing the device shouldn't turn it
                // off, but it also shouldn't turn it on.
                mKeyguardMediator.pokeWakelock();
            }
        } else {
            mPowerManager.userActivity(SystemClock.uptimeMillis(), false,
                    LocalPowerManager.OTHER_EVENT);
        }
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
    
    /** {@inheritDoc} */
    public int windowTypeToLayerLw(int type) {
        if (type >= FIRST_APPLICATION_WINDOW && type <= LAST_APPLICATION_WINDOW) {
            return APPLICATION_LAYER;
        }
        switch (type) {
        case TYPE_APPLICATION_PANEL:
            return APPLICATION_LAYER;
        case TYPE_APPLICATION_SUB_PANEL:
            return APPLICATION_LAYER;
        case TYPE_STATUS_BAR:
            return STATUS_BAR_LAYER;
        case TYPE_STATUS_BAR_PANEL:
            return STATUS_BAR_PANEL_LAYER;
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
        case TYPE_SYSTEM_OVERLAY:
            return SYSTEM_OVERLAY_LAYER;
        case TYPE_PRIORITY_PHONE:
            return PRIORITY_PHONE_LAYER;
        case TYPE_TOAST:
            return TOAST_LAYER;
        }
        Log.e(TAG, "Unknown window type: " + type);
        return APPLICATION_LAYER;
    }

    /** {@inheritDoc} */
    public int subWindowTypeToLayerLw(int type) {
        switch (type) {
        case TYPE_APPLICATION_PANEL:
            return APPLICATION_PANEL_SUBLAYER;
        case TYPE_APPLICATION_MEDIA:
            return APPLICATION_MEDIA_SUBLAYER;
        case TYPE_APPLICATION_SUB_PANEL:
            return APPLICATION_SUB_PANEL_SUBLAYER;
        }
        Log.e(TAG, "Unknown sub-window type: " + type);
        return 0;
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
        Resources r = context.getResources();
        win.setTitle(r.getText(labelRes, nonLocalizedLabel));

        win.setType(
            WindowManager.LayoutParams.TYPE_APPLICATION_STARTING);
        win.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

        win.setLayout(WindowManager.LayoutParams.FILL_PARENT,
                            WindowManager.LayoutParams.FILL_PARENT);

        final WindowManager.LayoutParams params = win.getAttributes();
        params.token = appToken;
        params.packageName = packageName;
        params.windowAnimations = win.getWindowStyle().getResourceId(
                com.android.internal.R.styleable.Window_windowAnimationStyle, 0);
        params.setTitle("Starting " + packageName);

        try {
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
     * <li>SEARCH_BAR_TYPE</li>
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
                if (mStatusBar != null) {
                    return WindowManagerImpl.ADD_MULTIPLE_SINGLETON;
                }
                mStatusBar = win;
                break;
            case TYPE_SEARCH_BAR:
                if (mSearchBar != null) {
                    return WindowManagerImpl.ADD_MULTIPLE_SINGLETON;
                }
                mSearchBar = win;
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
        else if (mSearchBar == win) {
            mSearchBar = null;
        }
        else if (mKeyguard == win) {
            mKeyguard = null;
        }
    }

    private static final boolean PRINT_ANIM = false;
    
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

    private static ITelephony getPhoneInterface() {
        return ITelephony.Stub.asInterface(ServiceManager.checkService(Context.TELEPHONY_SERVICE));
    }

    private static IAudioService getAudioInterface() {
        return IAudioService.Stub.asInterface(ServiceManager.checkService(Context.AUDIO_SERVICE));
    }

    private boolean keyguardOn() {
        return keyguardIsShowingTq() || inKeyguardRestrictedKeyInputMode();
    }

    /** {@inheritDoc} */
    public boolean interceptKeyTi(WindowState win, int code, int metaKeys, boolean down, 
            int repeatCount) {
        boolean keyguardOn = keyguardOn();

        if (false) {
            Log.d(TAG, "interceptKeyTi code=" + code + " down=" + down + " repeatCount="
                    + repeatCount + " keyguardOn=" + keyguardOn);
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
                int type = attrs.type;
                if (type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW
                        && type <= WindowManager.LayoutParams.LAST_SYSTEM_WINDOW) {
                    // Only do this once, so home-key-longpress doesn't close itself
                    if (repeatCount == 0 && down) {
                		sendCloseSystemWindows();
                    }
                    return false;
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
        } else if (code == KeyEvent.KEYCODE_NOTIFICATION) {
            if (down) {
                // this key doesn't exist on current hardware, but if a device
                // didn't have a touchscreen, it would want one of these to open
                // the status bar.
                IStatusBar sbs = IStatusBar.Stub.asInterface(ServiceManager.getService("statusbar"));
                if (sbs != null) {
                    try {
                        sbs.toggle();
                    } catch (RemoteException e) {
                        // we're screwed anyway, since it's in this process
                        throw new RuntimeException(e);
                    }
                }
            }
            return true;
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
    private void launchHomeFromHotKey() {
        if (mKeyguardMediator.isShowing()) {
            // don't launch home if keyguard showing
        } else if (mKeyguardMediator.isInputRestricted()) {
            // when in keyguard restricted mode, must first verify unlock
            // before launching home
            mKeyguardMediator.verifyUnlock(new OnKeyguardExitResult() {
                public void onKeyguardExitResult(boolean success) {
                    if (success) {
                        mContext.startActivity(mHomeIntent);
                        sendCloseSystemWindows();
                    }
                }
            });
        } else {
            // no keyguard stuff to worry about, just launch home!
            mContext.startActivity(mHomeIntent);
            sendCloseSystemWindows();
        }
    }

    public void getCoveredInsetHintLw(WindowManager.LayoutParams attrs, Rect coveredInset) {
        final int fl = attrs.flags;
        
        if ((fl &
                (FLAG_LAYOUT_IN_SCREEN | FLAG_FULLSCREEN | FLAG_LAYOUT_INSET_DECOR))
                == (FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR)) {
            coveredInset.set(mCurLeft, mCurTop, mW - mCurRight, mH - mCurBottom);
        } else {
            coveredInset.setEmpty();
        }
    }
    
    /** {@inheritDoc} */
    public void beginLayoutLw(int displayWidth, int displayHeight) {
        mW = displayWidth;
        mH = displayHeight;
        mCurLeft = 0;
        mCurTop = 0;
        mCurRight = displayWidth;
        mCurBottom = displayHeight;

        // decide where the status bar goes ahead of time
        if (mStatusBar != null) {
            mStatusBar.computeFrameLw(0, 0, displayWidth, displayHeight,
                                    0, 0, displayWidth, displayHeight);
            mCurTop = mStatusBar.getFrameLw().bottom;
        }
    }

    /** {@inheritDoc} */
    public void layoutWindowLw(WindowState win, WindowManager.LayoutParams attrs, WindowState attached) {
        // we've already done the status bar
        if (win == mStatusBar) {
            return;
        }

        final int fl = attrs.flags;
        
        int dl, dt, dr, db;
        if ((fl & FLAG_LAYOUT_IN_SCREEN) == 0) {
            // Make sure this window doesn't intrude into the status bar.
            dl = mCurLeft;
            dt = mCurTop;
            dr = mCurRight;
            db = mCurBottom;
        } else {
            dl = 0;
            dt = 0;
            dr = mW;
            db = mH;
        }
        
        if ((fl &
                (FLAG_LAYOUT_IN_SCREEN | FLAG_FULLSCREEN | FLAG_LAYOUT_INSET_DECOR))
                == (FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR)) {
            win.setCoveredInsetsLw(mCurLeft, mCurTop, mW - mCurRight, mH - mCurBottom);
        } else {
            win.setCoveredInsetsLw(0, 0, 0, 0);
        }

        int pl, pt, pr, pb;
        if (attached != null && (fl & (FLAG_LAYOUT_IN_SCREEN)) == 0) {
            final Rect r = attached.getFrameLw();
            pl = r.left;
            pt = r.top;
            pr = r.right;
            pb = r.bottom;
        } else {
            pl = dl;
            pt = dt;
            pr = dr;
            pb = db;
        }
        
        if ((fl & FLAG_LAYOUT_NO_LIMITS) != 0) {
            dl = -100000;
            dt = -100000;
            dr = 100000;
            db = 100000;
        }

        win.computeFrameLw(pl, pt, pr, pb, dl, dt, dr, db);
    }

    /** {@inheritDoc} */
    public void finishLayoutLw() {
    }

    /** {@inheritDoc} */
    public void beginAnimationLw(int displayWidth, int displayHeight) {
        mTopFullscreenOpaqueWindowState = null;
        mForceStatusBar = false;
    }

    /** {@inheritDoc} */
    public void animatingWindowLw(WindowState win,
                                WindowManager.LayoutParams attrs) {
        if (mTopFullscreenOpaqueWindowState == null
            && attrs.type >= FIRST_APPLICATION_WINDOW
            && attrs.type <= LAST_APPLICATION_WINDOW
            && win.fillsScreenLw(mW, mH, true)
            && win.isDisplayedLw()) {
            mTopFullscreenOpaqueWindowState = win;
        } else if ((attrs.flags & FLAG_FORCE_NOT_FULLSCREEN) != 0) {
            mForceStatusBar = true;
        }
    }

    /** {@inheritDoc} */
    public boolean finishAnimationLw() {
        if (mStatusBar != null) {
            if (mForceStatusBar) {
                mStatusBar.showLw();
            } else if (mTopFullscreenOpaqueWindowState != null) {
               WindowManager.LayoutParams lp =
                   mTopFullscreenOpaqueWindowState.getAttrs();
               boolean hideStatusBar =
                   (lp.flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0;
               if (hideStatusBar) {
                   mStatusBar.hideLw();
               } else {
                   mStatusBar.showLw();
               }
           }
        }
       return false;
    }

    /** {@inheritDoc} */
    public boolean preprocessInputEventTq(RawInputEvent event) {
        switch (event.type) {
            case RawInputEvent.EV_SW:
                if (event.keycode == 0) {
                    // lid changed state
                    mLidOpen = event.value == 0;
                    updateRotation();
                }
        }
        return false;
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
    private boolean isInCall() {
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
    private boolean isMusicActive() {
        final IAudioService audio = getAudioInterface();
        if (audio == null) {
            Log.w(TAG, "isMusicActive: couldn't get IAudioService reference");
            return false;
        }
        try {
            return audio.isMusicActive();
        } catch (RemoteException e) {
            Log.w(TAG, "IAudioService.isMusicActive() threw RemoteException " + e);
            return false;
        }
    }

    /**
     * Tell the audio service to adjust the volume appropriate to the event.
     * @param keycode
     */
    private void sendVolToMusic(int keycode) {
        final IAudioService audio = getAudioInterface();
        if (audio == null) {
            Log.w(TAG, "sendVolToMusic: couldn't get IAudioService reference");
            return;
        }
        try {
            // since audio is playing, we shouldn't have to hold a wake lock
            // during the call, but we do it as a precaution for the rare possibility
            // that the music stops right before we call this
            mBroadcastWakeLock.acquire();
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
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

    /** {@inheritDoc} */
    public int interceptKeyTq(RawInputEvent event, boolean screenIsOn) {
        int result = ACTION_PASS_TO_USER;
        final boolean isWakeKey = isWakeKeyTq(event);
        final boolean keyguardShowing = keyguardIsShowingTq();

        if (keyguardShowing) {
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
                        if (isInCall()) {
                            // if the keyguard didn't wake the device, we are in call, and
                            // it is a volume key, turn on the screen so that the user
                            // can more easily adjust the in call volume.
                            mKeyguardMediator.pokeWakelock();
                        } else if (isMusicActive()) {
                            // when keyguard is showing and screen off, we need
                            // to handle the volume key for music here
                            sendVolToMusic(event.keycode);
                        }
                    }
                }
            }
        } else if (!screenIsOn) {
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
            if (code == KeyEvent.KEYCODE_ENDCALL) {
                if (down) {
                    boolean hungUp = false;
                    // key repeats are generated by the window manager, and we don't see them
                    // here, so unless the driver is doing something it shouldn't be, we know
                    // this is the real press event.
                    try {
                        ITelephony phoneServ = getPhoneInterface();
                        if (phoneServ != null) {
                            hungUp = phoneServ.endCall();
                        } else {
                            Log.w(TAG, "!!! Unable to find ITelephony interface !!!");
                        }
                    } catch (RemoteException ex) {
                        Log.w(TAG, "ITelephony.endCall() threw RemoteException" + ex);
                    }
                    if (hungUp || !screenIsOn) {
                        mShouldTurnOffOnKeyUp = false;
                    } else {
                        // only try to turn off the screen if we didn't already hang up
                        mShouldTurnOffOnKeyUp = true;
                        mHandler.postDelayed(mEndCallLongPress,
                                ViewConfiguration.getGlobalActionKeyTimeout());
                        result &= ~ACTION_PASS_TO_USER;
                    }
                } else {
                    mHandler.removeCallbacks(mEndCallLongPress);
                    if (mShouldTurnOffOnKeyUp) {
                        mShouldTurnOffOnKeyUp = false;
                        boolean gohome = (mEndcallBehavior & ENDCALL_HOME) != 0;
                        boolean sleeps = (mEndcallBehavior & ENDCALL_SLEEPS) != 0;
                        if (keyguardShowing
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
            } else if (code == KeyEvent.KEYCODE_HEADSETHOOK) {
                // This key needs to be handled even if the screen is off.
                // If others need to be handled while it's off, this is a reasonable
                // pattern to follow.
                if ((result & ACTION_PASS_TO_USER) == 0) {
                    // Only do this if we would otherwise not pass it to the user. In that
                    // case, the PhoneWindow class will do the same thing, except it will
                    // only do it if the showing app doesn't process the key on its own.
                    KeyEvent keyEvent = new KeyEvent(event.when, event.when,
                            down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                            KeyEvent.KEYCODE_HEADSETHOOK, 0);
                    mBroadcastWakeLock.acquire();
                    mHandler.post(new PassHeadsetKey(keyEvent));
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
            Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
            intent.putExtra(Intent.EXTRA_KEY_EVENT, mKeyEvent);
            mContext.sendOrderedBroadcast(intent, null, mBroadcastDone,
                    mHandler, Activity.RESULT_OK, null, null);
        }
    }

    private BroadcastReceiver mBroadcastDone = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            mBroadcastWakeLock.release();
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
        mScreenOn = false;
        updateOrientationListener();
    }

    /** {@inheritDoc} */
    public void screenTurnedOn() {
        EventLog.writeEvent(70000, 1);
        mKeyguardMediator.onScreenTurnedOn();
        mScreenOn = true;
        updateOrientationListener();
    }

    /** {@inheritDoc} */
    public void enableKeyguard(boolean enabled) {
        mKeyguardMediator.setKeyguardEnabled(enabled);
    }

    /** {@inheritDoc} */
    public void exitKeyguardSecurely(OnKeyguardExitResult callback) {
        mKeyguardMediator.verifyUnlock(callback);
    }

    /** {@inheritDoc} */
    public boolean keyguardIsShowingTq() {
        return mKeyguardMediator.isShowing();
    }

    /** {@inheritDoc} */
    public boolean inKeyguardRestrictedKeyInputMode() {
        return mKeyguardMediator.isInputRestricted();
    }

    /**
     * Callback from {@link KeyguardViewMediator}
     */
    public void onKeyguardShow() {
        sendCloseSystemWindows();
    }

    private void sendCloseSystemWindows() {
        sendCloseSystemWindows(null);
    }

    private void sendCloseSystemWindows(String reason) {
        Intent intent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        if (reason != null) {
            intent.putExtra(SYSTEM_DIALOG_REASON_KEY, reason);
        }
        mContext.sendBroadcast(intent);
    }

    public int rotationForOrientation(int orientation) {
        switch (orientation) {
            case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
                //always return landscape if orientation set to landscape
                return Surface.ROTATION_90;
            case ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
                //always return portrait if orientation set to portrait
                return Surface.ROTATION_0;
            case ActivityInfo.SCREEN_ORIENTATION_SENSOR:
                if(mOrientationSensorEnabled) {
                    //consider only sensor based orientation keyboard slide ignored
                    return mSensorRotation >= 0 ? mSensorRotation : Surface.ROTATION_0;
                }
                //if orientation sensor is disabled fall back to default behaviour
                //based on lid
        }
        // case for nosensor meaning ignore sensor and consider only lid
        // or orientation sensor disabled
        //or case.unspecified
        if(mLidOpen) {
            return Surface.ROTATION_90;
        } else {
            return Surface.ROTATION_0;
        }
    }
    
    /** {@inheritDoc} */
    public void systemReady() {
        try {
            int menuState = mWindowManager.getKeycodeState(KeyEvent.KEYCODE_MENU);
            Log.i(TAG, "Menu key state: " + menuState);
            if (menuState > 0) {
                // If the user is holding the menu key code, then we are
                // going to boot into safe mode.
                ActivityManagerNative.getDefault().enterSafeMode();
            } else {
                // tell the keyguard
                mKeyguardMediator.onSystemReady();
                android.os.SystemProperties.set("dev.bootcomplete", "1"); 
            }
        } catch (RemoteException e) {
            // Ignore
        }
    }
    
    /** {@inheritDoc} */
    public void enableScreenAfterBoot() {
        readLidState();
        updateRotation();
    }
    
    private void updateRotation() {
        mPowerManager.setKeyboardVisibility(mLidOpen);
        int rotation=  Surface.ROTATION_0;
        if (mLidOpen) {
            // always use landscape if lid is open             
            rotation = Surface.ROTATION_90;
        }
        //if lid is closed orientation will be portrait
        try {
            //set orientation on WindowManager
            mWindowManager.setRotation(rotation, true);
        } catch (RemoteException e) {
            // Ignore
        }
        if (keyguardIsShowingTq()) {
            if (mLidOpen) {
                // only do this if it's opening -- closing the device shouldn't turn it
                // off, but it also shouldn't turn it on.
                mKeyguardMediator.pokeWakelock();
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

    /**
     * goes to the home screen
     * @return whether it did anything
     */
    boolean goHome() {
        if (false) {
            // This code always brings home to the front.
            mContext.startActivity(mHomeIntent);
        } else {
            // This code brings home to the front or, if it is already
            // at the front, puts the device to sleep.
            try {
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
        sendCloseSystemWindows();
        return true;
    }
    
    public void setCurrentOrientation(int newOrientation) {
        if(newOrientation != mCurrentAppOrientation) {
            mCurrentAppOrientation = newOrientation;
            updateOrientationListener();
        }
    }
}
