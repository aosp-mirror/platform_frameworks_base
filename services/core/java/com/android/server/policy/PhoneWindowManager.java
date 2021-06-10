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

package com.android.server.policy;

import static android.Manifest.permission.INTERNAL_SYSTEM_WINDOW;
import static android.Manifest.permission.SYSTEM_ALERT_WINDOW;
import static android.app.AppOpsManager.OP_SYSTEM_ALERT_WINDOW;
import static android.app.AppOpsManager.OP_TOAST_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.content.Context.CONTEXT_RESTRICTED;
import static android.content.Context.WINDOW_SERVICE;
import static android.content.pm.PackageManager.FEATURE_HDMI_CEC;
import static android.content.pm.PackageManager.FEATURE_LEANBACK;
import static android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE;
import static android.content.pm.PackageManager.FEATURE_WATCH;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.content.res.Configuration.EMPTY;
import static android.os.Build.VERSION_CODES.M;
import static android.os.Build.VERSION_CODES.O;
import static android.provider.Settings.Secure.VOLUME_HUSH_OFF;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.Display.STATE_OFF;
import static android.view.KeyEvent.KEYCODE_UNKNOWN;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_SYSTEM_WINDOW;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BOOT_PROGRESS;
import static android.view.WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_DREAM;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_CONSUMER;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_PHONE;
import static android.view.WindowManager.LayoutParams.TYPE_POINTER;
import static android.view.WindowManager.LayoutParams.TYPE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_PRIORITY_PHONE;
import static android.view.WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_QS_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_SEARCH_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;
import static android.view.WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManager.LayoutParams.isSystemAlertWindowType;
import static android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN;
import static android.view.WindowManager.TAKE_SCREENSHOT_SELECTED_REGION;
import static android.view.WindowManagerGlobal.ADD_OKAY;
import static android.view.WindowManagerGlobal.ADD_PERMISSION_DENIED;

import static com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs.CAMERA_LENS_COVERED;
import static com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs.CAMERA_LENS_COVER_ABSENT;
import static com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs.CAMERA_LENS_UNCOVERED;
import static com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs.LID_BEHAVIOR_LOCK;
import static com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs.LID_BEHAVIOR_NONE;
import static com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs.LID_BEHAVIOR_SLEEP;
import static com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs.LID_CLOSED;
import static com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs.LID_OPEN;
import static com.android.server.wm.WindowManagerPolicyProto.KEYGUARD_DELEGATE;
import static com.android.server.wm.WindowManagerPolicyProto.KEYGUARD_DRAW_COMPLETE;
import static com.android.server.wm.WindowManagerPolicyProto.KEYGUARD_OCCLUDED;
import static com.android.server.wm.WindowManagerPolicyProto.KEYGUARD_OCCLUDED_CHANGED;
import static com.android.server.wm.WindowManagerPolicyProto.KEYGUARD_OCCLUDED_PENDING;
import static com.android.server.wm.WindowManagerPolicyProto.ORIENTATION;
import static com.android.server.wm.WindowManagerPolicyProto.ROTATION;
import static com.android.server.wm.WindowManagerPolicyProto.ROTATION_MODE;
import static com.android.server.wm.WindowManagerPolicyProto.SCREEN_ON_FULLY;
import static com.android.server.wm.WindowManagerPolicyProto.WINDOW_MANAGER_DRAW_COMPLETE;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityTaskManager;
import android.app.AppOpsManager;
import android.app.IUiModeManager;
import android.app.ProgressDialog;
import android.app.SearchManager;
import android.app.UiModeManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.hardware.hdmi.HdmiAudioSystemClient;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiPlaybackClient;
import android.hardware.hdmi.HdmiPlaybackClient.OneTouchPlayCallback;
import android.hardware.input.InputManagerInternal;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioManagerInternal;
import android.media.AudioSystem;
import android.media.IAudioService;
import android.media.session.MediaSessionLegacyHelper;
import android.os.Binder;
import android.os.Bundle;
import android.os.FactoryTest;
import android.os.Handler;
import android.os.IBinder;
import android.os.IDeviceIdleController;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeReason;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UEventObserver;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.Settings;
import android.service.dreams.DreamManagerInternal;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.service.vr.IPersistentVrStateCallbacks;
import android.speech.RecognizerIntent;
import android.telecom.TelecomManager;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.MutableBoolean;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.IDisplayFoldListener;
import android.view.IWindowManager;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyCharacterMap.FallbackAction;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerPolicyConstants;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.view.autofill.AutofillManagerInternal;

import com.android.internal.R;
import com.android.internal.accessibility.AccessibilityShortcutController;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.os.RoSystemProperties;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IShortcutService;
import com.android.internal.policy.PhoneWindow;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.ArrayUtils;
import com.android.server.ExtconStateObserver;
import com.android.server.ExtconUEventObserver;
import com.android.server.GestureLauncherService;
import com.android.server.LocalServices;
import com.android.server.SystemServiceManager;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.policy.keyguard.KeyguardServiceDelegate;
import com.android.server.policy.keyguard.KeyguardServiceDelegate.DrawnListener;
import com.android.server.policy.keyguard.KeyguardStateMonitor.StateCallback;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.vr.VrManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal.SleepToken;
import com.android.server.wm.AppTransition;
import com.android.server.wm.DisplayPolicy;
import com.android.server.wm.DisplayRotation;
import com.android.server.wm.WindowManagerInternal;
import com.android.server.wm.WindowManagerInternal.AppTransitionListener;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.List;

/**
 * WindowManagerPolicy implementation for the Android phone UI.  This
 * introduces a new method suffix, Lp, for an internal lock of the
 * PhoneWindowManager.  This is used to protect some internal state, and
 * can be acquired with either the Lw and Li lock held, so has the restrictions
 * of both of those when held.
 */
public class PhoneWindowManager implements WindowManagerPolicy {
    static final String TAG = "WindowManager";
    static final boolean localLOGV = false;
    static final boolean DEBUG_INPUT = false;
    static final boolean DEBUG_KEYGUARD = false;
    static final boolean DEBUG_SPLASH_SCREEN = false;
    static final boolean DEBUG_WAKEUP = false;
    static final boolean SHOW_SPLASH_SCREENS = true;

    // Whether to allow dock apps with METADATA_DOCK_HOME to temporarily take over the Home key.
    // No longer recommended for desk docks;
    static final boolean ENABLE_DESK_DOCK_HOME_CAPTURE = false;

    // Whether to allow devices placed in vr headset viewers to have an alternative Home intent.
    static final boolean ENABLE_VR_HEADSET_HOME_CAPTURE = true;

    // must match: config_shortPressOnPowerBehavior in config.xml
    static final int SHORT_PRESS_POWER_NOTHING = 0;
    static final int SHORT_PRESS_POWER_GO_TO_SLEEP = 1;
    static final int SHORT_PRESS_POWER_REALLY_GO_TO_SLEEP = 2;
    static final int SHORT_PRESS_POWER_REALLY_GO_TO_SLEEP_AND_GO_HOME = 3;
    static final int SHORT_PRESS_POWER_GO_HOME = 4;
    static final int SHORT_PRESS_POWER_CLOSE_IME_OR_GO_HOME = 5;

    // must match: config_LongPressOnPowerBehavior in config.xml
    static final int LONG_PRESS_POWER_NOTHING = 0;
    static final int LONG_PRESS_POWER_GLOBAL_ACTIONS = 1;
    static final int LONG_PRESS_POWER_SHUT_OFF = 2;
    static final int LONG_PRESS_POWER_SHUT_OFF_NO_CONFIRM = 3;
    static final int LONG_PRESS_POWER_GO_TO_VOICE_ASSIST = 4;
    static final int LONG_PRESS_POWER_ASSISTANT = 5; // Settings.Secure.ASSISTANT

    // must match: config_veryLongPresOnPowerBehavior in config.xml
    static final int VERY_LONG_PRESS_POWER_NOTHING = 0;
    static final int VERY_LONG_PRESS_POWER_GLOBAL_ACTIONS = 1;

    // must match: config_doublePressOnPowerBehavior in config.xml
    static final int MULTI_PRESS_POWER_NOTHING = 0;
    static final int MULTI_PRESS_POWER_THEATER_MODE = 1;
    static final int MULTI_PRESS_POWER_BRIGHTNESS_BOOST = 2;

    // must match: config_longPressOnBackBehavior in config.xml
    static final int LONG_PRESS_BACK_NOTHING = 0;
    static final int LONG_PRESS_BACK_GO_TO_VOICE_ASSIST = 1;

    // must match: config_longPressOnHomeBehavior in config.xml
    static final int LONG_PRESS_HOME_NOTHING = 0;
    static final int LONG_PRESS_HOME_ALL_APPS = 1;
    static final int LONG_PRESS_HOME_ASSIST = 2;
    static final int LAST_LONG_PRESS_HOME_BEHAVIOR = LONG_PRESS_HOME_ASSIST;

    // must match: config_doubleTapOnHomeBehavior in config.xml
    static final int DOUBLE_TAP_HOME_NOTHING = 0;
    static final int DOUBLE_TAP_HOME_RECENT_SYSTEM_UI = 1;

    static final int SHORT_PRESS_WINDOW_NOTHING = 0;
    static final int SHORT_PRESS_WINDOW_PICTURE_IN_PICTURE = 1;

    static final int SHORT_PRESS_SLEEP_GO_TO_SLEEP = 0;
    static final int SHORT_PRESS_SLEEP_GO_TO_SLEEP_AND_GO_HOME = 1;

    static final int PENDING_KEY_NULL = -1;

    static public final String SYSTEM_DIALOG_REASON_KEY = "reason";
    static public final String SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS = "globalactions";
    static public final String SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps";
    static public final String SYSTEM_DIALOG_REASON_HOME_KEY = "homekey";
    static public final String SYSTEM_DIALOG_REASON_ASSIST = "assist";
    static public final String SYSTEM_DIALOG_REASON_SCREENSHOT = "screenshot";

    private static final int POWER_BUTTON_SUPPRESSION_DELAY_DEFAULT_MILLIS = 800;
    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build();

    /**
     * Keyguard stuff
     */
    private boolean mKeyguardDrawnOnce;

    /* Table of Application Launch keys.  Maps from key codes to intent categories.
     *
     * These are special keys that are used to launch particular kinds of applications,
     * such as a web browser.  HID defines nearly a hundred of them in the Consumer (0x0C)
     * usage page.  We don't support quite that many yet...
     */
    static SparseArray<String> sApplicationLaunchKeyCategories;
    static {
        sApplicationLaunchKeyCategories = new SparseArray<String>();
        sApplicationLaunchKeyCategories.append(
                KeyEvent.KEYCODE_EXPLORER, Intent.CATEGORY_APP_BROWSER);
        sApplicationLaunchKeyCategories.append(
                KeyEvent.KEYCODE_ENVELOPE, Intent.CATEGORY_APP_EMAIL);
        sApplicationLaunchKeyCategories.append(
                KeyEvent.KEYCODE_CONTACTS, Intent.CATEGORY_APP_CONTACTS);
        sApplicationLaunchKeyCategories.append(
                KeyEvent.KEYCODE_CALENDAR, Intent.CATEGORY_APP_CALENDAR);
        sApplicationLaunchKeyCategories.append(
                KeyEvent.KEYCODE_MUSIC, Intent.CATEGORY_APP_MUSIC);
        sApplicationLaunchKeyCategories.append(
                KeyEvent.KEYCODE_CALCULATOR, Intent.CATEGORY_APP_CALCULATOR);
    }

    private static final int USER_ACTIVITY_NOTIFICATION_DELAY = 200;

    /** Amount of time (in milliseconds) to wait for windows drawn before powering on. */
    static final int WAITING_FOR_DRAWN_TIMEOUT = 1000;

    /** Amount of time (in milliseconds) a toast window can be shown. */
    public static final int TOAST_WINDOW_TIMEOUT = 3500; // 3.5 seconds

    /**
     * Lock protecting internal state.  Must not call out into window
     * manager with lock held.  (This lock will be acquired in places
     * where the window manager is calling in with its own lock held.)
     */
    private final Object mLock = new Object();

    Context mContext;
    IWindowManager mWindowManager;
    WindowManagerFuncs mWindowManagerFuncs;
    WindowManagerInternal mWindowManagerInternal;
    PowerManager mPowerManager;
    ActivityManagerInternal mActivityManagerInternal;
    ActivityTaskManagerInternal mActivityTaskManagerInternal;
    AutofillManagerInternal mAutofillManagerInternal;
    InputManagerInternal mInputManagerInternal;
    InputMethodManagerInternal mInputMethodManagerInternal;
    DreamManagerInternal mDreamManagerInternal;
    PowerManagerInternal mPowerManagerInternal;
    IStatusBarService mStatusBarService;
    StatusBarManagerInternal mStatusBarManagerInternal;
    AudioManagerInternal mAudioManagerInternal;
    DisplayManager mDisplayManager;
    boolean mPreloadedRecentApps;
    final Object mServiceAquireLock = new Object();
    Vibrator mVibrator; // Vibrator for giving feedback of orientation changes
    SearchManager mSearchManager;
    AccessibilityManager mAccessibilityManager;
    BurnInProtectionHelper mBurnInProtectionHelper;
    private DisplayFoldController mDisplayFoldController;
    AppOpsManager mAppOpsManager;
    private boolean mHasFeatureWatch;
    private boolean mHasFeatureLeanback;
    private boolean mHasFeatureHdmiCec;

    // Assigned on main thread, accessed on UI thread
    volatile VrManagerInternal mVrManagerInternal;

    // Vibrator pattern for haptic feedback of a long press.
    long[] mLongPressVibePattern;

    // Vibrator pattern for a short vibration when tapping on a day/month/year date of a Calendar.
    long[] mCalendarDateVibePattern;

    // Vibrator pattern for haptic feedback during boot when safe mode is enabled.
    long[] mSafeModeEnabledVibePattern;

    /** If true, hitting shift & menu will broadcast Intent.ACTION_BUG_REPORT */
    boolean mEnableShiftMenuBugReports = false;

    /** Controller that supports enabling an AccessibilityService by holding down the volume keys */
    private AccessibilityShortcutController mAccessibilityShortcutController;

    boolean mSafeMode;
    private WindowState mKeyguardCandidate = null;

    private LongSparseArray<IShortcutService> mShortcutKeyServices = new LongSparseArray<>();

    // Whether to allow dock apps with METADATA_DOCK_HOME to temporarily take over the Home key.
    // This is for car dock and this is updated from resource.
    private boolean mEnableCarDockHomeCapture = true;

    boolean mBootMessageNeedsHiding;
    KeyguardServiceDelegate mKeyguardDelegate;
    private boolean mKeyguardBound;
    final Runnable mWindowManagerDrawCallback = new Runnable() {
        @Override
        public void run() {
            if (DEBUG_WAKEUP) Slog.i(TAG, "All windows ready for display!");
            mHandler.sendEmptyMessage(MSG_WINDOW_MANAGER_DRAWN_COMPLETE);
        }
    };
    final DrawnListener mKeyguardDrawnCallback = new DrawnListener() {
        @Override
        public void onDrawn() {
            if (DEBUG_WAKEUP) Slog.d(TAG, "mKeyguardDelegate.ShowListener.onDrawn.");
            mHandler.sendEmptyMessage(MSG_KEYGUARD_DRAWN_COMPLETE);
        }
    };

    GlobalActions mGlobalActions;
    Handler mHandler;

    // FIXME This state is shared between the input reader and handler thread.
    // Technically it's broken and buggy but it has been like this for many years
    // and we have not yet seen any problems.  Someday we'll rewrite this logic
    // so that only one thread is involved in handling input policy.  Unfortunately
    // it's on a critical path for power management so we can't just post the work to the
    // handler thread.  We'll need to resolve this someday by teaching the input dispatcher
    // to hold wakelocks during dispatch and eliminating the critical path.
    volatile boolean mPowerKeyHandled;
    volatile boolean mBackKeyHandled;
    volatile boolean mBeganFromNonInteractive;
    volatile int mPowerKeyPressCounter;
    volatile boolean mEndCallKeyHandled;
    volatile boolean mCameraGestureTriggeredDuringGoingToSleep;
    volatile boolean mGoingToSleep;
    volatile boolean mRequestedOrGoingToSleep;
    volatile boolean mRecentsVisible;
    volatile boolean mNavBarVirtualKeyHapticFeedbackEnabled = true;
    volatile boolean mPictureInPictureVisible;
    volatile private boolean mDismissImeOnBackKeyPressed;

    // Used to hold the last user key used to wake the device.  This helps us prevent up events
    // from being passed to the foregrounded app without a corresponding down event
    volatile int mPendingWakeKey = PENDING_KEY_NULL;

    int mRecentAppsHeldModifiers;
    boolean mLanguageSwitchKeyPressed;

    int mCameraLensCoverState = CAMERA_LENS_COVER_ABSENT;
    boolean mHaveBuiltInKeyboard;

    boolean mSystemReady;
    boolean mSystemBooted;
    HdmiControl mHdmiControl;
    IUiModeManager mUiModeManager;
    int mUiMode;

    boolean mWakeGestureEnabledSetting;
    MyWakeGestureListener mWakeGestureListener;

    int mLidKeyboardAccessibility;
    int mLidNavigationAccessibility;
    private boolean mLidControlsDisplayFold;
    int mShortPressOnPowerBehavior;
    int mLongPressOnPowerBehavior;
    int mVeryLongPressOnPowerBehavior;
    int mDoublePressOnPowerBehavior;
    int mTriplePressOnPowerBehavior;
    int mLongPressOnBackBehavior;
    int mShortPressOnSleepBehavior;
    int mShortPressOnWindowBehavior;
    boolean mHasSoftInput = false;
    boolean mHapticTextHandleEnabled;
    boolean mUseTvRouting;
    int mVeryLongPressTimeout;
    boolean mAllowStartActivityForLongPressOnPowerDuringSetup;
    MetricsLogger mLogger;

    private boolean mHandleVolumeKeysInWM;

    private boolean mPendingKeyguardOccluded;
    private boolean mKeyguardOccludedChanged;
    private boolean mNotifyUserActivity;

    SleepToken mScreenOffSleepToken;
    volatile boolean mKeyguardOccluded;
    Intent mHomeIntent;
    Intent mCarDockIntent;
    Intent mDeskDockIntent;
    Intent mVrHeadsetHomeIntent;
    boolean mSearchKeyShortcutPending;
    boolean mConsumeSearchKeyUp;
    boolean mPendingMetaAction;
    boolean mPendingCapsLockToggle;
    int mMetaState;
    int mInitialMetaState;

    // support for activating the lock screen while the screen is on
    private HashSet<Integer> mAllowLockscreenWhenOnDisplays = new HashSet<>();
    int mLockScreenTimeout;
    boolean mLockScreenTimerActive;

    // Behavior of ENDCALL Button.  (See Settings.System.END_BUTTON_BEHAVIOR.)
    int mEndcallBehavior;

    // Behavior of POWER button while in-call and screen on.
    // (See Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR.)
    int mIncallPowerBehavior;

    // Behavior of Back button while in-call and screen on
    int mIncallBackBehavior;

    // Whether system navigation keys are enabled
    boolean mSystemNavigationKeysEnabled;

    // TODO(b/111361251): Remove default when the dependencies are multi-display ready.
    Display mDefaultDisplay;
    DisplayRotation mDefaultDisplayRotation;
    DisplayPolicy mDefaultDisplayPolicy;

    // What we do when the user long presses on home
    private int mLongPressOnHomeBehavior;

    // What we do when the user double-taps on home
    private int mDoubleTapOnHomeBehavior;

    // Allowed theater mode wake actions
    private boolean mAllowTheaterModeWakeFromKey;
    private boolean mAllowTheaterModeWakeFromPowerKey;
    private boolean mAllowTheaterModeWakeFromMotion;
    private boolean mAllowTheaterModeWakeFromMotionWhenNotDreaming;
    private boolean mAllowTheaterModeWakeFromCameraLens;
    private boolean mAllowTheaterModeWakeFromLidSwitch;
    private boolean mAllowTheaterModeWakeFromWakeGesture;

    // Whether to support long press from power button in non-interactive mode
    private boolean mSupportLongPressPowerWhenNonInteractive;

    // Whether to go to sleep entering theater mode from power button
    private boolean mGoToSleepOnButtonPressTheaterMode;

    // Screenshot trigger states
    // Time to volume and power must be pressed within this interval of each other.
    private static final long SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS = 150;
    // Increase the chord delay when taking a screenshot from the keyguard
    private static final float KEYGUARD_SCREENSHOT_CHORD_DELAY_MULTIPLIER = 2.5f;
    private boolean mScreenshotChordEnabled;
    private boolean mScreenshotChordVolumeDownKeyTriggered;
    private long mScreenshotChordVolumeDownKeyTime;
    private boolean mScreenshotChordVolumeDownKeyConsumed;
    private boolean mA11yShortcutChordVolumeUpKeyTriggered;
    private long mA11yShortcutChordVolumeUpKeyTime;
    private boolean mA11yShortcutChordVolumeUpKeyConsumed;

    private boolean mScreenshotChordPowerKeyTriggered;
    private long mScreenshotChordPowerKeyTime;

    private static final long MOVING_DISPLAY_TO_TOP_DURATION_MILLIS = 10;
    private volatile boolean mMovingDisplayToTopKeyTriggered;
    private volatile long mMovingDisplayToTopKeyTime;

    // Ringer toggle should reuse timing and triggering from screenshot power and a11y vol up
    private int mRingerToggleChord = VOLUME_HUSH_OFF;

    private static final long BUGREPORT_TV_GESTURE_TIMEOUT_MILLIS = 1000;

    private boolean mBugreportTvKey1Pressed;
    private boolean mBugreportTvKey2Pressed;
    private boolean mBugreportTvScheduled;

    private boolean mAccessibilityTvKey1Pressed;
    private boolean mAccessibilityTvKey2Pressed;
    private boolean mAccessibilityTvScheduled;

    /* The number of steps between min and max brightness */
    private static final int BRIGHTNESS_STEPS = 10;

    SettingsObserver mSettingsObserver;
    ShortcutManager mShortcutManager;
    PowerManager.WakeLock mBroadcastWakeLock;
    PowerManager.WakeLock mPowerKeyWakeLock;
    boolean mHavePendingMediaKeyRepeatWithWakeLock;

    private int mCurrentUserId;

    // Maps global key codes to the components that will handle them.
    private GlobalKeyManager mGlobalKeyManager;

    // Fallback actions by key code.
    private final SparseArray<KeyCharacterMap.FallbackAction> mFallbackActions =
            new SparseArray<KeyCharacterMap.FallbackAction>();

    private final LogDecelerateInterpolator mLogDecelerateInterpolator
            = new LogDecelerateInterpolator(100, 0);

    private final MutableBoolean mTmpBoolean = new MutableBoolean(false);

    private boolean mAodShowing;

    private boolean mPerDisplayFocusEnabled = false;
    private volatile int mTopFocusedDisplayId = INVALID_DISPLAY;

    private int mPowerButtonSuppressionDelayMillis = POWER_BUTTON_SUPPRESSION_DELAY_DEFAULT_MILLIS;

    private boolean mLockNowPending = false;

    private static final int MSG_DISPATCH_MEDIA_KEY_WITH_WAKE_LOCK = 3;
    private static final int MSG_DISPATCH_MEDIA_KEY_REPEAT_WITH_WAKE_LOCK = 4;
    private static final int MSG_KEYGUARD_DRAWN_COMPLETE = 5;
    private static final int MSG_KEYGUARD_DRAWN_TIMEOUT = 6;
    private static final int MSG_WINDOW_MANAGER_DRAWN_COMPLETE = 7;
    private static final int MSG_DISPATCH_SHOW_RECENTS = 9;
    private static final int MSG_DISPATCH_SHOW_GLOBAL_ACTIONS = 10;
    private static final int MSG_HIDE_BOOT_MESSAGE = 11;
    private static final int MSG_LAUNCH_VOICE_ASSIST_WITH_WAKE_LOCK = 12;
    private static final int MSG_POWER_DELAYED_PRESS = 13;
    private static final int MSG_POWER_LONG_PRESS = 14;
    private static final int MSG_SHOW_PICTURE_IN_PICTURE_MENU = 15;
    private static final int MSG_BACK_LONG_PRESS = 16;
    private static final int MSG_ACCESSIBILITY_SHORTCUT = 17;
    private static final int MSG_BUGREPORT_TV = 18;
    private static final int MSG_ACCESSIBILITY_TV = 19;
    private static final int MSG_DISPATCH_BACK_KEY_TO_AUTOFILL = 20;
    private static final int MSG_SYSTEM_KEY_PRESS = 21;
    private static final int MSG_HANDLE_ALL_APPS = 22;
    private static final int MSG_LAUNCH_ASSIST = 23;
    private static final int MSG_LAUNCH_ASSIST_LONG_PRESS = 24;
    private static final int MSG_POWER_VERY_LONG_PRESS = 25;
    private static final int MSG_NOTIFY_USER_ACTIVITY = 26;
    private static final int MSG_RINGER_TOGGLE_CHORD = 27;
    private static final int MSG_MOVE_DISPLAY_TO_TOP = 28;

    private class PolicyHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DISPATCH_MEDIA_KEY_WITH_WAKE_LOCK:
                    dispatchMediaKeyWithWakeLock((KeyEvent)msg.obj);
                    break;
                case MSG_DISPATCH_MEDIA_KEY_REPEAT_WITH_WAKE_LOCK:
                    dispatchMediaKeyRepeatWithWakeLock((KeyEvent)msg.obj);
                    break;
                case MSG_DISPATCH_SHOW_RECENTS:
                    showRecentApps(false);
                    break;
                case MSG_DISPATCH_SHOW_GLOBAL_ACTIONS:
                    showGlobalActionsInternal();
                    break;
                case MSG_KEYGUARD_DRAWN_COMPLETE:
                    if (DEBUG_WAKEUP) Slog.w(TAG, "Setting mKeyguardDrawComplete");
                    finishKeyguardDrawn();
                    break;
                case MSG_KEYGUARD_DRAWN_TIMEOUT:
                    Slog.w(TAG, "Keyguard drawn timeout. Setting mKeyguardDrawComplete");
                    finishKeyguardDrawn();
                    break;
                case MSG_WINDOW_MANAGER_DRAWN_COMPLETE:
                    if (DEBUG_WAKEUP) Slog.w(TAG, "Setting mWindowManagerDrawComplete");
                    finishWindowsDrawn();
                    break;
                case MSG_HIDE_BOOT_MESSAGE:
                    handleHideBootMessage();
                    break;
                case MSG_LAUNCH_ASSIST:
                    final int deviceId = msg.arg1;
                    final String hint = (String) msg.obj;
                    launchAssistAction(hint, deviceId);
                    break;
                case MSG_LAUNCH_ASSIST_LONG_PRESS:
                    launchAssistLongPressAction();
                    break;
                case MSG_LAUNCH_VOICE_ASSIST_WITH_WAKE_LOCK:
                    launchVoiceAssistWithWakeLock();
                    break;
                case MSG_POWER_DELAYED_PRESS:
                    powerPress((Long) msg.obj, msg.arg1 != 0, msg.arg2);
                    finishPowerKeyPress();
                    break;
                case MSG_POWER_LONG_PRESS:
                    powerLongPress();
                    break;
                case MSG_POWER_VERY_LONG_PRESS:
                    powerVeryLongPress();
                    break;
                case MSG_SHOW_PICTURE_IN_PICTURE_MENU:
                    showPictureInPictureMenuInternal();
                    break;
                case MSG_BACK_LONG_PRESS:
                    backLongPress();
                    break;
                case MSG_ACCESSIBILITY_SHORTCUT:
                    accessibilityShortcutActivated();
                    break;
                case MSG_BUGREPORT_TV:
                    requestFullBugreport();
                    break;
                case MSG_ACCESSIBILITY_TV:
                    if (mAccessibilityShortcutController.isAccessibilityShortcutAvailable(false)) {
                        accessibilityShortcutActivated();
                    }
                    break;
                case MSG_DISPATCH_BACK_KEY_TO_AUTOFILL:
                    mAutofillManagerInternal.onBackKeyPressed();
                    break;
                case MSG_SYSTEM_KEY_PRESS:
                    sendSystemKeyToStatusBar(msg.arg1);
                    break;
                case MSG_HANDLE_ALL_APPS:
                    launchAllAppsAction();
                    break;
                case MSG_NOTIFY_USER_ACTIVITY:
                    removeMessages(MSG_NOTIFY_USER_ACTIVITY);
                    Intent intent = new Intent(ACTION_USER_ACTIVITY_NOTIFICATION);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                            android.Manifest.permission.USER_ACTIVITY);
                    break;
                case MSG_RINGER_TOGGLE_CHORD:
                    handleRingerChordGesture();
                    break;
                case MSG_MOVE_DISPLAY_TO_TOP:
                    mWindowManagerFuncs.moveDisplayToTop(msg.arg1);
                    mMovingDisplayToTopKeyTriggered = false;
                    break;
            }
        }
    }

    private UEventObserver mHDMIObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEventObserver.UEvent event) {
            mDefaultDisplayPolicy.setHdmiPlugged("1".equals(event.get("SWITCH_STATE")));
        }
    };

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            // Observe all users' changes
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.END_BUTTON_BEHAVIOR), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.INCALL_BACK_BUTTON_BEHAVIOR), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.WAKE_GESTURE_ENABLED), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_OFF_TIMEOUT), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.DEFAULT_INPUT_METHOD), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.VOLUME_HUSH_GESTURE), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.SYSTEM_NAVIGATION_KEYS_ENABLED), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.POWER_BUTTON_LONG_PRESS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.POWER_BUTTON_VERY_LONG_PRESS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.POWER_BUTTON_SUPPRESSION_DELAY_AFTER_GESTURE_WAKE), false, this,
                    UserHandle.USER_ALL);
            updateSettings();
        }

        @Override public void onChange(boolean selfChange) {
            updateSettings();
            updateRotation(false);
        }
    }

    class MyWakeGestureListener extends WakeGestureListener {
        MyWakeGestureListener(Context context, Handler handler) {
            super(context, handler);
        }

        @Override
        public void onWakeUp() {
            synchronized (mLock) {
                if (shouldEnableWakeGestureLp()) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, false,
                            "Wake Up");
                    wakeUp(SystemClock.uptimeMillis(), mAllowTheaterModeWakeFromWakeGesture,
                            PowerManager.WAKE_REASON_GESTURE, "android.policy:GESTURE");
                }
            }
        }
    }

    final IPersistentVrStateCallbacks mPersistentVrModeListener =
            new IPersistentVrStateCallbacks.Stub() {
        @Override
        public void onPersistentVrStateChanged(boolean enabled) {
            mDefaultDisplayPolicy.setPersistentVrModeEnabled(enabled);
        }
    };

    private Runnable mPossibleVeryLongPressReboot = new Runnable() {
        @Override
        public void run() {
            mActivityManagerInternal.prepareForPossibleShutdown();
        }
    };

    private void handleRingerChordGesture() {
        if (mRingerToggleChord == VOLUME_HUSH_OFF) {
            return;
        }
        getAudioManagerInternal();
        mAudioManagerInternal.silenceRingerModeInternal("volume_hush");
        Settings.Secure.putInt(mContext.getContentResolver(), Settings.Secure.HUSH_GESTURE_USED, 1);
        mLogger.action(MetricsProto.MetricsEvent.ACTION_HUSH_GESTURE, mRingerToggleChord);
    }

    IStatusBarService getStatusBarService() {
        synchronized (mServiceAquireLock) {
            if (mStatusBarService == null) {
                mStatusBarService = IStatusBarService.Stub.asInterface(
                        ServiceManager.getService("statusbar"));
            }
            return mStatusBarService;
        }
    }

    StatusBarManagerInternal getStatusBarManagerInternal() {
        synchronized (mServiceAquireLock) {
            if (mStatusBarManagerInternal == null) {
                mStatusBarManagerInternal =
                        LocalServices.getService(StatusBarManagerInternal.class);
            }
            return mStatusBarManagerInternal;
        }
    }

    AudioManagerInternal getAudioManagerInternal() {
        synchronized (mServiceAquireLock) {
            if (mAudioManagerInternal == null) {
                mAudioManagerInternal = LocalServices.getService(AudioManagerInternal.class);
            }
            return mAudioManagerInternal;
        }
    }

    private void interceptBackKeyDown() {
        mLogger.count("key_back_down", 1);
        // Reset back key state for long press
        mBackKeyHandled = false;

        if (hasLongPressOnBackBehavior()) {
            Message msg = mHandler.obtainMessage(MSG_BACK_LONG_PRESS);
            msg.setAsynchronous(true);
            mHandler.sendMessageDelayed(msg,
                    ViewConfiguration.get(mContext).getDeviceGlobalActionKeyTimeout());
        }
    }

    // returns true if the key was handled and should not be passed to the user
    private boolean interceptBackKeyUp(KeyEvent event) {
        mLogger.count("key_back_up", 1);
        // Cache handled state
        boolean handled = mBackKeyHandled;

        // Reset back long press state
        cancelPendingBackKeyAction();

        if (mHasFeatureWatch) {
            TelecomManager telecomManager = getTelecommService();

            if (telecomManager != null) {
                if (telecomManager.isRinging()) {
                    // Pressing back while there's a ringing incoming
                    // call should silence the ringer.
                    telecomManager.silenceRinger();

                    // It should not prevent navigating away
                    return false;
                } else if (
                    (mIncallBackBehavior & Settings.Secure.INCALL_BACK_BUTTON_BEHAVIOR_HANGUP) != 0
                        && telecomManager.isInCall()) {
                    // Otherwise, if "Back button ends call" is enabled,
                    // the Back button will hang up any current active call.
                    return telecomManager.endCall();
                }
            }
        }

        if (mAutofillManagerInternal != null && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_DISPATCH_BACK_KEY_TO_AUTOFILL));
        }

        return handled;
    }

    private void interceptPowerKeyDown(KeyEvent event, boolean interactive) {
        // Hold a wake lock until the power key is released.
        if (!mPowerKeyWakeLock.isHeld()) {
            mPowerKeyWakeLock.acquire();
        }

        // Cancel multi-press detection timeout.
        if (mPowerKeyPressCounter != 0) {
            mHandler.removeMessages(MSG_POWER_DELAYED_PRESS);
        }

        mWindowManagerFuncs.onPowerKeyDown(interactive);

        // Latch power key state to detect screenshot chord.
        if (interactive && !mScreenshotChordPowerKeyTriggered
                && (event.getFlags() & KeyEvent.FLAG_FALLBACK) == 0) {
            mScreenshotChordPowerKeyTriggered = true;
            mScreenshotChordPowerKeyTime = event.getDownTime();
            interceptScreenshotChord();
            interceptRingerToggleChord();
        }

        // Stop ringing or end call if configured to do so when power is pressed.
        TelecomManager telecomManager = getTelecommService();
        boolean hungUp = false;
        if (telecomManager != null) {
            if (telecomManager.isRinging()) {
                // Pressing Power while there's a ringing incoming
                // call should silence the ringer.
                telecomManager.silenceRinger();
            } else if ((mIncallPowerBehavior
                    & Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR_HANGUP) != 0
                    && telecomManager.isInCall() && interactive) {
                // Otherwise, if "Power button ends call" is enabled,
                // the Power button will hang up any current active call.
                hungUp = telecomManager.endCall();
            }
        }

        GestureLauncherService gestureService = LocalServices.getService(
                GestureLauncherService.class);
        boolean gesturedServiceIntercepted = false;
        if (gestureService != null) {
            gesturedServiceIntercepted = gestureService.interceptPowerKeyDown(event, interactive,
                    mTmpBoolean);
            if (mTmpBoolean.value && mRequestedOrGoingToSleep) {
                mCameraGestureTriggeredDuringGoingToSleep = true;
            }
        }

        // Inform the StatusBar; but do not allow it to consume the event.
        sendSystemKeyToStatusBarAsync(event.getKeyCode());

        schedulePossibleVeryLongPressReboot();

        // If the power key has still not yet been handled, then detect short
        // press, long press, or multi press and decide what to do.
        mPowerKeyHandled = hungUp || mScreenshotChordVolumeDownKeyTriggered
                || mA11yShortcutChordVolumeUpKeyTriggered || gesturedServiceIntercepted;
        if (!mPowerKeyHandled) {
            if (interactive) {
                // When interactive, we're already awake.
                // Wait for a long press or for the button to be released to decide what to do.
                if (hasLongPressOnPowerBehavior()) {
                    if ((event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0) {
                        powerLongPress();
                    } else {
                        Message msg = mHandler.obtainMessage(MSG_POWER_LONG_PRESS);
                        msg.setAsynchronous(true);
                        mHandler.sendMessageDelayed(msg,
                                ViewConfiguration.get(mContext).getDeviceGlobalActionKeyTimeout());

                        if (hasVeryLongPressOnPowerBehavior()) {
                            Message longMsg = mHandler.obtainMessage(MSG_POWER_VERY_LONG_PRESS);
                            longMsg.setAsynchronous(true);
                            mHandler.sendMessageDelayed(longMsg, mVeryLongPressTimeout);
                        }
                    }
                }
            } else {
                wakeUpFromPowerKey(event.getDownTime());

                if (mSupportLongPressPowerWhenNonInteractive && hasLongPressOnPowerBehavior()) {
                    if ((event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0) {
                        powerLongPress();
                    } else {
                        Message msg = mHandler.obtainMessage(MSG_POWER_LONG_PRESS);
                        msg.setAsynchronous(true);
                        mHandler.sendMessageDelayed(msg,
                                ViewConfiguration.get(mContext).getDeviceGlobalActionKeyTimeout());

                        if (hasVeryLongPressOnPowerBehavior()) {
                            Message longMsg = mHandler.obtainMessage(MSG_POWER_VERY_LONG_PRESS);
                            longMsg.setAsynchronous(true);
                            mHandler.sendMessageDelayed(longMsg, mVeryLongPressTimeout);
                        }
                    }

                    mBeganFromNonInteractive = true;
                } else {
                    final int maxCount = getMaxMultiPressPowerCount();

                    if (maxCount <= 1) {
                        mPowerKeyHandled = true;
                    } else {
                        mBeganFromNonInteractive = true;
                    }
                }
            }
        }
    }

    private void interceptPowerKeyUp(KeyEvent event, boolean interactive, boolean canceled) {
        final boolean handled = canceled || mPowerKeyHandled;
        mScreenshotChordPowerKeyTriggered = false;
        cancelPendingScreenshotChordAction();
        cancelPendingPowerKeyAction();

        if (!handled) {
            if ((event.getFlags() & KeyEvent.FLAG_LONG_PRESS) == 0) {
                // Abort possibly stuck animations only when power key up without long press case.
                mHandler.post(mWindowManagerFuncs::triggerAnimationFailsafe);
            }

            // Figure out how to handle the key now that it has been released.
            mPowerKeyPressCounter += 1;

            final int maxCount = getMaxMultiPressPowerCount();
            final long eventTime = event.getDownTime();
            if (mPowerKeyPressCounter < maxCount) {
                // This could be a multi-press.  Wait a little bit longer to confirm.
                // Continue holding the wake lock.
                Message msg = mHandler.obtainMessage(MSG_POWER_DELAYED_PRESS,
                        interactive ? 1 : 0, mPowerKeyPressCounter, eventTime);
                msg.setAsynchronous(true);
                mHandler.sendMessageDelayed(msg, ViewConfiguration.getMultiPressTimeout());
                return;
            }

            // No other actions.  Handle it immediately.
            powerPress(eventTime, interactive, mPowerKeyPressCounter);
        }

        // Done.  Reset our state.
        finishPowerKeyPress();
    }

    private void finishPowerKeyPress() {
        mBeganFromNonInteractive = false;
        mPowerKeyPressCounter = 0;
        if (mPowerKeyWakeLock.isHeld()) {
            mPowerKeyWakeLock.release();
        }
    }

    private void cancelPendingPowerKeyAction() {
        if (!mPowerKeyHandled) {
            mPowerKeyHandled = true;
            mHandler.removeMessages(MSG_POWER_LONG_PRESS);
        }
        if (hasVeryLongPressOnPowerBehavior()) {
            mHandler.removeMessages(MSG_POWER_VERY_LONG_PRESS);
        }
        cancelPossibleVeryLongPressReboot();
    }

    private void cancelPendingBackKeyAction() {
        if (!mBackKeyHandled) {
            mBackKeyHandled = true;
            mHandler.removeMessages(MSG_BACK_LONG_PRESS);
        }
    }

    private void powerPress(long eventTime, boolean interactive, int count) {
        if (mDefaultDisplayPolicy.isScreenOnEarly() && !mDefaultDisplayPolicy.isScreenOnFully()) {
            Slog.i(TAG, "Suppressed redundant power key press while "
                    + "already in the process of turning the screen on.");
            return;
        }
        Slog.d(TAG, "powerPress: eventTime=" + eventTime + " interactive=" + interactive
                + " count=" + count + " beganFromNonInteractive=" + mBeganFromNonInteractive +
                " mShortPressOnPowerBehavior=" + mShortPressOnPowerBehavior);

        if (count == 2) {
            powerMultiPressAction(eventTime, interactive, mDoublePressOnPowerBehavior);
        } else if (count == 3) {
            powerMultiPressAction(eventTime, interactive, mTriplePressOnPowerBehavior);
        } else if (interactive && !mBeganFromNonInteractive) {
            switch (mShortPressOnPowerBehavior) {
                case SHORT_PRESS_POWER_NOTHING:
                    break;
                case SHORT_PRESS_POWER_GO_TO_SLEEP:
                    goToSleepFromPowerButton(eventTime, 0);
                    break;
                case SHORT_PRESS_POWER_REALLY_GO_TO_SLEEP:
                    goToSleepFromPowerButton(eventTime, PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE);
                    break;
                case SHORT_PRESS_POWER_REALLY_GO_TO_SLEEP_AND_GO_HOME:
                    if (goToSleepFromPowerButton(eventTime,
                            PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE)) {
                        launchHomeFromHotKey(DEFAULT_DISPLAY);
                    }
                    break;
                case SHORT_PRESS_POWER_GO_HOME:
                    shortPressPowerGoHome();
                    break;
                case SHORT_PRESS_POWER_CLOSE_IME_OR_GO_HOME: {
                    if (mDismissImeOnBackKeyPressed) {
                        if (mInputMethodManagerInternal == null) {
                            mInputMethodManagerInternal =
                                    LocalServices.getService(InputMethodManagerInternal.class);
                        }
                        if (mInputMethodManagerInternal != null) {
                            mInputMethodManagerInternal.hideCurrentInputMethod();
                        }
                    } else {
                        shortPressPowerGoHome();
                    }
                    break;
                }
            }
        }
    }

    /**
     * Sends the device to sleep as a result of a power button press.
     *
     * @return True if the was device was sent to sleep, false if sleep was suppressed.
     */
    private boolean goToSleepFromPowerButton(long eventTime, int flags) {
        // Before we actually go to sleep, we check the last wakeup reason.
        // If the device very recently woke up from a gesture (like user lifting their device)
        // then ignore the sleep instruction. This is because users have developed
        // a tendency to hit the power button immediately when they pick up their device, and we
        // don't want to put the device back to sleep in those cases.
        final PowerManager.WakeData lastWakeUp = mPowerManagerInternal.getLastWakeup();
        if (lastWakeUp != null && lastWakeUp.wakeReason == PowerManager.WAKE_REASON_GESTURE) {
            final int gestureDelayMillis = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.POWER_BUTTON_SUPPRESSION_DELAY_AFTER_GESTURE_WAKE,
                    POWER_BUTTON_SUPPRESSION_DELAY_DEFAULT_MILLIS);
            final long now = SystemClock.uptimeMillis();
            if (mPowerButtonSuppressionDelayMillis > 0
                    && (now < lastWakeUp.wakeTime + mPowerButtonSuppressionDelayMillis)) {
                Slog.i(TAG, "Sleep from power button suppressed. Time since gesture: "
                        + (now - lastWakeUp.wakeTime) + "ms");
                return false;
            }
        }

        goToSleep(eventTime, PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, flags);
        return true;
    }

    private void goToSleep(long eventTime, int reason, int flags) {
        mRequestedOrGoingToSleep = true;
        mPowerManager.goToSleep(eventTime, reason, flags);
    }

    private void shortPressPowerGoHome() {
        launchHomeFromHotKey(DEFAULT_DISPLAY, true /* awakenFromDreams */,
                false /*respectKeyguard*/);
        if (isKeyguardShowingAndNotOccluded()) {
            // Notify keyguard so it can do any special handling for the power button since the
            // device will not power off and only launch home.
            mKeyguardDelegate.onShortPowerPressedGoHome();
        }
    }

    private void powerMultiPressAction(long eventTime, boolean interactive, int behavior) {
        switch (behavior) {
            case MULTI_PRESS_POWER_NOTHING:
                break;
            case MULTI_PRESS_POWER_THEATER_MODE:
                if (!isUserSetupComplete()) {
                    Slog.i(TAG, "Ignoring toggling theater mode - device not setup.");
                    break;
                }

                if (isTheaterModeEnabled()) {
                    Slog.i(TAG, "Toggling theater mode off.");
                    Settings.Global.putInt(mContext.getContentResolver(),
                            Settings.Global.THEATER_MODE_ON, 0);
                    if (!interactive) {
                        wakeUpFromPowerKey(eventTime);
                    }
                } else {
                    Slog.i(TAG, "Toggling theater mode on.");
                    Settings.Global.putInt(mContext.getContentResolver(),
                            Settings.Global.THEATER_MODE_ON, 1);

                    if (mGoToSleepOnButtonPressTheaterMode && interactive) {
                        goToSleep(eventTime, PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, 0);
                    }
                }
                break;
            case MULTI_PRESS_POWER_BRIGHTNESS_BOOST:
                Slog.i(TAG, "Starting brightness boost.");
                if (!interactive) {
                    wakeUpFromPowerKey(eventTime);
                }
                mPowerManager.boostScreenBrightness(eventTime);
                break;
        }
    }

    private int getLidBehavior() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.LID_BEHAVIOR, LID_BEHAVIOR_NONE);
    }

    private int getMaxMultiPressPowerCount() {
        if (mTriplePressOnPowerBehavior != MULTI_PRESS_POWER_NOTHING) {
            return 3;
        }
        if (mDoublePressOnPowerBehavior != MULTI_PRESS_POWER_NOTHING) {
            return 2;
        }
        return 1;
    }

    private void powerLongPress() {
        final int behavior = getResolvedLongPressOnPowerBehavior();
        switch (behavior) {
            case LONG_PRESS_POWER_NOTHING:
                break;
            case LONG_PRESS_POWER_GLOBAL_ACTIONS:
                mPowerKeyHandled = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, false,
                        "Power - Long Press - Global Actions");
                showGlobalActionsInternal();
                break;
            case LONG_PRESS_POWER_SHUT_OFF:
            case LONG_PRESS_POWER_SHUT_OFF_NO_CONFIRM:
                mPowerKeyHandled = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, false,
                        "Power - Long Press - Shut Off");
                sendCloseSystemWindows(SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS);
                mWindowManagerFuncs.shutdown(behavior == LONG_PRESS_POWER_SHUT_OFF);
                break;
            case LONG_PRESS_POWER_GO_TO_VOICE_ASSIST:
                mPowerKeyHandled = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, false,
                        "Power - Long Press - Go To Voice Assist");
                // Some devices allow the voice assistant intent during setup (and use that intent
                // to launch something else, like Settings). So we explicitly allow that via the
                // config_allowStartActivityForLongPressOnPowerInSetup resource in config.xml.
                launchVoiceAssist(mAllowStartActivityForLongPressOnPowerDuringSetup);
                break;
            case LONG_PRESS_POWER_ASSISTANT:
                mPowerKeyHandled = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, false,
                        "Power - Long Press - Go To Assistant");
                final int powerKeyDeviceId = Integer.MIN_VALUE;
                launchAssistAction(null, powerKeyDeviceId);
                break;
        }
    }

    private void powerVeryLongPress() {
        switch (mVeryLongPressOnPowerBehavior) {
        case VERY_LONG_PRESS_POWER_NOTHING:
            break;
        case VERY_LONG_PRESS_POWER_GLOBAL_ACTIONS:
            mPowerKeyHandled = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, false,
                    "Power - Very Long Press - Show Global Actions");
            showGlobalActionsInternal();
            break;
        }
    }

    private void backLongPress() {
        mBackKeyHandled = true;

        switch (mLongPressOnBackBehavior) {
            case LONG_PRESS_BACK_NOTHING:
                break;
            case LONG_PRESS_BACK_GO_TO_VOICE_ASSIST:
                launchVoiceAssist(false /* allowDuringSetup */);
                break;
        }
    }

    private void accessibilityShortcutActivated() {
        mAccessibilityShortcutController.performAccessibilityShortcut();
    }

    private void sleepPress() {
        if (mShortPressOnSleepBehavior == SHORT_PRESS_SLEEP_GO_TO_SLEEP_AND_GO_HOME) {
            launchHomeFromHotKey(DEFAULT_DISPLAY, false /* awakenDreams */,
                    true /*respectKeyguard*/);
        }
    }

    private void sleepRelease(long eventTime) {
        switch (mShortPressOnSleepBehavior) {
            case SHORT_PRESS_SLEEP_GO_TO_SLEEP:
            case SHORT_PRESS_SLEEP_GO_TO_SLEEP_AND_GO_HOME:
                Slog.i(TAG, "sleepRelease() calling goToSleep(GO_TO_SLEEP_REASON_SLEEP_BUTTON)");
                goToSleep(eventTime, PowerManager.GO_TO_SLEEP_REASON_SLEEP_BUTTON, 0);
                break;
        }
    }

    private int getResolvedLongPressOnPowerBehavior() {
        if (FactoryTest.isLongPressOnPowerOffEnabled()) {
            return LONG_PRESS_POWER_SHUT_OFF_NO_CONFIRM;
        }
        return mLongPressOnPowerBehavior;
    }

    private boolean hasLongPressOnPowerBehavior() {
        return getResolvedLongPressOnPowerBehavior() != LONG_PRESS_POWER_NOTHING;
    }

    private boolean hasVeryLongPressOnPowerBehavior() {
        return mVeryLongPressOnPowerBehavior != VERY_LONG_PRESS_POWER_NOTHING;
    }

    private boolean hasLongPressOnBackBehavior() {
        return mLongPressOnBackBehavior != LONG_PRESS_BACK_NOTHING;
    }

    private void interceptScreenshotChord() {
        if (mScreenshotChordEnabled
                && mScreenshotChordVolumeDownKeyTriggered && mScreenshotChordPowerKeyTriggered
                && !mA11yShortcutChordVolumeUpKeyTriggered) {
            final long now = SystemClock.uptimeMillis();
            if (now <= mScreenshotChordVolumeDownKeyTime + SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS
                    && now <= mScreenshotChordPowerKeyTime
                            + SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS) {
                mScreenshotChordVolumeDownKeyConsumed = true;
                cancelPendingPowerKeyAction();
                mScreenshotRunnable.setScreenshotType(TAKE_SCREENSHOT_FULLSCREEN);
                mHandler.postDelayed(mScreenshotRunnable, getScreenshotChordLongPressDelay());
            }
        }
    }

    private void interceptAccessibilityShortcutChord() {
        if (mAccessibilityShortcutController.isAccessibilityShortcutAvailable(isKeyguardLocked())
                && mScreenshotChordVolumeDownKeyTriggered && mA11yShortcutChordVolumeUpKeyTriggered
                && !mScreenshotChordPowerKeyTriggered) {
            final long now = SystemClock.uptimeMillis();
            if (now <= mScreenshotChordVolumeDownKeyTime + SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS
                    && now <= mA11yShortcutChordVolumeUpKeyTime
                    + SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS) {
                mScreenshotChordVolumeDownKeyConsumed = true;
                mA11yShortcutChordVolumeUpKeyConsumed = true;
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_ACCESSIBILITY_SHORTCUT),
                        getAccessibilityShortcutTimeout());
            }
        }
    }

    private void interceptRingerToggleChord() {
        if (mRingerToggleChord != Settings.Secure.VOLUME_HUSH_OFF
                && mScreenshotChordPowerKeyTriggered && mA11yShortcutChordVolumeUpKeyTriggered) {
            final long now = SystemClock.uptimeMillis();
            if (now <= mA11yShortcutChordVolumeUpKeyTime + SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS
                    && now <= mScreenshotChordPowerKeyTime
                    + SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS) {
                mA11yShortcutChordVolumeUpKeyConsumed = true;
                cancelPendingPowerKeyAction();

                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_RINGER_TOGGLE_CHORD),
                        getRingerToggleChordDelay());
            }
        }
    }

    private long getAccessibilityShortcutTimeout() {
        ViewConfiguration config = ViewConfiguration.get(mContext);
        return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 0, mCurrentUserId) == 0
                ? config.getAccessibilityShortcutKeyTimeout()
                : config.getAccessibilityShortcutKeyTimeoutAfterConfirmation();
    }

    private long getScreenshotChordLongPressDelay() {
        if (mKeyguardDelegate.isShowing()) {
            // Double the time it takes to take a screenshot from the keyguard
            return (long) (KEYGUARD_SCREENSHOT_CHORD_DELAY_MULTIPLIER *
                    ViewConfiguration.get(mContext).getScreenshotChordKeyTimeout());
        }
        return ViewConfiguration.get(mContext).getScreenshotChordKeyTimeout();
    }

    private long getRingerToggleChordDelay() {
        // Always timeout like a tap
        return ViewConfiguration.getTapTimeout();
    }

    private void cancelPendingScreenshotChordAction() {
        mHandler.removeCallbacks(mScreenshotRunnable);
    }

    private void cancelPendingAccessibilityShortcutAction() {
        mHandler.removeMessages(MSG_ACCESSIBILITY_SHORTCUT);
    }

    private void cancelPendingRingerToggleChordAction() {
        mHandler.removeMessages(MSG_RINGER_TOGGLE_CHORD);
    }

    private final Runnable mEndCallLongPress = new Runnable() {
        @Override
        public void run() {
            mEndCallKeyHandled = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, false,
                    "End Call - Long Press - Show Global Actions");
            showGlobalActionsInternal();
        }
    };

    private class ScreenshotRunnable implements Runnable {
        private int mScreenshotType = TAKE_SCREENSHOT_FULLSCREEN;

        public void setScreenshotType(int screenshotType) {
            mScreenshotType = screenshotType;
        }

        @Override
        public void run() {
            mDefaultDisplayPolicy.takeScreenshot(mScreenshotType);
        }
    }

    private final ScreenshotRunnable mScreenshotRunnable = new ScreenshotRunnable();

    @Override
    public void showGlobalActions() {
        mHandler.removeMessages(MSG_DISPATCH_SHOW_GLOBAL_ACTIONS);
        mHandler.sendEmptyMessage(MSG_DISPATCH_SHOW_GLOBAL_ACTIONS);
    }

    void showGlobalActionsInternal() {
        if (mGlobalActions == null) {
            mGlobalActions = new GlobalActions(mContext, mWindowManagerFuncs);
        }
        final boolean keyguardShowing = isKeyguardShowingAndNotOccluded();
        mGlobalActions.showDialog(keyguardShowing, isDeviceProvisioned());
        // since it took two seconds of long press to bring this up,
        // poke the wake lock so they have some time to see the dialog.
        mPowerManager.userActivity(SystemClock.uptimeMillis(), false);
    }

    boolean isDeviceProvisioned() {
        return Settings.Global.getInt(
                mContext.getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 0) != 0;
    }

    @Override
    public boolean isUserSetupComplete() {
        boolean isSetupComplete = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0, UserHandle.USER_CURRENT) != 0;
        if (mHasFeatureLeanback) {
            isSetupComplete &= isTvUserSetupComplete();
        }
        return isSetupComplete;
    }

    private boolean isTvUserSetupComplete() {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.TV_USER_SETUP_COMPLETE, 0, UserHandle.USER_CURRENT) != 0;
    }

    private void handleShortPressOnHome(int displayId) {
        // Turn on the connected TV and switch HDMI input if we're a HDMI playback device.
        final HdmiControl hdmiControl = getHdmiControl();
        if (hdmiControl != null) {
            hdmiControl.turnOnTv();
        }

        // If there's a dream running then use home to escape the dream
        // but don't actually go home.
        if (mDreamManagerInternal != null && mDreamManagerInternal.isDreaming()) {
            mDreamManagerInternal.stopDream(false /*immediate*/);
            return;
        }

        // Go home!
        launchHomeFromHotKey(displayId);
    }

    /**
     * Creates an accessor to HDMI control service that performs the operation of
     * turning on TV (optional) and switching input to us. If HDMI control service
     * is not available or we're not a HDMI playback device, the operation is no-op.
     * @return {@link HdmiControl} instance if available, null otherwise.
     */
    private HdmiControl getHdmiControl() {
        if (null == mHdmiControl) {
            if (!mHasFeatureHdmiCec) {
                return null;
            }
            HdmiControlManager manager = (HdmiControlManager) mContext.getSystemService(
                        Context.HDMI_CONTROL_SERVICE);
            HdmiPlaybackClient client = null;
            if (manager != null) {
                client = manager.getPlaybackClient();
            }
            mHdmiControl = new HdmiControl(client);
        }
        return mHdmiControl;
    }

    private static class HdmiControl {
        private final HdmiPlaybackClient mClient;

        private HdmiControl(HdmiPlaybackClient client) {
            mClient = client;
        }

        public void turnOnTv() {
            if (mClient == null) {
                return;
            }
            mClient.oneTouchPlay(new OneTouchPlayCallback() {
                @Override
                public void onComplete(int result) {
                    if (result != HdmiControlManager.RESULT_SUCCESS) {
                        Log.w(TAG, "One touch play failed: " + result);
                    }
                }
            });
        }
    }

    private void launchAllAppsAction() {
        Intent intent = new Intent(Intent.ACTION_ALL_APPS);
        if (mHasFeatureLeanback) {
            final PackageManager pm = mContext.getPackageManager();
            Intent intentLauncher = new Intent(Intent.ACTION_MAIN);
            intentLauncher.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo resolveInfo = pm.resolveActivityAsUser(intentLauncher,
                    PackageManager.MATCH_SYSTEM_ONLY,
                    mCurrentUserId);
            if (resolveInfo != null) {
                intent.setPackage(resolveInfo.activityInfo.packageName);
            }
        }
        startActivityAsUser(intent, UserHandle.CURRENT);
    }

    private void showPictureInPictureMenu(KeyEvent event) {
        if (DEBUG_INPUT) Log.d(TAG, "showPictureInPictureMenu event=" + event);
        mHandler.removeMessages(MSG_SHOW_PICTURE_IN_PICTURE_MENU);
        Message msg = mHandler.obtainMessage(MSG_SHOW_PICTURE_IN_PICTURE_MENU);
        msg.setAsynchronous(true);
        msg.sendToTarget();
    }

    private void showPictureInPictureMenuInternal() {
        StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
        if (statusbar != null) {
            statusbar.showPictureInPictureMenu();
        }
    }

    /** A handler to handle home keys per display */
    private class DisplayHomeButtonHandler {

        private final int mDisplayId;

        private boolean mHomeDoubleTapPending;
        private boolean mHomePressed;
        private boolean mHomeConsumed;

        private final Runnable mHomeDoubleTapTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (mHomeDoubleTapPending) {
                    mHomeDoubleTapPending = false;
                    handleShortPressOnHome(mDisplayId);
                }
            }
        };

        DisplayHomeButtonHandler(int displayId) {
            mDisplayId = displayId;
        }

        int handleHomeButton(WindowState win, KeyEvent event) {
            final boolean keyguardOn = keyguardOn();
            final int repeatCount = event.getRepeatCount();
            final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            final boolean canceled = event.isCanceled();

            if (DEBUG_INPUT) {
                Log.d(TAG, String.format("handleHomeButton in display#%d mHomePressed = %b",
                        mDisplayId, mHomePressed));
            }

            // If we have released the home key, and didn't do anything else
            // while it was pressed, then it is time to go home!
            if (!down) {
                if (mDisplayId == DEFAULT_DISPLAY) {
                    cancelPreloadRecentApps();
                }

                mHomePressed = false;
                if (mHomeConsumed) {
                    mHomeConsumed = false;
                    return -1;
                }

                if (canceled) {
                    Log.i(TAG, "Ignoring HOME; event canceled.");
                    return -1;
                }

                // Delay handling home if a double-tap is possible.
                if (mDoubleTapOnHomeBehavior != DOUBLE_TAP_HOME_NOTHING) {
                    mHandler.removeCallbacks(mHomeDoubleTapTimeoutRunnable); // just in case
                    mHomeDoubleTapPending = true;
                    mHandler.postDelayed(mHomeDoubleTapTimeoutRunnable,
                            ViewConfiguration.getDoubleTapTimeout());
                    return -1;
                }

                // Post to main thread to avoid blocking input pipeline.
                mHandler.post(() -> handleShortPressOnHome(mDisplayId));
                return -1;
            }

            // If a system window has focus, then it doesn't make sense
            // right now to interact with applications.
            WindowManager.LayoutParams attrs = win != null ? win.getAttrs() : null;
            if (attrs != null) {
                final int type = attrs.type;
                if (type == TYPE_KEYGUARD_DIALOG
                        || (attrs.privateFlags & PRIVATE_FLAG_KEYGUARD) != 0) {
                    // the "app" is keyguard, so give it the key
                    return 0;
                }
                for (int t : WINDOW_TYPES_WHERE_HOME_DOESNT_WORK) {
                    if (type == t) {
                        // don't do anything, but also don't pass it to the app
                        return -1;
                    }
                }
            }

            // Remember that home is pressed and handle special actions.
            if (repeatCount == 0) {
                mHomePressed = true;
                if (mHomeDoubleTapPending) {
                    mHomeDoubleTapPending = false;
                    mHandler.removeCallbacks(mHomeDoubleTapTimeoutRunnable);
                    handleDoubleTapOnHome();
                // TODO(multi-display): Remove display id check once we support recents on
                // multi-display
                } else if (mDoubleTapOnHomeBehavior == DOUBLE_TAP_HOME_RECENT_SYSTEM_UI
                        && mDisplayId == DEFAULT_DISPLAY) {
                    preloadRecentApps();
                }
            } else if ((event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0) {
                if (!keyguardOn) {
                    // Post to main thread to avoid blocking input pipeline.
                    mHandler.post(() -> handleLongPressOnHome(event.getDeviceId()));
                }
            }
            return -1;
        }

        private void handleDoubleTapOnHome() {
            if (mDoubleTapOnHomeBehavior == DOUBLE_TAP_HOME_RECENT_SYSTEM_UI) {
                mHomeConsumed = true;
                toggleRecentApps();
            }
        }

        private void handleLongPressOnHome(int deviceId) {
            if (mLongPressOnHomeBehavior == LONG_PRESS_HOME_NOTHING) {
                return;
            }
            mHomeConsumed = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, false,
                    "Home - Long Press");
            switch (mLongPressOnHomeBehavior) {
                case LONG_PRESS_HOME_ALL_APPS:
                    launchAllAppsAction();
                    break;
                case LONG_PRESS_HOME_ASSIST:
                    launchAssistAction(null, deviceId);
                    break;
                default:
                    Log.w(TAG, "Undefined home long press behavior: "
                            + mLongPressOnHomeBehavior);
                    break;
            }
        }

        @Override
        public String toString() {
            return String.format("mDisplayId = %d, mHomePressed = %b", mDisplayId, mHomePressed);
        }
    }

    /** A DisplayHomeButtonHandler map indexed by display id */
    private final SparseArray<DisplayHomeButtonHandler> mDisplayHomeButtonHandlers =
            new SparseArray<>();

    private boolean isRoundWindow() {
        return mContext.getResources().getConfiguration().isScreenRound();
    }

    @Override
    public void setDefaultDisplay(DisplayContentInfo displayContentInfo) {
        mDefaultDisplay = displayContentInfo.getDisplay();
        mDefaultDisplayRotation = displayContentInfo.getDisplayRotation();
        mDefaultDisplayPolicy = mDefaultDisplayRotation.getDisplayPolicy();
    }

    /** {@inheritDoc} */
    @Override
    public void init(Context context, IWindowManager windowManager,
            WindowManagerFuncs windowManagerFuncs) {
        mContext = context;
        mWindowManager = windowManager;
        mWindowManagerFuncs = windowManagerFuncs;
        mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mActivityTaskManagerInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
        mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);
        mDreamManagerInternal = LocalServices.getService(DreamManagerInternal.class);
        mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
        mAppOpsManager = mContext.getSystemService(AppOpsManager.class);
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        mHasFeatureWatch = mContext.getPackageManager().hasSystemFeature(FEATURE_WATCH);
        mHasFeatureLeanback = mContext.getPackageManager().hasSystemFeature(FEATURE_LEANBACK);
        mHasFeatureHdmiCec = mContext.getPackageManager().hasSystemFeature(FEATURE_HDMI_CEC);
        mAccessibilityShortcutController =
                new AccessibilityShortcutController(mContext, new Handler(), mCurrentUserId);
        mLogger = new MetricsLogger();
        // Init display burn-in protection
        boolean burnInProtectionEnabled = context.getResources().getBoolean(
                com.android.internal.R.bool.config_enableBurnInProtection);
        // Allow a system property to override this. Used by developer settings.
        boolean burnInProtectionDevMode =
                SystemProperties.getBoolean("persist.debug.force_burn_in", false);
        if (burnInProtectionEnabled || burnInProtectionDevMode) {
            final int minHorizontal;
            final int maxHorizontal;
            final int minVertical;
            final int maxVertical;
            final int maxRadius;
            if (burnInProtectionDevMode) {
                minHorizontal = -8;
                maxHorizontal = 8;
                minVertical = -8;
                maxVertical = -4;
                maxRadius = (isRoundWindow()) ? 6 : -1;
            } else {
                Resources resources = context.getResources();
                minHorizontal = resources.getInteger(
                        com.android.internal.R.integer.config_burnInProtectionMinHorizontalOffset);
                maxHorizontal = resources.getInteger(
                        com.android.internal.R.integer.config_burnInProtectionMaxHorizontalOffset);
                minVertical = resources.getInteger(
                        com.android.internal.R.integer.config_burnInProtectionMinVerticalOffset);
                maxVertical = resources.getInteger(
                        com.android.internal.R.integer.config_burnInProtectionMaxVerticalOffset);
                maxRadius = resources.getInteger(
                        com.android.internal.R.integer.config_burnInProtectionMaxRadius);
            }
            mBurnInProtectionHelper = new BurnInProtectionHelper(
                    context, minHorizontal, maxHorizontal, minVertical, maxVertical, maxRadius);
        }

        mHandler = new PolicyHandler();
        mWakeGestureListener = new MyWakeGestureListener(mContext, mHandler);
        mSettingsObserver = new SettingsObserver(mHandler);
        mSettingsObserver.observe();
        mShortcutManager = new ShortcutManager(context);
        mUiMode = context.getResources().getInteger(
                com.android.internal.R.integer.config_defaultUiModeType);
        mHomeIntent =  new Intent(Intent.ACTION_MAIN, null);
        mHomeIntent.addCategory(Intent.CATEGORY_HOME);
        mHomeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        mEnableCarDockHomeCapture = context.getResources().getBoolean(
                com.android.internal.R.bool.config_enableCarDockHomeLaunch);
        mCarDockIntent =  new Intent(Intent.ACTION_MAIN, null);
        mCarDockIntent.addCategory(Intent.CATEGORY_CAR_DOCK);
        mCarDockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        mDeskDockIntent =  new Intent(Intent.ACTION_MAIN, null);
        mDeskDockIntent.addCategory(Intent.CATEGORY_DESK_DOCK);
        mDeskDockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        mVrHeadsetHomeIntent =  new Intent(Intent.ACTION_MAIN, null);
        mVrHeadsetHomeIntent.addCategory(Intent.CATEGORY_VR_HOME);
        mVrHeadsetHomeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        mPowerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mBroadcastWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "PhoneWindowManager.mBroadcastWakeLock");
        mPowerKeyWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "PhoneWindowManager.mPowerKeyWakeLock");
        mEnableShiftMenuBugReports = "1".equals(SystemProperties.get("ro.debuggable"));
        mLidKeyboardAccessibility = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lidKeyboardAccessibility);
        mLidNavigationAccessibility = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lidNavigationAccessibility);
        mLidControlsDisplayFold = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_lidControlsDisplayFold);

        mAllowTheaterModeWakeFromKey = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromKey);
        mAllowTheaterModeWakeFromPowerKey = mAllowTheaterModeWakeFromKey
                || mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_allowTheaterModeWakeFromPowerKey);
        mAllowTheaterModeWakeFromMotion = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromMotion);
        mAllowTheaterModeWakeFromMotionWhenNotDreaming = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromMotionWhenNotDreaming);
        mAllowTheaterModeWakeFromCameraLens = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromCameraLens);
        mAllowTheaterModeWakeFromLidSwitch = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromLidSwitch);
        mAllowTheaterModeWakeFromWakeGesture = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromGesture);

        mGoToSleepOnButtonPressTheaterMode = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_goToSleepOnButtonPressTheaterMode);

        mSupportLongPressPowerWhenNonInteractive = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_supportLongPressPowerWhenNonInteractive);

        mLongPressOnBackBehavior = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_longPressOnBackBehavior);

        mShortPressOnPowerBehavior = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_shortPressOnPowerBehavior);
        mLongPressOnPowerBehavior = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_longPressOnPowerBehavior);
        mVeryLongPressOnPowerBehavior = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_veryLongPressOnPowerBehavior);
        mDoublePressOnPowerBehavior = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_doublePressOnPowerBehavior);
        mTriplePressOnPowerBehavior = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_triplePressOnPowerBehavior);
        mShortPressOnSleepBehavior = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_shortPressOnSleepBehavior);
        mVeryLongPressTimeout = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_veryLongPressTimeout);
        mAllowStartActivityForLongPressOnPowerDuringSetup = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_allowStartActivityForLongPressOnPowerInSetup);

        mHapticTextHandleEnabled = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_enableHapticTextHandle);

        mUseTvRouting = AudioSystem.getPlatformType(mContext) == AudioSystem.PLATFORM_TELEVISION;

        mHandleVolumeKeysInWM = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_handleVolumeKeysInWindowManager);

        mPerDisplayFocusEnabled = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_perDisplayFocusEnabled);

        readConfigurationDependentBehaviors();

        if (mLidControlsDisplayFold) {
            mDisplayFoldController = DisplayFoldController.create(context, DEFAULT_DISPLAY);
        } else if (SystemProperties.getBoolean("persist.debug.force_foldable", false)) {
            mDisplayFoldController = DisplayFoldController.createWithProxSensor(context,
                    DEFAULT_DISPLAY);
        }

        mAccessibilityManager = (AccessibilityManager) context.getSystemService(
                Context.ACCESSIBILITY_SERVICE);

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
            mDefaultDisplayPolicy.setDockMode(intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                    Intent.EXTRA_DOCK_STATE_UNDOCKED));
        }

        // register for dream-related broadcasts
        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_DREAMING_STARTED);
        filter.addAction(Intent.ACTION_DREAMING_STOPPED);
        context.registerReceiver(mDreamReceiver, filter);

        // register for multiuser-relevant broadcasts
        filter = new IntentFilter(Intent.ACTION_USER_SWITCHED);
        context.registerReceiver(mMultiuserReceiver, filter);

        mVibrator = (Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE);
        mLongPressVibePattern = getLongIntArray(mContext.getResources(),
                com.android.internal.R.array.config_longPressVibePattern);
        mCalendarDateVibePattern = getLongIntArray(mContext.getResources(),
                com.android.internal.R.array.config_calendarDateVibePattern);
        mSafeModeEnabledVibePattern = getLongIntArray(mContext.getResources(),
                com.android.internal.R.array.config_safeModeEnabledVibePattern);

        mScreenshotChordEnabled = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_enableScreenshotChord);

        mGlobalKeyManager = new GlobalKeyManager(mContext);

        // Controls rotation and the like.
        initializeHdmiState();

        // Match current screen state.
        if (!mPowerManager.isInteractive()) {
            startedGoingToSleep(WindowManagerPolicy.OFF_BECAUSE_OF_USER);
            finishedGoingToSleep(WindowManagerPolicy.OFF_BECAUSE_OF_USER);
        }

        mWindowManagerInternal.registerAppTransitionListener(new AppTransitionListener() {
            @Override
            public int onAppTransitionStartingLocked(int transit, long duration,
                    long statusBarAnimationStartTime, long statusBarAnimationDuration) {
                return handleStartTransitionForKeyguardLw(transit, duration);
            }

            @Override
            public void onAppTransitionCancelledLocked(int transit) {
                handleStartTransitionForKeyguardLw(transit, 0 /* duration */);
            }
        });
        mKeyguardDelegate = new KeyguardServiceDelegate(mContext,
                new StateCallback() {
                    @Override
                    public void onTrustedChanged() {
                        mWindowManagerFuncs.notifyKeyguardTrustedChanged();
                    }

                    @Override
                    public void onShowingChanged() {
                        mWindowManagerFuncs.onKeyguardShowingAndNotOccludedChanged();
                    }
                });
    }

    /**
     * Read values from config.xml that may be overridden depending on
     * the configuration of the device.
     * eg. Disable long press on home goes to recents on sw600dp.
     */
    private void readConfigurationDependentBehaviors() {
        final Resources res = mContext.getResources();

        mLongPressOnHomeBehavior = res.getInteger(
                com.android.internal.R.integer.config_longPressOnHomeBehavior);
        if (mLongPressOnHomeBehavior < LONG_PRESS_HOME_NOTHING ||
                mLongPressOnHomeBehavior > LAST_LONG_PRESS_HOME_BEHAVIOR) {
            mLongPressOnHomeBehavior = LONG_PRESS_HOME_NOTHING;
        }

        mDoubleTapOnHomeBehavior = res.getInteger(
                com.android.internal.R.integer.config_doubleTapOnHomeBehavior);
        if (mDoubleTapOnHomeBehavior < DOUBLE_TAP_HOME_NOTHING ||
                mDoubleTapOnHomeBehavior > DOUBLE_TAP_HOME_RECENT_SYSTEM_UI) {
            mDoubleTapOnHomeBehavior = LONG_PRESS_HOME_NOTHING;
        }

        mShortPressOnWindowBehavior = SHORT_PRESS_WINDOW_NOTHING;
        if (mContext.getPackageManager().hasSystemFeature(FEATURE_PICTURE_IN_PICTURE)) {
            mShortPressOnWindowBehavior = SHORT_PRESS_WINDOW_PICTURE_IN_PICTURE;
        }
    }

    public void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();
        boolean updateRotation = false;
        synchronized (mLock) {
            mEndcallBehavior = Settings.System.getIntForUser(resolver,
                    Settings.System.END_BUTTON_BEHAVIOR,
                    Settings.System.END_BUTTON_BEHAVIOR_DEFAULT,
                    UserHandle.USER_CURRENT);
            mIncallPowerBehavior = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR,
                    Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR_DEFAULT,
                    UserHandle.USER_CURRENT);
            mIncallBackBehavior = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.INCALL_BACK_BUTTON_BEHAVIOR,
                    Settings.Secure.INCALL_BACK_BUTTON_BEHAVIOR_DEFAULT,
                    UserHandle.USER_CURRENT);
            mSystemNavigationKeysEnabled = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.SYSTEM_NAVIGATION_KEYS_ENABLED,
                    0, UserHandle.USER_CURRENT) == 1;
            mRingerToggleChord = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.VOLUME_HUSH_GESTURE, VOLUME_HUSH_OFF,
                    UserHandle.USER_CURRENT);
            mPowerButtonSuppressionDelayMillis = Settings.Global.getInt(resolver,
                    Settings.Global.POWER_BUTTON_SUPPRESSION_DELAY_AFTER_GESTURE_WAKE,
                    POWER_BUTTON_SUPPRESSION_DELAY_DEFAULT_MILLIS);
            if (!mContext.getResources()
                    .getBoolean(com.android.internal.R.bool.config_volumeHushGestureEnabled)) {
                mRingerToggleChord = Settings.Secure.VOLUME_HUSH_OFF;
            }

            // Configure wake gesture.
            boolean wakeGestureEnabledSetting = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.WAKE_GESTURE_ENABLED, 0,
                    UserHandle.USER_CURRENT) != 0;
            if (mWakeGestureEnabledSetting != wakeGestureEnabledSetting) {
                mWakeGestureEnabledSetting = wakeGestureEnabledSetting;
                updateWakeGestureListenerLp();
            }

            // use screen off timeout setting as the timeout for the lockscreen
            mLockScreenTimeout = Settings.System.getIntForUser(resolver,
                    Settings.System.SCREEN_OFF_TIMEOUT, 0, UserHandle.USER_CURRENT);
            String imId = Settings.Secure.getStringForUser(resolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD, UserHandle.USER_CURRENT);
            boolean hasSoftInput = imId != null && imId.length() > 0;
            if (mHasSoftInput != hasSoftInput) {
                mHasSoftInput = hasSoftInput;
                updateRotation = true;
            }

            mLongPressOnPowerBehavior = Settings.Global.getInt(resolver,
                    Settings.Global.POWER_BUTTON_LONG_PRESS,
                    mContext.getResources().getInteger(
                            com.android.internal.R.integer.config_longPressOnPowerBehavior));
            mVeryLongPressOnPowerBehavior = Settings.Global.getInt(resolver,
                    Settings.Global.POWER_BUTTON_VERY_LONG_PRESS,
                    mContext.getResources().getInteger(
                            com.android.internal.R.integer.config_veryLongPressOnPowerBehavior));
        }
        if (updateRotation) {
            updateRotation(true);
        }
    }

    private void updateWakeGestureListenerLp() {
        if (shouldEnableWakeGestureLp()) {
            mWakeGestureListener.requestWakeUpTrigger();
        } else {
            mWakeGestureListener.cancelWakeUpTrigger();
        }
    }

    private boolean shouldEnableWakeGestureLp() {
        return mWakeGestureEnabledSetting && !mDefaultDisplayPolicy.isAwake()
                && (getLidBehavior() != LID_BEHAVIOR_SLEEP
                || mDefaultDisplayPolicy.getLidState() != LID_CLOSED)
                && mWakeGestureListener.isSupported();
    }

    /** {@inheritDoc} */
    @Override
    public int checkAddPermission(WindowManager.LayoutParams attrs, int[] outAppOp) {
        final int type = attrs.type;
        final boolean isRoundedCornerOverlay =
                (attrs.privateFlags & PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY) != 0;

        if (isRoundedCornerOverlay && mContext.checkCallingOrSelfPermission(INTERNAL_SYSTEM_WINDOW)
                != PERMISSION_GRANTED) {
            return ADD_PERMISSION_DENIED;
        }

        outAppOp[0] = AppOpsManager.OP_NONE;

        if (!((type >= FIRST_APPLICATION_WINDOW && type <= LAST_APPLICATION_WINDOW)
                || (type >= FIRST_SUB_WINDOW && type <= LAST_SUB_WINDOW)
                || (type >= FIRST_SYSTEM_WINDOW && type <= LAST_SYSTEM_WINDOW))) {
            return WindowManagerGlobal.ADD_INVALID_TYPE;
        }

        if (type < FIRST_SYSTEM_WINDOW || type > LAST_SYSTEM_WINDOW) {
            // Window manager will make sure these are okay.
            return ADD_OKAY;
        }

        if (!isSystemAlertWindowType(type)) {
            switch (type) {
                case TYPE_TOAST:
                    // Only apps that target older than O SDK can add window without a token, after
                    // that we require a token so apps cannot add toasts directly as the token is
                    // added by the notification system.
                    // Window manager does the checking for this.
                    outAppOp[0] = OP_TOAST_WINDOW;
                    return ADD_OKAY;
                case TYPE_DREAM:
                case TYPE_INPUT_METHOD:
                case TYPE_WALLPAPER:
                case TYPE_PRESENTATION:
                case TYPE_PRIVATE_PRESENTATION:
                case TYPE_VOICE_INTERACTION:
                case TYPE_ACCESSIBILITY_OVERLAY:
                case TYPE_QS_DIALOG:
                    // The window manager will check these.
                    return ADD_OKAY;
            }
            return mContext.checkCallingOrSelfPermission(INTERNAL_SYSTEM_WINDOW)
                    == PERMISSION_GRANTED ? ADD_OKAY : ADD_PERMISSION_DENIED;
        }

        // Things get a little more interesting for alert windows...
        outAppOp[0] = OP_SYSTEM_ALERT_WINDOW;

        final int callingUid = Binder.getCallingUid();
        // system processes will be automatically granted privilege to draw
        if (UserHandle.getAppId(callingUid) == Process.SYSTEM_UID) {
            return ADD_OKAY;
        }

        ApplicationInfo appInfo;
        try {
            appInfo = mContext.getPackageManager().getApplicationInfoAsUser(
                            attrs.packageName,
                            0 /* flags */,
                            UserHandle.getUserId(callingUid));
        } catch (PackageManager.NameNotFoundException e) {
            appInfo = null;
        }

        if (appInfo == null || (type != TYPE_APPLICATION_OVERLAY && appInfo.targetSdkVersion >= O)) {
            /**
             * Apps targeting >= {@link Build.VERSION_CODES#O} are required to hold
             * {@link android.Manifest.permission#INTERNAL_SYSTEM_WINDOW} (system signature apps)
             * permission to add alert windows that aren't
             * {@link android.view.WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY}.
             */
            return (mContext.checkCallingOrSelfPermission(INTERNAL_SYSTEM_WINDOW)
                    == PERMISSION_GRANTED) ? ADD_OKAY : ADD_PERMISSION_DENIED;
        }

        // check if user has enabled this operation. SecurityException will be thrown if this app
        // has not been allowed by the user
        final int mode = mAppOpsManager.noteOpNoThrow(outAppOp[0], callingUid, attrs.packageName);
        switch (mode) {
            case AppOpsManager.MODE_ALLOWED:
            case AppOpsManager.MODE_IGNORED:
                // although we return ADD_OKAY for MODE_IGNORED, the added window will
                // actually be hidden in WindowManagerService
                return ADD_OKAY;
            case AppOpsManager.MODE_ERRORED:
                // Don't crash legacy apps
                if (appInfo.targetSdkVersion < M) {
                    return ADD_OKAY;
                }
                return ADD_PERMISSION_DENIED;
            default:
                // in the default mode, we will make a decision here based on
                // checkCallingPermission()
                return (mContext.checkCallingOrSelfPermission(SYSTEM_ALERT_WINDOW)
                        == PERMISSION_GRANTED) ? ADD_OKAY : ADD_PERMISSION_DENIED;
        }
    }

    @Override
    public boolean checkShowToOwnerOnly(WindowManager.LayoutParams attrs) {

        // If this switch statement is modified, modify the comment in the declarations of
        // the type in {@link WindowManager.LayoutParams} as well.
        switch (attrs.type) {
            default:
                // These are the windows that by default are shown only to the user that created
                // them. If this needs to be overridden, set
                // {@link WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS} in
                // {@link WindowManager.LayoutParams}. Note that permission
                // {@link android.Manifest.permission.INTERNAL_SYSTEM_WINDOW} is required as well.
                if ((attrs.privateFlags & PRIVATE_FLAG_SHOW_FOR_ALL_USERS) == 0) {
                    return true;
                }
                break;

            // These are the windows that by default are shown to all users. However, to
            // protect against spoofing, check permissions below.
            case TYPE_APPLICATION_STARTING:
            case TYPE_BOOT_PROGRESS:
            case TYPE_DISPLAY_OVERLAY:
            case TYPE_INPUT_CONSUMER:
            case TYPE_KEYGUARD_DIALOG:
            case TYPE_MAGNIFICATION_OVERLAY:
            case TYPE_NAVIGATION_BAR:
            case TYPE_NAVIGATION_BAR_PANEL:
            case TYPE_PHONE:
            case TYPE_POINTER:
            case TYPE_PRIORITY_PHONE:
            case TYPE_SEARCH_BAR:
            case TYPE_STATUS_BAR:
            case TYPE_STATUS_BAR_PANEL:
            case TYPE_STATUS_BAR_SUB_PANEL:
            case TYPE_SYSTEM_DIALOG:
            case TYPE_VOLUME_OVERLAY:
            case TYPE_PRESENTATION:
            case TYPE_PRIVATE_PRESENTATION:
            case TYPE_DOCK_DIVIDER:
                break;
        }

        // Check if third party app has set window to system window type.
        return mContext.checkCallingOrSelfPermission(INTERNAL_SYSTEM_WINDOW) != PERMISSION_GRANTED;
    }

    void readLidState() {
        mDefaultDisplayPolicy.setLidState(mWindowManagerFuncs.getLidState());
    }

    private void readCameraLensCoverState() {
        mCameraLensCoverState = mWindowManagerFuncs.getCameraLensCoverState();
    }

    private boolean isHidden(int accessibilityMode) {
        final int lidState = mDefaultDisplayPolicy.getLidState();
        switch (accessibilityMode) {
            case 1:
                return lidState == LID_CLOSED;
            case 2:
                return lidState == LID_OPEN;
            default:
                return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void adjustConfigurationLw(Configuration config, int keyboardPresence,
            int navigationPresence) {
        mHaveBuiltInKeyboard = (keyboardPresence & PRESENCE_INTERNAL) != 0;

        readConfigurationDependentBehaviors();
        readLidState();

        if (config.keyboard == Configuration.KEYBOARD_NOKEYS
                || (keyboardPresence == PRESENCE_INTERNAL
                        && isHidden(mLidKeyboardAccessibility))) {
            config.hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_YES;
            if (!mHasSoftInput) {
                config.keyboardHidden = Configuration.KEYBOARDHIDDEN_YES;
            }
        }

        if (config.navigation == Configuration.NAVIGATION_NONAV
                || (navigationPresence == PRESENCE_INTERNAL
                        && isHidden(mLidNavigationAccessibility))) {
            config.navigationHidden = Configuration.NAVIGATIONHIDDEN_YES;
        }
    }

    @Override
    public int getMaxWallpaperLayer() {
        return getWindowLayerFromTypeLw(TYPE_STATUS_BAR);
    }

    @Override
    public boolean isKeyguardHostWindow(WindowManager.LayoutParams attrs) {
        return attrs.type == TYPE_STATUS_BAR;
    }

    @Override
    public boolean canBeHiddenByKeyguardLw(WindowState win) {

        // Keyguard visibility of window from activities are determined over activity visibility.
        if (win.getAppToken() != null) {
            return false;
        }
        switch (win.getAttrs().type) {
            case TYPE_STATUS_BAR:
            case TYPE_NAVIGATION_BAR:
            case TYPE_WALLPAPER:
            case TYPE_DREAM:
                return false;
            default:
                // Hide only windows below the keyguard host window.
                return getWindowLayerLw(win) < getWindowLayerFromTypeLw(TYPE_STATUS_BAR);
        }
    }

    private boolean shouldBeHiddenByKeyguard(WindowState win, WindowState imeTarget) {
        final LayoutParams attrs = win.getAttrs();

        boolean hideDockDivider = attrs.type == TYPE_DOCK_DIVIDER
                && !mWindowManagerInternal.isStackVisibleLw(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        if (hideDockDivider) {
            return true;
        }

        // If AOD is showing, the IME should be hidden. However, sometimes the AOD is considered
        // hidden because it's in the process of hiding, but it's still being shown on screen.
        // In that case, we want to continue hiding the IME until the windows have completed
        // drawing. This way, we know that the IME can be safely shown since the other windows are
        // now shown.
        final boolean hideIme = win.isInputMethodWindow()
                && (mAodShowing || !mDefaultDisplayPolicy.isWindowManagerDrawComplete());
        if (hideIme) {
            return true;
        }

        final boolean showImeOverKeyguard = imeTarget != null && imeTarget.isVisibleLw()
                && (imeTarget.canShowWhenLocked() || !canBeHiddenByKeyguardLw(imeTarget));

        // Show IME over the keyguard if the target allows it
        boolean allowWhenLocked = win.isInputMethodWindow() && showImeOverKeyguard;

        final boolean isKeyguardShowing = mKeyguardDelegate.isShowing();

        if (isKeyguardShowing && isKeyguardOccluded()) {
            // Show SHOW_WHEN_LOCKED windows if Keyguard is occluded.
            allowWhenLocked |= win.canShowWhenLocked()
                    // Show error dialogs over apps that are shown on lockscreen
                    || (attrs.privateFlags & PRIVATE_FLAG_SYSTEM_ERROR) != 0;
        }

        return isKeyguardShowing && !allowWhenLocked && win.getDisplayId() == DEFAULT_DISPLAY;
    }

    /** {@inheritDoc} */
    @Override
    public StartingSurface addSplashScreen(IBinder appToken, String packageName, int theme,
            CompatibilityInfo compatInfo, CharSequence nonLocalizedLabel, int labelRes, int icon,
            int logo, int windowFlags, Configuration overrideConfig, int displayId) {
        if (!SHOW_SPLASH_SCREENS) {
            return null;
        }
        if (packageName == null) {
            return null;
        }

        WindowManager wm = null;
        View view = null;

        try {
            Context context = mContext;
            if (DEBUG_SPLASH_SCREEN) Slog.d(TAG, "addSplashScreen " + packageName
                    + ": nonLocalizedLabel=" + nonLocalizedLabel + " theme="
                    + Integer.toHexString(theme));

            // Obtain proper context to launch on the right display.
            final Context displayContext = getDisplayContext(context, displayId);
            if (displayContext == null) {
                // Can't show splash screen on requested display, so skip showing at all.
                return null;
            }
            context = displayContext;

            if (theme != context.getThemeResId() || labelRes != 0) {
                try {
                    context = context.createPackageContext(packageName, CONTEXT_RESTRICTED);
                    context.setTheme(theme);
                } catch (PackageManager.NameNotFoundException e) {
                    // Ignore
                }
            }

            if (overrideConfig != null && !overrideConfig.equals(EMPTY)) {
                if (DEBUG_SPLASH_SCREEN) Slog.d(TAG, "addSplashScreen: creating context based"
                        + " on overrideConfig" + overrideConfig + " for splash screen");
                final Context overrideContext = context.createConfigurationContext(overrideConfig);
                overrideContext.setTheme(theme);
                final TypedArray typedArray = overrideContext.obtainStyledAttributes(
                        com.android.internal.R.styleable.Window);
                final int resId = typedArray.getResourceId(R.styleable.Window_windowBackground, 0);
                if (resId != 0 && overrideContext.getDrawable(resId) != null) {
                    // We want to use the windowBackground for the override context if it is
                    // available, otherwise we use the default one to make sure a themed starting
                    // window is displayed for the app.
                    if (DEBUG_SPLASH_SCREEN) Slog.d(TAG, "addSplashScreen: apply overrideConfig"
                            + overrideConfig + " to starting window resId=" + resId);
                    context = overrideContext;
                }
                typedArray.recycle();
            }

            final PhoneWindow win = new PhoneWindow(context);
            win.setIsStartingWindow(true);

            CharSequence label = context.getResources().getText(labelRes, null);
            // Only change the accessibility title if the label is localized
            if (label != null) {
                win.setTitle(label, true);
            } else {
                win.setTitle(nonLocalizedLabel, false);
            }

            win.setType(
                WindowManager.LayoutParams.TYPE_APPLICATION_STARTING);

            synchronized (mWindowManagerFuncs.getWindowManagerLock()) {
                // Assumes it's safe to show starting windows of launched apps while
                // the keyguard is being hidden. This is okay because starting windows never show
                // secret information.
                // TODO(b/113840485): Occluded may not only happen on default display
                if (displayId == DEFAULT_DISPLAY && mKeyguardOccluded) {
                    windowFlags |= FLAG_SHOW_WHEN_LOCKED;
                }
            }

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

            win.setDefaultIcon(icon);
            win.setDefaultLogo(logo);

            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);

            final WindowManager.LayoutParams params = win.getAttributes();
            params.token = appToken;
            params.packageName = packageName;
            params.windowAnimations = win.getWindowStyle().getResourceId(
                    com.android.internal.R.styleable.Window_windowAnimationStyle, 0);
            params.privateFlags |=
                    WindowManager.LayoutParams.PRIVATE_FLAG_FAKE_HARDWARE_ACCELERATED;
            params.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;

            if (!compatInfo.supportsScreen()) {
                params.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
            }

            params.setTitle("Splash Screen " + packageName);
            addSplashscreenContent(win, context);

            wm = (WindowManager) context.getSystemService(WINDOW_SERVICE);
            view = win.getDecorView();

            if (DEBUG_SPLASH_SCREEN) Slog.d(TAG, "Adding splash screen window for "
                + packageName + " / " + appToken + ": " + (view.getParent() != null ? view : null));

            wm.addView(view, params);

            // Only return the view if it was successfully added to the
            // window manager... which we can tell by it having a parent.
            return view.getParent() != null ? new SplashScreenSurface(view, appToken) : null;
        } catch (WindowManager.BadTokenException e) {
            // ignore
            Log.w(TAG, appToken + " already running, starting window not displayed. " +
                    e.getMessage());
        } catch (RuntimeException e) {
            // don't crash if something else bad happens, for example a
            // failure loading resources because we are loading from an app
            // on external storage that has been unmounted.
            Log.w(TAG, appToken + " failed creating starting window", e);
        } finally {
            if (view != null && view.getParent() == null) {
                Log.w(TAG, "view not successfully added to wm, removing view");
                wm.removeViewImmediate(view);
            }
        }

        return null;
    }

    private void addSplashscreenContent(PhoneWindow win, Context ctx) {
        final TypedArray a = ctx.obtainStyledAttributes(R.styleable.Window);
        final int resId = a.getResourceId(R.styleable.Window_windowSplashscreenContent, 0);
        a.recycle();
        if (resId == 0) {
            return;
        }
        final Drawable drawable = ctx.getDrawable(resId);
        if (drawable == null) {
            return;
        }

        // We wrap this into a view so the system insets get applied to the drawable.
        final View v = new View(ctx);
        v.setBackground(drawable);
        win.setContentView(v);
    }

    /** Obtain proper context for showing splash screen on the provided display. */
    private Context getDisplayContext(Context context, int displayId) {
        if (displayId == DEFAULT_DISPLAY) {
            // The default context fits.
            return context;
        }

        final Display targetDisplay = mDisplayManager.getDisplay(displayId);
        if (targetDisplay == null) {
            // Failed to obtain the non-default display where splash screen should be shown,
            // lets not show at all.
            return null;
        }

        return context.createDisplayContext(targetDisplay);
    }

    @Override
    public Animation createHiddenByKeyguardExit(boolean onWallpaper,
            boolean goingToNotificationShade) {
        if (goingToNotificationShade) {
            return AnimationUtils.loadAnimation(mContext, R.anim.lock_screen_behind_enter_fade_in);
        }

        AnimationSet set = (AnimationSet) AnimationUtils.loadAnimation(mContext, onWallpaper ?
                    R.anim.lock_screen_behind_enter_wallpaper :
                    R.anim.lock_screen_behind_enter);

        // TODO: Use XML interpolators when we have log interpolators available in XML.
        final List<Animation> animations = set.getAnimations();
        for (int i = animations.size() - 1; i >= 0; --i) {
            animations.get(i).setInterpolator(mLogDecelerateInterpolator);
        }

        return set;
    }


    @Override
    public Animation createKeyguardWallpaperExit(boolean goingToNotificationShade) {
        if (goingToNotificationShade) {
            return null;
        } else {
            return AnimationUtils.loadAnimation(mContext, R.anim.lock_screen_wallpaper_exit);
        }
    }

    private static void awakenDreams() {
        IDreamManager dreamManager = getDreamManager();
        if (dreamManager != null) {
            try {
                dreamManager.awaken();
            } catch (RemoteException e) {
                // fine, stay asleep then
            }
        }
    }

    static IDreamManager getDreamManager() {
        return IDreamManager.Stub.asInterface(
                ServiceManager.checkService(DreamService.DREAM_SERVICE));
    }

    TelecomManager getTelecommService() {
        return (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
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
        return isKeyguardShowingAndNotOccluded() || inKeyguardRestrictedKeyInputMode();
    }

    private static final int[] WINDOW_TYPES_WHERE_HOME_DOESNT_WORK = {
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
        };

    // TODO(b/117479243): handle it in InputPolicy
    /** {@inheritDoc} */
    @Override
    public long interceptKeyBeforeDispatching(WindowState win, KeyEvent event, int policyFlags) {
        final long result = interceptKeyBeforeDispatchingInner(win, event, policyFlags);
        final int eventDisplayId = event.getDisplayId();
        if (result == 0 && !mPerDisplayFocusEnabled
                && eventDisplayId != INVALID_DISPLAY && eventDisplayId != mTopFocusedDisplayId) {
            // An event is targeting a non-focused display. Try to move the display to top so that
            // it can become the focused display to interact with the user.
            final long eventDownTime = event.getDownTime();
            if (mMovingDisplayToTopKeyTime < eventDownTime) {
                // We have not handled this event yet. Move the display to top, and then tell
                // dispatcher to try again later.
                mMovingDisplayToTopKeyTime = eventDownTime;
                mMovingDisplayToTopKeyTriggered = true;
                mHandler.sendMessage(
                        mHandler.obtainMessage(MSG_MOVE_DISPLAY_TO_TOP, eventDisplayId, 0));
                return MOVING_DISPLAY_TO_TOP_DURATION_MILLIS;
            } else if (mMovingDisplayToTopKeyTriggered) {
                // The message has not been handled yet. Tell dispatcher to try again later.
                return MOVING_DISPLAY_TO_TOP_DURATION_MILLIS;
            }
            // The target display is still not the top focused display. Drop the event because the
            // display may not contain any window which can receive keys.
            Slog.w(TAG, "Dropping key targeting non-focused display #" + eventDisplayId
                    + " keyCode=" + KeyEvent.keyCodeToString(event.getKeyCode()));
            return -1;
        }
        return result;
    }

    private long interceptKeyBeforeDispatchingInner(WindowState win, KeyEvent event,
            int policyFlags) {
        final boolean keyguardOn = keyguardOn();
        final int keyCode = event.getKeyCode();
        final int repeatCount = event.getRepeatCount();
        final int metaState = event.getMetaState();
        final int flags = event.getFlags();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final boolean canceled = event.isCanceled();
        final int displayId = event.getDisplayId();

        if (DEBUG_INPUT) {
            Log.d(TAG, "interceptKeyTi keyCode=" + keyCode + " down=" + down + " repeatCount="
                    + repeatCount + " keyguardOn=" + keyguardOn + " canceled=" + canceled);
        }

        // If we think we might have a volume down & power key chord on the way
        // but we're not sure, then tell the dispatcher to wait a little while and
        // try again later before dispatching.
        if (mScreenshotChordEnabled && (flags & KeyEvent.FLAG_FALLBACK) == 0) {
            if (mScreenshotChordVolumeDownKeyTriggered && !mScreenshotChordPowerKeyTriggered) {
                final long now = SystemClock.uptimeMillis();
                final long timeoutTime = mScreenshotChordVolumeDownKeyTime
                        + SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS;
                if (now < timeoutTime) {
                    return timeoutTime - now;
                }
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                    && mScreenshotChordVolumeDownKeyConsumed) {
                if (!down) {
                    mScreenshotChordVolumeDownKeyConsumed = false;
                }
                return -1;
            }
        }

        // If an accessibility shortcut might be partially complete, hold off dispatching until we
        // know if it is complete or not
        if (mAccessibilityShortcutController.isAccessibilityShortcutAvailable(false)
                && (flags & KeyEvent.FLAG_FALLBACK) == 0) {
            if (mScreenshotChordVolumeDownKeyTriggered ^ mA11yShortcutChordVolumeUpKeyTriggered) {
                final long now = SystemClock.uptimeMillis();
                final long timeoutTime = (mScreenshotChordVolumeDownKeyTriggered
                        ? mScreenshotChordVolumeDownKeyTime : mA11yShortcutChordVolumeUpKeyTime)
                        + SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS;
                if (now < timeoutTime) {
                    return timeoutTime - now;
                }
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && mScreenshotChordVolumeDownKeyConsumed) {
                if (!down) {
                    mScreenshotChordVolumeDownKeyConsumed = false;
                }
                return -1;
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && mA11yShortcutChordVolumeUpKeyConsumed) {
                if (!down) {
                    mA11yShortcutChordVolumeUpKeyConsumed = false;
                }
                return -1;
            }
        }

        // If a ringer toggle chord could be on the way but we're not sure, then tell the dispatcher
        // to wait a little while and try again later before dispatching.
        if (mRingerToggleChord != VOLUME_HUSH_OFF && (flags & KeyEvent.FLAG_FALLBACK) == 0) {
            if (mA11yShortcutChordVolumeUpKeyTriggered && !mScreenshotChordPowerKeyTriggered) {
                final long now = SystemClock.uptimeMillis();
                final long timeoutTime = mA11yShortcutChordVolumeUpKeyTime
                        + SCREENSHOT_CHORD_DEBOUNCE_DELAY_MILLIS;
                if (now < timeoutTime) {
                    return timeoutTime - now;
                }
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && mA11yShortcutChordVolumeUpKeyConsumed) {
                if (!down) {
                    mA11yShortcutChordVolumeUpKeyConsumed = false;
                }
                return -1;
            }
        }

        // Cancel any pending meta actions if we see any other keys being pressed between the down
        // of the meta key and its corresponding up.
        if (mPendingMetaAction && !KeyEvent.isMetaKey(keyCode)) {
            mPendingMetaAction = false;
        }
        // Any key that is not Alt or Meta cancels Caps Lock combo tracking.
        if (mPendingCapsLockToggle && !KeyEvent.isMetaKey(keyCode) && !KeyEvent.isAltKey(keyCode)) {
            mPendingCapsLockToggle = false;
        }

        // First we always handle the home key here, so applications
        // can never break it, although if keyguard is on, we do let
        // it handle it, because that gives us the correct 5 second
        // timeout.
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            DisplayHomeButtonHandler handler = mDisplayHomeButtonHandlers.get(displayId);
            if (handler == null) {
                handler = new DisplayHomeButtonHandler(displayId);
                mDisplayHomeButtonHandlers.put(displayId, handler);
            }
            return handler.handleHomeButton(win, event);
        } else if (keyCode == KeyEvent.KEYCODE_MENU) {
            // Hijack modified menu keys for debugging features
            final int chordBug = KeyEvent.META_SHIFT_ON;

            if (down && repeatCount == 0) {
                if (mEnableShiftMenuBugReports && (metaState & chordBug) == chordBug) {
                    Intent intent = new Intent(Intent.ACTION_BUG_REPORT);
                    mContext.sendOrderedBroadcastAsUser(intent, UserHandle.CURRENT,
                            null, null, null, 0, null, null);
                    return -1;
                }
            }
        } else if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (down) {
                if (repeatCount == 0) {
                    mSearchKeyShortcutPending = true;
                    mConsumeSearchKeyUp = false;
                }
            } else {
                mSearchKeyShortcutPending = false;
                if (mConsumeSearchKeyUp) {
                    mConsumeSearchKeyUp = false;
                    return -1;
                }
            }
            return 0;
        } else if (keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            if (!keyguardOn) {
                if (down && repeatCount == 0) {
                    preloadRecentApps();
                } else if (!down) {
                    toggleRecentApps();
                }
            }
            return -1;
        } else if (keyCode == KeyEvent.KEYCODE_N && event.isMetaPressed()) {
            if (down) {
                IStatusBarService service = getStatusBarService();
                if (service != null) {
                    try {
                        service.expandNotificationsPanel();
                    } catch (RemoteException e) {
                        // do nothing.
                    }
                }
            }
        } else if (keyCode == KeyEvent.KEYCODE_S && event.isMetaPressed()
                && event.isCtrlPressed()) {
            if (down && repeatCount == 0) {
                int type = event.isShiftPressed() ? TAKE_SCREENSHOT_SELECTED_REGION
                        : TAKE_SCREENSHOT_FULLSCREEN;
                mScreenshotRunnable.setScreenshotType(type);
                mHandler.post(mScreenshotRunnable);
                return -1;
            }
        } else if (keyCode == KeyEvent.KEYCODE_SLASH && event.isMetaPressed()) {
            if (down && repeatCount == 0 && !isKeyguardLocked()) {
                toggleKeyboardShortcutsMenu(event.getDeviceId());
            }
        } else if (keyCode == KeyEvent.KEYCODE_ASSIST) {
            Slog.wtf(TAG, "KEYCODE_ASSIST should be handled in interceptKeyBeforeQueueing");
            return -1;
        } else if (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
            Slog.wtf(TAG, "KEYCODE_VOICE_ASSIST should be handled in interceptKeyBeforeQueueing");
            return -1;
        } else if (keyCode == KeyEvent.KEYCODE_SYSRQ) {
            if (down && repeatCount == 0) {
                mScreenshotRunnable.setScreenshotType(TAKE_SCREENSHOT_FULLSCREEN);
                mHandler.post(mScreenshotRunnable);
            }
            return -1;
        } else if (keyCode == KeyEvent.KEYCODE_BRIGHTNESS_UP
                || keyCode == KeyEvent.KEYCODE_BRIGHTNESS_DOWN) {
            if (down) {
                int direction = keyCode == KeyEvent.KEYCODE_BRIGHTNESS_UP ? 1 : -1;

                // Disable autobrightness if it's on
                int auto = Settings.System.getIntForUser(
                        mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                        UserHandle.USER_CURRENT_OR_SELF);
                if (auto != 0) {
                    Settings.System.putIntForUser(mContext.getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                            UserHandle.USER_CURRENT_OR_SELF);
                }

                int min = mPowerManager.getMinimumScreenBrightnessSetting();
                int max = mPowerManager.getMaximumScreenBrightnessSetting();
                int step = (max - min + BRIGHTNESS_STEPS - 1) / BRIGHTNESS_STEPS * direction;
                int brightness = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS,
                        mPowerManager.getDefaultScreenBrightnessSetting(),
                        UserHandle.USER_CURRENT_OR_SELF);
                brightness += step;
                // Make sure we don't go beyond the limits.
                brightness = Math.min(max, brightness);
                brightness = Math.max(min, brightness);

                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, brightness,
                        UserHandle.USER_CURRENT_OR_SELF);
                startActivityAsUser(new Intent(Intent.ACTION_SHOW_BRIGHTNESS_DIALOG),
                        UserHandle.CURRENT_OR_SELF);
            }
            return -1;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (mUseTvRouting || mHandleVolumeKeysInWM) {
                // On TVs or when the configuration is enabled, volume keys never
                // go to the foreground app.
                dispatchDirectAudioEvent(event);
                return -1;
            }

            // If the device is in VR mode and keys are "internal" (e.g. on the side of the
            // device), then drop the volume keys and don't forward it to the application/dispatch
            // the audio event.
            if (mDefaultDisplayPolicy.isPersistentVrModeEnabled()) {
                final InputDevice d = event.getDevice();
                if (d != null && !d.isExternal()) {
                    return -1;
                }
            }
        } else if (keyCode == KeyEvent.KEYCODE_TAB && event.isMetaPressed()) {
            // Pass through keyboard navigation keys.
            return 0;
        } else if (mHasFeatureLeanback && interceptBugreportGestureTv(keyCode, down)) {
            return -1;
        } else if (keyCode == KeyEvent.KEYCODE_ALL_APPS) {
            if (!down) {
                mHandler.removeMessages(MSG_HANDLE_ALL_APPS);
                Message msg = mHandler.obtainMessage(MSG_HANDLE_ALL_APPS);
                msg.setAsynchronous(true);
                msg.sendToTarget();
            }
            return -1;
        }

        // Toggle Caps Lock on META-ALT.
        boolean actionTriggered = false;
        if (KeyEvent.isModifierKey(keyCode)) {
            if (!mPendingCapsLockToggle) {
                // Start tracking meta state for combo.
                mInitialMetaState = mMetaState;
                mPendingCapsLockToggle = true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                int altOnMask = mMetaState & KeyEvent.META_ALT_MASK;
                int metaOnMask = mMetaState & KeyEvent.META_META_MASK;

                // Check for Caps Lock toggle
                if ((metaOnMask != 0) && (altOnMask != 0)) {
                    // Check if nothing else is pressed
                    if (mInitialMetaState == (mMetaState ^ (altOnMask | metaOnMask))) {
                        // Handle Caps Lock Toggle
                        mInputManagerInternal.toggleCapsLock(event.getDeviceId());
                        actionTriggered = true;
                    }
                }

                // Always stop tracking when key goes up.
                mPendingCapsLockToggle = false;
            }
        }
        // Store current meta state to be able to evaluate it later.
        mMetaState = metaState;

        if (actionTriggered) {
            return -1;
        }

        if (KeyEvent.isMetaKey(keyCode)) {
            if (down) {
                mPendingMetaAction = true;
            } else if (mPendingMetaAction) {
                launchAssistAction(Intent.EXTRA_ASSIST_INPUT_HINT_KEYBOARD, event.getDeviceId());
            }
            return -1;
        }

        // Shortcuts are invoked through Search+key, so intercept those here
        // Any printing key that is chorded with Search should be consumed
        // even if no shortcut was invoked.  This prevents text from being
        // inadvertently inserted when using a keyboard that has built-in macro
        // shortcut keys (that emit Search+x) and some of them are not registered.
        if (mSearchKeyShortcutPending) {
            final KeyCharacterMap kcm = event.getKeyCharacterMap();
            if (kcm.isPrintingKey(keyCode)) {
                mConsumeSearchKeyUp = true;
                mSearchKeyShortcutPending = false;
                if (down && repeatCount == 0 && !keyguardOn) {
                    Intent shortcutIntent = mShortcutManager.getIntent(kcm, keyCode, metaState);
                    if (shortcutIntent != null) {
                        shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            startActivityAsUser(shortcutIntent, UserHandle.CURRENT);
                            dismissKeyboardShortcutsMenu();
                        } catch (ActivityNotFoundException ex) {
                            Slog.w(TAG, "Dropping shortcut key combination because "
                                    + "the activity to which it is registered was not found: "
                                    + "SEARCH+" + KeyEvent.keyCodeToString(keyCode), ex);
                        }
                    } else {
                        Slog.i(TAG, "Dropping unregistered shortcut key combination: "
                                + "SEARCH+" + KeyEvent.keyCodeToString(keyCode));
                    }
                }
                return -1;
            }
        }

        // Invoke shortcuts using Meta.
        if (down && repeatCount == 0 && !keyguardOn
                && (metaState & KeyEvent.META_META_ON) != 0) {
            final KeyCharacterMap kcm = event.getKeyCharacterMap();
            if (kcm.isPrintingKey(keyCode)) {
                Intent shortcutIntent = mShortcutManager.getIntent(kcm, keyCode,
                        metaState & ~(KeyEvent.META_META_ON
                                | KeyEvent.META_META_LEFT_ON | KeyEvent.META_META_RIGHT_ON));
                if (shortcutIntent != null) {
                    shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivityAsUser(shortcutIntent, UserHandle.CURRENT);
                        dismissKeyboardShortcutsMenu();
                    } catch (ActivityNotFoundException ex) {
                        Slog.w(TAG, "Dropping shortcut key combination because "
                                + "the activity to which it is registered was not found: "
                                + "META+" + KeyEvent.keyCodeToString(keyCode), ex);
                    }
                    return -1;
                }
            }
        }

        // Handle application launch keys.
        if (down && repeatCount == 0 && !keyguardOn) {
            String category = sApplicationLaunchKeyCategories.get(keyCode);
            if (category != null) {
                Intent intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivityAsUser(intent, UserHandle.CURRENT);
                    dismissKeyboardShortcutsMenu();
                } catch (ActivityNotFoundException ex) {
                    Slog.w(TAG, "Dropping application launch key because "
                            + "the activity to which it is registered was not found: "
                            + "keyCode=" + keyCode + ", category=" + category, ex);
                }
                return -1;
            }
        }

        // Display task switcher for ALT-TAB.
        if (down && repeatCount == 0 && keyCode == KeyEvent.KEYCODE_TAB) {
            if (mRecentAppsHeldModifiers == 0 && !keyguardOn && isUserSetupComplete()) {
                final int shiftlessModifiers = event.getModifiers() & ~KeyEvent.META_SHIFT_MASK;
                if (KeyEvent.metaStateHasModifiers(shiftlessModifiers, KeyEvent.META_ALT_ON)) {
                    mRecentAppsHeldModifiers = shiftlessModifiers;
                    showRecentApps(true);
                    return -1;
                }
            }
        } else if (!down && mRecentAppsHeldModifiers != 0
                && (metaState & mRecentAppsHeldModifiers) == 0) {
            mRecentAppsHeldModifiers = 0;
            hideRecentApps(true, false);
        }

        // Handle keyboard language switching.
        final boolean isCtrlOrMetaSpace = keyCode == KeyEvent.KEYCODE_SPACE
                && (metaState & (KeyEvent.META_CTRL_MASK | KeyEvent.META_META_MASK)) != 0;
        if (down && repeatCount == 0
                && (keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH || isCtrlOrMetaSpace)) {
            int direction = (metaState & KeyEvent.META_SHIFT_MASK) != 0 ? -1 : 1;
            mWindowManagerFuncs.switchKeyboardLayout(event.getDeviceId(), direction);
            return -1;
        }
        if (mLanguageSwitchKeyPressed && !down
                && (keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH
                        || keyCode == KeyEvent.KEYCODE_SPACE)) {
            mLanguageSwitchKeyPressed = false;
            return -1;
        }

        if (isValidGlobalKey(keyCode)
                && mGlobalKeyManager.handleGlobalKey(mContext, keyCode, event)) {
            return -1;
        }

        if (down) {
            long shortcutCode = keyCode;
            if (event.isCtrlPressed()) {
                shortcutCode |= ((long) KeyEvent.META_CTRL_ON) << Integer.SIZE;
            }

            if (event.isAltPressed()) {
                shortcutCode |= ((long) KeyEvent.META_ALT_ON) << Integer.SIZE;
            }

            if (event.isShiftPressed()) {
                shortcutCode |= ((long) KeyEvent.META_SHIFT_ON) << Integer.SIZE;
            }

            if (event.isMetaPressed()) {
                shortcutCode |= ((long) KeyEvent.META_META_ON) << Integer.SIZE;
            }

            IShortcutService shortcutService = mShortcutKeyServices.get(shortcutCode);
            if (shortcutService != null) {
                try {
                    if (isUserSetupComplete()) {
                        shortcutService.notifyShortcutKeyPressed(shortcutCode);
                    }
                } catch (RemoteException e) {
                    mShortcutKeyServices.delete(shortcutCode);
                }
                return -1;
            }
        }

        // Reserve all the META modifier combos for system behavior
        if ((metaState & KeyEvent.META_META_ON) != 0) {
            return -1;
        }

        // Let the application handle the key.
        return 0;
    }

    /**
     * TV only: recognizes a remote control gesture for capturing a bug report.
     */
    private boolean interceptBugreportGestureTv(int keyCode, boolean down) {
        // The bugreport capture chord is a long press on DPAD CENTER and BACK simultaneously.
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            mBugreportTvKey1Pressed = down;
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            mBugreportTvKey2Pressed = down;
        }

        if (mBugreportTvKey1Pressed && mBugreportTvKey2Pressed) {
            if (!mBugreportTvScheduled) {
                mBugreportTvScheduled = true;
                Message msg = Message.obtain(mHandler, MSG_BUGREPORT_TV);
                msg.setAsynchronous(true);
                mHandler.sendMessageDelayed(msg, BUGREPORT_TV_GESTURE_TIMEOUT_MILLIS);
            }
        } else if (mBugreportTvScheduled) {
            mHandler.removeMessages(MSG_BUGREPORT_TV);
            mBugreportTvScheduled = false;
        }

        return mBugreportTvScheduled;
    }

    /**
     * TV only: recognizes a remote control gesture as Accessibility shortcut.
     * Shortcut: Long press (BACK + DPAD_DOWN)
     */
    private boolean interceptAccessibilityGestureTv(int keyCode, boolean down) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            mAccessibilityTvKey1Pressed = down;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            mAccessibilityTvKey2Pressed = down;
        }

        if (mAccessibilityTvKey1Pressed && mAccessibilityTvKey2Pressed) {
            if (!mAccessibilityTvScheduled) {
                mAccessibilityTvScheduled = true;
                Message msg = Message.obtain(mHandler, MSG_ACCESSIBILITY_TV);
                msg.setAsynchronous(true);
                mHandler.sendMessageDelayed(msg, getAccessibilityShortcutTimeout());
            }
        } else if (mAccessibilityTvScheduled) {
            mHandler.removeMessages(MSG_ACCESSIBILITY_TV);
            mAccessibilityTvScheduled = false;
        }

        return mAccessibilityTvScheduled;
    }

    private void requestFullBugreport() {
        if ("1".equals(SystemProperties.get("ro.debuggable"))
                || Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1) {
            try {
                ActivityManager.getService()
                        .requestBugReport(ActivityManager.BUGREPORT_OPTION_FULL);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error taking bugreport", e);
            }
        }
    }

    // TODO(b/117479243): handle it in InputPolicy
    /** {@inheritDoc} */
    @Override
    public KeyEvent dispatchUnhandledKey(WindowState win, KeyEvent event, int policyFlags) {
        // Note: This method is only called if the initial down was unhandled.
        if (DEBUG_INPUT) {
            Slog.d(TAG, "Unhandled key: win=" + win + ", action=" + event.getAction()
                    + ", flags=" + event.getFlags()
                    + ", keyCode=" + event.getKeyCode()
                    + ", scanCode=" + event.getScanCode()
                    + ", metaState=" + event.getMetaState()
                    + ", repeatCount=" + event.getRepeatCount()
                    + ", policyFlags=" + policyFlags);
        }

        KeyEvent fallbackEvent = null;
        if ((event.getFlags() & KeyEvent.FLAG_FALLBACK) == 0) {
            final KeyCharacterMap kcm = event.getKeyCharacterMap();
            final int keyCode = event.getKeyCode();
            final int metaState = event.getMetaState();
            final boolean initialDown = event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0;

            // Check for fallback actions specified by the key character map.
            final FallbackAction fallbackAction;
            if (initialDown) {
                fallbackAction = kcm.getFallbackAction(keyCode, metaState);
            } else {
                fallbackAction = mFallbackActions.get(keyCode);
            }

            if (fallbackAction != null) {
                if (DEBUG_INPUT) {
                    Slog.d(TAG, "Fallback: keyCode=" + fallbackAction.keyCode
                            + " metaState=" + Integer.toHexString(fallbackAction.metaState));
                }

                final int flags = event.getFlags() | KeyEvent.FLAG_FALLBACK;
                fallbackEvent = KeyEvent.obtain(
                        event.getDownTime(), event.getEventTime(),
                        event.getAction(), fallbackAction.keyCode,
                        event.getRepeatCount(), fallbackAction.metaState,
                        event.getDeviceId(), event.getScanCode(),
                        flags, event.getSource(), event.getDisplayId(), null);

                if (!interceptFallback(win, fallbackEvent, policyFlags)) {
                    fallbackEvent.recycle();
                    fallbackEvent = null;
                }

                if (initialDown) {
                    mFallbackActions.put(keyCode, fallbackAction);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    mFallbackActions.remove(keyCode);
                    fallbackAction.recycle();
                }
            }
        }

        if (DEBUG_INPUT) {
            if (fallbackEvent == null) {
                Slog.d(TAG, "No fallback.");
            } else {
                Slog.d(TAG, "Performing fallback: " + fallbackEvent);
            }
        }
        return fallbackEvent;
    }

    private boolean interceptFallback(WindowState win, KeyEvent fallbackEvent, int policyFlags) {
        int actions = interceptKeyBeforeQueueing(fallbackEvent, policyFlags);
        if ((actions & ACTION_PASS_TO_USER) != 0) {
            long delayMillis = interceptKeyBeforeDispatching(
                    win, fallbackEvent, policyFlags);
            if (delayMillis == 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void setTopFocusedDisplay(int displayId) {
        mTopFocusedDisplayId = displayId;
    }

    @Override
    public void registerDisplayFoldListener(IDisplayFoldListener listener) {
        if (mDisplayFoldController != null) {
            mDisplayFoldController.registerDisplayFoldListener(listener);
        }
    }

    @Override
    public void unregisterDisplayFoldListener(IDisplayFoldListener listener) {
        if (mDisplayFoldController != null) {
            mDisplayFoldController.unregisterDisplayFoldListener(listener);
        }
    }

    @Override
    public void setOverrideFoldedArea(Rect area) {
        if (mDisplayFoldController != null) {
            mDisplayFoldController.setOverrideFoldedArea(area);
        }
    }

    @Override
    public Rect getFoldedArea() {
        if (mDisplayFoldController != null) {
            return mDisplayFoldController.getFoldedArea();
        }
        return new Rect();
    }

    @Override
    public void onDefaultDisplayFocusChangedLw(WindowState newFocus) {
        if (mDisplayFoldController != null) {
            mDisplayFoldController.onDefaultDisplayFocusChanged(
                    newFocus != null ? newFocus.getOwningPackage() : null);
        }
    }

    @Override
    public void registerShortcutKey(long shortcutCode, IShortcutService shortcutService)
            throws RemoteException {
        synchronized (mLock) {
            IShortcutService service = mShortcutKeyServices.get(shortcutCode);
            if (service != null && service.asBinder().pingBinder()) {
                throw new RemoteException("Key already exists.");
            }

            mShortcutKeyServices.put(shortcutCode, shortcutService);
        }
    }

    @Override
    public void onKeyguardOccludedChangedLw(boolean occluded) {
        if (mKeyguardDelegate != null && mKeyguardDelegate.isShowing()) {
            mPendingKeyguardOccluded = occluded;
            mKeyguardOccludedChanged = true;
        } else {
            setKeyguardOccludedLw(occluded, false /* force */);
        }
    }

    private int handleStartTransitionForKeyguardLw(int transit, long duration) {
        if (mKeyguardOccludedChanged) {
            if (DEBUG_KEYGUARD) Slog.d(TAG, "transition/occluded changed occluded="
                    + mPendingKeyguardOccluded);
            mKeyguardOccludedChanged = false;
            if (setKeyguardOccludedLw(mPendingKeyguardOccluded, false /* force */)) {
                return FINISH_LAYOUT_REDO_LAYOUT | FINISH_LAYOUT_REDO_WALLPAPER;
            }
        }
        if (AppTransition.isKeyguardGoingAwayTransit(transit)) {
            if (DEBUG_KEYGUARD) Slog.d(TAG, "Starting keyguard exit animation");
            startKeyguardExitAnimation(SystemClock.uptimeMillis(), duration);
        }
        return 0;
    }

    // There are several different flavors of "assistant" that can be launched from
    // various parts of the UI.

    /** starts ACTION_SEARCH_LONG_PRESS, usually a voice search prompt */
    private void launchAssistLongPressAction() {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, false,
                "Assist - Long Press");
        sendCloseSystemWindows(SYSTEM_DIALOG_REASON_ASSIST);

        // launch the search activity
        Intent intent = new Intent(Intent.ACTION_SEARCH_LONG_PRESS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            // TODO: This only stops the factory-installed search manager.
            // Need to formalize an API to handle others
            SearchManager searchManager = getSearchManager();
            if (searchManager != null) {
                searchManager.stopSearch();
            }
            startActivityAsUser(intent, UserHandle.CURRENT);
        } catch (ActivityNotFoundException e) {
            Slog.w(TAG, "No activity to handle assist long press action.", e);
        }
    }

    /** Asks the status bar to startAssist(), usually a full "assistant" interface */
    private void launchAssistAction(String hint, int deviceId) {
        sendCloseSystemWindows(SYSTEM_DIALOG_REASON_ASSIST);
        if (!isUserSetupComplete()) {
            // Disable opening assist window during setup
            return;
        }
        Bundle args = null;
        if (deviceId > Integer.MIN_VALUE) {
            args = new Bundle();
            args.putInt(Intent.EXTRA_ASSIST_INPUT_DEVICE_ID, deviceId);
        }
        if ((mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION) {
            // On TV, use legacy handling until assistants are implemented in the proper way.
            ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                    .launchLegacyAssist(hint, UserHandle.myUserId(), args);
        } else {
            if (hint != null) {
                if (args == null) {
                    args = new Bundle();
                }
                args.putBoolean(hint, true);
            }
            StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
            if (statusbar != null) {
                statusbar.startAssist(args);
            }
        }
    }

    /** Launches ACTION_VOICE_ASSIST. Does nothing on keyguard. */
    private void launchVoiceAssist(boolean allowDuringSetup) {
        final boolean keyguardActive = mKeyguardDelegate == null
                ? false
                : mKeyguardDelegate.isShowing();
        if (!keyguardActive) {
            Intent intent = new Intent(Intent.ACTION_VOICE_ASSIST);
            startActivityAsUser(intent, null, UserHandle.CURRENT_OR_SELF,
                    allowDuringSetup);
        }

    }

    private void startActivityAsUser(Intent intent, UserHandle handle) {
        startActivityAsUser(intent, null, handle);
    }

    private void startActivityAsUser(Intent intent, Bundle bundle, UserHandle handle) {
        startActivityAsUser(intent, bundle, handle, false /* allowDuringSetup */);
    }

    private void startActivityAsUser(Intent intent, Bundle bundle, UserHandle handle,
            boolean allowDuringSetup) {
        if (allowDuringSetup || isUserSetupComplete()) {
            mContext.startActivityAsUser(intent, bundle, handle);
        } else {
            Slog.i(TAG, "Not starting activity because user setup is in progress: " + intent);
        }
    }

    private SearchManager getSearchManager() {
        if (mSearchManager == null) {
            mSearchManager = (SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE);
        }
        return mSearchManager;
    }

    private void preloadRecentApps() {
        mPreloadedRecentApps = true;
        StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
        if (statusbar != null) {
            statusbar.preloadRecentApps();
        }
    }

    private void cancelPreloadRecentApps() {
        if (mPreloadedRecentApps) {
            mPreloadedRecentApps = false;
            StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
            if (statusbar != null) {
                statusbar.cancelPreloadRecentApps();
            }
        }
    }

    private void toggleRecentApps() {
        mPreloadedRecentApps = false; // preloading no longer needs to be canceled
        StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
        if (statusbar != null) {
            statusbar.toggleRecentApps();
        }
    }

    @Override
    public void showRecentApps() {
        mHandler.removeMessages(MSG_DISPATCH_SHOW_RECENTS);
        mHandler.obtainMessage(MSG_DISPATCH_SHOW_RECENTS).sendToTarget();
    }

    private void showRecentApps(boolean triggeredFromAltTab) {
        mPreloadedRecentApps = false; // preloading no longer needs to be canceled
        StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
        if (statusbar != null) {
            statusbar.showRecentApps(triggeredFromAltTab);
        }
    }

    private void toggleKeyboardShortcutsMenu(int deviceId) {
        StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
        if (statusbar != null) {
            statusbar.toggleKeyboardShortcutsMenu(deviceId);
        }
    }

    private void dismissKeyboardShortcutsMenu() {
        StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
        if (statusbar != null) {
            statusbar.dismissKeyboardShortcutsMenu();
        }
    }

    private void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHome) {
        mPreloadedRecentApps = false; // preloading no longer needs to be canceled
        StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
        if (statusbar != null) {
            statusbar.hideRecentApps(triggeredFromAltTab, triggeredFromHome);
        }
    }

    void launchHomeFromHotKey(int displayId) {
        launchHomeFromHotKey(displayId, true /* awakenFromDreams */, true /*respectKeyguard*/);
    }

    /**
     * A home key -> launch home action was detected.  Take the appropriate action
     * given the situation with the keyguard.
     */
    void launchHomeFromHotKey(int displayId, final boolean awakenFromDreams,
            final boolean respectKeyguard) {
        if (respectKeyguard) {
            if (isKeyguardShowingAndNotOccluded()) {
                // don't launch home if keyguard showing
                return;
            }

            if (!mKeyguardOccluded && mKeyguardDelegate.isInputRestricted()) {
                // when in keyguard restricted mode, must first verify unlock
                // before launching home
                mKeyguardDelegate.verifyUnlock(new OnKeyguardExitResult() {
                    @Override
                    public void onKeyguardExitResult(boolean success) {
                        if (success) {
                            startDockOrHome(displayId, true /*fromHomeKey*/, awakenFromDreams);
                        }
                    }
                });
                return;
            }
        }

        // no keyguard stuff to worry about, just launch home!
        if (mRecentsVisible) {
            try {
                ActivityManager.getService().stopAppSwitches();
            } catch (RemoteException e) {}

            // Hide Recents and notify it to launch Home
            if (awakenFromDreams) {
                awakenDreams();
            }
            hideRecentApps(false, true);
        } else {
            // Otherwise, just launch Home
            startDockOrHome(displayId, true /*fromHomeKey*/, awakenFromDreams);
        }
    }

    @Override
    public void setRecentsVisibilityLw(boolean visible) {
        mRecentsVisible = visible;
    }

    @Override
    public void setPipVisibilityLw(boolean visible) {
        mPictureInPictureVisible = visible;
    }

    @Override
    public void setNavBarVirtualKeyHapticFeedbackEnabledLw(boolean enabled) {
        mNavBarVirtualKeyHapticFeedbackEnabled = enabled;
    }

    /** {@inheritDoc} */
    @Override
    public void applyKeyguardPolicyLw(WindowState win, WindowState imeTarget) {
        if (canBeHiddenByKeyguardLw(win)) {
            if (shouldBeHiddenByKeyguard(win, imeTarget)) {
                win.hideLw(false /* doAnimation */);
            } else {
                win.showLw(false /* doAnimation */);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void setKeyguardCandidateLw(WindowState win) {
        mKeyguardCandidate = win;
        setKeyguardOccludedLw(mKeyguardOccluded, true /* force */);
    }

    /**
     * Updates the occluded state of the Keyguard.
     *
     * @return Whether the flags have changed and we have to redo the layout.
     */
    private boolean setKeyguardOccludedLw(boolean isOccluded, boolean force) {
        if (DEBUG_KEYGUARD) Slog.d(TAG, "setKeyguardOccluded occluded=" + isOccluded);
        final boolean wasOccluded = mKeyguardOccluded;
        final boolean showing = mKeyguardDelegate.isShowing();
        final boolean changed = wasOccluded != isOccluded || force;
        if (!isOccluded && changed && showing) {
            mKeyguardOccluded = false;
            mKeyguardDelegate.setOccluded(false, true /* animate */);
            if (mKeyguardCandidate != null) {
                mKeyguardCandidate.getAttrs().privateFlags |= PRIVATE_FLAG_KEYGUARD;
                if (!mKeyguardDelegate.hasLockscreenWallpaper()) {
                    mKeyguardCandidate.getAttrs().flags |= FLAG_SHOW_WALLPAPER;
                }
            }
            return true;
        } else if (isOccluded && changed && showing) {
            mKeyguardOccluded = true;
            mKeyguardDelegate.setOccluded(true, false /* animate */);
            if (mKeyguardCandidate != null) {
                mKeyguardCandidate.getAttrs().privateFlags &= ~PRIVATE_FLAG_KEYGUARD;
                mKeyguardCandidate.getAttrs().flags &= ~FLAG_SHOW_WALLPAPER;
            }
            return true;
        } else if (changed) {
            mKeyguardOccluded = isOccluded;
            mKeyguardDelegate.setOccluded(isOccluded, false /* animate */);
            return false;
        } else {
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen) {
        // lid changed state
        final int newLidState = lidOpen ? LID_OPEN : LID_CLOSED;
        if (newLidState == mDefaultDisplayPolicy.getLidState()) {
            return;
        }

        mDefaultDisplayPolicy.setLidState(newLidState);
        applyLidSwitchState();
        updateRotation(true);

        if (lidOpen) {
            wakeUp(SystemClock.uptimeMillis(), mAllowTheaterModeWakeFromLidSwitch,
                    PowerManager.WAKE_REASON_LID, "android.policy:LID");
        } else if (getLidBehavior() != LID_BEHAVIOR_SLEEP) {
            mPowerManager.userActivity(SystemClock.uptimeMillis(), false);
        }
    }

    @Override
    public void notifyCameraLensCoverSwitchChanged(long whenNanos, boolean lensCovered) {
        int lensCoverState = lensCovered ? CAMERA_LENS_COVERED : CAMERA_LENS_UNCOVERED;
        if (mCameraLensCoverState == lensCoverState) {
            return;
        }
        if (mCameraLensCoverState == CAMERA_LENS_COVERED &&
                lensCoverState == CAMERA_LENS_UNCOVERED) {
            Intent intent;
            final boolean keyguardActive = mKeyguardDelegate == null ? false :
                    mKeyguardDelegate.isShowing();
            if (keyguardActive) {
                intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
            } else {
                intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
            }
            wakeUp(whenNanos / 1000000, mAllowTheaterModeWakeFromCameraLens,
                    PowerManager.WAKE_REASON_CAMERA_LAUNCH, "android.policy:CAMERA_COVER");
            startActivityAsUser(intent, UserHandle.CURRENT_OR_SELF);
        }
        mCameraLensCoverState = lensCoverState;
    }

    void initializeHdmiState() {
        final int oldMask = StrictMode.allowThreadDiskReadsMask();
        try {
            initializeHdmiStateInternal();
        } finally {
            StrictMode.setThreadPolicyMask(oldMask);
        }
    }

    void initializeHdmiStateInternal() {
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
                    plugged = 0 != Integer.parseInt(new String(buf, 0, n - 1));
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
        } else if (ExtconUEventObserver.extconExists()
                && ExtconUEventObserver.namedExtconDirExists(HdmiVideoExtconUEventObserver.NAME)) {
            HdmiVideoExtconUEventObserver observer = new HdmiVideoExtconUEventObserver();
            plugged = observer.init();
            mHDMIObserver = observer;
        } else if (localLOGV) {
            Slog.v(TAG, "Not observing HDMI plug state because HDMI was not found.");
        }

        // This dance forces the code in setHdmiPlugged to run.
        // Always do this so the sticky intent is stuck (to false) if there is no hdmi.
        mDefaultDisplayPolicy.setHdmiPlugged(plugged, true /* force */);
    }

    // TODO(b/117479243): handle it in InputPolicy
    /** {@inheritDoc} */
    @Override
    public int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags) {
        if (!mSystemBooted) {
            // If we have not yet booted, don't let key events do anything.
            return 0;
        }

        final boolean interactive = (policyFlags & FLAG_INTERACTIVE) != 0;
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final boolean canceled = event.isCanceled();
        final int keyCode = event.getKeyCode();
        final int displayId = event.getDisplayId();

        final boolean isInjected = (policyFlags & WindowManagerPolicy.FLAG_INJECTED) != 0;

        // If screen is off then we treat the case where the keyguard is open but hidden
        // the same as if it were open and in front.
        // This will prevent any keys other than the power button from waking the screen
        // when the keyguard is hidden by another activity.
        final boolean keyguardActive = (mKeyguardDelegate == null ? false :
                                            (interactive ?
                                                isKeyguardShowingAndNotOccluded() :
                                                mKeyguardDelegate.isShowing()));

        if (DEBUG_INPUT) {
            Log.d(TAG, "interceptKeyTq keycode=" + keyCode
                    + " interactive=" + interactive + " keyguardActive=" + keyguardActive
                    + " policyFlags=" + Integer.toHexString(policyFlags));
        }

        // Basic policy based on interactive state.
        int result;
        boolean isWakeKey = (policyFlags & WindowManagerPolicy.FLAG_WAKE) != 0
                || event.isWakeKey();
        if (interactive || (isInjected && !isWakeKey)) {
            // When the device is interactive or the key is injected pass the
            // key to the application.
            result = ACTION_PASS_TO_USER;
            isWakeKey = false;

            if (interactive) {
                // If the screen is awake, but the button pressed was the one that woke the device
                // then don't pass it to the application
                if (keyCode == mPendingWakeKey && !down) {
                    result = 0;
                }
                // Reset the pending key
                mPendingWakeKey = PENDING_KEY_NULL;
            }
        } else if (!interactive && shouldDispatchInputWhenNonInteractive(displayId, keyCode)) {
            // If we're currently dozing with the screen on and the keyguard showing, pass the key
            // to the application but preserve its wake key status to make sure we still move
            // from dozing to fully interactive if we would normally go from off to fully
            // interactive.
            result = ACTION_PASS_TO_USER;
            // Since we're dispatching the input, reset the pending key
            mPendingWakeKey = PENDING_KEY_NULL;
        } else {
            // When the screen is off and the key is not injected, determine whether
            // to wake the device but don't pass the key to the application.
            result = 0;
            if (isWakeKey && (!down || !isWakeKeyWhenScreenOff(keyCode))) {
                isWakeKey = false;
            }
            // Cache the wake key on down event so we can also avoid sending the up event to the app
            if (isWakeKey && down) {
                mPendingWakeKey = keyCode;
            }
        }

        // If the key would be handled globally, just return the result, don't worry about special
        // key processing.
        if (isValidGlobalKey(keyCode)
                && mGlobalKeyManager.shouldHandleGlobalKey(keyCode, event)) {
            if (isWakeKey) {
                wakeUp(event.getEventTime(), mAllowTheaterModeWakeFromKey,
                        PowerManager.WAKE_REASON_WAKE_KEY, "android.policy:KEY");
            }
            return result;
        }

        // Enable haptics if down and virtual key without multiple repetitions. If this is a hard
        // virtual key such as a navigation bar button, only vibrate if flag is enabled.
        final boolean isNavBarVirtKey = ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0);
        boolean useHapticFeedback = down
                && (policyFlags & WindowManagerPolicy.FLAG_VIRTUAL) != 0
                && (!isNavBarVirtKey || mNavBarVirtualKeyHapticFeedbackEnabled)
                && event.getRepeatCount() == 0;

        // Handle special keys.
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK: {
                if (down) {
                    interceptBackKeyDown();
                } else {
                    boolean handled = interceptBackKeyUp(event);

                    // Don't pass back press to app if we've already handled it via long press
                    if (handled) {
                        result &= ~ACTION_PASS_TO_USER;
                    }
                }
                break;
            }

            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_MUTE: {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    if (down) {
                        // Any activity on the vol down button stops the ringer toggle shortcut
                        cancelPendingRingerToggleChordAction();

                        if (interactive && !mScreenshotChordVolumeDownKeyTriggered
                                && (event.getFlags() & KeyEvent.FLAG_FALLBACK) == 0) {
                            mScreenshotChordVolumeDownKeyTriggered = true;
                            mScreenshotChordVolumeDownKeyTime = event.getDownTime();
                            mScreenshotChordVolumeDownKeyConsumed = false;
                            cancelPendingPowerKeyAction();
                            interceptScreenshotChord();
                            interceptAccessibilityShortcutChord();
                        }
                    } else {
                        mScreenshotChordVolumeDownKeyTriggered = false;
                        cancelPendingScreenshotChordAction();
                        cancelPendingAccessibilityShortcutAction();
                    }
                } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    if (down) {
                        if (interactive && !mA11yShortcutChordVolumeUpKeyTriggered
                                && (event.getFlags() & KeyEvent.FLAG_FALLBACK) == 0) {
                            mA11yShortcutChordVolumeUpKeyTriggered = true;
                            mA11yShortcutChordVolumeUpKeyTime = event.getDownTime();
                            mA11yShortcutChordVolumeUpKeyConsumed = false;
                            cancelPendingPowerKeyAction();
                            cancelPendingScreenshotChordAction();
                            cancelPendingRingerToggleChordAction();

                            interceptAccessibilityShortcutChord();
                            interceptRingerToggleChord();
                        }
                    } else {
                        mA11yShortcutChordVolumeUpKeyTriggered = false;
                        cancelPendingScreenshotChordAction();
                        cancelPendingAccessibilityShortcutAction();
                        cancelPendingRingerToggleChordAction();
                    }
                }
                if (down) {
                    sendSystemKeyToStatusBarAsync(event.getKeyCode());

                    TelecomManager telecomManager = getTelecommService();
                    if (telecomManager != null && !mHandleVolumeKeysInWM) {
                        // When {@link #mHandleVolumeKeysInWM} is set, volume key events
                        // should be dispatched to WM.
                        if (telecomManager.isRinging()) {
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
                            telecomManager.silenceRinger();

                            // And *don't* pass this key thru to the current activity
                            // (which is probably the InCallScreen.)
                            result &= ~ACTION_PASS_TO_USER;
                            break;
                        }
                    }
                    int audioMode = AudioManager.MODE_NORMAL;
                    try {
                        audioMode = getAudioService().getMode();
                    } catch (Exception e) {
                        Log.e(TAG, "Error getting AudioService in interceptKeyBeforeQueueing.", e);
                    }
                    boolean isInCall = (telecomManager != null && telecomManager.isInCall()) ||
                            audioMode == AudioManager.MODE_IN_COMMUNICATION;
                    if (isInCall && (result & ACTION_PASS_TO_USER) == 0) {
                        // If we are in call but we decided not to pass the key to
                        // the application, just pass it to the session service.
                        MediaSessionLegacyHelper.getHelper(mContext).sendVolumeKeyEvent(
                                event, AudioManager.USE_DEFAULT_STREAM_TYPE, false);
                        break;
                    }
                }
                if (mUseTvRouting || mHandleVolumeKeysInWM) {
                    // Defer special key handlings to
                    // {@link interceptKeyBeforeDispatching()}.
                    result |= ACTION_PASS_TO_USER;
                } else if ((result & ACTION_PASS_TO_USER) == 0) {
                    // If we aren't passing to the user and no one else
                    // handled it send it to the session manager to
                    // figure out.
                    MediaSessionLegacyHelper.getHelper(mContext).sendVolumeKeyEvent(
                            event, AudioManager.USE_DEFAULT_STREAM_TYPE, true);
                }
                break;
            }

            case KeyEvent.KEYCODE_ENDCALL: {
                result &= ~ACTION_PASS_TO_USER;
                if (down) {
                    TelecomManager telecomManager = getTelecommService();
                    boolean hungUp = false;
                    if (telecomManager != null) {
                        hungUp = telecomManager.endCall();
                    }
                    if (interactive && !hungUp) {
                        mEndCallKeyHandled = false;
                        mHandler.postDelayed(mEndCallLongPress,
                                ViewConfiguration.get(mContext).getDeviceGlobalActionKeyTimeout());
                    } else {
                        mEndCallKeyHandled = true;
                    }
                } else {
                    if (!mEndCallKeyHandled) {
                        mHandler.removeCallbacks(mEndCallLongPress);
                        if (!canceled) {
                            if ((mEndcallBehavior
                                    & Settings.System.END_BUTTON_BEHAVIOR_HOME) != 0) {
                                if (goHome()) {
                                    break;
                                }
                            }
                            if ((mEndcallBehavior
                                    & Settings.System.END_BUTTON_BEHAVIOR_SLEEP) != 0) {
                                goToSleep(event.getEventTime(),
                                        PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, 0);
                                isWakeKey = false;
                            }
                        }
                    }
                }
                break;
            }

            case KeyEvent.KEYCODE_POWER: {
                EventLogTags.writeInterceptPower(
                        KeyEvent.actionToString(event.getAction()),
                        mPowerKeyHandled ? 1 : 0, mPowerKeyPressCounter);
                // Any activity on the power button stops the accessibility shortcut
                cancelPendingAccessibilityShortcutAction();
                result &= ~ACTION_PASS_TO_USER;
                isWakeKey = false; // wake-up will be handled separately
                if (down) {
                    interceptPowerKeyDown(event, interactive);
                } else {
                    interceptPowerKeyUp(event, interactive, canceled);
                }
                break;
            }

            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN:
                // fall through
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP:
                // fall through
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT:
                // fall through
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT: {
                result &= ~ACTION_PASS_TO_USER;
                interceptSystemNavigationKey(event);
                break;
            }

            case KeyEvent.KEYCODE_SLEEP: {
                result &= ~ACTION_PASS_TO_USER;
                isWakeKey = false;
                if (!mPowerManager.isInteractive()) {
                    useHapticFeedback = false; // suppress feedback if already non-interactive
                }
                if (down) {
                    sleepPress();
                } else {
                    sleepRelease(event.getEventTime());
                }
                break;
            }

            case KeyEvent.KEYCODE_SOFT_SLEEP: {
                result &= ~ACTION_PASS_TO_USER;
                isWakeKey = false;
                if (!down) {
                    mPowerManagerInternal.setUserInactiveOverrideFromWindowManager();
                }
                break;
            }

            case KeyEvent.KEYCODE_WAKEUP: {
                result &= ~ACTION_PASS_TO_USER;
                isWakeKey = true;
                break;
            }

            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK: {
                if (MediaSessionLegacyHelper.getHelper(mContext).isGlobalPriorityActive()) {
                    // If the global session is active pass all media keys to it
                    // instead of the active window.
                    result &= ~ACTION_PASS_TO_USER;
                }
                if ((result & ACTION_PASS_TO_USER) == 0) {
                    // Only do this if we would otherwise not pass it to the user. In that
                    // case, the PhoneWindow class will do the same thing, except it will
                    // only do it if the showing app doesn't process the key on its own.
                    // Note that we need to make a copy of the key event here because the
                    // original key event will be recycled when we return.
                    mBroadcastWakeLock.acquire();
                    Message msg = mHandler.obtainMessage(MSG_DISPATCH_MEDIA_KEY_WITH_WAKE_LOCK,
                            new KeyEvent(event));
                    msg.setAsynchronous(true);
                    msg.sendToTarget();
                }
                break;
            }

            case KeyEvent.KEYCODE_CALL: {
                if (down) {
                    TelecomManager telecomManager = getTelecommService();
                    if (telecomManager != null) {
                        if (telecomManager.isRinging()) {
                            Log.i(TAG, "interceptKeyBeforeQueueing:"
                                  + " CALL key-down while ringing: Answer the call!");
                            telecomManager.acceptRingingCall();

                            // And *don't* pass this key thru to the current activity
                            // (which is presumably the InCallScreen.)
                            result &= ~ACTION_PASS_TO_USER;
                        }
                    }
                }
                break;
            }
            case KeyEvent.KEYCODE_ASSIST: {
                final boolean longPressed = event.getRepeatCount() > 0;
                if (down && longPressed) {
                    Message msg = mHandler.obtainMessage(MSG_LAUNCH_ASSIST_LONG_PRESS);
                    msg.setAsynchronous(true);
                    msg.sendToTarget();
                }
                if (!down && !longPressed) {
                    Message msg = mHandler.obtainMessage(MSG_LAUNCH_ASSIST, event.getDeviceId(),
                            0 /* unused */, null /* hint */);
                    msg.setAsynchronous(true);
                    msg.sendToTarget();
                }
                result &= ~ACTION_PASS_TO_USER;
                break;
            }
            case KeyEvent.KEYCODE_VOICE_ASSIST: {
                if (!down) {
                    mBroadcastWakeLock.acquire();
                    Message msg = mHandler.obtainMessage(MSG_LAUNCH_VOICE_ASSIST_WITH_WAKE_LOCK);
                    msg.setAsynchronous(true);
                    msg.sendToTarget();
                }
                result &= ~ACTION_PASS_TO_USER;
                break;
            }
            case KeyEvent.KEYCODE_WINDOW: {
                if (mShortPressOnWindowBehavior == SHORT_PRESS_WINDOW_PICTURE_IN_PICTURE) {
                    if (mPictureInPictureVisible) {
                        // Consumes the key only if picture-in-picture is visible to show
                        // picture-in-picture control menu. This gives a chance to the foreground
                        // activity to customize PIP key behavior.
                        if (!down) {
                            showPictureInPictureMenu(event);
                        }
                        result &= ~ACTION_PASS_TO_USER;
                    }
                }
                break;
            }
        }

        // Intercept the Accessibility keychord for TV (DPAD_DOWN + Back) before the keyevent is
        // processed through interceptKeyEventBeforeDispatch since Talkback may consume this event
        // before it has a chance to reach that method.
        if (mHasFeatureLeanback) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_BACK: {
                    boolean handled = interceptAccessibilityGestureTv(keyCode, down);
                    if (handled) {
                        result &= ~ACTION_PASS_TO_USER;
                    }
                    break;
                }
            }
        }

        // Intercept the Accessibility keychord (CTRL + ALT + Z) for keyboard users.
        if (mAccessibilityShortcutController.isAccessibilityShortcutAvailable(isKeyguardLocked())) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_Z: {
                    if (down && event.isCtrlPressed() && event.isAltPressed()) {
                        mHandler.sendMessage(mHandler.obtainMessage(MSG_ACCESSIBILITY_SHORTCUT));
                        result &= ~ACTION_PASS_TO_USER;
                    }
                    break;
                }
            }
        }

        if (useHapticFeedback) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, false,
                    "Virtual Key - Press");
        }

        if (isWakeKey) {
            wakeUp(event.getEventTime(), mAllowTheaterModeWakeFromKey,
                    PowerManager.WAKE_REASON_WAKE_KEY, "android.policy:KEY");
        }

        return result;
    }

    /**
     * Handle statusbar expansion events.
     * @param event
     */
    private void interceptSystemNavigationKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (!mAccessibilityManager.isEnabled()
                    || !mAccessibilityManager.sendFingerprintGesture(event.getKeyCode())) {
                if (mSystemNavigationKeysEnabled) {
                    sendSystemKeyToStatusBarAsync(event.getKeyCode());
                }
            }
        }
    }

    /**
     * Notify the StatusBar that a system key was pressed.
     */
    private void sendSystemKeyToStatusBar(int keyCode) {
        IStatusBarService statusBar = getStatusBarService();
        if (statusBar != null) {
            try {
                statusBar.handleSystemKey(keyCode);
            } catch (RemoteException e) {
                // Oh well.
            }
        }
    }

    /**
     * Notify the StatusBar that a system key was pressed without blocking the current thread.
     */
    private void sendSystemKeyToStatusBarAsync(int keyCode) {
        Message message = mHandler.obtainMessage(MSG_SYSTEM_KEY_PRESS, keyCode, 0);
        message.setAsynchronous(true);
        mHandler.sendMessage(message);
    }

    /**
     * Returns true if the key can have global actions attached to it.
     * We reserve all power management keys for the system since they require
     * very careful handling.
     */
    private static boolean isValidGlobalKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_SLEEP:
                return false;
            default:
                return true;
        }
    }

    /**
     * When the screen is off we ignore some keys that might otherwise typically
     * be considered wake keys.  We filter them out here.
     *
     * {@link KeyEvent#KEYCODE_POWER} is notably absent from this list because it
     * is always considered a wake key.
     */
    private boolean isWakeKeyWhenScreenOff(int keyCode) {
        switch (keyCode) {
            // ignore volume keys unless docked
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return mDefaultDisplayPolicy.getDockMode() != Intent.EXTRA_DOCK_STATE_UNDOCKED;

            // ignore media and camera keys
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK:
            case KeyEvent.KEYCODE_CAMERA:
                return false;
        }
        return true;
    }

    // TODO(b/117479243): handle it in InputPolicy
    /** {@inheritDoc} */
    @Override
    public int interceptMotionBeforeQueueingNonInteractive(int displayId, long whenNanos,
            int policyFlags) {
        if ((policyFlags & FLAG_WAKE) != 0) {
            if (wakeUp(whenNanos / 1000000, mAllowTheaterModeWakeFromMotion,
                    PowerManager.WAKE_REASON_WAKE_MOTION, "android.policy:MOTION")) {
                return 0;
            }
        }

        if (shouldDispatchInputWhenNonInteractive(displayId, KEYCODE_UNKNOWN)) {
            return ACTION_PASS_TO_USER;
        }

        // If we have not passed the action up and we are in theater mode without dreaming,
        // there will be no dream to intercept the touch and wake into ambient.  The device should
        // wake up in this case.
        if (isTheaterModeEnabled() && (policyFlags & FLAG_WAKE) != 0) {
            wakeUp(whenNanos / 1000000, mAllowTheaterModeWakeFromMotionWhenNotDreaming,
                    PowerManager.WAKE_REASON_WAKE_MOTION, "android.policy:MOTION");
        }

        return 0;
    }

    private boolean shouldDispatchInputWhenNonInteractive(int displayId, int keyCode) {
        // Apply the default display policy to unknown displays as well.
        final boolean isDefaultDisplay = displayId == DEFAULT_DISPLAY
                || displayId == INVALID_DISPLAY;
        final Display display = isDefaultDisplay
                ? mDefaultDisplay
                : mDisplayManager.getDisplay(displayId);
        final boolean displayOff = (display == null
                || display.getState() == STATE_OFF);

        if (displayOff && !mHasFeatureWatch) {
            return false;
        }

        // Send events to keyguard while the screen is on and it's showing.
        if (isKeyguardShowingAndNotOccluded() && !displayOff) {
            return true;
        }

        // Watches handle BACK specially
        if (mHasFeatureWatch && (keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_STEM_PRIMARY)) {
            return false;
        }

        // TODO(b/123372519): Refine when dream can support multi display.
        if (isDefaultDisplay) {
            // Send events to a dozing dream even if the screen is off since the dream
            // is in control of the state of the screen.
            IDreamManager dreamManager = getDreamManager();

            try {
                if (dreamManager != null && dreamManager.isDreaming()) {
                    return true;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "RemoteException when checking if dreaming", e);
            }
        }
        // Otherwise, consume events since the user can't see what is being
        // interacted with.
        return false;
    }

    private void dispatchDirectAudioEvent(KeyEvent event) {
        // When System Audio Mode is off, volume keys received by AVR can be either consumed by AVR
        // or forwarded to the TV. It's up to Amplifier manufacturer’s implementation.
        HdmiControlManager hdmiControlManager = getHdmiControlManager();
        if (null != hdmiControlManager
                && !hdmiControlManager.getSystemAudioMode()
                && shouldCecAudioDeviceForwardVolumeKeysSystemAudioModeOff()) {
            HdmiAudioSystemClient audioSystemClient = hdmiControlManager.getAudioSystemClient();
            if (audioSystemClient != null) {
                audioSystemClient.sendKeyEvent(
                        event.getKeyCode(), event.getAction() == KeyEvent.ACTION_DOWN);
                return;
            }
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return;
        }
        int keyCode = event.getKeyCode();
        int flags = AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_PLAY_SOUND
                | AudioManager.FLAG_FROM_KEY;
        String pkgName = mContext.getOpPackageName();

        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                try {
                    getAudioService().adjustSuggestedStreamVolume(AudioManager.ADJUST_RAISE,
                            AudioManager.USE_DEFAULT_STREAM_TYPE, flags, pkgName, TAG);
                } catch (Exception e) {
                    Log.e(TAG, "Error dispatching volume up in dispatchTvAudioEvent.", e);
                }
                break;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                try {
                    getAudioService().adjustSuggestedStreamVolume(AudioManager.ADJUST_LOWER,
                            AudioManager.USE_DEFAULT_STREAM_TYPE, flags, pkgName, TAG);
                } catch (Exception e) {
                    Log.e(TAG, "Error dispatching volume down in dispatchTvAudioEvent.", e);
                }
                break;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                try {
                    if (event.getRepeatCount() == 0) {
                        getAudioService().adjustSuggestedStreamVolume(
                                AudioManager.ADJUST_TOGGLE_MUTE,
                                AudioManager.USE_DEFAULT_STREAM_TYPE, flags, pkgName, TAG);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error dispatching mute in dispatchTvAudioEvent.", e);
                }
                break;
        }
    }

    @Nullable
    private HdmiControlManager getHdmiControlManager() {
        if (!mHasFeatureHdmiCec) {
            return null;
        }
        return (HdmiControlManager) mContext.getSystemService(HdmiControlManager.class);
    }

    private boolean shouldCecAudioDeviceForwardVolumeKeysSystemAudioModeOff() {
        return RoSystemProperties.CEC_AUDIO_DEVICE_FORWARD_VOLUME_KEYS_SYSTEM_AUDIO_MODE_OFF;
    }

    void dispatchMediaKeyWithWakeLock(KeyEvent event) {
        if (DEBUG_INPUT) {
            Slog.d(TAG, "dispatchMediaKeyWithWakeLock: " + event);
        }

        if (mHavePendingMediaKeyRepeatWithWakeLock) {
            if (DEBUG_INPUT) {
                Slog.d(TAG, "dispatchMediaKeyWithWakeLock: canceled repeat");
            }

            mHandler.removeMessages(MSG_DISPATCH_MEDIA_KEY_REPEAT_WITH_WAKE_LOCK);
            mHavePendingMediaKeyRepeatWithWakeLock = false;
            mBroadcastWakeLock.release(); // pending repeat was holding onto the wake lock
        }

        dispatchMediaKeyWithWakeLockToAudioService(event);

        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            mHavePendingMediaKeyRepeatWithWakeLock = true;

            Message msg = mHandler.obtainMessage(
                    MSG_DISPATCH_MEDIA_KEY_REPEAT_WITH_WAKE_LOCK, event);
            msg.setAsynchronous(true);
            mHandler.sendMessageDelayed(msg, ViewConfiguration.getKeyRepeatTimeout());
        } else {
            mBroadcastWakeLock.release();
        }
    }

    void dispatchMediaKeyRepeatWithWakeLock(KeyEvent event) {
        mHavePendingMediaKeyRepeatWithWakeLock = false;

        KeyEvent repeatEvent = KeyEvent.changeTimeRepeat(event,
                SystemClock.uptimeMillis(), 1, event.getFlags() | KeyEvent.FLAG_LONG_PRESS);
        if (DEBUG_INPUT) {
            Slog.d(TAG, "dispatchMediaKeyRepeatWithWakeLock: " + repeatEvent);
        }

        dispatchMediaKeyWithWakeLockToAudioService(repeatEvent);
        mBroadcastWakeLock.release();
    }

    void dispatchMediaKeyWithWakeLockToAudioService(KeyEvent event) {
        if (mActivityManagerInternal.isSystemReady()) {
            MediaSessionLegacyHelper.getHelper(mContext).sendMediaButtonEvent(event, true);
        }
    }

    void launchVoiceAssistWithWakeLock() {
        sendCloseSystemWindows(SYSTEM_DIALOG_REASON_ASSIST);

        final Intent voiceIntent;
        if (!keyguardOn()) {
            voiceIntent = new Intent(RecognizerIntent.ACTION_WEB_SEARCH);
        } else {
            IDeviceIdleController dic = IDeviceIdleController.Stub.asInterface(
                    ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));
            if (dic != null) {
                try {
                    dic.exitIdle("voice-search");
                } catch (RemoteException e) {
                }
            }
            voiceIntent = new Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE);
            voiceIntent.putExtra(RecognizerIntent.EXTRA_SECURE, true);
        }
        startActivityAsUser(voiceIntent, UserHandle.CURRENT_OR_SELF);
        mBroadcastWakeLock.release();
    }

    BroadcastReceiver mDockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_DOCK_EVENT.equals(intent.getAction())) {
                mDefaultDisplayPolicy.setDockMode(intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                        Intent.EXTRA_DOCK_STATE_UNDOCKED));
            } else {
                try {
                    IUiModeManager uiModeService = IUiModeManager.Stub.asInterface(
                            ServiceManager.getService(Context.UI_MODE_SERVICE));
                    mUiMode = uiModeService.getCurrentModeType();
                } catch (RemoteException e) {
                }
            }
            updateRotation(true);
            mDefaultDisplayRotation.updateOrientationListener();
        }
    };

    BroadcastReceiver mDreamReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_DREAMING_STARTED.equals(intent.getAction())) {
                if (mKeyguardDelegate != null) {
                    mKeyguardDelegate.onDreamingStarted();
                }
            } else if (Intent.ACTION_DREAMING_STOPPED.equals(intent.getAction())) {
                if (mKeyguardDelegate != null) {
                    mKeyguardDelegate.onDreamingStopped();
                }
            }
        }
    };

    BroadcastReceiver mMultiuserReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                // tickle the settings observer: this first ensures that we're
                // observing the relevant settings for the newly-active user,
                // and then updates our own bookkeeping based on the now-
                // current user.
                mSettingsObserver.onChange(false);
                mDefaultDisplayRotation.onUserSwitch();
                mWindowManagerFuncs.onUserSwitched();
            }
        }
    };

    // Called on the PowerManager's Notifier thread.
    @Override
    public void startedGoingToSleep(int why) {
        if (DEBUG_WAKEUP) {
            Slog.i(TAG, "Started going to sleep... (why="
                    + WindowManagerPolicyConstants.offReasonToString(why) + ")");
        }

        mGoingToSleep = true;
        mRequestedOrGoingToSleep = true;

        if (mKeyguardDelegate != null) {
            mKeyguardDelegate.onStartedGoingToSleep(why);
        }
    }

    // Called on the PowerManager's Notifier thread.
    @Override
    public void finishedGoingToSleep(int why) {
        EventLogTags.writeScreenToggled(0);
        if (DEBUG_WAKEUP) {
            Slog.i(TAG, "Finished going to sleep... (why="
                    + WindowManagerPolicyConstants.offReasonToString(why) + ")");
        }
        MetricsLogger.histogram(mContext, "screen_timeout", mLockScreenTimeout / 1000);

        mGoingToSleep = false;
        mRequestedOrGoingToSleep = false;
        mDefaultDisplayPolicy.setAwake(false);

        // We must get this work done here because the power manager will drop
        // the wake lock and let the system suspend once this function returns.
        synchronized (mLock) {
            updateWakeGestureListenerLp();
            updateLockScreenTimeout();
        }
        mDefaultDisplayRotation.updateOrientationListener();

        if (mKeyguardDelegate != null) {
            mKeyguardDelegate.onFinishedGoingToSleep(why,
                    mCameraGestureTriggeredDuringGoingToSleep);
        }
        if (mDisplayFoldController != null) {
            mDisplayFoldController.finishedGoingToSleep();
        }
        mCameraGestureTriggeredDuringGoingToSleep = false;
    }

    // Called on the PowerManager's Notifier thread.
    @Override
    public void startedWakingUp(@OnReason int why) {
        EventLogTags.writeScreenToggled(1);
        if (DEBUG_WAKEUP) {
            Slog.i(TAG, "Started waking up... (why="
                    + WindowManagerPolicyConstants.onReasonToString(why) + ")");
        }

        mDefaultDisplayPolicy.setAwake(true);

        // Since goToSleep performs these functions synchronously, we must
        // do the same here.  We cannot post this work to a handler because
        // that might cause it to become reordered with respect to what
        // may happen in a future call to goToSleep.
        synchronized (mLock) {
            updateWakeGestureListenerLp();
            updateLockScreenTimeout();
        }
        mDefaultDisplayRotation.updateOrientationListener();

        if (mKeyguardDelegate != null) {
            mKeyguardDelegate.onStartedWakingUp();
        }
    }

    // Called on the PowerManager's Notifier thread.
    @Override
    public void finishedWakingUp(@OnReason int why) {
        if (DEBUG_WAKEUP) {
            Slog.i(TAG, "Finished waking up... (why="
                    + WindowManagerPolicyConstants.onReasonToString(why) + ")");
        }

        if (mKeyguardDelegate != null) {
            mKeyguardDelegate.onFinishedWakingUp();
        }
        if (mDisplayFoldController != null) {
            mDisplayFoldController.finishedWakingUp();
        }
    }

    private void wakeUpFromPowerKey(long eventTime) {
        wakeUp(eventTime, mAllowTheaterModeWakeFromPowerKey,
                PowerManager.WAKE_REASON_POWER_BUTTON, "android.policy:POWER");
    }

    private boolean wakeUp(long wakeTime, boolean wakeInTheaterMode, @WakeReason int reason,
            String details) {
        final boolean theaterModeEnabled = isTheaterModeEnabled();
        if (!wakeInTheaterMode && theaterModeEnabled) {
            return false;
        }

        if (theaterModeEnabled) {
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.THEATER_MODE_ON, 0);
        }

        mPowerManager.wakeUp(wakeTime, reason, details);
        return true;
    }

    private void finishKeyguardDrawn() {
        if (!mDefaultDisplayPolicy.finishKeyguardDrawn()) {
            return;
        }

        synchronized (mLock) {
            if (mKeyguardDelegate != null) {
                mHandler.removeMessages(MSG_KEYGUARD_DRAWN_TIMEOUT);
            }
        }

        // ... eventually calls finishWindowsDrawn which will finalize our screen turn on
        // as well as enabling the orientation change logic/sensor.
        mWindowManagerInternal.waitForAllWindowsDrawn(mWindowManagerDrawCallback,
                WAITING_FOR_DRAWN_TIMEOUT);
    }

    // Called on the DisplayManager's DisplayPowerController thread.
    @Override
    public void screenTurnedOff() {
        if (DEBUG_WAKEUP) Slog.i(TAG, "Screen turned off...");

        updateScreenOffSleepToken(true);
        mDefaultDisplayPolicy.screenTurnedOff();
        synchronized (mLock) {
            if (mKeyguardDelegate != null) {
                mKeyguardDelegate.onScreenTurnedOff();
            }
        }
        mDefaultDisplayRotation.updateOrientationListener();
        reportScreenStateToVrManager(false);
    }

    private long getKeyguardDrawnTimeout() {
        final boolean bootCompleted =
                LocalServices.getService(SystemServiceManager.class).isBootCompleted();
        // Set longer timeout if it has not booted yet to prevent showing empty window.
        return bootCompleted ? 1000 : 5000;
    }

    // Called on the DisplayManager's DisplayPowerController thread.
    @Override
    public void screenTurningOn(final ScreenOnListener screenOnListener) {
        if (DEBUG_WAKEUP) Slog.i(TAG, "Screen turning on...");

        updateScreenOffSleepToken(false);
        mDefaultDisplayPolicy.screenTurnedOn(screenOnListener);

        synchronized (mLock) {
            if (mKeyguardDelegate != null && mKeyguardDelegate.hasKeyguard()) {
                mHandler.removeMessages(MSG_KEYGUARD_DRAWN_TIMEOUT);
                mHandler.sendEmptyMessageDelayed(MSG_KEYGUARD_DRAWN_TIMEOUT,
                        getKeyguardDrawnTimeout());
                mKeyguardDelegate.onScreenTurningOn(mKeyguardDrawnCallback);
            } else {
                if (DEBUG_WAKEUP) Slog.d(TAG,
                        "null mKeyguardDelegate: setting mKeyguardDrawComplete.");
                mHandler.sendEmptyMessage(MSG_KEYGUARD_DRAWN_COMPLETE);
            }
        }
    }

    // Called on the DisplayManager's DisplayPowerController thread.
    @Override
    public void screenTurnedOn() {
        synchronized (mLock) {
            if (mKeyguardDelegate != null) {
                mKeyguardDelegate.onScreenTurnedOn();
            }
        }
        reportScreenStateToVrManager(true);
    }

    @Override
    public void screenTurningOff(ScreenOffListener screenOffListener) {
        mWindowManagerFuncs.screenTurningOff(screenOffListener);
        synchronized (mLock) {
            if (mKeyguardDelegate != null) {
                mKeyguardDelegate.onScreenTurningOff();
            }
        }
    }

    private void reportScreenStateToVrManager(boolean isScreenOn) {
        if (mVrManagerInternal == null) {
            return;
        }
        mVrManagerInternal.onScreenStateChanged(isScreenOn);
    }

    private void finishWindowsDrawn() {
        if (!mDefaultDisplayPolicy.finishWindowsDrawn()) {
            return;
        }

        finishScreenTurningOn();
    }

    private void finishScreenTurningOn() {
        // We have just finished drawing screen content. Since the orientation listener
        // gets only installed when all windows are drawn, we try to install it again.
        mDefaultDisplayRotation.updateOrientationListener();

        final ScreenOnListener listener = mDefaultDisplayPolicy.getScreenOnListener();
        if (!mDefaultDisplayPolicy.finishScreenTurningOn()) {
            return; // Spurious or not ready yet.
        }

        final boolean enableScreen;
        final boolean awake = mDefaultDisplayPolicy.isAwake();
        synchronized (mLock) {
            // Remember the first time we draw the keyguard so we know when we're done with
            // the main part of booting and can enable the screen and hide boot messages.
            if (!mKeyguardDrawnOnce && awake) {
                mKeyguardDrawnOnce = true;
                enableScreen = true;
                if (mBootMessageNeedsHiding) {
                    mBootMessageNeedsHiding = false;
                    hideBootMessages();
                }
            } else {
                enableScreen = false;
            }
        }

        if (listener != null) {
            listener.onScreenOn();
        }

        if (enableScreen) {
            try {
                mWindowManager.enableScreenIfNeeded();
            } catch (RemoteException unhandled) {
            }
        }
    }

    private void handleHideBootMessage() {
        synchronized (mLock) {
            if (!mKeyguardDrawnOnce) {
                mBootMessageNeedsHiding = true;
                return; // keyguard hasn't drawn the first time yet, not done booting
            }
        }

        if (mBootMsgDialog != null) {
            if (DEBUG_WAKEUP) Slog.d(TAG, "handleHideBootMessage: dismissing");
            mBootMsgDialog.dismiss();
            mBootMsgDialog = null;
        }
    }

    @Override
    public boolean isScreenOn() {
        return mDefaultDisplayPolicy.isScreenOnEarly();
    }

    @Override
    public boolean okToAnimate() {
        return mDefaultDisplayPolicy.isAwake() && !mGoingToSleep;
    }

    /** {@inheritDoc} */
    @Override
    public void enableKeyguard(boolean enabled) {
        if (mKeyguardDelegate != null) {
            mKeyguardDelegate.setKeyguardEnabled(enabled);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void exitKeyguardSecurely(OnKeyguardExitResult callback) {
        if (mKeyguardDelegate != null) {
            mKeyguardDelegate.verifyUnlock(callback);
        }
    }

    @Override
    public boolean isKeyguardShowingAndNotOccluded() {
        if (mKeyguardDelegate == null) return false;
        return mKeyguardDelegate.isShowing() && !mKeyguardOccluded;
    }

    @Override
    public boolean isKeyguardTrustedLw() {
        if (mKeyguardDelegate == null) return false;
        return mKeyguardDelegate.isTrusted();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isKeyguardLocked() {
        return keyguardOn();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isKeyguardSecure(int userId) {
        if (mKeyguardDelegate == null) return false;
        return mKeyguardDelegate.isSecure(userId);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isKeyguardOccluded() {
        if (mKeyguardDelegate == null) return false;
        return mKeyguardOccluded;
    }

    /** {@inheritDoc} */
    @Override
    public boolean inKeyguardRestrictedKeyInputMode() {
        if (mKeyguardDelegate == null) return false;
        return mKeyguardDelegate.isInputRestricted();
    }

    @Override
    public void dismissKeyguardLw(IKeyguardDismissCallback callback, CharSequence message) {
        if (mKeyguardDelegate != null && mKeyguardDelegate.isShowing()) {
            if (DEBUG_KEYGUARD) Slog.d(TAG, "PWM.dismissKeyguardLw");

            // ask the keyguard to prompt the user to authenticate if necessary
            mKeyguardDelegate.dismiss(callback, message);
        } else if (callback != null) {
            try {
                callback.onDismissError();
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to call callback", e);
            }
        }
    }

    @Override
    public boolean isKeyguardDrawnLw() {
        synchronized (mLock) {
            return mKeyguardDrawnOnce;
        }
    }

    @Override
    public void startKeyguardExitAnimation(long startTime, long fadeoutDuration) {
        if (mKeyguardDelegate != null) {
            if (DEBUG_KEYGUARD) Slog.d(TAG, "PWM.startKeyguardExitAnimation");
            mKeyguardDelegate.startKeyguardExitAnimation(startTime, fadeoutDuration);
        }
    }

    void sendCloseSystemWindows() {
        PhoneWindow.sendCloseSystemWindows(mContext, null);
    }

    void sendCloseSystemWindows(String reason) {
        PhoneWindow.sendCloseSystemWindows(mContext, reason);
    }

    @Override
    public void setSafeMode(boolean safeMode) {
        mSafeMode = safeMode;
        if (safeMode) {
            performHapticFeedback(HapticFeedbackConstants.SAFE_MODE_ENABLED, true,
                    "Safe Mode Enabled");
        }
    }

    static long[] getLongIntArray(Resources r, int resid) {
        return ArrayUtils.convertToLongArray(r.getIntArray(resid));
    }

    private void bindKeyguard() {
        synchronized (mLock) {
            if (mKeyguardBound) {
                return;
            }
            mKeyguardBound = true;
        }
        mKeyguardDelegate.bindService(mContext);
    }

    @Override
    public void onSystemUiStarted() {
        bindKeyguard();
    }

    /** {@inheritDoc} */
    @Override
    public void systemReady() {
        // In normal flow, systemReady is called before other system services are ready.
        // So it is better not to bind keyguard here.
        mKeyguardDelegate.onSystemReady();

        mVrManagerInternal = LocalServices.getService(VrManagerInternal.class);
        if (mVrManagerInternal != null) {
            mVrManagerInternal.addPersistentVrModeStateListener(mPersistentVrModeListener);
        }

        readCameraLensCoverState();
        updateUiMode();
        mDefaultDisplayRotation.updateOrientationListener();
        synchronized (mLock) {
            mSystemReady = true;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateSettings();
                }
            });
            // If this happens, for whatever reason, systemReady came later than systemBooted.
            // And keyguard should be already bound from systemBooted
            if (mSystemBooted) {
                mKeyguardDelegate.onBootCompleted();
            }
        }

        mAutofillManagerInternal = LocalServices.getService(AutofillManagerInternal.class);
    }

    /** {@inheritDoc} */
    @Override
    public void systemBooted() {
        bindKeyguard();
        synchronized (mLock) {
            mSystemBooted = true;
            if (mSystemReady) {
                mKeyguardDelegate.onBootCompleted();
            }
        }
        startedWakingUp(ON_BECAUSE_OF_UNKNOWN);
        finishedWakingUp(ON_BECAUSE_OF_UNKNOWN);
        screenTurningOn(null);
        screenTurnedOn();
    }

    @Override
    public boolean canDismissBootAnimation() {
        return mDefaultDisplayPolicy.isKeyguardDrawComplete();
    }

    ProgressDialog mBootMsgDialog = null;

    /** {@inheritDoc} */
    @Override
    public void showBootMessage(final CharSequence msg, final boolean always) {
        mHandler.post(new Runnable() {
            @Override public void run() {
                if (mBootMsgDialog == null) {
                    int theme;
                    if (mContext.getPackageManager().hasSystemFeature(FEATURE_LEANBACK)) {
                        theme = com.android.internal.R.style.Theme_Leanback_Dialog_Alert;
                    } else {
                        theme = 0;
                    }

                    mBootMsgDialog = new ProgressDialog(mContext, theme) {
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
                    if (mContext.getPackageManager().isDeviceUpgrading()) {
                        mBootMsgDialog.setTitle(R.string.android_upgrading_title);
                    } else {
                        mBootMsgDialog.setTitle(R.string.android_start_title);
                    }
                    mBootMsgDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                    mBootMsgDialog.setIndeterminate(true);
                    mBootMsgDialog.getWindow().setType(
                            WindowManager.LayoutParams.TYPE_BOOT_PROGRESS);
                    mBootMsgDialog.getWindow().addFlags(
                            WindowManager.LayoutParams.FLAG_DIM_BEHIND
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
                    mBootMsgDialog.getWindow().setDimAmount(1);
                    WindowManager.LayoutParams lp = mBootMsgDialog.getWindow().getAttributes();
                    lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
                    mBootMsgDialog.getWindow().setAttributes(lp);
                    mBootMsgDialog.setCancelable(false);
                    mBootMsgDialog.show();
                }
                mBootMsgDialog.setMessage(msg);
            }
        });
    }

    /** {@inheritDoc} */
    @Override
    public void hideBootMessages() {
        mHandler.sendEmptyMessage(MSG_HIDE_BOOT_MESSAGE);
    }

    @Override
    public void requestUserActivityNotification() {
        if (!mNotifyUserActivity && !mHandler.hasMessages(MSG_NOTIFY_USER_ACTIVITY)) {
            mNotifyUserActivity = true;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void userActivity() {
        // ***************************************
        // NOTE NOTE NOTE NOTE NOTE NOTE NOTE NOTE
        // ***************************************
        // THIS IS CALLED FROM DEEP IN THE POWER MANAGER
        // WITH ITS LOCKS HELD.
        //
        // This code must be VERY careful about the locks
        // it acquires.
        // In fact, the current code acquires way too many,
        // and probably has lurking deadlocks.

        synchronized (mScreenLockTimeout) {
            if (mLockScreenTimerActive) {
                // reset the timer
                mHandler.removeCallbacks(mScreenLockTimeout);
                mHandler.postDelayed(mScreenLockTimeout, mLockScreenTimeout);
            }
        }

        if (mDefaultDisplayPolicy.isAwake() && mNotifyUserActivity) {
            mHandler.sendEmptyMessageDelayed(MSG_NOTIFY_USER_ACTIVITY,
                    USER_ACTIVITY_NOTIFICATION_DELAY);
            mNotifyUserActivity = false;
        }
    }

    class ScreenLockTimeout implements Runnable {
        Bundle options;

        @Override
        public void run() {
            synchronized (this) {
                if (localLOGV) Log.v(TAG, "mScreenLockTimeout activating keyguard");
                if (mKeyguardDelegate != null) {
                    mKeyguardDelegate.doKeyguardTimeout(options);
                }
                mLockScreenTimerActive = false;
                mLockNowPending = false;
                options = null;
            }
        }

        public void setLockOptions(Bundle options) {
            this.options = options;
        }
    }

    final ScreenLockTimeout mScreenLockTimeout = new ScreenLockTimeout();

    @Override
    public void lockNow(Bundle options) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
        mHandler.removeCallbacks(mScreenLockTimeout);
        if (options != null) {
            // In case multiple calls are made to lockNow, we don't wipe out the options
            // until the runnable actually executes.
            mScreenLockTimeout.setLockOptions(options);
        }
        mHandler.post(mScreenLockTimeout);
        synchronized (mScreenLockTimeout) {
            mLockNowPending = true;
        }
    }

    // TODO (b/113840485): Move this logic to DisplayPolicy when lockscreen supports multi-display.
    @Override
    public void setAllowLockscreenWhenOn(int displayId, boolean allow) {
        if (allow) {
            mAllowLockscreenWhenOnDisplays.add(displayId);
        } else {
            mAllowLockscreenWhenOnDisplays.remove(displayId);
        }
        updateLockScreenTimeout();
    }

    private void updateLockScreenTimeout() {
        synchronized (mScreenLockTimeout) {
            if (mLockNowPending) {
                Log.w(TAG, "lockNow pending, ignore updating lockscreen timeout");
                return;
            }
            final boolean enable = !mAllowLockscreenWhenOnDisplays.isEmpty()
                    && mDefaultDisplayPolicy.isAwake()
                    && mKeyguardDelegate != null && mKeyguardDelegate.isSecure(mCurrentUserId);
            if (mLockScreenTimerActive != enable) {
                if (enable) {
                    if (localLOGV) Log.v(TAG, "setting lockscreen timer");
                    mHandler.removeCallbacks(mScreenLockTimeout); // remove any pending requests
                    mHandler.postDelayed(mScreenLockTimeout, mLockScreenTimeout);
                } else {
                    if (localLOGV) Log.v(TAG, "clearing lockscreen timer");
                    mHandler.removeCallbacks(mScreenLockTimeout);
                }
                mLockScreenTimerActive = enable;
            }
        }
    }

    private void schedulePossibleVeryLongPressReboot() {
        mHandler.removeCallbacks(mPossibleVeryLongPressReboot);
        mHandler.postDelayed(mPossibleVeryLongPressReboot, mVeryLongPressTimeout);
    }

    private void cancelPossibleVeryLongPressReboot() {
        mHandler.removeCallbacks(mPossibleVeryLongPressReboot);
    }

    // TODO (multidisplay): Support multiple displays in WindowManagerPolicy.
    private void updateScreenOffSleepToken(boolean acquire) {
        if (acquire) {
            if (mScreenOffSleepToken == null) {
                mScreenOffSleepToken = mActivityTaskManagerInternal.acquireSleepToken(
                        "ScreenOff", DEFAULT_DISPLAY);
            }
        } else {
            if (mScreenOffSleepToken != null) {
                mScreenOffSleepToken.release();
                mScreenOffSleepToken = null;
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void enableScreenAfterBoot() {
        readLidState();
        applyLidSwitchState();
        updateRotation(true);
    }

    private void applyLidSwitchState() {
        final int lidState = mDefaultDisplayPolicy.getLidState();
        if (mLidControlsDisplayFold && mDisplayFoldController != null) {
            mDisplayFoldController.requestDeviceFolded(lidState == LID_CLOSED);
        } else if (lidState == LID_CLOSED) {
            int lidBehavior = getLidBehavior();
            switch (lidBehavior) {
                case LID_BEHAVIOR_LOCK:
                    mWindowManagerFuncs.lockDeviceNow();
                    break;
                case LID_BEHAVIOR_SLEEP:
                    goToSleep(SystemClock.uptimeMillis(),
                            PowerManager.GO_TO_SLEEP_REASON_LID_SWITCH,
                            PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE);
                    break;
                case LID_BEHAVIOR_NONE:
                    // fall through
                default:
                    break;
            }
        }

        synchronized (mLock) {
            updateWakeGestureListenerLp();
        }
    }

    void updateUiMode() {
        if (mUiModeManager == null) {
            mUiModeManager = IUiModeManager.Stub.asInterface(
                    ServiceManager.getService(Context.UI_MODE_SERVICE));
        }
        try {
            mUiMode = mUiModeManager.getCurrentModeType();
        } catch (RemoteException e) {
        }
    }

    @Override
    public int getUiMode() {
        return mUiMode;
    }

    void updateRotation(boolean alwaysSendConfiguration) {
        try {
            // Set orientation on WindowManager.
            mWindowManager.updateRotation(alwaysSendConfiguration, false /* forceRelayout */);
        } catch (RemoteException e) {
            // Ignore
        }
    }

    /**
     * Return an Intent to launch the currently active dock app as home.  Returns
     * null if the standard home should be launched, which is the case if any of the following is
     * true:
     * <ul>
     *  <li>The device is not in either car mode or desk mode
     *  <li>The device is in car mode but mEnableCarDockHomeCapture is false
     *  <li>The device is in desk mode but ENABLE_DESK_DOCK_HOME_CAPTURE is false
     *  <li>The device is in car mode but there's no CAR_DOCK app with METADATA_DOCK_HOME
     *  <li>The device is in desk mode but there's no DESK_DOCK app with METADATA_DOCK_HOME
     * </ul>
     * @return A dock intent.
     */
    Intent createHomeDockIntent() {
        Intent intent = null;

        // What home does is based on the mode, not the dock state.  That
        // is, when in car mode you should be taken to car home regardless
        // of whether we are actually in a car dock.
        if (mUiMode == Configuration.UI_MODE_TYPE_CAR) {
            if (mEnableCarDockHomeCapture) {
                intent = mCarDockIntent;
            }
        } else if (mUiMode == Configuration.UI_MODE_TYPE_DESK) {
            if (ENABLE_DESK_DOCK_HOME_CAPTURE) {
                intent = mDeskDockIntent;
            }
        } else if (mUiMode == Configuration.UI_MODE_TYPE_WATCH) {
            final int dockMode = mDefaultDisplayPolicy.getDockMode();
            if (dockMode == Intent.EXTRA_DOCK_STATE_DESK
                    || dockMode == Intent.EXTRA_DOCK_STATE_HE_DESK
                    || dockMode == Intent.EXTRA_DOCK_STATE_LE_DESK) {
                // Always launch dock home from home when watch is docked, if it exists.
                intent = mDeskDockIntent;
            }
        } else if (mUiMode == Configuration.UI_MODE_TYPE_VR_HEADSET) {
            if (ENABLE_VR_HEADSET_HOME_CAPTURE) {
                intent = mVrHeadsetHomeIntent;
            }
        }

        if (intent == null) {
            return null;
        }

        ActivityInfo ai = null;
        ResolveInfo info = mContext.getPackageManager().resolveActivityAsUser(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.GET_META_DATA,
                mCurrentUserId);
        if (info != null) {
            ai = info.activityInfo;
        }
        if (ai != null
                && ai.metaData != null
                && ai.metaData.getBoolean(Intent.METADATA_DOCK_HOME)) {
            intent = new Intent(intent);
            intent.setClassName(ai.packageName, ai.name);
            return intent;
        }

        return null;
    }

    void startDockOrHome(int displayId, boolean fromHomeKey, boolean awakenFromDreams) {
        try {
            ActivityManager.getService().stopAppSwitches();
        } catch (RemoteException e) {}
        sendCloseSystemWindows(SYSTEM_DIALOG_REASON_HOME_KEY);

        if (awakenFromDreams) {
            awakenDreams();
        }

        if (!isUserSetupComplete()) {
            Slog.i(TAG, "Not going home because user setup is in progress.");
            return;
        }

        // Start dock.
        Intent dock = createHomeDockIntent();
        if (dock != null) {
            try {
                if (fromHomeKey) {
                    dock.putExtra(WindowManagerPolicy.EXTRA_FROM_HOME_KEY, fromHomeKey);
                }
                startActivityAsUser(dock, UserHandle.CURRENT);
                return;
            } catch (ActivityNotFoundException e) {
            }
        }

        // Start home.
        mActivityTaskManagerInternal.startHomeOnDisplay(mCurrentUserId, "startDockOrHome",
                displayId, true /* allowInstrumenting */, fromHomeKey);
    }

    /**
     * goes to the home screen
     * @return whether it did anything
     */
    boolean goHome() {
        if (!isUserSetupComplete()) {
            Slog.i(TAG, "Not going home because user setup is in progress.");
            return false;
        }
        if (false) {
            // This code always brings home to the front.
            startDockOrHome(DEFAULT_DISPLAY, false /*fromHomeKey*/, true /* awakenFromDreams */);
        } else {
            // This code brings home to the front or, if it is already
            // at the front, puts the device to sleep.
            try {
                if (SystemProperties.getInt("persist.sys.uts-test-mode", 0) == 1) {
                    /// Roll back EndcallBehavior as the cupcake design to pass P1 lab entry.
                    Log.d(TAG, "UTS-TEST-MODE");
                } else {
                    ActivityManager.getService().stopAppSwitches();
                    sendCloseSystemWindows();
                    final Intent dock = createHomeDockIntent();
                    if (dock != null) {
                        int result = ActivityTaskManager.getService()
                                .startActivityAsUser(null, null, dock,
                                        dock.resolveTypeIfNeeded(mContext.getContentResolver()),
                                        null, null, 0,
                                        ActivityManager.START_FLAG_ONLY_IF_NEEDED,
                                        null, null, UserHandle.USER_CURRENT);
                        if (result == ActivityManager.START_RETURN_INTENT_TO_CALLER) {
                            return false;
                        }
                    }
                }
                int result = ActivityTaskManager.getService()
                        .startActivityAsUser(null, null, mHomeIntent,
                                mHomeIntent.resolveTypeIfNeeded(mContext.getContentResolver()),
                                null, null, 0,
                                ActivityManager.START_FLAG_ONLY_IF_NEEDED,
                                null, null, UserHandle.USER_CURRENT);
                if (result == ActivityManager.START_RETURN_INTENT_TO_CALLER) {
                    return false;
                }
            } catch (RemoteException ex) {
                // bummer, the activity manager, which is in this process, is dead
            }
        }
        return true;
    }

    private boolean isTheaterModeEnabled() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.THEATER_MODE_ON, 0) == 1;
    }

    private boolean performHapticFeedback(int effectId, boolean always, String reason) {
        return performHapticFeedback(Process.myUid(), mContext.getOpPackageName(),
            effectId, always, reason);
    }

    @Override
    public boolean performHapticFeedback(int uid, String packageName, int effectId,
            boolean always, String reason) {
        if (!mVibrator.hasVibrator()) {
            return false;
        }
        final boolean hapticsDisabled = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 0, UserHandle.USER_CURRENT) == 0;
        if (hapticsDisabled && !always) {
            return false;
        }

        VibrationEffect effect = getVibrationEffect(effectId);
        if (effect == null) {
            return false;
        }

        mVibrator.vibrate(uid, packageName, effect, reason, VIBRATION_ATTRIBUTES);
        return true;
    }

    private VibrationEffect getVibrationEffect(int effectId) {
        long[] pattern;
        switch (effectId) {
            case HapticFeedbackConstants.CONTEXT_CLICK:
                return VibrationEffect.get(VibrationEffect.EFFECT_TICK);
            case HapticFeedbackConstants.TEXT_HANDLE_MOVE:
                if (!mHapticTextHandleEnabled) {
                    return null;
                }
                // fallthrough
            case HapticFeedbackConstants.CLOCK_TICK:
                return VibrationEffect.get(VibrationEffect.EFFECT_TEXTURE_TICK);
            case HapticFeedbackConstants.KEYBOARD_RELEASE:
            case HapticFeedbackConstants.VIRTUAL_KEY_RELEASE:
            case HapticFeedbackConstants.ENTRY_BUMP:
            case HapticFeedbackConstants.DRAG_CROSSING:
            case HapticFeedbackConstants.GESTURE_END:
                return VibrationEffect.get(VibrationEffect.EFFECT_TICK, false);
            case HapticFeedbackConstants.KEYBOARD_TAP: // == KEYBOARD_PRESS
            case HapticFeedbackConstants.VIRTUAL_KEY:
            case HapticFeedbackConstants.EDGE_RELEASE:
            case HapticFeedbackConstants.CONFIRM:
            case HapticFeedbackConstants.GESTURE_START:
                return VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
            case HapticFeedbackConstants.LONG_PRESS:
            case HapticFeedbackConstants.EDGE_SQUEEZE:
                return VibrationEffect.get(VibrationEffect.EFFECT_HEAVY_CLICK);
            case HapticFeedbackConstants.REJECT:
                return VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK);

            case HapticFeedbackConstants.CALENDAR_DATE:
                pattern = mCalendarDateVibePattern;
                break;
            case HapticFeedbackConstants.SAFE_MODE_ENABLED:
                pattern = mSafeModeEnabledVibePattern;
                break;

            default:
                return null;
        }
        if (pattern.length == 0) {
            // No vibration
            return null;
        } else if (pattern.length == 1) {
            // One-shot vibration
            return VibrationEffect.createOneShot(pattern[0], VibrationEffect.DEFAULT_AMPLITUDE);
        } else {
            // Pattern vibration
            return VibrationEffect.createWaveform(pattern, -1);
        }
    }

    @Override
    public void keepScreenOnStartedLw() {
    }

    @Override
    public void keepScreenOnStoppedLw() {
        if (isKeyguardShowingAndNotOccluded()) {
            mPowerManager.userActivity(SystemClock.uptimeMillis(), false);
        }
    }

    // Use this instead of checking config_showNavigationBar so that it can be consistently
    // overridden by qemu.hw.mainkeys in the emulator.
    @Override
    public boolean hasNavigationBar() {
        return mDefaultDisplayPolicy.hasNavigationBar();
    }

    @Override
    public void setDismissImeOnBackKeyPressed(boolean newValue) {
        mDismissImeOnBackKeyPressed = newValue;
    }

    @Override
    public void setCurrentUserLw(int newUserId) {
        mCurrentUserId = newUserId;
        if (mKeyguardDelegate != null) {
            mKeyguardDelegate.setCurrentUser(newUserId);
        }
        if (mAccessibilityShortcutController != null) {
            mAccessibilityShortcutController.setCurrentUser(newUserId);
        }
        StatusBarManagerInternal statusBar = getStatusBarManagerInternal();
        if (statusBar != null) {
            statusBar.setCurrentUser(newUserId);
        }
    }

    @Override
    public void setSwitchingUser(boolean switching) {
        mKeyguardDelegate.setSwitchingUser(switching);
    }

    @Override
    public boolean isTopLevelWindow(int windowType) {
        if (windowType >= WindowManager.LayoutParams.FIRST_SUB_WINDOW
                && windowType <= WindowManager.LayoutParams.LAST_SUB_WINDOW) {
            return (windowType == WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG);
        }
        return true;
    }

    @Override
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(ROTATION_MODE, mDefaultDisplayRotation.getUserRotationMode());
        proto.write(ROTATION, mDefaultDisplayRotation.getUserRotation());
        proto.write(ORIENTATION, mDefaultDisplayRotation.getCurrentAppOrientation());
        proto.write(SCREEN_ON_FULLY, mDefaultDisplayPolicy.isScreenOnFully());
        proto.write(KEYGUARD_DRAW_COMPLETE, mDefaultDisplayPolicy.isKeyguardDrawComplete());
        proto.write(WINDOW_MANAGER_DRAW_COMPLETE,
                mDefaultDisplayPolicy.isWindowManagerDrawComplete());
        proto.write(KEYGUARD_OCCLUDED, mKeyguardOccluded);
        proto.write(KEYGUARD_OCCLUDED_CHANGED, mKeyguardOccludedChanged);
        proto.write(KEYGUARD_OCCLUDED_PENDING, mPendingKeyguardOccluded);
        if (mKeyguardDelegate != null) {
            mKeyguardDelegate.writeToProto(proto, KEYGUARD_DELEGATE);
        }
        proto.end(token);
    }

    @Override
    public void dump(String prefix, PrintWriter pw, String[] args) {
        pw.print(prefix); pw.print("mSafeMode="); pw.print(mSafeMode);
                pw.print(" mSystemReady="); pw.print(mSystemReady);
                pw.print(" mSystemBooted="); pw.println(mSystemBooted);
        pw.print(prefix); pw.print("mCameraLensCoverState=");
                pw.println(WindowManagerFuncs.cameraLensStateToString(mCameraLensCoverState));
        pw.print(prefix); pw.print("mWakeGestureEnabledSetting=");
                pw.println(mWakeGestureEnabledSetting);

        pw.print(prefix);
                pw.print("mUiMode=");
                pw.print(Configuration.uiModeToString(mUiMode));
                pw.print("mEnableCarDockHomeCapture="); pw.println(mEnableCarDockHomeCapture);
        pw.print(prefix); pw.print("mLidKeyboardAccessibility=");
                pw.print(mLidKeyboardAccessibility);
                pw.print(" mLidNavigationAccessibility="); pw.print(mLidNavigationAccessibility);
                pw.print(" getLidBehavior="); pw.println(lidBehaviorToString(getLidBehavior()));
        pw.print(prefix);
                pw.print("mLongPressOnBackBehavior=");
                pw.println(longPressOnBackBehaviorToString(mLongPressOnBackBehavior));
        pw.print(prefix);
                pw.print("mLongPressOnHomeBehavior=");
                pw.println(longPressOnHomeBehaviorToString(mLongPressOnHomeBehavior));
        pw.print(prefix);
                pw.print("mDoubleTapOnHomeBehavior=");
                pw.println(doubleTapOnHomeBehaviorToString(mDoubleTapOnHomeBehavior));
        pw.print(prefix);
                pw.print("mShortPressOnPowerBehavior=");
                pw.println(shortPressOnPowerBehaviorToString(mShortPressOnPowerBehavior));
        pw.print(prefix);
                pw.print("mLongPressOnPowerBehavior=");
                pw.println(longPressOnPowerBehaviorToString(mLongPressOnPowerBehavior));
        pw.print(prefix);
                pw.print("mVeryLongPressOnPowerBehavior=");
                pw.println(veryLongPressOnPowerBehaviorToString(mVeryLongPressOnPowerBehavior));
        pw.print(prefix);
                pw.print("mDoublePressOnPowerBehavior=");
                pw.println(multiPressOnPowerBehaviorToString(mDoublePressOnPowerBehavior));
        pw.print(prefix);
                pw.print("mTriplePressOnPowerBehavior=");
                pw.println(multiPressOnPowerBehaviorToString(mTriplePressOnPowerBehavior));
        pw.print(prefix);
                pw.print("mShortPressOnSleepBehavior=");
                pw.println(shortPressOnSleepBehaviorToString(mShortPressOnSleepBehavior));
        pw.print(prefix);
                pw.print("mShortPressOnWindowBehavior=");
                pw.println(shortPressOnWindowBehaviorToString(mShortPressOnWindowBehavior));
        pw.print(prefix);
                pw.print("mAllowStartActivityForLongPressOnPowerDuringSetup=");
                pw.println(mAllowStartActivityForLongPressOnPowerDuringSetup);
        pw.print(prefix);
                pw.print("mHasSoftInput="); pw.print(mHasSoftInput);
                pw.print(" mHapticTextHandleEnabled="); pw.println(mHapticTextHandleEnabled);
        pw.print(prefix);
                pw.print("mDismissImeOnBackKeyPressed="); pw.print(mDismissImeOnBackKeyPressed);
                pw.print(" mIncallPowerBehavior=");
                pw.println(incallPowerBehaviorToString(mIncallPowerBehavior));
        pw.print(prefix);
                pw.print("mIncallBackBehavior=");
                pw.print(incallBackBehaviorToString(mIncallBackBehavior));
                pw.print(" mEndcallBehavior=");
                pw.println(endcallBehaviorToString(mEndcallBehavior));
        pw.print(prefix);
        // TODO(b/117479243): handle it in InputPolicy
        pw.print("mDisplayHomeButtonHandlers=");
        for (int i = 0; i < mDisplayHomeButtonHandlers.size(); i++) {
            final int key = mDisplayHomeButtonHandlers.keyAt(i);
            pw.println(mDisplayHomeButtonHandlers.get(key));
        }
        pw.print(prefix); pw.print("mKeyguardOccluded="); pw.print(mKeyguardOccluded);
                pw.print(" mKeyguardOccludedChanged="); pw.print(mKeyguardOccludedChanged);
                pw.print(" mPendingKeyguardOccluded="); pw.println(mPendingKeyguardOccluded);
        pw.print(prefix); pw.print("mAllowLockscreenWhenOnDisplays=");
                pw.print(!mAllowLockscreenWhenOnDisplays.isEmpty());
                pw.print(" mLockScreenTimeout="); pw.print(mLockScreenTimeout);
                pw.print(" mLockScreenTimerActive="); pw.println(mLockScreenTimerActive);
        if (mHasFeatureLeanback) {
            pw.print(prefix);
            pw.print("mAccessibilityTvKey1Pressed="); pw.println(mAccessibilityTvKey1Pressed);
            pw.print(prefix);
            pw.print("mAccessibilityTvKey2Pressed="); pw.println(mAccessibilityTvKey2Pressed);
            pw.print(prefix);
            pw.print("mAccessibilityTvScheduled="); pw.println(mAccessibilityTvScheduled);
        }

        mGlobalKeyManager.dump(prefix, pw);

        if (mWakeGestureListener != null) {
            mWakeGestureListener.dump(pw, prefix);
        }
        if (mBurnInProtectionHelper != null) {
            mBurnInProtectionHelper.dump(prefix, pw);
        }
        if (mKeyguardDelegate != null) {
            mKeyguardDelegate.dump(prefix, pw);
        }

        pw.print(prefix); pw.println("Looper state:");
        mHandler.getLooper().dump(new PrintWriterPrinter(pw), prefix + "  ");
    }

    private static String endcallBehaviorToString(int behavior) {
        StringBuilder sb = new StringBuilder();
        if ((behavior & Settings.System.END_BUTTON_BEHAVIOR_HOME) != 0 ) {
            sb.append("home|");
        }
        if ((behavior & Settings.System.END_BUTTON_BEHAVIOR_SLEEP) != 0) {
            sb.append("sleep|");
        }

        final int N = sb.length();
        if (N == 0) {
            return "<nothing>";
        } else {
            // Chop off the trailing '|'
            return sb.substring(0, N - 1);
        }
    }

    private static String incallPowerBehaviorToString(int behavior) {
        if ((behavior & Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR_HANGUP) != 0) {
            return "hangup";
        } else {
            return "sleep";
        }
    }

    private static String incallBackBehaviorToString(int behavior) {
        if ((behavior & Settings.Secure.INCALL_BACK_BUTTON_BEHAVIOR_HANGUP) != 0) {
            return "hangup";
        } else {
            return "<nothing>";
        }
    }

    private static String longPressOnBackBehaviorToString(int behavior) {
        switch (behavior) {
            case LONG_PRESS_BACK_NOTHING:
                return "LONG_PRESS_BACK_NOTHING";
            case LONG_PRESS_BACK_GO_TO_VOICE_ASSIST:
                return "LONG_PRESS_BACK_GO_TO_VOICE_ASSIST";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String longPressOnHomeBehaviorToString(int behavior) {
        switch (behavior) {
            case LONG_PRESS_HOME_NOTHING:
                return "LONG_PRESS_HOME_NOTHING";
            case LONG_PRESS_HOME_ALL_APPS:
                return "LONG_PRESS_HOME_ALL_APPS";
            case LONG_PRESS_HOME_ASSIST:
                return "LONG_PRESS_HOME_ASSIST";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String doubleTapOnHomeBehaviorToString(int behavior) {
        switch (behavior) {
            case DOUBLE_TAP_HOME_NOTHING:
                return "DOUBLE_TAP_HOME_NOTHING";
            case DOUBLE_TAP_HOME_RECENT_SYSTEM_UI:
                return "DOUBLE_TAP_HOME_RECENT_SYSTEM_UI";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String shortPressOnPowerBehaviorToString(int behavior) {
        switch (behavior) {
            case SHORT_PRESS_POWER_NOTHING:
                return "SHORT_PRESS_POWER_NOTHING";
            case SHORT_PRESS_POWER_GO_TO_SLEEP:
                return "SHORT_PRESS_POWER_GO_TO_SLEEP";
            case SHORT_PRESS_POWER_REALLY_GO_TO_SLEEP:
                return "SHORT_PRESS_POWER_REALLY_GO_TO_SLEEP";
            case SHORT_PRESS_POWER_REALLY_GO_TO_SLEEP_AND_GO_HOME:
                return "SHORT_PRESS_POWER_REALLY_GO_TO_SLEEP_AND_GO_HOME";
            case SHORT_PRESS_POWER_GO_HOME:
                return "SHORT_PRESS_POWER_GO_HOME";
            case SHORT_PRESS_POWER_CLOSE_IME_OR_GO_HOME:
                return "SHORT_PRESS_POWER_CLOSE_IME_OR_GO_HOME";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String longPressOnPowerBehaviorToString(int behavior) {
        switch (behavior) {
            case LONG_PRESS_POWER_NOTHING:
                return "LONG_PRESS_POWER_NOTHING";
            case LONG_PRESS_POWER_GLOBAL_ACTIONS:
                return "LONG_PRESS_POWER_GLOBAL_ACTIONS";
            case LONG_PRESS_POWER_SHUT_OFF:
                return "LONG_PRESS_POWER_SHUT_OFF";
            case LONG_PRESS_POWER_SHUT_OFF_NO_CONFIRM:
                return "LONG_PRESS_POWER_SHUT_OFF_NO_CONFIRM";
            case LONG_PRESS_POWER_GO_TO_VOICE_ASSIST:
                return "LONG_PRESS_POWER_GO_TO_VOICE_ASSIST";
            case LONG_PRESS_POWER_ASSISTANT:
                return "LONG_PRESS_POWER_ASSISTANT";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String veryLongPressOnPowerBehaviorToString(int behavior) {
        switch (behavior) {
            case VERY_LONG_PRESS_POWER_NOTHING:
                return "VERY_LONG_PRESS_POWER_NOTHING";
            case VERY_LONG_PRESS_POWER_GLOBAL_ACTIONS:
                return "VERY_LONG_PRESS_POWER_GLOBAL_ACTIONS";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String multiPressOnPowerBehaviorToString(int behavior) {
        switch (behavior) {
            case MULTI_PRESS_POWER_NOTHING:
                return "MULTI_PRESS_POWER_NOTHING";
            case MULTI_PRESS_POWER_THEATER_MODE:
                return "MULTI_PRESS_POWER_THEATER_MODE";
            case MULTI_PRESS_POWER_BRIGHTNESS_BOOST:
                return "MULTI_PRESS_POWER_BRIGHTNESS_BOOST";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String shortPressOnSleepBehaviorToString(int behavior) {
        switch (behavior) {
            case SHORT_PRESS_SLEEP_GO_TO_SLEEP:
                return "SHORT_PRESS_SLEEP_GO_TO_SLEEP";
            case SHORT_PRESS_SLEEP_GO_TO_SLEEP_AND_GO_HOME:
                return "SHORT_PRESS_SLEEP_GO_TO_SLEEP_AND_GO_HOME";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String shortPressOnWindowBehaviorToString(int behavior) {
        switch (behavior) {
            case SHORT_PRESS_WINDOW_NOTHING:
                return "SHORT_PRESS_WINDOW_NOTHING";
            case SHORT_PRESS_WINDOW_PICTURE_IN_PICTURE:
                return "SHORT_PRESS_WINDOW_PICTURE_IN_PICTURE";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String lidBehaviorToString(int behavior) {
        switch (behavior) {
            case LID_BEHAVIOR_LOCK:
                return "LID_BEHAVIOR_LOCK";
            case LID_BEHAVIOR_SLEEP:
                return "LID_BEHAVIOR_SLEEP";
            case LID_BEHAVIOR_NONE:
                return "LID_BEHAVIOR_NONE";
            default:
                return Integer.toString(behavior);
        }
    }

    @Override
    public boolean setAodShowing(boolean aodShowing) {
        if (mAodShowing != aodShowing) {
            mAodShowing = aodShowing;
            return true;
        }
        return false;
    }

    private class HdmiVideoExtconUEventObserver extends ExtconStateObserver<Boolean> {
        private static final String HDMI_EXIST = "HDMI=1";
        private static final String NAME = "hdmi";
        private final ExtconInfo mHdmi = new ExtconInfo(NAME);

        private boolean init() {
            boolean plugged = false;
            try {
                plugged = parseStateFromFile(mHdmi);
            } catch (FileNotFoundException e) {
                Slog.w(TAG, mHdmi.getStatePath()
                        + " not found while attempting to determine initial state", e);
            } catch (IOException e) {
                Slog.e(
                        TAG,
                        "Error reading " + mHdmi.getStatePath()
                                + " while attempting to determine initial state",
                        e);
            }
            startObserving(mHdmi);
            return plugged;
        }

        @Override
        public void updateState(ExtconInfo extconInfo, String eventName, Boolean state) {
            mDefaultDisplayPolicy.setHdmiPlugged(state);
        }

        @Override
        public Boolean parseState(ExtconInfo extconIfno, String state) {
            // extcon event state changes from kernel4.9
            // new state will be like STATE=HDMI=1
            return state.contains(HDMI_EXIST);
        }
    }

}
