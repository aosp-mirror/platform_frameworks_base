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

import static android.Manifest.permission.CREATE_VIRTUAL_DEVICE;
import static android.Manifest.permission.INTERNAL_SYSTEM_WINDOW;
import static android.Manifest.permission.OVERRIDE_SYSTEM_KEY_BEHAVIOR_IN_FOCUSED_WINDOW;
import static android.Manifest.permission.SYSTEM_ALERT_WINDOW;
import static android.Manifest.permission.SYSTEM_APPLICATION_OVERLAY;
import static android.app.AppOpsManager.OP_CREATE_ACCESSIBILITY_OVERLAY;
import static android.app.AppOpsManager.OP_SYSTEM_ALERT_WINDOW;
import static android.app.AppOpsManager.OP_TOAST_WINDOW;
import static android.content.PermissionChecker.PID_UNKNOWN;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
import static android.content.pm.PackageManager.FEATURE_AUTOMOTIVE;
import static android.content.pm.PackageManager.FEATURE_HDMI_CEC;
import static android.content.pm.PackageManager.FEATURE_LEANBACK;
import static android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE;
import static android.content.pm.PackageManager.FEATURE_WATCH;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION_CODES.M;
import static android.os.Build.VERSION_CODES.O;
import static android.os.IInputConstants.INVALID_INPUT_DEVICE_ID;
import static android.os.UserManager.isVisibleBackgroundUsersEnabled;
import static android.provider.Settings.Secure.VOLUME_HUSH_OFF;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.Display.STATE_OFF;
import static android.view.KeyEvent.KEYCODE_BACK;
import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_HOME;
import static android.view.KeyEvent.KEYCODE_POWER;
import static android.view.KeyEvent.KEYCODE_STEM_PRIMARY;
import static android.view.KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL;
import static android.view.KeyEvent.KEYCODE_UNKNOWN;
import static android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
import static android.view.KeyEvent.KEYCODE_VOLUME_UP;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_SYSTEM_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.LayoutParams.TYPE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_QS_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManager.LayoutParams.isSystemAlertWindowType;
import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_KEY_CHORD;
import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_KEY_OTHER;
import static android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN;
import static android.view.WindowManagerGlobal.ADD_OKAY;
import static android.view.WindowManagerGlobal.ADD_PERMISSION_DENIED;
import static android.view.contentprotection.flags.Flags.createAccessibilityOverlayAppOpEnabled;

import static com.android.hardware.input.Flags.emojiAndScreenshotKeycodesAvailable;
import static com.android.hardware.input.Flags.keyboardA11yShortcutControl;
import static com.android.hardware.input.Flags.modifierShortcutDump;
import static com.android.hardware.input.Flags.useKeyGestureEventHandler;
import static com.android.hardware.input.Flags.useKeyGestureEventHandlerMultiPressGestures;
import static com.android.server.flags.Flags.modifierShortcutManagerMultiuser;
import static com.android.server.flags.Flags.newBugreportKeyboardShortcut;
import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.SCREENSHOT_KEYCHORD_DELAY;
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

import android.accessibilityservice.AccessibilityService;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityManager.RecentTaskInfo;
import android.app.ActivityManagerInternal;
import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.IUiModeManager;
import android.app.NotificationManager;
import android.app.ProgressDialog;
import android.app.SearchManager;
import android.app.UiModeManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.PermissionChecker;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.hardware.SensorPrivacyManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.hdmi.HdmiAudioSystemClient;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiPlaybackClient;
import android.hardware.hdmi.HdmiPlaybackClient.OneTouchPlayCallback;
import android.hardware.input.InputManager;
import android.hardware.input.KeyGestureEvent;
import android.media.AudioManager;
import android.media.AudioManagerInternal;
import android.media.AudioSystem;
import android.media.IAudioService;
import android.media.session.MediaSessionLegacyHelper;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeviceIdleManager;
import android.os.FactoryTest;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import android.os.Trace;
import android.os.UEventObserver;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.DeviceConfig;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.service.dreams.DreamManagerInternal;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.service.vr.IPersistentVrStateCallbacks;
import android.speech.RecognizerIntent;
import android.telecom.TelecomManager;
import android.util.ArraySet;
import android.util.Log;
import android.util.MathUtils;
import android.util.MutableBoolean;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.IDisplayFoldListener;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerPolicyConstants;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.autofill.AutofillManagerInternal;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.accessibility.AccessibilityShortcutController;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.AssistUtils;
import com.android.internal.display.BrightnessUtils;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.os.RoSystemProperties;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IShortcutService;
import com.android.internal.policy.KeyInterceptionInfo;
import com.android.internal.policy.LogDecelerateInterpolator;
import com.android.internal.policy.PhoneWindow;
import com.android.internal.policy.TransitionAnimation;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.AccessibilityManagerInternal;
import com.android.server.ExtconStateObserver;
import com.android.server.ExtconUEventObserver;
import com.android.server.GestureLauncherService;
import com.android.server.LocalServices;
import com.android.server.SystemServiceManager;
import com.android.server.UiThread;
import com.android.server.input.InputManagerInternal;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.policy.KeyCombinationManager.TwoKeysCombinationRule;
import com.android.server.policy.keyguard.KeyguardServiceDelegate;
import com.android.server.policy.keyguard.KeyguardServiceDelegate.DrawnListener;
import com.android.server.policy.keyguard.KeyguardStateMonitor.StateCallback;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.vr.VrManagerInternal;
import com.android.server.wallpaper.WallpaperManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.DisplayPolicy;
import com.android.server.wm.DisplayRotation;
import com.android.server.wm.WindowManagerInternal;
import com.android.server.wm.WindowManagerInternal.AppTransitionListener;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

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
    static final boolean DEBUG_WAKEUP = false;

    // Whether to allow dock apps with METADATA_DOCK_HOME to temporarily take over the Home key.
    // No longer recommended for desk docks;
    static final boolean ENABLE_DESK_DOCK_HOME_CAPTURE = false;

    // Whether to allow devices placed in vr headset viewers to have an alternative Home intent.
    static final boolean ENABLE_VR_HEADSET_HOME_CAPTURE = true;

    // --------- Key behavior definitions below. ---------
    // NOTE: When updating the valid range of any of these behaviors, and if that behavior has a
    // Settings key associated to it for dynamic update, update its valid Settings value range in
    // android.provider.settings.validators.GlobalSettingsValidators.

    // must match: config_shortPressOnPowerBehavior in config.xml
    // The config value can be overridden using Settings.Global.POWER_BUTTON_SHORT_PRESS
    static final int SHORT_PRESS_POWER_NOTHING = 0;
    static final int SHORT_PRESS_POWER_GO_TO_SLEEP = 1;
    static final int SHORT_PRESS_POWER_REALLY_GO_TO_SLEEP = 2;
    static final int SHORT_PRESS_POWER_REALLY_GO_TO_SLEEP_AND_GO_HOME = 3;
    static final int SHORT_PRESS_POWER_GO_HOME = 4;
    static final int SHORT_PRESS_POWER_CLOSE_IME_OR_GO_HOME = 5;
    static final int SHORT_PRESS_POWER_LOCK_OR_SLEEP = 6;
    static final int SHORT_PRESS_POWER_DREAM_OR_SLEEP = 7;

    // must match: config_LongPressOnPowerBehavior in config.xml
    // The config value can be overridden using Settings.Global.POWER_BUTTON_LONG_PRESS
    static final int LONG_PRESS_POWER_NOTHING = 0;
    static final int LONG_PRESS_POWER_GLOBAL_ACTIONS = 1;
    static final int LONG_PRESS_POWER_SHUT_OFF = 2;
    static final int LONG_PRESS_POWER_SHUT_OFF_NO_CONFIRM = 3;
    static final int LONG_PRESS_POWER_GO_TO_VOICE_ASSIST = 4;
    static final int LONG_PRESS_POWER_ASSISTANT = 5; // Settings.Secure.ASSISTANT

    // must match: config_veryLongPresOnPowerBehavior in config.xml
    // The config value can be overridden using Settings.Global.POWER_BUTTON_VERY_LONG_PRESS
    static final int VERY_LONG_PRESS_POWER_NOTHING = 0;
    static final int VERY_LONG_PRESS_POWER_GLOBAL_ACTIONS = 1;

    // must match: config_keyChordPowerVolumeUp in config.xml
    static final int POWER_VOLUME_UP_BEHAVIOR_NOTHING = 0;
    static final int POWER_VOLUME_UP_BEHAVIOR_MUTE = 1;
    static final int POWER_VOLUME_UP_BEHAVIOR_GLOBAL_ACTIONS = 2;

    // must match: config_doublePressOnPowerBehavior in config.xml
    // The config value can be overridden using Settings.Global.POWER_BUTTON_DOUBLE_PRESS and/or
    // Settings.Global.POWER_BUTTON_TRIPLE_PRESS
    static final int MULTI_PRESS_POWER_NOTHING = 0;
    static final int MULTI_PRESS_POWER_THEATER_MODE = 1;
    static final int MULTI_PRESS_POWER_BRIGHTNESS_BOOST = 2;
    static final int MULTI_PRESS_POWER_LAUNCH_TARGET_ACTIVITY = 3;

    // must match: config_longPressOnBackBehavior in config.xml
    static final int LONG_PRESS_BACK_NOTHING = 0;
    static final int LONG_PRESS_BACK_GO_TO_VOICE_ASSIST = 1;

    // must match: config_longPressOnHomeBehavior in config.xml
    static final int LONG_PRESS_HOME_NOTHING = 0;
    static final int LONG_PRESS_HOME_ALL_APPS = 1;
    static final int LONG_PRESS_HOME_ASSIST = 2;
    static final int LONG_PRESS_HOME_NOTIFICATION_PANEL = 3;
    static final int LAST_LONG_PRESS_HOME_BEHAVIOR = LONG_PRESS_HOME_NOTIFICATION_PANEL;

    // must match: config_doubleTapOnHomeBehavior in config.xml
    static final int DOUBLE_TAP_HOME_NOTHING = 0;
    static final int DOUBLE_TAP_HOME_RECENT_SYSTEM_UI = 1;
    static final int DOUBLE_TAP_HOME_PIP_MENU = 2;

    static final int SHORT_PRESS_WINDOW_NOTHING = 0;
    static final int SHORT_PRESS_WINDOW_PICTURE_IN_PICTURE = 1;

    static final int SHORT_PRESS_SLEEP_GO_TO_SLEEP = 0;
    static final int SHORT_PRESS_SLEEP_GO_TO_SLEEP_AND_GO_HOME = 1;

    // must match: config_settingsKeyBehavior in config.xml
    static final int SETTINGS_KEY_BEHAVIOR_SETTINGS_ACTIVITY = 0;
    static final int SETTINGS_KEY_BEHAVIOR_NOTIFICATION_PANEL = 1;
    static final int SETTINGS_KEY_BEHAVIOR_NOTHING = 2;
    static final int LAST_SETTINGS_KEY_BEHAVIOR = SETTINGS_KEY_BEHAVIOR_NOTHING;

    static final int PENDING_KEY_NULL = -1;

    // Must match: config_shortPressOnStemPrimaryBehavior in config.xml
    // The config value can be overridden using Settings.Global.STEM_PRIMARY_BUTTON_SHORT_PRESS
    static final int SHORT_PRESS_PRIMARY_NOTHING = 0;
    static final int SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS = 1;
    static final int SHORT_PRESS_PRIMARY_LAUNCH_TARGET_ACTIVITY = 2;

    // Must match: config_longPressOnStemPrimaryBehavior in config.xml
    // The config value can be overridden using Settings.Global.STEM_PRIMARY_BUTTON_LONG_PRESS
    static final int LONG_PRESS_PRIMARY_NOTHING = 0;
    static final int LONG_PRESS_PRIMARY_LAUNCH_VOICE_ASSISTANT = 1;

    // Must match: config_doublePressOnStemPrimaryBehavior in config.xml
    //The config value can be overridden using Settings.Global.STEM_PRIMARY_BUTTON_DOUBLE_PRESS
    static final int DOUBLE_PRESS_PRIMARY_NOTHING = 0;
    static final int DOUBLE_PRESS_PRIMARY_SWITCH_RECENT_APP = 1;
    static final int DOUBLE_PRESS_PRIMARY_LAUNCH_DEFAULT_FITNESS_APP = 2;

    // Must match: config_triplePressOnStemPrimaryBehavior in config.xml
    // The config value can be overridden using Settings.Global.STEM_PRIMARY_BUTTON_TRIPLE_PRESS
    static final int TRIPLE_PRESS_PRIMARY_NOTHING = 0;
    static final int TRIPLE_PRESS_PRIMARY_TOGGLE_ACCESSIBILITY = 1;

    // Must match: config_searchKeyBehavior in config.xml
    static final int SEARCH_KEY_BEHAVIOR_DEFAULT_SEARCH = 0;
    static final int SEARCH_KEY_BEHAVIOR_TARGET_ACTIVITY = 1;

    static public final String SYSTEM_DIALOG_REASON_KEY = "reason";
    static public final String SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS = "globalactions";
    static public final String SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps";
    static public final String SYSTEM_DIALOG_REASON_HOME_KEY = "homekey";
    static public final String SYSTEM_DIALOG_REASON_ASSIST = "assist";
    static public final String SYSTEM_DIALOG_REASON_SCREENSHOT = "screenshot";
    static public final String SYSTEM_DIALOG_REASON_GESTURE_NAV = "gestureNav";

    public static final String TRACE_WAIT_FOR_ALL_WINDOWS_DRAWN_METHOD = "waitForAllWindowsDrawn";

    private static final int POWER_BUTTON_SUPPRESSION_DELAY_DEFAULT_MILLIS = 800;

    /**
     * Keyguard stuff
     */
    private boolean mKeyguardDrawnOnce;

    /** Amount of time (in milliseconds) to wait for windows drawn before powering on. */
    static final int WAITING_FOR_DRAWN_TIMEOUT = 1000;

    /**
      * Extra time for additional SystemUI animations.
      * <p>Since legacy apps can add Toast windows directly instead of using Toast APIs,
      * {@link DisplayPolicy} ensures that the window manager removes toast windows after
      * TOAST_WINDOW_TIMEOUT. We increase this timeout by TOAST_WINDOW_ANIM_BUFFER to account for
      * SystemUI's in/out toast animations, so that the toast text is still shown for a minimum
      * of 3.5 seconds and the animations are finished before window manager removes the window.
      */
    public static final int TOAST_WINDOW_ANIM_BUFFER = 600;

    /**
      * Amount of time (in milliseconds) a toast window can be shown before it's automatically
      * removed by window manager.
      */
    public static final int TOAST_WINDOW_TIMEOUT = 3500 + TOAST_WINDOW_ANIM_BUFFER;

    /**
     * Action for launching assistant in retail mode
     */
    private static final String ACTION_VOICE_ASSIST_RETAIL =
            "android.intent.action.VOICE_ASSIST_RETAIL";

    /**
     * Lock protecting internal state.  Must not call out into window
     * manager with lock held.  (This lock will be acquired in places
     * where the window manager is calling in with its own lock held.)
     */
    private final Object mLock = new Object();

    /** List of {@link ScreenOnListener}s which do not belong to the default display. */
    private final SparseArray<ScreenOnListener> mScreenOnListeners = new SparseArray<>();

    Context mContext;
    WindowManagerFuncs mWindowManagerFuncs;
    WindowManagerInternal mWindowManagerInternal;
    PowerManager mPowerManager;
    ActivityManagerInternal mActivityManagerInternal;
    IActivityManager mActivityManagerService;
    ActivityTaskManagerInternal mActivityTaskManagerInternal;
    AutofillManagerInternal mAutofillManagerInternal;
    InputManager mInputManager;
    InputManagerInternal mInputManagerInternal;
    DreamManagerInternal mDreamManagerInternal;
    PowerManagerInternal mPowerManagerInternal;
    IStatusBarService mStatusBarService;
    StatusBarManagerInternal mStatusBarManagerInternal;
    AudioManagerInternal mAudioManagerInternal;
    SensorPrivacyManager mSensorPrivacyManager;
    DisplayManager mDisplayManager;
    DisplayManagerInternal mDisplayManagerInternal;
    UserManagerInternal mUserManagerInternal;

    private WallpaperManagerInternal mWallpaperManagerInternal;

    boolean mPreloadedRecentApps;
    final Object mServiceAcquireLock = new Object();
    Vibrator mVibrator; // Vibrator for giving feedback of orientation changes
    AccessibilityManager mAccessibilityManager;
    AccessibilityManagerInternal mAccessibilityManagerInternal;
    BurnInProtectionHelper mBurnInProtectionHelper;
    private DisplayFoldController mDisplayFoldController;
    AppOpsManager mAppOpsManager;
    PackageManager mPackageManager;
    SideFpsEventHandler mSideFpsEventHandler;
    LockPatternUtils mLockPatternUtils;
    private boolean mHasFeatureAuto;
    private boolean mHasFeatureWatch;
    private boolean mHasFeatureLeanback;
    private boolean mHasFeatureHdmiCec;

    // Assigned on main thread, accessed on UI thread
    volatile VrManagerInternal mVrManagerInternal;

    /** If true, can use a keyboard shortcut to trigger a bugreport. */
    boolean mEnableBugReportKeyboardShortcut = false;

    /** Controller that supports enabling an AccessibilityService by holding down the volume keys */
    private AccessibilityShortcutController mAccessibilityShortcutController;

    private TalkbackShortcutController mTalkbackShortcutController;

    private WindowWakeUpPolicy mWindowWakeUpPolicy;

    boolean mSafeMode;

    // Whether to allow dock apps with METADATA_DOCK_HOME to temporarily take over the Home key.
    // This is for car dock and this is updated from resource.
    private boolean mEnableCarDockHomeCapture = true;

    boolean mBootMessageNeedsHiding;
    volatile boolean mBootAnimationDismissable;
    @VisibleForTesting KeyguardServiceDelegate mKeyguardDelegate;
    private boolean mKeyguardBound;
    final DrawnListener mKeyguardDrawnCallback = new DrawnListener() {
        @Override
        public void onDrawn() {
            if (DEBUG_WAKEUP) Slog.d(TAG, "mKeyguardDelegate.ShowListener.onDrawn.");
            mHandler.sendEmptyMessage(MSG_KEYGUARD_DRAWN_COMPLETE);
        }
    };

    private Supplier<GlobalActions> mGlobalActionsFactory;
    private GlobalActions mGlobalActions;
    private Handler mHandler;

    // FIXME This state is shared between the input reader and handler thread.
    // Technically it's broken and buggy but it has been like this for many years
    // and we have not yet seen any problems.  Someday we'll rewrite this logic
    // so that only one thread is involved in handling input policy.  Unfortunately
    // it's on a critical path for power management so we can't just post the work to the
    // handler thread.  We'll need to resolve this someday by teaching the input dispatcher
    // to hold wakelocks during dispatch and eliminating the critical path.
    volatile boolean mPowerKeyHandled;
    volatile boolean mBackKeyHandled;
    volatile boolean mEndCallKeyHandled;
    volatile boolean mCameraGestureTriggered;
    volatile boolean mCameraGestureTriggeredDuringGoingToSleep;

    /**
     * {@code true} if the device is entering a low-power state; {@code false otherwise}.
     *
     * <p>This differs from {@link #mRequestedOrSleepingDefaultDisplay} which tracks the power state
     * of the {@link #mDefaultDisplay default display} versus the power state of the entire device.
     */
    volatile boolean mDeviceGoingToSleep;

    /**
     * {@code true} if the {@link #mDefaultDisplay default display} is entering or was requested to
     * enter a low-power state; {@code false otherwise}.
     *
     * <p>This differs from {@link #mDeviceGoingToSleep} which tracks the power state of the entire
     * device versus the power state of the {@link #mDefaultDisplay default display}.
     */
    // TODO(b/178103325): Track sleep/requested sleep for every display.
    volatile boolean mRequestedOrSleepingDefaultDisplay;

    /**
     * This is used to check whether to acquire screen-off sleep token when screen is
     * turned off. E.g. if it is false when screen is turned off and the display is swapping, it
     * is expected that the screen will be on in a short time. Then it is unnecessary to acquire
     * screen-off-sleep-token, so it can avoid intermediate visibility or lifecycle changes.
     */
    volatile boolean mIsGoingToSleepDefaultDisplay;

    volatile boolean mRecentsVisible;
    volatile boolean mNavBarVirtualKeyHapticFeedbackEnabled = true;
    volatile boolean mPictureInPictureVisible;
    volatile private boolean mDismissImeOnBackKeyPressed;

    // Used to hold the last user key used to wake the device.  This helps us prevent up events
    // from being passed to the foregrounded app without a corresponding down event
    volatile int mPendingWakeKey = PENDING_KEY_NULL;

    int mRecentAppsHeldModifiers;

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
    int mShortPressOnPowerBehavior;
    private boolean mShouldEarlyShortPressOnPower;
    boolean mShouldEarlyShortPressOnStemPrimary;
    int mLongPressOnPowerBehavior;
    long mLongPressOnPowerAssistantTimeoutMs;
    int mVeryLongPressOnPowerBehavior;
    int mDoublePressOnPowerBehavior;
    ComponentName mPowerDoublePressTargetActivity;
    int mTriplePressOnPowerBehavior;
    int mLongPressOnBackBehavior;
    int mShortPressOnSleepBehavior;
    int mShortPressOnWindowBehavior;
    int mPowerVolUpBehavior;
    boolean mStylusButtonsEnabled = true;
    boolean mKidsModeEnabled;
    boolean mHasSoftInput = false;
    boolean mUseTvRouting;
    boolean mAllowStartActivityForLongPressOnPowerDuringSetup;
    MetricsLogger mLogger;
    boolean mWakeOnDpadKeyPress;
    boolean mWakeOnAssistKeyPress;
    boolean mWakeOnBackKeyPress;
    boolean mSilenceRingerOnSleepKey;
    long mWakeUpToLastStateTimeout;
    int mSearchKeyBehavior;
    ComponentName mSearchKeyTargetActivity;

    // Key Behavior - Stem Primary
    private int mShortPressOnStemPrimaryBehavior;
    private int mDoublePressOnStemPrimaryBehavior;
    private int mTriplePressOnStemPrimaryBehavior;
    private int mLongPressOnStemPrimaryBehavior;
    private RecentTaskInfo mBackgroundRecentTaskInfoOnStemPrimarySingleKeyUp;

    // The focused task at the time when the first STEM_PRIMARY key was released. This can only
    // be accessed from the looper thread.
    private RootTaskInfo mFocusedTaskInfoOnStemPrimarySingleKeyUp;

    private boolean mHandleVolumeKeysInWM;

    private boolean mPendingKeyguardOccluded;
    private boolean mKeyguardOccludedChanged;

    Intent mHomeIntent;
    Intent mCarDockIntent;
    Intent mDeskDockIntent;
    Intent mVrHeadsetHomeIntent;
    boolean mPendingMetaAction;
    boolean mPendingCapsLockToggle;

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
    int mLongPressOnHomeBehavior;

    // What we do when the user double-taps on home
    int mDoubleTapOnHomeBehavior;

    // What we do when the user presses the settings key
    int mSettingsKeyBehavior;

    // Must match config_primaryShortPressTargetActivity in config.xml
    ComponentName mPrimaryShortPressTargetActivity;

    // Whether to lock the device after the next dreaming transition has finished.
    private boolean mLockAfterDreamingTransitionFinished;

    // If true, the power button long press behavior will be invoked even if the default display is
    // non-interactive. If false, the power button long press behavior will be skipped if the
    // default display is non-interactive.
    private boolean mSupportLongPressPowerWhenNonInteractive;

    // If true, the power button short press behavior will be always invoked as long as the default
    // display is on, even if the display is not interactive. If false, the power button short press
    // behavior will be skipped if the default display is non-interactive.
    private boolean mSupportShortPressPowerWhenDefaultDisplayOn;

    // Whether to go to sleep entering theater mode from power button
    private boolean mGoToSleepOnButtonPressTheaterMode;

    // Screenshot trigger states
    // Increase the chord delay when taking a screenshot from the keyguard
    private static final float KEYGUARD_SCREENSHOT_CHORD_DELAY_MULTIPLIER = 2.5f;

    // Ringer toggle should reuse timing and triggering from screenshot power and a11y vol up
    int mRingerToggleChord = VOLUME_HUSH_OFF;

    private static final long BUGREPORT_TV_GESTURE_TIMEOUT_MILLIS = 1000;

    /* The number of steps between min and max brightness */
    private static final int BRIGHTNESS_STEPS = 10;

    SettingsObserver mSettingsObserver;
    ModifierShortcutManager mModifierShortcutManager;
    /** Currently fully consumed key codes per device */
    private final SparseArray<Set<Integer>> mConsumedKeysForDevice = new SparseArray<>();
    PowerManager.WakeLock mBroadcastWakeLock;
    PowerManager.WakeLock mPowerKeyWakeLock;
    boolean mHavePendingMediaKeyRepeatWithWakeLock;

    private int mCurrentUserId;

    // Maps global key codes to the components that will handle them.
    private GlobalKeyManager mGlobalKeyManager;

    private final com.android.internal.policy.LogDecelerateInterpolator mLogDecelerateInterpolator
            = new LogDecelerateInterpolator(100, 0);
    private final DeferredKeyActionExecutor mDeferredKeyActionExecutor =
            new DeferredKeyActionExecutor();

    private volatile int mTopFocusedDisplayId = INVALID_DISPLAY;

    private int mPowerButtonSuppressionDelayMillis = POWER_BUTTON_SUPPRESSION_DELAY_DEFAULT_MILLIS;

    private KeyCombinationManager mKeyCombinationManager;
    private SingleKeyGestureDetector mSingleKeyGestureDetector;
    private GestureLauncherService mGestureLauncherService;
    private ButtonOverridePermissionChecker mButtonOverridePermissionChecker;

    private boolean mLockNowPending = false;

    // Timeout for showing the keyguard after the screen is on, in case no "ready" is received.
    private int mKeyguardDrawnTimeout = 1000;

    private final boolean mVisibleBackgroundUsersEnabled = isVisibleBackgroundUsersEnabled();

    // Key codes that should be ignored for visible background users in MUMD environment.
    private static final Set<Integer> KEY_CODES_IGNORED_FOR_VISIBLE_BACKGROUND_USERS =
            new ArraySet<>(Arrays.asList(
                    KeyEvent.KEYCODE_POWER,
                    KeyEvent.KEYCODE_SLEEP,
                    KeyEvent.KEYCODE_WAKEUP,
                    KeyEvent.KEYCODE_CALL,
                    KeyEvent.KEYCODE_ENDCALL,
                    KeyEvent.KEYCODE_ASSIST,
                    KeyEvent.KEYCODE_VOICE_ASSIST,
                    KeyEvent.KEYCODE_MUTE,
                    KeyEvent.KEYCODE_VOLUME_MUTE,
                    KeyEvent.KEYCODE_RECENT_APPS,
                    KeyEvent.KEYCODE_APP_SWITCH,
                    KeyEvent.KEYCODE_NOTIFICATION
            ));

    private static final int MSG_DISPATCH_MEDIA_KEY_WITH_WAKE_LOCK = 3;
    private static final int MSG_DISPATCH_MEDIA_KEY_REPEAT_WITH_WAKE_LOCK = 4;
    private static final int MSG_KEYGUARD_DRAWN_COMPLETE = 5;
    private static final int MSG_KEYGUARD_DRAWN_TIMEOUT = 6;
    private static final int MSG_WINDOW_MANAGER_DRAWN_COMPLETE = 7;
    private static final int MSG_DISPATCH_SHOW_RECENTS = 9;
    private static final int MSG_DISPATCH_SHOW_GLOBAL_ACTIONS = 10;
    private static final int MSG_HIDE_BOOT_MESSAGE = 11;
    private static final int MSG_LAUNCH_VOICE_ASSIST_WITH_WAKE_LOCK = 12;
    private static final int MSG_SHOW_PICTURE_IN_PICTURE_MENU = 15;
    private static final int MSG_SCREENSHOT_CHORD = 16;
    private static final int MSG_ACCESSIBILITY_SHORTCUT = 17;
    private static final int MSG_BUGREPORT_TV = 18;
    private static final int MSG_ACCESSIBILITY_TV = 19;
    private static final int MSG_DISPATCH_BACK_KEY_TO_AUTOFILL = 20;
    private static final int MSG_SYSTEM_KEY_PRESS = 21;
    private static final int MSG_HANDLE_ALL_APPS = 22;
    private static final int MSG_LAUNCH_ASSIST = 23;
    private static final int MSG_RINGER_TOGGLE_CHORD = 24;
    private static final int MSG_SWITCH_KEYBOARD_LAYOUT = 25;
    private static final int MSG_SET_DEFERRED_KEY_ACTIONS_EXECUTABLE = 27;

    private class PolicyHandler extends Handler {

        private PolicyHandler(Looper looper) {
            super(looper);
        }

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
                    final int displayId = msg.arg1;
                    if (DEBUG_WAKEUP) Slog.w(TAG, "All windows drawn on display " + displayId);
                    Trace.asyncTraceEnd(Trace.TRACE_TAG_WINDOW_MANAGER,
                            TRACE_WAIT_FOR_ALL_WINDOWS_DRAWN_METHOD, displayId /* cookie */);
                    finishWindowsDrawn(displayId);
                    break;
                case MSG_HIDE_BOOT_MESSAGE:
                    handleHideBootMessage();
                    break;
                case MSG_LAUNCH_ASSIST:
                    final int deviceId = msg.arg1;
                    final Long eventTime = (Long) msg.obj;
                    launchAssistAction(null /* hint */, deviceId, eventTime,
                            AssistUtils.INVOCATION_TYPE_ASSIST_BUTTON);
                    break;
                case MSG_LAUNCH_VOICE_ASSIST_WITH_WAKE_LOCK:
                    launchVoiceAssistWithWakeLock();
                    break;
                case MSG_SHOW_PICTURE_IN_PICTURE_MENU:
                    showPictureInPictureMenuInternal();
                    break;
                case MSG_ACCESSIBILITY_SHORTCUT:
                    accessibilityShortcutActivated();
                    break;
                case MSG_BUGREPORT_TV:
                    requestBugreportForTv();
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
                    KeyEvent event = (KeyEvent) msg.obj;
                    sendSystemKeyToStatusBar(event);
                    event.recycle();
                    break;
                case MSG_HANDLE_ALL_APPS:
                    KeyEvent keyEvent = (KeyEvent) msg.obj;
                    if (isKeyEventForCurrentUser(keyEvent.getDisplayId(), keyEvent.getKeyCode(),
                            "launchAllAppsViaA11y")) {
                        launchAllAppsAction();
                    }
                    break;
                case MSG_RINGER_TOGGLE_CHORD:
                    handleRingerChordGesture();
                    break;
                case MSG_SCREENSHOT_CHORD:
                    handleScreenShot(msg.arg1);
                    break;
                case MSG_SWITCH_KEYBOARD_LAYOUT:
                    SwitchKeyboardLayoutMessageObject object =
                            (SwitchKeyboardLayoutMessageObject) msg.obj;
                    handleSwitchKeyboardLayout(object.displayId, object.direction,
                            object.focusedToken);
                    break;
                case MSG_SET_DEFERRED_KEY_ACTIONS_EXECUTABLE:
                    final int keyCode = msg.arg1;
                    final long downTime = (Long) msg.obj;
                    mDeferredKeyActionExecutor.setActionsExecutable(keyCode, downTime);
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
                    Settings.Global.POWER_BUTTON_SHORT_PRESS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.POWER_BUTTON_DOUBLE_PRESS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.POWER_BUTTON_TRIPLE_PRESS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.POWER_BUTTON_LONG_PRESS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.POWER_BUTTON_LONG_PRESS_DURATION_MS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.POWER_BUTTON_VERY_LONG_PRESS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.STEM_PRIMARY_BUTTON_SHORT_PRESS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.STEM_PRIMARY_BUTTON_DOUBLE_PRESS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.STEM_PRIMARY_BUTTON_TRIPLE_PRESS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.STEM_PRIMARY_BUTTON_LONG_PRESS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.KEY_CHORD_POWER_VOLUME_UP), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.POWER_BUTTON_SUPPRESSION_DELAY_AFTER_GESTURE_WAKE), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.STYLUS_BUTTONS_ENABLED), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.NAV_BAR_KIDS_MODE), false, this,
                    UserHandle.USER_ALL);
            updateSettings();
        }

        @Override public void onChange(boolean selfChange) {
            updateSettings();
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
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, "Wake Up");
                    mWindowWakeUpPolicy.wakeUpFromWakeGesture();
                }
            }
        }
    }

    private record SwitchKeyboardLayoutMessageObject(int displayId, IBinder focusedToken,
                                                     int direction) {
    }

    final IPersistentVrStateCallbacks mPersistentVrModeListener =
            new IPersistentVrStateCallbacks.Stub() {
        @Override
        public void onPersistentVrStateChanged(boolean enabled) {
            mDefaultDisplayPolicy.setPersistentVrModeEnabled(enabled);
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
        synchronized (mServiceAcquireLock) {
            if (mStatusBarService == null) {
                mStatusBarService = IStatusBarService.Stub.asInterface(
                        ServiceManager.getService("statusbar"));
            }
            return mStatusBarService;
        }
    }

    StatusBarManagerInternal getStatusBarManagerInternal() {
        synchronized (mServiceAcquireLock) {
            if (mStatusBarManagerInternal == null) {
                mStatusBarManagerInternal =
                        LocalServices.getService(StatusBarManagerInternal.class);
            }
            return mStatusBarManagerInternal;
        }
    }

    AudioManagerInternal getAudioManagerInternal() {
        synchronized (mServiceAcquireLock) {
            if (mAudioManagerInternal == null) {
                mAudioManagerInternal = LocalServices.getService(AudioManagerInternal.class);
            }
            return mAudioManagerInternal;
        }
    }

    AccessibilityManagerInternal getAccessibilityManagerInternal() {
        synchronized (mServiceAcquireLock) {
            if (mAccessibilityManagerInternal == null) {
                mAccessibilityManagerInternal =
                        LocalServices.getService(AccessibilityManagerInternal.class);
            }
            return mAccessibilityManagerInternal;
        }
    }

    // returns true if the key was handled and should not be passed to the user
    private boolean backKeyPress() {
        mLogger.count("key_back_press", 1);
        // Cache handled state
        boolean handled = mBackKeyHandled;

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

        if (mAutofillManagerInternal != null) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_DISPATCH_BACK_KEY_TO_AUTOFILL));
        }
        return handled;
    }

    private void interceptPowerKeyDown(KeyEvent event, boolean interactive,
            boolean isKeyGestureTriggered) {
        // Hold a wake lock until the power key is released.
        if (!mPowerKeyWakeLock.isHeld()) {
            mPowerKeyWakeLock.acquire();
        }

        mWindowManagerFuncs.onPowerKeyDown(interactive);

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

        final boolean handledByPowerManager = mPowerManagerInternal.interceptPowerKeyDown(event);

        // Inform the StatusBar; but do not allow it to consume the event.
        sendSystemKeyToStatusBarAsync(event);

        // If the power key has still not yet been handled, then detect short
        // press, long press, or multi press and decide what to do.
        mPowerKeyHandled = mPowerKeyHandled || hungUp
                || handledByPowerManager || isKeyGestureTriggered
                || mKeyCombinationManager.isPowerKeyIntercepted();
        if (!mPowerKeyHandled) {
            if (!interactive) {
                wakeUpFromWakeKey(event);
            }
        } else {
            // handled by another power key policy.
            if (mSingleKeyGestureDetector.isKeyIntercepted(KEYCODE_POWER)) {
                Slog.d(TAG, "Skip power key gesture for other policy has handled it.");
                mSingleKeyGestureDetector.reset();
            }
        }
    }

    private void interceptPowerKeyUp(KeyEvent event, boolean canceled) {
        // Inform the StatusBar; but do not allow it to consume the event.
        sendSystemKeyToStatusBarAsync(event);
        finishPowerKeyPress();
    }

    private void finishPowerKeyPress() {
        mPowerKeyHandled = false;
        if (mPowerKeyWakeLock.isHeld()) {
            mPowerKeyWakeLock.release();
        }
    }

    private void powerPress(long eventTime, int count, int displayId) {
        // SideFPS still needs to know about suppressed power buttons, in case it needs to block
        // an auth attempt.
        if (count == 1) {
            mSideFpsEventHandler.notifyPowerPressed();
        }
        if (mDefaultDisplayPolicy.isScreenOnEarly() && !mDefaultDisplayPolicy.isScreenOnFully()) {
            Slog.i(TAG, "Suppressed redundant power key press while "
                    + "already in the process of turning the screen on.");
            return;
        }

        final boolean interactive = mDefaultDisplayPolicy.isAwake();

        Slog.d(
                TAG,
                "powerPress: eventTime="
                        + eventTime
                        + " interactive="
                        + interactive
                        + " count="
                        + count
                        + " mShortPressOnPowerBehavior="
                        + mShortPressOnPowerBehavior);

        if (count == 2) {
            powerMultiPressAction(eventTime, interactive, mDoublePressOnPowerBehavior);
        } else if (count == 3) {
            powerMultiPressAction(eventTime, interactive, mTriplePressOnPowerBehavior);
        } else if (count > 3 && count <= getMaxMultiPressPowerCount()) {
            Slog.d(TAG, "No behavior defined for power press count " + count);
        } else if (count == 1 && shouldHandleShortPressPowerAction(interactive, eventTime)) {
            switch (mShortPressOnPowerBehavior) {
                case SHORT_PRESS_POWER_NOTHING:
                    break;
                case SHORT_PRESS_POWER_GO_TO_SLEEP:
                    sleepDefaultDisplayFromPowerButton(eventTime, 0);
                    break;
                case SHORT_PRESS_POWER_REALLY_GO_TO_SLEEP:
                    sleepDefaultDisplayFromPowerButton(eventTime,
                            PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE);
                    break;
                case SHORT_PRESS_POWER_REALLY_GO_TO_SLEEP_AND_GO_HOME:
                    if (sleepDefaultDisplayFromPowerButton(eventTime,
                            PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE)) {
                        launchHomeFromHotKey(DEFAULT_DISPLAY);
                    }
                    break;
                case SHORT_PRESS_POWER_GO_HOME:
                    shortPressPowerGoHome();
                    break;
                case SHORT_PRESS_POWER_CLOSE_IME_OR_GO_HOME: {
                    if (mDismissImeOnBackKeyPressed) {
                        InputMethodManagerInternal.get().hideInputMethod(
                                SoftInputShowHideReason.HIDE_POWER_BUTTON_GO_HOME, displayId);
                    } else {
                        shortPressPowerGoHome();
                    }
                    break;
                }
                case SHORT_PRESS_POWER_LOCK_OR_SLEEP: {
                    if (mKeyguardDelegate == null || !mKeyguardDelegate.hasKeyguard()
                            || !mKeyguardDelegate.isSecure(mCurrentUserId) || keyguardOn()) {
                        sleepDefaultDisplayFromPowerButton(eventTime, 0);
                    } else {
                        lockNow(null /*options*/);
                    }
                    break;
                }
                case SHORT_PRESS_POWER_DREAM_OR_SLEEP: {
                    attemptToDreamFromShortPowerButtonPress(
                            true,
                            () -> sleepDefaultDisplayFromPowerButton(eventTime, 0));
                    break;
                }
            }
        }
    }

    private boolean shouldHandleShortPressPowerAction(boolean interactive, long eventTime) {
        if (mSupportShortPressPowerWhenDefaultDisplayOn) {
            final boolean defaultDisplayOn = Display.isOnState(mDefaultDisplay.getState());
            final boolean beganFromDefaultDisplayOn =
                    mSingleKeyGestureDetector.beganFromDefaultDisplayOn();
            if (!defaultDisplayOn || !beganFromDefaultDisplayOn) {
                Slog.v(
                        TAG,
                        "Ignoring short press of power button because the default display is not"
                                + " on. defaultDisplayOn="
                                + defaultDisplayOn
                                + ", beganFromDefaultDisplayOn="
                                + beganFromDefaultDisplayOn);
                return false;
            }
            return true;
        }
        final boolean beganFromNonInteractive = mSingleKeyGestureDetector.beganFromNonInteractive();
        if (!interactive || beganFromNonInteractive) {
            Slog.v(
                    TAG,
                    "Ignoring short press of power button because the device is not interactive."
                            + " interactive="
                            + interactive
                            + ", beganFromNonInteractive="
                            + beganFromNonInteractive);
            return false;
        }
        if (mSideFpsEventHandler.shouldConsumeSinglePress(eventTime)) {
            Slog.i(
                    TAG,
                    "Suppressing power key because the user is interacting with the "
                            + "fingerprint sensor");
            return false;
        }
        return true;
    }

    /**
     * Attempt to dream from a power button press.
     *
     * @param isScreenOn Whether the screen is currently on.
     * @param noDreamAction The action to perform if dreaming is not possible.
     */
    private void attemptToDreamFromShortPowerButtonPress(
            boolean isScreenOn, Runnable noDreamAction) {
        if (mShortPressOnPowerBehavior != SHORT_PRESS_POWER_DREAM_OR_SLEEP) {
            noDreamAction.run();
            return;
        }

        final DreamManagerInternal dreamManagerInternal = getDreamManagerInternal();
        if (dreamManagerInternal == null || !dreamManagerInternal.canStartDreaming(isScreenOn)) {
            Slog.d(TAG, "Can't start dreaming when attempting to dream from short power"
                    + " press (isScreenOn=" + isScreenOn + ")");
            noDreamAction.run();
            return;
        }

        synchronized (mLock) {
            // If the setting to lock instantly on power button press is true, then set the flag to
            // lock after the dream transition has finished.
            mLockAfterDreamingTransitionFinished =
                    mLockPatternUtils.getPowerButtonInstantlyLocks(mCurrentUserId);
        }

        dreamManagerInternal.requestDream();
    }

    /**
     * Sends the default display to sleep as a result of a power button press.
     *
     * @return {@code true} if the device was sent to sleep, {@code false} if the device did not
     * sleep.
     */
    private boolean sleepDefaultDisplayFromPowerButton(long eventTime, int flags) {
        // Before we actually go to sleep, we check the last wakeup reason.
        // If the device very recently woke up from a gesture (like user lifting their device)
        // then ignore the sleep instruction. This is because users have developed
        // a tendency to hit the power button immediately when they pick up their device, and we
        // don't want to put the device back to sleep in those cases.
        final PowerManager.WakeData lastWakeUp = mPowerManagerInternal.getLastWakeup();
        if (lastWakeUp != null && (lastWakeUp.wakeReason == PowerManager.WAKE_REASON_GESTURE
                || lastWakeUp.wakeReason == PowerManager.WAKE_REASON_LIFT
                || lastWakeUp.wakeReason == PowerManager.WAKE_REASON_BIOMETRIC)) {
            final long now = SystemClock.uptimeMillis();
            if (mPowerButtonSuppressionDelayMillis > 0
                    && (now < lastWakeUp.wakeTime + mPowerButtonSuppressionDelayMillis)) {
                Slog.i(TAG, "Sleep from power button suppressed. Time since gesture: "
                        + (now - lastWakeUp.wakeTime) + "ms");
                return false;
            }
        }

        sleepDefaultDisplay(eventTime, PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, flags);
        return true;
    }

    private void sleepDefaultDisplay(long eventTime, int reason, int flags) {
        mRequestedOrSleepingDefaultDisplay = true;
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
                        wakeUpFromWakeKey(eventTime, KEYCODE_POWER, /* isDown= */ false);
                    }
                } else {
                    Slog.i(TAG, "Toggling theater mode on.");
                    Settings.Global.putInt(mContext.getContentResolver(),
                            Settings.Global.THEATER_MODE_ON, 1);

                    if (mGoToSleepOnButtonPressTheaterMode && interactive) {
                        sleepDefaultDisplay(eventTime, PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON,
                                0);
                    }
                }
                break;
            case MULTI_PRESS_POWER_BRIGHTNESS_BOOST:
                Slog.i(TAG, "Starting brightness boost.");
                if (!interactive) {
                    wakeUpFromWakeKey(eventTime, KEYCODE_POWER, /* isDown= */ false);
                }
                mPowerManager.boostScreenBrightness(eventTime);
                break;
            case MULTI_PRESS_POWER_LAUNCH_TARGET_ACTIVITY:
                launchTargetActivityOnMultiPressPower();
                break;
        }
    }

    private void launchTargetActivityOnMultiPressPower() {
        if (DEBUG_INPUT) {
            Slog.d(TAG, "Executing the double press power action.");
        }
        if (mPowerDoublePressTargetActivity != null) {
            Intent intent = new Intent();
            intent.setComponent(mPowerDoublePressTargetActivity);
            ResolveInfo resolveInfo = mContext.getPackageManager().resolveActivity(
                    intent, /* flags= */0);
            if (resolveInfo != null) {
                final boolean keyguardActive =
                        mKeyguardDelegate != null && mKeyguardDelegate.isShowing();
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                if (!keyguardActive) {
                    startActivityAsUser(intent, UserHandle.CURRENT_OR_SELF);
                } else {
                    mKeyguardDelegate.dismissKeyguardToLaunch(intent);
                }
            } else {
                Slog.e(TAG, "Could not resolve activity with : "
                        + mPowerDoublePressTargetActivity.flattenToString()
                        + " name.");
            }
        }
    }

    private int getLidBehavior() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.LID_BEHAVIOR, LID_BEHAVIOR_NONE);
    }

    private int getMaxMultiPressPowerCount() {
        // The actual max power button press count is 5
        // (EMERGENCY_GESTURE_POWER_TAP_COUNT_THRESHOLD), which is coming from
        // GestureLauncherService.
        // To speed up the handling of single-press of power button inside SingleKeyGestureDetector,
        // however, we limit the max count to the number of button presses actually handled by the
        // SingleKeyGestureDetector except for wearable devices, where we want to de-dup the double
        // press gesture with the emergency gesture.
        if (mHasFeatureWatch
                && GestureLauncherService.isEmergencyGestureSettingEnabled(
                        mContext, ActivityManager.getCurrentUser())) {
            return 5;
        }
        if (mTriplePressOnPowerBehavior != MULTI_PRESS_POWER_NOTHING) {
            return 3;
        }
        if (mDoublePressOnPowerBehavior != MULTI_PRESS_POWER_NOTHING) {
            return 2;
        }
        return 1;
    }

    private void powerLongPress(long eventTime) {
        final int behavior = getResolvedLongPressOnPowerBehavior();
        Slog.d(TAG, "powerLongPress: eventTime=" + eventTime
                + " mLongPressOnPowerBehavior=" + mLongPressOnPowerBehavior);

        switch (behavior) {
            case LONG_PRESS_POWER_NOTHING:
                break;
            case LONG_PRESS_POWER_GLOBAL_ACTIONS:
                mPowerKeyHandled = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS_POWER_BUTTON,
                        "Power - Long Press - Global Actions");
                showGlobalActions();
                break;
            case LONG_PRESS_POWER_SHUT_OFF:
            case LONG_PRESS_POWER_SHUT_OFF_NO_CONFIRM:
                mPowerKeyHandled = true;
                // don't actually trigger the shutdown if we are running stability
                // tests via monkey
                if (ActivityManager.isUserAMonkey()) {
                    break;
                }
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS_POWER_BUTTON,
                        "Power - Long Press - Shut Off");
                sendCloseSystemWindows(SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS);
                mWindowManagerFuncs.shutdown(behavior == LONG_PRESS_POWER_SHUT_OFF);
                break;
            case LONG_PRESS_POWER_GO_TO_VOICE_ASSIST:
                mPowerKeyHandled = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS_POWER_BUTTON,
                        "Power - Long Press - Go To Voice Assist");
                // Some devices allow the voice assistant intent during setup (and use that intent
                // to launch something else, like Settings). So we explicitly allow that via the
                // config_allowStartActivityForLongPressOnPowerInSetup resource in config.xml.
                launchVoiceAssist(mAllowStartActivityForLongPressOnPowerDuringSetup);
                break;
            case LONG_PRESS_POWER_ASSISTANT:
                mPowerKeyHandled = true;
                performHapticFeedback(HapticFeedbackConstants.ASSISTANT_BUTTON,
                        "Power - Long Press - Go To Assistant");
                final int powerKeyDeviceId = INVALID_INPUT_DEVICE_ID;
                launchAssistAction(null, powerKeyDeviceId, eventTime,
                        AssistUtils.INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS);
                break;
        }
    }

    private void powerVeryLongPress() {
        switch (mVeryLongPressOnPowerBehavior) {
            case VERY_LONG_PRESS_POWER_NOTHING:
                break;
            case VERY_LONG_PRESS_POWER_GLOBAL_ACTIONS:
                mPowerKeyHandled = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS_POWER_BUTTON,
                        "Power - Very Long Press - Show Global Actions");
                showGlobalActions();
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
        if (mSilenceRingerOnSleepKey) {
            TelecomManager telecomManager = getTelecommService();
            if (telecomManager != null && telecomManager.isRinging()) {
                telecomManager.silenceRinger();
                Slog.i(TAG, "sleepRelease() silence ringer");
                return;
            }
        }

        switch (mShortPressOnSleepBehavior) {
            case SHORT_PRESS_SLEEP_GO_TO_SLEEP:
            case SHORT_PRESS_SLEEP_GO_TO_SLEEP_AND_GO_HOME:
                Slog.i(TAG, "sleepRelease() calling goToSleep(GO_TO_SLEEP_REASON_SLEEP_BUTTON)");
                sleepDefaultDisplay(eventTime, PowerManager.GO_TO_SLEEP_REASON_SLEEP_BUTTON, 0);
                break;
        }
    }

    private int getResolvedLongPressOnPowerBehavior() {
        if (FactoryTest.isLongPressOnPowerOffEnabled()) {
            return LONG_PRESS_POWER_SHUT_OFF_NO_CONFIRM;
        }

        // If the config indicates the assistant behavior but the device isn't yet provisioned, show
        // global actions instead.
        if (mLongPressOnPowerBehavior == LONG_PRESS_POWER_ASSISTANT && !isDeviceProvisioned()) {
            return LONG_PRESS_POWER_GLOBAL_ACTIONS;
        }

        // If long press to launch assistant is disabled in settings, do nothing.
        if (mLongPressOnPowerBehavior == LONG_PRESS_POWER_GO_TO_VOICE_ASSIST
                && !isLongPressToAssistantEnabled(mContext)) {
            return LONG_PRESS_POWER_NOTHING;
        }

        return mLongPressOnPowerBehavior;
    }

    private void stemPrimaryPress(int count) {
        Slog.d(TAG, "stemPrimaryPress: " + count);
        if (count == 3) {
            stemPrimaryTriplePressAction(mTriplePressOnStemPrimaryBehavior);
        } else if (count == 2) {
            stemPrimaryDoublePressAction(mDoublePressOnStemPrimaryBehavior);
        } else if (count == 1) {
            stemPrimarySinglePressAction(mShortPressOnStemPrimaryBehavior);
        }
    }

    private void stemPrimarySinglePressAction(int behavior) {
        Slog.d(TAG, "stemPrimarySinglePressAction: behavior=" + behavior);
        if (behavior == SHORT_PRESS_PRIMARY_NOTHING) return;

        final boolean keyguardActive = mKeyguardDelegate != null && mKeyguardDelegate.isShowing();
        if (keyguardActive) {
            // If keyguarded then notify the keyguard.
            mKeyguardDelegate.onSystemKeyPressed(KeyEvent.KEYCODE_STEM_PRIMARY);
            Slog.d(TAG, "stemPrimarySinglePressAction: skip due to keyguard");
            return;
        }
        switch (behavior) {
            case SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS:
                Intent allAppsIntent = new Intent(Intent.ACTION_ALL_APPS);
                allAppsIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivityAsUser(allAppsIntent, UserHandle.CURRENT_OR_SELF);
                break;
            case SHORT_PRESS_PRIMARY_LAUNCH_TARGET_ACTIVITY:
                if (mPrimaryShortPressTargetActivity != null) {
                    Intent targetActivityIntent = new Intent();
                    targetActivityIntent.setComponent(mPrimaryShortPressTargetActivity);
                    ResolveInfo resolveInfo =
                            mContext.getPackageManager()
                                    .resolveActivity(targetActivityIntent, /* flags= */ 0);
                    if (resolveInfo != null) {
                        targetActivityIntent.addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                                        | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
                        startActivityAsUser(targetActivityIntent, UserHandle.CURRENT_OR_SELF);
                    } else {
                        Slog.wtf(
                                TAG,
                                "Could not resolve activity with : "
                                        + mPrimaryShortPressTargetActivity.flattenToString()
                                        + " name.");
                    }
                } else {
                    Slog.wtf(
                            TAG,
                            "mPrimaryShortPressTargetActivity must not be null and correctly"
                                + " specified");
                }
                break;
        }
    }

    private void stemPrimaryDoublePressAction(int behavior) {
        Slog.d(TAG, "stemPrimaryDoublePressAction: " + behavior);
        switch (behavior) {
            case DOUBLE_PRESS_PRIMARY_NOTHING:
                break;
            case DOUBLE_PRESS_PRIMARY_SWITCH_RECENT_APP:
                final boolean keyguardActive = mKeyguardDelegate == null
                        ? false
                        : mKeyguardDelegate.isShowing();
                if (!keyguardActive) {
                    performStemPrimaryDoublePressSwitchToRecentTask();
                }
                break;
            case DOUBLE_PRESS_PRIMARY_LAUNCH_DEFAULT_FITNESS_APP:
                final int stemPrimaryKeyDeviceId = INVALID_INPUT_DEVICE_ID;
                handleKeyGestureInKeyGestureController(
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FITNESS,
                        stemPrimaryKeyDeviceId, KEYCODE_STEM_PRIMARY, /* metaState= */ 0);
                break;
        }
    }

    private void stemPrimaryTriplePressAction(int behavior) {
        Slog.d(TAG, "stemPrimaryTriplePressAction: " + behavior);
        switch (behavior) {
            case TRIPLE_PRESS_PRIMARY_NOTHING:
                break;
            case TRIPLE_PRESS_PRIMARY_TOGGLE_ACCESSIBILITY:
                mTalkbackShortcutController.toggleTalkback(mCurrentUserId,
                        TalkbackShortcutController.ShortcutSource.GESTURE);
                if (mTalkbackShortcutController.isTalkBackShortcutGestureEnabled()) {
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM,
                            "Stem primary - Triple Press - Toggle Accessibility");
                }
                break;
        }
    }

    private void stemPrimaryLongPress(long eventTime) {
        Slog.d(TAG, "stemPrimaryLongPress: "  + mLongPressOnStemPrimaryBehavior);

        switch (mLongPressOnStemPrimaryBehavior) {
            case LONG_PRESS_PRIMARY_NOTHING:
                break;
            case LONG_PRESS_PRIMARY_LAUNCH_VOICE_ASSISTANT:
                final int stemPrimaryKeyDeviceId = INVALID_INPUT_DEVICE_ID;
                launchAssistAction(
                        null,
                        stemPrimaryKeyDeviceId,
                        eventTime,
                        AssistUtils.INVOCATION_TYPE_UNKNOWN);
                break;
        }
    }

    /**
     * Load most recent task (expect current task) and bring it to the front.
     */
    void performStemPrimaryDoublePressSwitchToRecentTask() {
        RecentTaskInfo targetTask = mBackgroundRecentTaskInfoOnStemPrimarySingleKeyUp;
        if (targetTask == null) {
            if (DEBUG_INPUT) {
                Slog.w(TAG, "No recent task available! Show wallpaper.");
            }
            goHome();
            return;
        }

        if (DEBUG_INPUT) {
            Slog.d(
                    TAG,
                    "Starting task from recents. id="
                            + targetTask.id
                            + ", persistentId="
                            + targetTask.persistentId
                            + ", topActivity="
                            + targetTask.topActivity
                            + ", baseIntent="
                            + targetTask.baseIntent);
        }
        try {
            mActivityManagerService.startActivityFromRecents(targetTask.persistentId, null);
        } catch (RemoteException | IllegalArgumentException e) {
            Slog.e(TAG, "Failed to start task " + targetTask.persistentId + " from recents", e);
        }
    }

    private int getMaxMultiPressStemPrimaryCount() {
        switch (mTriplePressOnStemPrimaryBehavior) {
            case TRIPLE_PRESS_PRIMARY_NOTHING:
                break;
            case TRIPLE_PRESS_PRIMARY_TOGGLE_ACCESSIBILITY:
                if (mTalkbackShortcutController.isTalkBackShortcutGestureEnabled()) {
                    return 3;
                }
                break;
        }
        if (mDoublePressOnStemPrimaryBehavior != DOUBLE_PRESS_PRIMARY_NOTHING) {
            return 2;
        }
        return 1;
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

    private boolean hasLongPressOnStemPrimaryBehavior() {
        return mLongPressOnStemPrimaryBehavior != LONG_PRESS_PRIMARY_NOTHING;
    }

    /** Determine whether the device has any stem primary behaviors. */
    private boolean hasStemPrimaryBehavior() {
        // Read the default stem behaviors from the XML config to determine whether stem primary
        // behaviors are supported in this build. If they are supported, then the behaviors may be
        // overridden at runtime through their respective Settings overrides. If they are not
        // supported, the Settings overrides will not apply.
        final int defaultShortPressOnStemPrimaryBehavior = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_shortPressOnStemPrimaryBehavior);
        final int defaultLongPressOnStemPrimaryBehavior = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_longPressOnStemPrimaryBehavior);
        return getMaxMultiPressStemPrimaryCount() > 1
                || defaultLongPressOnStemPrimaryBehavior != LONG_PRESS_PRIMARY_NOTHING
                || defaultShortPressOnStemPrimaryBehavior != SHORT_PRESS_PRIMARY_NOTHING;
    }

    private void interceptScreenshotChord(int source, long pressDelay) {
        mHandler.removeMessages(MSG_SCREENSHOT_CHORD);
        // arg2 is unused, but necessary to insure we call the correct method signature
        // since the screenshot source is read from message.arg1
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SCREENSHOT_CHORD, source, 0),
                pressDelay);
    }

    private void interceptAccessibilityShortcutChord() {
        mHandler.removeMessages(MSG_ACCESSIBILITY_SHORTCUT);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_ACCESSIBILITY_SHORTCUT),
                getAccessibilityShortcutTimeout());
    }

    private void interceptRingerToggleChord() {
        mHandler.removeMessages(MSG_RINGER_TOGGLE_CHORD);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_RINGER_TOGGLE_CHORD),
                getRingerToggleChordDelay());
    }

    private long getAccessibilityShortcutTimeout() {
        final ViewConfiguration config = ViewConfiguration.get(mContext);
        final boolean hasDialogShown = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 0, mCurrentUserId) != 0;
        final boolean skipTimeoutRestriction =
                Settings.Secure.getIntForUser(mContext.getContentResolver(),
                        Settings.Secure.SKIP_ACCESSIBILITY_SHORTCUT_DIALOG_TIMEOUT_RESTRICTION, 0,
                        mCurrentUserId) != 0;

        // If users manually set the volume key shortcut for any accessibility service, the
        // system would bypass the timeout restriction of the shortcut dialog.
        return hasDialogShown || skipTimeoutRestriction
                ? config.getAccessibilityShortcutKeyTimeoutAfterConfirmation()
                : config.getAccessibilityShortcutKeyTimeout();
    }

    private long getScreenshotChordLongPressDelay() {
        long delayMs = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_SYSTEMUI, SCREENSHOT_KEYCHORD_DELAY,
                ViewConfiguration.get(mContext).getScreenshotChordKeyTimeout());
        if (mKeyguardDelegate.isShowing()) {
            // Double the time it takes to take a screenshot from the keyguard
            return (long) (KEYGUARD_SCREENSHOT_CHORD_DELAY_MULTIPLIER * delayMs);
        }
        return delayMs;
    }

    private long getRingerToggleChordDelay() {
        // Always timeout like a tap
        return ViewConfiguration.getTapTimeout();
    }

    private void cancelPendingScreenshotChordAction() {
        mHandler.removeMessages(MSG_SCREENSHOT_CHORD);
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
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS,
                    "End Call - Long Press - Show Global Actions");
            showGlobalActionsInternal();
        }
    };

    private void handleScreenShot(@WindowManager.ScreenshotSource int source) {
        mDefaultDisplayPolicy.takeScreenshot(TAKE_SCREENSHOT_FULLSCREEN, source);
    }

    @Override
    public void showGlobalActions() {
        mHandler.removeMessages(MSG_DISPATCH_SHOW_GLOBAL_ACTIONS);
        mHandler.sendEmptyMessage(MSG_DISPATCH_SHOW_GLOBAL_ACTIONS);
    }

    void showGlobalActionsInternal() {
        if (mGlobalActions == null) {
            mGlobalActions = mGlobalActionsFactory.get();
        }
        final boolean keyguardShowing = isKeyguardShowingAndNotOccluded();
        mGlobalActions.showDialog(keyguardShowing, isDeviceProvisioned());
        // since it took two seconds of long press to bring this up,
        // poke the wake lock so they have some time to see the dialog.
        mPowerManager.userActivity(SystemClock.uptimeMillis(), false);
    }

    private void cancelGlobalActionsAction() {
        mHandler.removeMessages(MSG_DISPATCH_SHOW_GLOBAL_ACTIONS);
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
        } else if (mHasFeatureAuto) {
            isSetupComplete &= isAutoUserSetupComplete();
        }
        return isSetupComplete;
    }

    private boolean isAutoUserSetupComplete() {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                "android.car.SETUP_WIZARD_IN_PROGRESS", 0, UserHandle.USER_CURRENT) == 0;
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
        final DreamManagerInternal dreamManagerInternal = getDreamManagerInternal();
        if (dreamManagerInternal != null && dreamManagerInternal.isDreaming()) {
            mDreamManagerInternal.stopDream(false /*immediate*/, "short press on home" /*reason*/);
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
        if (mHasFeatureLeanback || mHasFeatureWatch) {
            // TV and watch support the all apps intent
            Intent intent = new Intent(Intent.ACTION_ALL_APPS);
            if (mHasFeatureLeanback) {
                Intent intentLauncher = new Intent(Intent.ACTION_MAIN);
                intentLauncher.addCategory(Intent.CATEGORY_HOME);
                ResolveInfo resolveInfo = mPackageManager.resolveActivityAsUser(intentLauncher,
                        PackageManager.MATCH_SYSTEM_ONLY,
                        mCurrentUserId);
                if (resolveInfo != null) {
                    intent.setPackage(resolveInfo.activityInfo.packageName);
                }
            }
            startActivityAsUser(intent, UserHandle.CURRENT);
        } else {
            AccessibilityManagerInternal accessibilityManager = getAccessibilityManagerInternal();
            if (accessibilityManager != null) {
                accessibilityManager.performSystemAction(
                        AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS);
            }
        }
        dismissKeyboardShortcutsMenu();
    }

    private void toggleNotificationPanel() {
        IStatusBarService statusBarService = getStatusBarService();
        if (isUserSetupComplete() && statusBarService != null) {
            try {
                statusBarService.togglePanel();
            } catch (RemoteException e) {
                // do nothing.
            }
        }
    }

    private void showSystemSettings() {
        startActivityAsUser(new Intent(android.provider.Settings.ACTION_SETTINGS),
                UserHandle.CURRENT_OR_SELF);
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
        private boolean mHomePressed;
        private boolean mHomeConsumed;
        private KeyEvent mPendingHomeKeyEvent;
        private final Runnable mHomeDoubleTapTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (mPendingHomeKeyEvent != null) {
                    notifyKeyGestureCompleted(mPendingHomeKeyEvent,
                            KeyGestureEvent.KEY_GESTURE_TYPE_HOME);
                    handleShortPressOnHome(mPendingHomeKeyEvent.getDisplayId());
                    mPendingHomeKeyEvent = null;
                }
            }
        };

        DisplayHomeButtonHandler(int displayId) {
            mDisplayId = displayId;
        }

        boolean handleHomeButton(IBinder focusedToken, KeyEvent event) {
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
                    return true;
                }

                if (canceled) {
                    Log.i(TAG, "Ignoring HOME; event canceled.");
                    return true;
                }

                // Delay handling home if a double-tap is possible.
                if (mDoubleTapOnHomeBehavior != DOUBLE_TAP_HOME_NOTHING) {
                    // For the picture-in-picture menu, only add the delay if a pip is there.
                    if (mDoubleTapOnHomeBehavior != DOUBLE_TAP_HOME_PIP_MENU
                            || mPictureInPictureVisible) {
                        mHandler.removeCallbacks(mHomeDoubleTapTimeoutRunnable); // just in case
                        mPendingHomeKeyEvent = event;
                        mHandler.postDelayed(mHomeDoubleTapTimeoutRunnable,
                                ViewConfiguration.getDoubleTapTimeout());
                        return true;
                    }
                }

                // Post to main thread to avoid blocking input pipeline.
                mHandler.post(() -> {
                    notifyKeyGestureCompleted(event, KeyGestureEvent.KEY_GESTURE_TYPE_HOME);
                    handleShortPressOnHome(event.getDisplayId());
                });
                return true;
            }

            final KeyInterceptionInfo info =
                    mWindowManagerInternal.getKeyInterceptionInfoFromToken(focusedToken);
            if (info != null) {
                // If a system window has focus, then it doesn't make sense
                // right now to interact with applications.
                if (info.layoutParamsType == TYPE_KEYGUARD_DIALOG
                        || (info.layoutParamsType == TYPE_NOTIFICATION_SHADE
                        && isKeyguardShowing())) {
                    // the "app" is keyguard, so give it the key
                    return false;
                }
                for (int t : WINDOW_TYPES_WHERE_HOME_DOESNT_WORK) {
                    if (info.layoutParamsType == t) {
                        // don't do anything, but also don't pass it to the app
                        return true;
                    }
                }
            }

            // Remember that home is pressed and handle special actions.
            if (repeatCount == 0) {
                mHomePressed = true;
                if (mPendingHomeKeyEvent != null) {
                    mPendingHomeKeyEvent = null;
                    mHandler.removeCallbacks(mHomeDoubleTapTimeoutRunnable);
                    mHandler.post(() -> handleDoubleTapOnHome(event));
                // TODO(multi-display): Remove display id check once we support recents on
                // multi-display
                } else if (mDoubleTapOnHomeBehavior == DOUBLE_TAP_HOME_RECENT_SYSTEM_UI
                        && mDisplayId == DEFAULT_DISPLAY) {
                    preloadRecentApps();
                }
            } else if ((event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0) {
                if (!keyguardOn) {
                    // Post to main thread to avoid blocking input pipeline.
                    mHandler.post(() -> handleLongPressOnHome(event));
                }
            }
            return true;
        }

        private void handleDoubleTapOnHome(KeyEvent event) {
            if (mHomeConsumed) {
                return;
            }
            switch (mDoubleTapOnHomeBehavior) {
                case DOUBLE_TAP_HOME_RECENT_SYSTEM_UI:
                    if (!isKeyEventForCurrentUser(
                            event.getDisplayId(), event.getKeyCode(), "toggleRecentApps")) {
                        break;
                    }
                    notifyKeyGestureCompleted(event,
                            KeyGestureEvent.KEY_GESTURE_TYPE_APP_SWITCH);
                    mHomeConsumed = true;
                    toggleRecentApps();
                    break;
                case DOUBLE_TAP_HOME_PIP_MENU:
                    if (!isKeyEventForCurrentUser(
                            event.getDisplayId(), event.getKeyCode(),
                            "showPictureInPictureMenu")) {
                        break;
                    }
                    mHomeConsumed = true;
                    showPictureInPictureMenuInternal();
                    break;
                default:
                    Log.w(TAG, "No action or undefined behavior for double tap home: "
                            + mDoubleTapOnHomeBehavior);
                    break;
            }
        }

        private void handleLongPressOnHome(KeyEvent event) {
            if (mHomeConsumed) {
                return;
            }
            if (mLongPressOnHomeBehavior == LONG_PRESS_HOME_NOTHING) {
                return;
            }
            mHomeConsumed = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, "Home - Long Press");
            switch (mLongPressOnHomeBehavior) {
                case LONG_PRESS_HOME_ALL_APPS:
                    notifyKeyGestureCompleted(event, KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS);
                    if (isKeyEventForCurrentUser(event.getDisplayId(), event.getKeyCode(),
                            "launchAllAppsViaA11y")) {
                        launchAllAppsAction();
                    }
                    break;
                case LONG_PRESS_HOME_ASSIST:
                    if (!isKeyEventForCurrentUser(
                            event.getDisplayId(), event.getKeyCode(), "launchAssistAction")) {
                        break;
                    }
                    notifyKeyGestureCompleted(event,
                            KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT);
                    launchAssistAction(null, event.getDeviceId(), event.getEventTime(),
                            AssistUtils.INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS);
                    break;
                case LONG_PRESS_HOME_NOTIFICATION_PANEL:
                    if (!isKeyEventForCurrentUser(
                            event.getDisplayId(), event.getKeyCode(), "toggleNotificationPanel")) {
                        break;
                    }
                    notifyKeyGestureCompleted(event,
                            KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL);
                    toggleNotificationPanel();
                    break;
                default:
                    Log.w(TAG, "Undefined long press on home behavior: "
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

    /** Point of injection for test dependencies. */
    @VisibleForTesting
    static class Injector {
        private final Context mContext;
        private final WindowManagerFuncs mWindowManagerFuncs;

        Injector(Context context, WindowManagerFuncs funcs) {
            mContext = context;
            mWindowManagerFuncs = funcs;
        }

        Context getContext() {
            return mContext;
        }

        WindowManagerFuncs getWindowManagerFuncs() {
            return mWindowManagerFuncs;
        }

        Looper getLooper() {
            return Looper.myLooper();
        }

        AccessibilityShortcutController getAccessibilityShortcutController(
                Context context, Handler handler, int initialUserId) {
            return new AccessibilityShortcutController(context, handler, initialUserId);
        }

        Supplier<GlobalActions> getGlobalActionsFactory() {
            return () -> new GlobalActions(mContext, mWindowManagerFuncs);
        }

        KeyguardServiceDelegate getKeyguardServiceDelegate() {
            return new KeyguardServiceDelegate(mContext,
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

        IActivityManager getActivityManagerService() {
            return ActivityManager.getService();
        }

        ButtonOverridePermissionChecker getButtonOverridePermissionChecker() {
            return new ButtonOverridePermissionChecker();
        }

        TalkbackShortcutController getTalkbackShortcutController() {
            return new TalkbackShortcutController(mContext);
        }

        WindowWakeUpPolicy getWindowWakeUpPolicy() {
            return new WindowWakeUpPolicy(mContext);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void init(Context context, WindowManagerFuncs funcs) {
        init(new Injector(context, funcs));
    }

    @VisibleForTesting
    void init(Injector injector) {
        mContext = injector.getContext();
        mWindowManagerFuncs = injector.getWindowManagerFuncs();
        mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mActivityManagerService = injector.getActivityManagerService();
        mActivityTaskManagerInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
        mInputManager = mContext.getSystemService(InputManager.class);
        mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);
        mDreamManagerInternal = LocalServices.getService(DreamManagerInternal.class);
        mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
        mAppOpsManager = mContext.getSystemService(AppOpsManager.class);
        mSensorPrivacyManager = mContext.getSystemService(SensorPrivacyManager.class);
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
        mPackageManager = mContext.getPackageManager();
        mHasFeatureWatch = mPackageManager.hasSystemFeature(FEATURE_WATCH);
        mHasFeatureLeanback = mPackageManager.hasSystemFeature(FEATURE_LEANBACK);
        mHasFeatureAuto = mPackageManager.hasSystemFeature(FEATURE_AUTOMOTIVE);
        mHasFeatureHdmiCec = mPackageManager.hasSystemFeature(FEATURE_HDMI_CEC);
        mAccessibilityShortcutController = injector.getAccessibilityShortcutController(
                mContext, new Handler(), mCurrentUserId);
        mGlobalActionsFactory = injector.getGlobalActionsFactory();
        mLockPatternUtils = new LockPatternUtils(mContext);
        mLogger = new MetricsLogger();

        Resources res = mContext.getResources();
        mWakeOnDpadKeyPress =
                res.getBoolean(com.android.internal.R.bool.config_wakeOnDpadKeyPress);
        mWakeOnAssistKeyPress =
                res.getBoolean(com.android.internal.R.bool.config_wakeOnAssistKeyPress);
        mWakeOnBackKeyPress =
                res.getBoolean(com.android.internal.R.bool.config_wakeOnBackKeyPress);

        // Init display burn-in protection
        boolean burnInProtectionEnabled = mContext.getResources().getBoolean(
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
                Resources resources = mContext.getResources();
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
                    mContext, minHorizontal, maxHorizontal, minVertical, maxVertical, maxRadius);
        }

        mHandler = new PolicyHandler(injector.getLooper());
        mWakeGestureListener = new MyWakeGestureListener(mContext, mHandler);
        mSettingsObserver = new SettingsObserver(mHandler);
        mSettingsObserver.observe();
        mModifierShortcutManager = new ModifierShortcutManager(
                mContext, mHandler, UserHandle.of(mCurrentUserId));
        mUiMode = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultUiModeType);
        mHomeIntent =  new Intent(Intent.ACTION_MAIN, null);
        mHomeIntent.addCategory(Intent.CATEGORY_HOME);
        mHomeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        mEnableCarDockHomeCapture = mContext.getResources().getBoolean(
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

        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mBroadcastWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "PhoneWindowManager.mBroadcastWakeLock");
        mPowerKeyWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "PhoneWindowManager.mPowerKeyWakeLock");
        mEnableBugReportKeyboardShortcut = "1".equals(SystemProperties.get("ro.debuggable"));
        mLidKeyboardAccessibility = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lidKeyboardAccessibility);
        mLidNavigationAccessibility = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lidNavigationAccessibility);

        mGoToSleepOnButtonPressTheaterMode = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_goToSleepOnButtonPressTheaterMode);

        mSupportLongPressPowerWhenNonInteractive = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_supportLongPressPowerWhenNonInteractive);
        mSupportShortPressPowerWhenDefaultDisplayOn =
                mContext.getResources()
                        .getBoolean(
                                com.android.internal.R.bool
                                        .config_supportShortPressPowerWhenDefaultDisplayOn);

        mLongPressOnBackBehavior = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_longPressOnBackBehavior);

        mLongPressOnPowerBehavior = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_longPressOnPowerBehavior);
        mLongPressOnPowerAssistantTimeoutMs = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_longPressOnPowerDurationMs);
        mVeryLongPressOnPowerBehavior = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_veryLongPressOnPowerBehavior);
        mPowerDoublePressTargetActivity = ComponentName.unflattenFromString(
            mContext.getResources().getString(
                com.android.internal.R.string.config_doublePressOnPowerTargetActivity));
        mPrimaryShortPressTargetActivity = ComponentName.unflattenFromString(
            mContext.getResources().getString(
                com.android.internal.R.string.config_primaryShortPressTargetActivity));
        mShortPressOnSleepBehavior = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_shortPressOnSleepBehavior);
        mSilenceRingerOnSleepKey = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_silenceRingerOnSleepKey);
        mAllowStartActivityForLongPressOnPowerDuringSetup = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_allowStartActivityForLongPressOnPowerInSetup);

        mUseTvRouting = AudioSystem.getPlatformType(mContext) == AudioSystem.PLATFORM_TELEVISION;

        mHandleVolumeKeysInWM = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_handleVolumeKeysInWindowManager);

        mWakeUpToLastStateTimeout = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_wakeUpToLastStateTimeoutMillis);

        mSearchKeyBehavior = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_searchKeyBehavior);

        mSearchKeyTargetActivity = ComponentName.unflattenFromString(
            mContext.getResources().getString(
                com.android.internal.R.string.config_searchKeyTargetActivity));
        readConfigurationDependentBehaviors();

        mDisplayFoldController = DisplayFoldController.create(mContext, DEFAULT_DISPLAY);

        mAccessibilityManager = mContext.getSystemService(AccessibilityManager.class);

        // register for dock events
        IntentFilter filter = new IntentFilter();
        filter.addAction(UiModeManager.ACTION_ENTER_CAR_MODE);
        filter.addAction(UiModeManager.ACTION_EXIT_CAR_MODE);
        filter.addAction(UiModeManager.ACTION_ENTER_DESK_MODE);
        filter.addAction(UiModeManager.ACTION_EXIT_DESK_MODE);
        filter.addAction(Intent.ACTION_DOCK_EVENT);
        Intent intent = mContext.registerReceiver(mDockReceiver, filter);
        if (intent != null) {
            // Retrieve current sticky dock event broadcast.
            mDefaultDisplayPolicy.setDockMode(intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                    Intent.EXTRA_DOCK_STATE_UNDOCKED));
        }

        // register for multiuser-relevant broadcasts
        filter = new IntentFilter(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiver(mMultiuserReceiver, filter);

        mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);

        mGlobalKeyManager = new GlobalKeyManager(mContext);

        // Controls rotation and the like.
        initializeHdmiState();

        // Match current screen state.
        if (!mPowerManager.isInteractive()) {
            startedGoingToSleep(Display.DEFAULT_DISPLAY_GROUP,
                    PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
            finishedGoingToSleep(Display.DEFAULT_DISPLAY_GROUP,
                    PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
        }

        final var transitionListener = new AppTransitionListener(DEFAULT_DISPLAY) {
            @Override
            public int onAppTransitionStartingLocked(long statusBarAnimationStartTime,
                    long statusBarAnimationDuration) {
                return handleTransitionForKeyguardLw(false /* startKeyguardExitAnimation */,
                        false /* notifyOccluded */);
            }

            @Override
            public void onAppTransitionCancelledLocked(boolean keyguardGoingAwayCancelled) {
                // When KEYGUARD_GOING_AWAY app transition is canceled, we need to trigger relevant
                // IKeyguardService calls to sync keyguard status in WindowManagerService and SysUI.
                handleTransitionForKeyguardLw(
                        keyguardGoingAwayCancelled /* startKeyguardExitAnimation */,
                        true /* notifyOccluded */);

                synchronized (mLock) {
                    mLockAfterDreamingTransitionFinished = false;
                }
            }

            @Override
            public void onAppTransitionFinishedLocked(IBinder token) {
                synchronized (mLock) {
                    final DreamManagerInternal dreamManagerInternal = getDreamManagerInternal();
                    // check both isDreaming and mLockAfterDreamingTransitionFinished before lockNow
                    // so it won't relock after dreaming has stopped
                    if (dreamManagerInternal != null && dreamManagerInternal.isDreaming()
                            && mLockAfterDreamingTransitionFinished) {
                        lockNow(null);
                    }
                    mLockAfterDreamingTransitionFinished = false;
                }
            }
        };
        mWindowManagerInternal.registerAppTransitionListener(transitionListener);

        mKeyguardDrawnTimeout = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_keyguardDrawnTimeout);
        mKeyguardDelegate = injector.getKeyguardServiceDelegate();
        mTalkbackShortcutController = injector.getTalkbackShortcutController();
        mWindowWakeUpPolicy = injector.getWindowWakeUpPolicy();
        initKeyCombinationRules();
        initSingleKeyGestureRules(injector.getLooper());
        initKeyGestures();
        mButtonOverridePermissionChecker = injector.getButtonOverridePermissionChecker();
        mSideFpsEventHandler = new SideFpsEventHandler(mContext, mHandler, mPowerManager);
    }

    private void initKeyCombinationRules() {
        mKeyCombinationManager = new KeyCombinationManager(mHandler);
        if (useKeyGestureEventHandler() && useKeyGestureEventHandlerMultiPressGestures()) {
            return;
        }
        final boolean screenshotChordEnabled = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_enableScreenshotChord);

        if (screenshotChordEnabled) {
            mKeyCombinationManager.addRule(
                    new TwoKeysCombinationRule(KEYCODE_VOLUME_DOWN, KEYCODE_POWER) {
                        @Override
                        public void execute() {
                            mPowerKeyHandled = true;
                            interceptScreenshotChord(
                                    SCREENSHOT_KEY_CHORD, getScreenshotChordLongPressDelay());
                        }
                        @Override
                        public void cancel() {
                            cancelPendingScreenshotChordAction();
                        }
                    });

            if (mHasFeatureWatch) {
                mKeyCombinationManager.addRule(
                        new TwoKeysCombinationRule(KEYCODE_POWER, KEYCODE_STEM_PRIMARY) {
                            @Override
                            public void execute() {
                                mPowerKeyHandled = true;
                                interceptScreenshotChord(SCREENSHOT_KEY_CHORD,
                                        getScreenshotChordLongPressDelay());
                            }
                            @Override
                            public void cancel() {
                                cancelPendingScreenshotChordAction();
                            }
                        });
            }
        }

        mKeyCombinationManager.addRule(
                new TwoKeysCombinationRule(KEYCODE_VOLUME_DOWN, KEYCODE_VOLUME_UP) {
                    @Override
                    public boolean preCondition() {
                        return mAccessibilityShortcutController
                                .isAccessibilityShortcutAvailable(isKeyguardLocked());
                    }
                    @Override
                    public void execute() {
                        interceptAccessibilityShortcutChord();
                    }
                    @Override
                    public void cancel() {
                        cancelPendingAccessibilityShortcutAction();
                    }
                });

        // Volume up + power can either be the "ringer toggle chord" or as another way to
        // launch GlobalActions. This behavior can change at runtime so we must check behavior
        // inside the TwoKeysCombinationRule.
        mKeyCombinationManager.addRule(
                new TwoKeysCombinationRule(KEYCODE_VOLUME_UP, KEYCODE_POWER) {
                    @Override
                    public boolean preCondition() {
                        switch (mPowerVolUpBehavior) {
                            case POWER_VOLUME_UP_BEHAVIOR_MUTE:
                                return mRingerToggleChord != VOLUME_HUSH_OFF;
                            default:
                                return true;
                        }
                    }
                    @Override
                    public void execute() {
                        switch (mPowerVolUpBehavior) {
                            case POWER_VOLUME_UP_BEHAVIOR_MUTE:
                                // no haptic feedback here since
                                interceptRingerToggleChord();
                                mPowerKeyHandled = true;
                                break;
                            case POWER_VOLUME_UP_BEHAVIOR_GLOBAL_ACTIONS:
                                performHapticFeedback(
                                        HapticFeedbackConstants.LONG_PRESS_POWER_BUTTON,
                                        "Power + Volume Up - Global Actions");
                                showGlobalActions();
                                mPowerKeyHandled = true;
                                break;
                            default:
                                break;
                        }
                    }
                    @Override
                    public void cancel() {
                        switch (mPowerVolUpBehavior) {
                            case POWER_VOLUME_UP_BEHAVIOR_MUTE:
                                cancelPendingRingerToggleChordAction();
                                break;
                            case POWER_VOLUME_UP_BEHAVIOR_GLOBAL_ACTIONS:
                                cancelGlobalActionsAction();
                                break;
                        }
                    }
                });

        if (mHasFeatureLeanback) {
            mKeyCombinationManager.addRule(
                    new TwoKeysCombinationRule(KEYCODE_BACK, KEYCODE_DPAD_DOWN) {
                        @Override
                        public void execute() {
                            mBackKeyHandled = true;
                            interceptAccessibilityGestureTv();
                        }
                        @Override
                        public void cancel() {
                            cancelAccessibilityGestureTv();
                        }
                        @Override
                        public long getKeyInterceptDelayMs() {
                            // Use a timeout of 0 to prevent additional latency in processing of
                            // this key. This will potentially cause some unwanted UI actions if the
                            // user does end up triggering the key combination later, but in most
                            // cases, the user will simply hit a single key, and this will allow us
                            // to process it without first waiting to see if the combination is
                            // going to be triggered.
                            return 0;
                        }
                    });

            mKeyCombinationManager.addRule(
                    new TwoKeysCombinationRule(KEYCODE_DPAD_CENTER, KEYCODE_BACK) {
                        @Override
                        public void execute() {
                            mBackKeyHandled = true;
                            interceptBugreportGestureTv();
                        }
                        @Override
                        public void cancel() {
                            cancelBugreportGestureTv();
                        }
                        @Override
                        public long getKeyInterceptDelayMs() {
                            return 0;
                        }
                    });
        }
    }

    /**
     * Rule for single power key gesture.
     */
    private final class PowerKeyRule extends SingleKeyGestureDetector.SingleKeyRule {
        PowerKeyRule() {
            super(KEYCODE_POWER);
        }

        @Override
        boolean supportLongPress() {
            return hasLongPressOnPowerBehavior();
        }

        @Override
        boolean supportVeryLongPress() {
            return hasVeryLongPressOnPowerBehavior();
        }


        @Override
        int getMaxMultiPressCount() {
            return getMaxMultiPressPowerCount();
        }

        @Override
        void onPress(long downTime, int displayId) {
            if (mShouldEarlyShortPressOnPower) {
                return;
            }
            powerPress(downTime, 1 /*count*/, displayId);
        }

        @Override
        long getLongPressTimeoutMs() {
            if (getResolvedLongPressOnPowerBehavior() == LONG_PRESS_POWER_ASSISTANT) {
                return mLongPressOnPowerAssistantTimeoutMs;
            } else {
                return super.getLongPressTimeoutMs();
            }
        }

        @Override
        void onLongPress(long eventTime) {
            if (mSingleKeyGestureDetector.beganFromNonInteractive()
                    && !mSupportLongPressPowerWhenNonInteractive) {
                Slog.v(TAG, "Not support long press power when device is not interactive.");
                return;
            }

            powerLongPress(eventTime);
        }

        @Override
        void onVeryLongPress(long eventTime) {
            mActivityManagerInternal.prepareForPossibleShutdown();
            powerVeryLongPress();
        }

        @Override
        void onMultiPress(long downTime, int count, int displayId) {
            powerPress(downTime, count, displayId);
        }

        @Override
        void onKeyUp(long eventTime, int count, int displayId, int deviceId, int metaState) {
            if (mShouldEarlyShortPressOnPower && count == 1) {
                powerPress(eventTime, 1 /*pressCount*/, displayId);
            }
        }
    }

    /**
     * Rule for single back key gesture.
     */
    private final class BackKeyRule extends SingleKeyGestureDetector.SingleKeyRule {
        BackKeyRule() {
            super(KEYCODE_BACK);
        }

        @Override
        boolean supportLongPress() {
            return hasLongPressOnBackBehavior();
        }

        @Override
        int getMaxMultiPressCount() {
            return 1;
        }

        @Override
        void onPress(long downTime, int unusedDisplayId) {
            mBackKeyHandled |= backKeyPress();
        }

        @Override
        void onLongPress(long downTime) {
            backLongPress();
        }
    }

    /**
     * Rule for single stem primary key gesture.
     */
    private final class StemPrimaryKeyRule extends SingleKeyGestureDetector.SingleKeyRule {
        StemPrimaryKeyRule() {
            super(KeyEvent.KEYCODE_STEM_PRIMARY);
        }

        @Override
        boolean supportLongPress() {
            return hasLongPressOnStemPrimaryBehavior();
        }

        @Override
        int getMaxMultiPressCount() {
            return getMaxMultiPressStemPrimaryCount();
        }

        @Override
        void onPress(long downTime, int unusedDisplayId) {
            if (shouldHandleStemPrimaryEarlyShortPress()) {
                return;
            }
            // Short-press should be triggered only if app doesn't handle it.
            mDeferredKeyActionExecutor.queueKeyAction(
                    KeyEvent.KEYCODE_STEM_PRIMARY, downTime, () -> stemPrimaryPress(1 /*count*/));
        }

        @Override
        void onLongPress(long eventTime) {
            if (mLongPressOnStemPrimaryBehavior == LONG_PRESS_PRIMARY_LAUNCH_VOICE_ASSISTANT) {
                // Long-press to assistant gesture is not overridable by apps.
                stemPrimaryLongPress(eventTime);
            } else {
                // Other long-press actions should be triggered only if app doesn't handle it.
                mDeferredKeyActionExecutor.queueKeyAction(
                        KeyEvent.KEYCODE_STEM_PRIMARY,
                        eventTime,
                        () -> stemPrimaryLongPress(eventTime));
            }
        }

        @Override
        void onMultiPress(long downTime, int count, int unusedDisplayId) {
            // Triple-press stem to toggle accessibility gesture should always be triggered
            // regardless of if app handles it.
            if (count == 3
                    && mTriplePressOnStemPrimaryBehavior
                    == TRIPLE_PRESS_PRIMARY_TOGGLE_ACCESSIBILITY) {
                // Cancel any queued actions for current key code to prevent them from being
                // launched after a11y layer enabled. If the action happens early,
                // undoEarlySinglePress will make sure the correct task is on top.
                mDeferredKeyActionExecutor.cancelQueuedAction(KeyEvent.KEYCODE_STEM_PRIMARY);
                undoEarlySinglePress();
                stemPrimaryPress(count);
            } else {
                // Other multi-press gestures should be triggered only if app doesn't handle it.
                mDeferredKeyActionExecutor.queueKeyAction(
                        KeyEvent.KEYCODE_STEM_PRIMARY, downTime, () -> stemPrimaryPress(count));
            }
        }

        /**
         * This method undo the previously launched early-single-press action by bringing the
         * focused task before launching early-single-press back to top.
         */
        private void undoEarlySinglePress() {
            if (shouldHandleStemPrimaryEarlyShortPress()
                    && mFocusedTaskInfoOnStemPrimarySingleKeyUp != null) {
                try {
                    mActivityManagerService.startActivityFromRecents(
                            mFocusedTaskInfoOnStemPrimarySingleKeyUp.taskId, null);
                } catch (RemoteException | IllegalArgumentException e) {
                    Slog.e(
                            TAG,
                            "Failed to start task "
                                    + mFocusedTaskInfoOnStemPrimarySingleKeyUp.taskId
                                    + " from recents",
                            e);
                }
            }
        }

        @Override
        void onKeyUp(long eventTime, int count, int displayId, int deviceId, int metaState) {
            if (count == 1) {
                // Save info about the most recent task on the first press of the stem key. This
                // may be used later to switch to the most recent app using double press gesture.
                // It is possible that we may navigate away from this task before the double
                // press is detected, as a result of the first press, so we save the  current
                // most recent task before that happens.
                // TODO(b/311497918): guard this with DOUBLE_PRESS_PRIMARY_SWITCH_RECENT_APP
                mBackgroundRecentTaskInfoOnStemPrimarySingleKeyUp =
                        mActivityTaskManagerInternal.getMostRecentTaskFromBackground();

                mFocusedTaskInfoOnStemPrimarySingleKeyUp = null;

                if (shouldHandleStemPrimaryEarlyShortPress()) {
                    // Key-up gesture should be triggered only if app doesn't handle it.
                    mDeferredKeyActionExecutor.queueKeyAction(
                            KeyEvent.KEYCODE_STEM_PRIMARY,
                            eventTime,
                            () -> {
                                Slog.d(TAG, "StemPrimaryKeyRule: executing deferred onKeyUp");
                                // Save the info of the focused task on screen. This may be used
                                // later to bring the current focused task back to top. For
                                // example, stem primary triple press enables the A11y interface
                                // on top of the current focused task. When early single press is
                                // enabled for stem primary, the focused task could change to
                                // something else upon first key up event. In that case, we will
                                // bring the task recorded by this variable back to top. Then, start
                                // A11y interface.
                                try {
                                    mFocusedTaskInfoOnStemPrimarySingleKeyUp =
                                            mActivityManagerService.getFocusedRootTaskInfo();
                                } catch (RemoteException e) {
                                    Slog.e(
                                            TAG,
                                            "StemPrimaryKeyRule: onKeyUp: error while getting "
                                                    + "focused task "
                                                    + "info.",
                                            e);
                                }

                                stemPrimaryPress(1);
                            });
                }
            }
        }

        // TODO(b/311497918): make a shouldHandlePowerEarlyShortPress for power button.
        private boolean shouldHandleStemPrimaryEarlyShortPress() {
            return mShouldEarlyShortPressOnStemPrimary
                    && mShortPressOnStemPrimaryBehavior == SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS;
        }
    }

    // TODO(b/358569822): Move to KeyGestureController.
    private final class StylusTailButtonRule extends SingleKeyGestureDetector.SingleKeyRule {
        StylusTailButtonRule() {
            super(KEYCODE_STYLUS_BUTTON_TAIL);
        }

        @Override
        int getMaxMultiPressCount() {
            return 2;
        }

        @Override
        void onPress(long downTime, int displayId) {

        }

        @Override
        void onKeyUp(long eventTime, int pressCount, int displayId, int deviceId, int metaState) {
            if (pressCount != 1) {
                return;
            }
            // Single press on tail button triggers the open notes gesture.
            handleKeyGestureInKeyGestureController(KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_NOTES,
                    deviceId, KEYCODE_STYLUS_BUTTON_TAIL, metaState);
        }
    }

    private void initSingleKeyGestureRules(Looper looper) {
        mSingleKeyGestureDetector = SingleKeyGestureDetector.get(mContext, looper);
        mSingleKeyGestureDetector.addRule(new PowerKeyRule());
        if (hasLongPressOnBackBehavior()) {
            mSingleKeyGestureDetector.addRule(new BackKeyRule());
        }
        if (hasStemPrimaryBehavior()) {
            mSingleKeyGestureDetector.addRule(new StemPrimaryKeyRule());
        }
        mSingleKeyGestureDetector.addRule(new StylusTailButtonRule());
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
                mDoubleTapOnHomeBehavior > DOUBLE_TAP_HOME_PIP_MENU) {
            mDoubleTapOnHomeBehavior = DOUBLE_TAP_HOME_NOTHING;
        }

        mShortPressOnWindowBehavior = SHORT_PRESS_WINDOW_NOTHING;
        if (mPackageManager.hasSystemFeature(FEATURE_PICTURE_IN_PICTURE)) {
            mShortPressOnWindowBehavior = SHORT_PRESS_WINDOW_PICTURE_IN_PICTURE;
        }

        mSettingsKeyBehavior = res.getInteger(
                com.android.internal.R.integer.config_settingsKeyBehavior);
        if (mSettingsKeyBehavior < SETTINGS_KEY_BEHAVIOR_SETTINGS_ACTIVITY
                || mSettingsKeyBehavior > LAST_SETTINGS_KEY_BEHAVIOR) {
            mSettingsKeyBehavior = SETTINGS_KEY_BEHAVIOR_SETTINGS_ACTIVITY;
        }
    }

    private void updateSettings() {
        updateSettings(null);
    }

    /**
     * Update provider Setting values on a given {@code handler}, or synchronously if {@code null}
     * is passed for handler.
     */
    void updateSettings(Handler handler) {
        if (handler != null) {
            handler.post(() -> updateSettings(null));
            return;
        }
        ContentResolver resolver = mContext.getContentResolver();
        boolean updateRotation = false;
        boolean updateKidsModeSettings = false;
        final boolean kidsModeEnabled;
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
                mRingerToggleChord = VOLUME_HUSH_OFF;
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

            mShortPressOnPowerBehavior = Settings.Global.getInt(resolver,
                    Settings.Global.POWER_BUTTON_SHORT_PRESS,
                    mContext.getResources().getInteger(
                            com.android.internal.R.integer.config_shortPressOnPowerBehavior));
            mDoublePressOnPowerBehavior = Settings.Global.getInt(resolver,
                    Settings.Global.POWER_BUTTON_DOUBLE_PRESS,
                    mContext.getResources().getInteger(
                            com.android.internal.R.integer.config_doublePressOnPowerBehavior));
            mTriplePressOnPowerBehavior = Settings.Global.getInt(resolver,
                    Settings.Global.POWER_BUTTON_TRIPLE_PRESS,
                    mContext.getResources().getInteger(
                            com.android.internal.R.integer.config_triplePressOnPowerBehavior));

            final int longPressOnPowerBehavior = Settings.Global.getInt(resolver,
                    Settings.Global.POWER_BUTTON_LONG_PRESS,
                    mContext.getResources().getInteger(
                            com.android.internal.R.integer.config_longPressOnPowerBehavior));
            final int veryLongPressOnPowerBehavior = Settings.Global.getInt(resolver,
                    Settings.Global.POWER_BUTTON_VERY_LONG_PRESS,
                    mContext.getResources().getInteger(
                            com.android.internal.R.integer.config_veryLongPressOnPowerBehavior));
            if (mLongPressOnPowerBehavior != longPressOnPowerBehavior
                    || mVeryLongPressOnPowerBehavior != veryLongPressOnPowerBehavior) {
                mLongPressOnPowerBehavior = longPressOnPowerBehavior;
                mVeryLongPressOnPowerBehavior = veryLongPressOnPowerBehavior;
            }

            mLongPressOnPowerAssistantTimeoutMs = Settings.Global.getLong(
                    mContext.getContentResolver(),
                    Settings.Global.POWER_BUTTON_LONG_PRESS_DURATION_MS,
                    mContext.getResources().getInteger(
                            com.android.internal.R.integer.config_longPressOnPowerDurationMs));

            mPowerVolUpBehavior = Settings.Global.getInt(resolver,
                    Settings.Global.KEY_CHORD_POWER_VOLUME_UP,
                    mContext.getResources().getInteger(
                            com.android.internal.R.integer.config_keyChordPowerVolumeUp));

            mShortPressOnStemPrimaryBehavior = Settings.Global.getInt(resolver,
                    Settings.Global.STEM_PRIMARY_BUTTON_SHORT_PRESS,
                    mContext.getResources().getInteger(
                            com.android.internal.R.integer.config_shortPressOnStemPrimaryBehavior));
            mDoublePressOnStemPrimaryBehavior = Settings.Global.getInt(resolver,
                    Settings.Global.STEM_PRIMARY_BUTTON_DOUBLE_PRESS,
                    mContext.getResources().getInteger(
                            com.android.internal.R.integer
                                    .config_doublePressOnStemPrimaryBehavior));
            mTriplePressOnStemPrimaryBehavior = Settings.Global.getInt(resolver,
                    Settings.Global.STEM_PRIMARY_BUTTON_TRIPLE_PRESS,
                    mContext.getResources().getInteger(
                            com.android.internal.R.integer
                                    .config_triplePressOnStemPrimaryBehavior));
            mLongPressOnStemPrimaryBehavior = Settings.Global.getInt(resolver,
                    Settings.Global.STEM_PRIMARY_BUTTON_LONG_PRESS,
                    mContext.getResources().getInteger(
                            com.android.internal.R.integer.config_longPressOnStemPrimaryBehavior));
            mShouldEarlyShortPressOnPower =
                    mContext.getResources()
                            .getBoolean(com.android.internal.R.bool.config_shortPressEarlyOnPower);
            mShouldEarlyShortPressOnStemPrimary = mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_shortPressEarlyOnStemPrimary);

            mStylusButtonsEnabled = Settings.Secure.getIntForUser(resolver,
                    Secure.STYLUS_BUTTONS_ENABLED, 1, UserHandle.USER_CURRENT) == 1;
            mInputManagerInternal.setStylusButtonMotionEventsEnabled(mStylusButtonsEnabled);

            kidsModeEnabled = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.NAV_BAR_KIDS_MODE, 0, UserHandle.USER_CURRENT) == 1;
            if (mKidsModeEnabled != kidsModeEnabled) {
                mKidsModeEnabled = kidsModeEnabled;
                updateKidsModeSettings = true;
            }
        }
        if (updateKidsModeSettings) {
            updateKidsModeSettings(kidsModeEnabled);
        }
        if (updateRotation) {
            updateRotation(true);
        }
    }

    private void updateKidsModeSettings(boolean kidsModeEnabled) {
        if (kidsModeEnabled) {
            // Needed since many Kids apps aren't optimised to support both orientations and it
            // will be hard for kids to understand the app compat mode.
            // TODO(229961548): Remove ignoreOrientationRequest exception for Kids Mode once
            //                  possible.
            if (mContext.getResources().getBoolean(R.bool.config_reverseDefaultRotation)) {
                mWindowManagerInternal.setOrientationRequestPolicy(
                        true /* isIgnoreOrientationRequestDisabled */,
                        new int[]{SCREEN_ORIENTATION_LANDSCAPE,
                                SCREEN_ORIENTATION_REVERSE_LANDSCAPE},
                        new int[]{SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                                SCREEN_ORIENTATION_SENSOR_LANDSCAPE});
            } else {
                mWindowManagerInternal.setOrientationRequestPolicy(
                        true /* isIgnoreOrientationRequestDisabled */,
                        null /* fromOrientations */, null /* toOrientations */);
            }
        } else {
            mWindowManagerInternal.setOrientationRequestPolicy(
                    false /* isIgnoreOrientationRequestDisabled */,
                    null /* fromOrientations */, null /* toOrientations */);
        }
    }

    private DreamManagerInternal getDreamManagerInternal() {
        if (mDreamManagerInternal == null) {
            // If mDreamManagerInternal is null, attempt to re-fetch it.
            mDreamManagerInternal = LocalServices.getService(DreamManagerInternal.class);
        }

        return mDreamManagerInternal;
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
    public int checkAddPermission(int type, boolean isRoundedCornerOverlay, String packageName,
            int[] outAppOp, int displayId) {
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
                case TYPE_ACCESSIBILITY_OVERLAY:
                    if (createAccessibilityOverlayAppOpEnabled()) {
                        outAppOp[0] = OP_CREATE_ACCESSIBILITY_OVERLAY;
                        return ADD_OKAY;
                    }
                case TYPE_INPUT_METHOD:
                case TYPE_WALLPAPER:
                case TYPE_PRESENTATION:
                case TYPE_PRIVATE_PRESENTATION:
                case TYPE_VOICE_INTERACTION:
                case TYPE_QS_DIALOG:
                case TYPE_NAVIGATION_BAR_PANEL:
                case TYPE_STATUS_BAR:
                case TYPE_NOTIFICATION_SHADE:
                case TYPE_NAVIGATION_BAR:
                case TYPE_STATUS_BAR_ADDITIONAL:
                case TYPE_STATUS_BAR_SUB_PANEL:
                case TYPE_VOICE_INTERACTION_STARTING:
                    // The window manager will check these.
                    return ADD_OKAY;
            }

            return (mContext.checkCallingOrSelfPermission(INTERNAL_SYSTEM_WINDOW)
                    == PERMISSION_GRANTED) ? ADD_OKAY : ADD_PERMISSION_DENIED;
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
            appInfo = mPackageManager.getApplicationInfoAsUser(
                            packageName,
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

        if (mContext.checkCallingOrSelfPermission(SYSTEM_APPLICATION_OVERLAY)
                == PERMISSION_GRANTED) {
            return ADD_OKAY;
        }

        // Allow virtual device owners to add overlays on the trusted displays they own.
        if (mWindowManagerFuncs.isCallerVirtualDeviceOwner(displayId, callingUid)
                && mWindowManagerFuncs.isDisplayTrusted(displayId)
                && mContext.checkCallingOrSelfPermission(CREATE_VIRTUAL_DEVICE)
                == PERMISSION_GRANTED) {
            return ADD_OKAY;
        }

        // check if user has enabled this operation. SecurityException will be thrown if this app
        // has not been allowed by the user. The reason to use "noteOp" (instead of checkOp) is to
        // make sure the usage is logged.
        final int mode = mAppOpsManager.noteOpNoThrow(outAppOp[0], callingUid, packageName,
                null /* featureId */, "check-add");
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
    public boolean isKeyguardHostWindow(WindowManager.LayoutParams attrs) {
        return attrs.type == TYPE_NOTIFICATION_SHADE;
    }

    @Override
    public Animation createHiddenByKeyguardExit(boolean onWallpaper,
            boolean goingToNotificationShade, boolean subtleAnimation) {
        return TransitionAnimation.createHiddenByKeyguardExit(mContext,
                mLogDecelerateInterpolator, onWallpaper, goingToNotificationShade, subtleAnimation);
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

    NotificationManager getNotificationService() {
        return mContext.getSystemService(NotificationManager.class);
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

    private void notifyKeyGestureCompletedOnActionUp(KeyEvent event,
            @KeyGestureEvent.KeyGestureType int gestureType) {
        if (event.getAction() != KeyEvent.ACTION_UP) {
            return;
        }
        notifyKeyGestureCompleted(event, gestureType);
    }

    private void notifyKeyGestureCompletedOnActionDown(KeyEvent event,
            @KeyGestureEvent.KeyGestureType int gestureType) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return;
        }
        notifyKeyGestureCompleted(event, gestureType);
    }

    private void notifyKeyGestureCompleted(KeyEvent event,
            @KeyGestureEvent.KeyGestureType int gestureType) {
        if (gestureType == KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED) {
            return;
        }
        mInputManagerInternal.notifyKeyGestureCompleted(event.getDeviceId(),
                new int[]{event.getKeyCode()}, event.getMetaState(), gestureType);
    }

    private void handleKeyGestureInKeyGestureController(
            @KeyGestureEvent.KeyGestureType int gestureType, int deviceId, int keyCode,
            int metaState) {
        if (gestureType == KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED) {
            return;
        }
        mInputManagerInternal.handleKeyGestureInKeyGestureController(deviceId, new int[]{keyCode},
                metaState, gestureType);
    }

    @Override
    public KeyboardShortcutGroup getApplicationLaunchKeyboardShortcuts(int deviceId) {
        return mModifierShortcutManager.getApplicationLaunchKeyboardShortcuts(deviceId);
    }

    // TODO(b/117479243): handle it in InputPolicy
    // TODO (b/283241997): Add the remaining keyboard shortcut logging after refactoring
    /** {@inheritDoc} */
    @Override
    public long interceptKeyBeforeDispatching(IBinder focusedToken, KeyEvent event,
            int policyFlags) {
        final int keyCode = event.getKeyCode();
        final int flags = event.getFlags();
        final long keyConsumed = -1;
        final long keyNotConsumed = 0;
        final int deviceId = event.getDeviceId();

        if (DEBUG_INPUT) {
            Log.d(TAG,
                    "interceptKeyTi keyCode=" + keyCode + " action=" + event.getAction()
                            + " repeatCount=" + event.getRepeatCount() + " keyguardOn="
                            + keyguardOn() + " canceled=" + event.isCanceled());
        }

        if (!useKeyGestureEventHandler()) {
            if (mKeyCombinationManager.isKeyConsumed(event)) {
                return keyConsumed;
            }

            if ((flags & KeyEvent.FLAG_FALLBACK) == 0) {
                final long now = SystemClock.uptimeMillis();
                final long interceptTimeout = mKeyCombinationManager.getKeyInterceptTimeout(
                        keyCode);
                if (now < interceptTimeout) {
                    return interceptTimeout - now;
                }
            }
        }

        Set<Integer> consumedKeys = mConsumedKeysForDevice.get(deviceId);
        if (consumedKeys == null) {
            consumedKeys = new HashSet<>();
            mConsumedKeysForDevice.put(deviceId, consumedKeys);
        }

        if (interceptSystemKeysAndShortcuts(focusedToken, event)
                && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            consumedKeys.add(keyCode);
            return keyConsumed;
        }

        boolean needToConsumeKey = consumedKeys.contains(keyCode);
        if (event.getAction() == KeyEvent.ACTION_UP || event.isCanceled()) {
            consumedKeys.remove(keyCode);
            if (consumedKeys.isEmpty()) {
                mConsumedKeysForDevice.remove(deviceId);
            }
        }

        return needToConsumeKey ? keyConsumed : keyNotConsumed;
    }

    // You can only start consuming the key gesture if ACTION_DOWN and repeat count
    // is 0. If you start intercepting the key halfway, then key will not be consumed
    // and will be sent to apps for processing too.
    // e.g. If a certain combination is only handled on ACTION_UP (i.e.
    // interceptShortcut() returns true only for ACTION_UP), then since we already
    // sent the ACTION_DOWN events to the application, we MUST also send the
    // ACTION_UP to the application.
    // So, to ensure that your intercept logic works properly, and we don't send any
    // conflicting events to application, make sure to consume the event on
    // ACTION_DOWN even if you want to do something on ACTION_UP. This is essential
    // to maintain event parity and to not have incomplete key gestures.
    //
    // NOTE: Please try not to add new Shortcut combinations here and instead use KeyGestureEvents.
    // Add shortcut trigger logic in {@code KeyGestureController} and add handling logic in
    // {@link handleKeyGesture()}
    private boolean interceptSystemKeysAndShortcuts(IBinder focusedToken, KeyEvent event) {
        if (useKeyGestureEventHandler()) {
            return interceptSystemKeysAndShortcutsNew(focusedToken, event);
        } else {
            return interceptSystemKeysAndShortcutsOld(focusedToken, event);
        }
    }

    @SuppressLint("MissingPermission")
    private boolean interceptSystemKeysAndShortcutsOld(IBinder focusedToken, KeyEvent event) {
        final boolean keyguardOn = keyguardOn();
        final int keyCode = event.getKeyCode();
        final int repeatCount = event.getRepeatCount();
        final int metaState = event.getMetaState();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final boolean canceled = event.isCanceled();
        final int displayId = event.getDisplayId();
        final int deviceId = event.getDeviceId();
        final boolean firstDown = down && repeatCount == 0;

        // Cancel any pending meta actions if we see any other keys being pressed between the
        // down of the meta key and its corresponding up.
        if (mPendingMetaAction && !KeyEvent.isMetaKey(keyCode)) {
            mPendingMetaAction = false;
        }
        // Any key that is not Alt or Meta cancels Caps Lock combo tracking.
        if (mPendingCapsLockToggle && !KeyEvent.isMetaKey(keyCode) && !KeyEvent.isAltKey(keyCode)) {
            mPendingCapsLockToggle = false;
        }

        if (isUserSetupComplete() && !keyguardOn) {
            if (mModifierShortcutManager.interceptKey(event)) {
                if (isKeyEventForCurrentUser(
                        event.getDisplayId(), event.getKeyCode(),
                        "dismissKeyboardShortcutsMenu")) {
                    dismissKeyboardShortcutsMenu();
                }
                mPendingMetaAction = false;
                mPendingCapsLockToggle = false;
                return true;
            }
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_HOME:
                return handleHomeShortcuts(focusedToken, event);
            case KeyEvent.KEYCODE_RECENT_APPS:
                if (firstDown) {
                    showRecentApps(false /* triggeredFromAltTab */);
                    notifyKeyGestureCompleted(event,
                            KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS);
                }
                return true;
            case KeyEvent.KEYCODE_APP_SWITCH:
                if (!keyguardOn) {
                    if (firstDown) {
                        preloadRecentApps();
                    } else if (!down) {
                        toggleRecentApps();
                        notifyKeyGestureCompleted(event,
                                KeyGestureEvent.KEY_GESTURE_TYPE_APP_SWITCH);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_A:
                if (firstDown && event.isMetaPressed()) {
                    launchAssistAction(Intent.EXTRA_ASSIST_INPUT_HINT_KEYBOARD,
                            deviceId, event.getEventTime(),
                            AssistUtils.INVOCATION_TYPE_UNKNOWN);
                    notifyKeyGestureCompleted(event,
                            KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_H:
            case KeyEvent.KEYCODE_ENTER:
                if (event.isMetaPressed()) {
                    return handleHomeShortcuts(focusedToken, event);
                }
                break;
            case KeyEvent.KEYCODE_I:
                if (firstDown && event.isMetaPressed()) {
                    showSystemSettings();
                    notifyKeyGestureCompleted(event,
                            KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_L:
                if (firstDown && event.isMetaPressed()) {
                    lockNow(null /* options */);
                    notifyKeyGestureCompleted(event,
                            KeyGestureEvent.KEY_GESTURE_TYPE_LOCK_SCREEN);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_N:
                if (firstDown && event.isMetaPressed()) {
                    if (event.isCtrlPressed()) {
                        sendSystemKeyToStatusBarAsync(event);
                        notifyKeyGestureCompleted(event,
                                KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_NOTES);
                    } else {
                        toggleNotificationPanel();
                        notifyKeyGestureCompleted(event,
                                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL);
                    }
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_S:
                if (firstDown && event.isMetaPressed() && event.isCtrlPressed()) {
                    interceptScreenshotChord(SCREENSHOT_KEY_OTHER, 0 /*pressDelay*/);
                    notifyKeyGestureCompleted(event,
                            KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_T:
                if (keyboardA11yShortcutControl()) {
                    if (firstDown && event.isMetaPressed() && event.isAltPressed()) {
                        mTalkbackShortcutController.toggleTalkback(mCurrentUserId,
                                TalkbackShortcutController.ShortcutSource.KEYBOARD);
                        notifyKeyGestureCompleted(event,
                                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_TALKBACK);
                        return true;
                    }
                }
                break;
            case KeyEvent.KEYCODE_DEL:
                if (newBugreportKeyboardShortcut()) {
                    if (mEnableBugReportKeyboardShortcut && firstDown
                            && event.isMetaPressed() && event.isCtrlPressed()) {
                        try {
                            mActivityManagerService.requestInteractiveBugReport();
                        } catch (RemoteException e) {
                            Slog.d(TAG, "Error taking bugreport", e);
                        }
                        notifyKeyGestureCompleted(event,
                                KeyGestureEvent.KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT);
                        return true;
                    }
                }
                // fall through
            case KeyEvent.KEYCODE_ESCAPE:
                if (firstDown && event.isMetaPressed()) {
                    notifyKeyGestureCompleted(event,
                            KeyGestureEvent.KEY_GESTURE_TYPE_BACK);
                    injectBackGesture(event.getDownTime());
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (firstDown && event.isMetaPressed() && event.isCtrlPressed()) {
                    StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
                    if (statusbar != null) {
                        statusbar.moveFocusedTaskToFullscreen(getTargetDisplayIdForKeyEvent(event));
                        notifyKeyGestureCompleted(event,
                                KeyGestureEvent.KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION);
                        return true;
                    }
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (firstDown && event.isMetaPressed() && event.isCtrlPressed()) {
                    StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
                    if (statusbar != null) {
                        statusbar.moveFocusedTaskToDesktop(getTargetDisplayIdForKeyEvent(event));
                        notifyKeyGestureCompleted(event,
                                KeyGestureEvent.KEY_GESTURE_TYPE_DESKTOP_MODE);
                        return true;
                    }
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (firstDown && event.isMetaPressed()) {
                    if (event.isCtrlPressed()) {
                        moveFocusedTaskToStageSplit(getTargetDisplayIdForKeyEvent(event),
                                true /* leftOrTop */);
                        notifyKeyGestureCompleted(event,
                                KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT);
                    } else if (event.isAltPressed()) {
                        setSplitscreenFocus(true /* leftOrTop */);
                        notifyKeyGestureCompleted(event,
                                KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT);
                    } else {
                        notifyKeyGestureCompleted(event,
                                KeyGestureEvent.KEY_GESTURE_TYPE_BACK);
                        injectBackGesture(event.getDownTime());
                    }
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (firstDown && event.isMetaPressed()) {
                    if (event.isCtrlPressed()) {
                        moveFocusedTaskToStageSplit(getTargetDisplayIdForKeyEvent(event),
                                false /* leftOrTop */);
                        notifyKeyGestureCompleted(event,
                                KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT);
                        return true;
                    } else if (event.isAltPressed()) {
                        setSplitscreenFocus(false /* leftOrTop */);
                        notifyKeyGestureCompleted(event,
                                KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT);
                        return true;
                    }
                }
                break;
            case KeyEvent.KEYCODE_SLASH:
                if (firstDown && event.isMetaPressed() && !keyguardOn) {
                    toggleKeyboardShortcutsMenu(event.getDeviceId());
                    notifyKeyGestureCompleted(event,
                            KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_ASSIST:
                Slog.wtf(TAG, "KEYCODE_ASSIST should be handled in interceptKeyBeforeQueueing");
                return true;
            case KeyEvent.KEYCODE_VOICE_ASSIST:
                Slog.wtf(TAG, "KEYCODE_VOICE_ASSIST should be handled in"
                        + " interceptKeyBeforeQueueing");
                return true;
            case KeyEvent.KEYCODE_BRIGHTNESS_UP:
            case KeyEvent.KEYCODE_BRIGHTNESS_DOWN:
                if (down) {
                    int direction = keyCode == KeyEvent.KEYCODE_BRIGHTNESS_UP ? 1 : -1;

                    changeDisplayBrightnessValue(displayId, direction);

                    int gestureType = keyCode == KeyEvent.KEYCODE_BRIGHTNESS_DOWN
                            ? KeyGestureEvent.KEY_GESTURE_TYPE_BRIGHTNESS_DOWN
                            : KeyGestureEvent.KEY_GESTURE_TYPE_BRIGHTNESS_UP;
                    notifyKeyGestureCompleted(event, gestureType);
                }
                return true;
            case KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_DOWN:
                if (down) {
                    mInputManagerInternal.decrementKeyboardBacklight(event.getDeviceId());
                    notifyKeyGestureCompleted(event,
                            KeyGestureEvent.KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_DOWN);
                }
                return true;
            case KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_UP:
                if (down) {
                    mInputManagerInternal.incrementKeyboardBacklight(event.getDeviceId());
                    notifyKeyGestureCompleted(event,
                            KeyGestureEvent.KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_UP);
                }
                return true;
            case KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_TOGGLE:
                // TODO: Add logic
                if (!down) {
                    notifyKeyGestureCompleted(event,
                            KeyGestureEvent.KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_TOGGLE);
                }
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                if (mUseTvRouting || mHandleVolumeKeysInWM) {
                    // On TVs or when the configuration is enabled, volume keys never
                    // go to the foreground app.
                    dispatchDirectAudioEvent(event);
                    return true;
                }

                // If the device is in VR mode and keys are "internal" (e.g. on the side of the
                // device), then drop the volume keys and don't forward it to the
                // application/dispatch the audio event.
                if (mDefaultDisplayPolicy.isPersistentVrModeEnabled()) {
                    final InputDevice d = event.getDevice();
                    if (d != null && !d.isExternal()) {
                        return true;
                    }
                }
                break;
            case KeyEvent.KEYCODE_TAB:
                if (firstDown && !keyguardOn && isUserSetupComplete()) {
                    if (event.isMetaPressed()) {
                        showRecentApps(false);
                        notifyKeyGestureCompleted(event,
                                KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS);
                        return true;
                    } else if (mRecentAppsHeldModifiers == 0) {
                        final int shiftlessModifiers =
                                event.getModifiers() & ~KeyEvent.META_SHIFT_MASK;
                        if (KeyEvent.metaStateHasModifiers(
                                shiftlessModifiers, KeyEvent.META_ALT_ON)) {
                            mRecentAppsHeldModifiers = shiftlessModifiers;
                            showRecentApps(true);
                            notifyKeyGestureCompleted(event,
                                    KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS);
                            return true;
                        }
                    }
                }
                break;
            case KeyEvent.KEYCODE_ALL_APPS:
                if (firstDown) {
                    mHandler.removeMessages(MSG_HANDLE_ALL_APPS);
                    Message msg = mHandler.obtainMessage(MSG_HANDLE_ALL_APPS, new KeyEvent(event));
                    msg.setAsynchronous(true);
                    msg.sendToTarget();

                    notifyKeyGestureCompleted(event, KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS);
                }
                return true;
            case KeyEvent.KEYCODE_NOTIFICATION:
                if (!down) {
                    toggleNotificationPanel();
                    notifyKeyGestureCompleted(event,
                            KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL);
                }
                return true;
            case KeyEvent.KEYCODE_SEARCH:
                if (firstDown && !keyguardOn) {
                    switch (mSearchKeyBehavior) {
                        case SEARCH_KEY_BEHAVIOR_TARGET_ACTIVITY: {
                            launchTargetSearchActivity();
                            notifyKeyGestureCompleted(event,
                                    KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SEARCH);
                            return true;
                        }
                        case SEARCH_KEY_BEHAVIOR_DEFAULT_SEARCH:
                        default:
                            break;
                    }
                }
                break;
            case KeyEvent.KEYCODE_LANGUAGE_SWITCH:
                if (firstDown) {
                    int direction = (metaState & KeyEvent.META_SHIFT_MASK) != 0 ? -1 : 1;
                    sendSwitchKeyboardLayout(displayId, focusedToken, direction);
                    notifyKeyGestureCompleted(event,
                            KeyGestureEvent.KEY_GESTURE_TYPE_LANGUAGE_SWITCH);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
                if (down) {
                    if (event.isAltPressed()) {
                        mPendingCapsLockToggle = true;
                        mPendingMetaAction = false;
                    } else {
                        mPendingCapsLockToggle = false;
                        mPendingMetaAction = true;
                    }
                } else {
                    // Toggle Caps Lock on META-ALT.
                    if (mPendingCapsLockToggle) {
                        mInputManagerInternal.toggleCapsLock(event.getDeviceId());
                        mPendingCapsLockToggle = false;
                        notifyKeyGestureCompleted(event,
                                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK);
                    } else if (mPendingMetaAction) {
                        if (!canceled) {
                            if (isKeyEventForCurrentUser(event.getDisplayId(), event.getKeyCode(),
                                    "launchAllAppsViaA11y")) {
                                launchAllAppsAction();
                            }
                            notifyKeyGestureCompleted(event,
                                    KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS);
                        }
                        mPendingMetaAction = false;
                    }
                }
                return true;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
                if (down) {
                    if (event.isMetaPressed()) {
                        mPendingCapsLockToggle = true;
                        mPendingMetaAction = false;
                    } else {
                        mPendingCapsLockToggle = false;
                    }
                } else {
                    // hide recent if triggered by ALT-TAB.
                    if (mRecentAppsHeldModifiers != 0
                            && (metaState & mRecentAppsHeldModifiers) == 0) {
                        mRecentAppsHeldModifiers = 0;
                        hideRecentApps(true, false);
                        return true;
                    }

                    // Toggle Caps Lock on META-ALT.
                    if (mPendingCapsLockToggle) {
                        mInputManagerInternal.toggleCapsLock(event.getDeviceId());
                        mPendingCapsLockToggle = false;
                        notifyKeyGestureCompleted(event,
                                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK);
                        return true;
                    }
                }
                break;
            case KeyEvent.KEYCODE_CAPS_LOCK:
                if (!down) {
                    notifyKeyGestureCompleted(event,
                            KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK);
                }
                break;
            case KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY:
            case KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY:
            case KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY:
            case KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL:
                Slog.wtf(TAG, "KEYCODE_STYLUS_BUTTON_* should be handled in"
                        + " interceptKeyBeforeQueueing");
                return true;
            case KeyEvent.KEYCODE_SETTINGS:
                if (firstDown) {
                    if (mSettingsKeyBehavior == SETTINGS_KEY_BEHAVIOR_NOTIFICATION_PANEL) {
                        toggleNotificationPanel();
                        notifyKeyGestureCompleted(event,
                                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL);
                    } else if (mSettingsKeyBehavior == SETTINGS_KEY_BEHAVIOR_SETTINGS_ACTIVITY) {
                        showSystemSettings();
                        notifyKeyGestureCompleted(event,
                                KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_STEM_PRIMARY:
                if (prepareToSendSystemKeyToApplication(focusedToken, event)) {
                    // Send to app.
                    return false;
                } else {
                    // Intercepted.
                    sendSystemKeyToStatusBarAsync(event);
                    return true;
                }
            case KeyEvent.KEYCODE_SCREENSHOT:
                if (emojiAndScreenshotKeycodesAvailable() && down && repeatCount == 0) {
                    interceptScreenshotChord(SCREENSHOT_KEY_OTHER, 0 /*pressDelay*/);
                }
                return true;
        }
        if (isValidGlobalKey(keyCode)
                && mGlobalKeyManager.handleGlobalKey(mContext, keyCode, event)) {
            return true;
        }

        // Reserve all the META modifier combos for system behavior
        return (metaState & KeyEvent.META_META_ON) != 0;
    }

    private boolean interceptSystemKeysAndShortcutsNew(IBinder focusedToken, KeyEvent event) {
        final int keyCode = event.getKeyCode();
        final int metaState = event.getMetaState();
        final boolean keyguardOn = keyguardOn();

        if (isUserSetupComplete() && !keyguardOn) {
            if (mModifierShortcutManager.interceptKey(event)) {
                dismissKeyboardShortcutsMenu();
                return true;
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_HOME:
                return handleHomeShortcuts(focusedToken, event);
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                if (mUseTvRouting || mHandleVolumeKeysInWM) {
                    // On TVs or when the configuration is enabled, volume keys never
                    // go to the foreground app.
                    dispatchDirectAudioEvent(event);
                    return true;
                }

                // If the device is in VR mode and keys are "internal" (e.g. on the side of the
                // device), then drop the volume keys and don't forward it to the
                // application/dispatch the audio event.
                if (mDefaultDisplayPolicy.isPersistentVrModeEnabled()) {
                    final InputDevice d = event.getDevice();
                    if (d != null && !d.isExternal()) {
                        return true;
                    }
                }
                break;
            case KeyEvent.KEYCODE_STEM_PRIMARY:
                if (prepareToSendSystemKeyToApplication(focusedToken, event)) {
                    // Send to app.
                    return false;
                } else {
                    // Intercepted.
                    sendSystemKeyToStatusBarAsync(event);
                    return true;
                }
        }
        if (isValidGlobalKey(keyCode)
                && mGlobalKeyManager.handleGlobalKey(mContext, keyCode, event)) {
            return true;
        }

        // Reserve all the META modifier combos for system behavior
        return (metaState & KeyEvent.META_META_ON) != 0;
    }

    @SuppressLint("MissingPermission")
    private void initKeyGestures() {
        if (!useKeyGestureEventHandler()) {
            return;
        }
        mInputManager.registerKeyGestureEventHandler(new InputManager.KeyGestureEventHandler() {
            @Override
            public boolean handleKeyGestureEvent(@NonNull KeyGestureEvent event,
                    @Nullable IBinder focusedToken) {
                boolean handled = PhoneWindowManager.this.handleKeyGestureEvent(event,
                        focusedToken);
                if (handled && Arrays.stream(event.getKeycodes()).anyMatch(
                        (keycode) -> keycode == KeyEvent.KEYCODE_POWER)) {
                    mPowerKeyHandled = true;
                }
                return handled;
            }

            @Override
            public boolean isKeyGestureSupported(int gestureType) {
                switch (gestureType) {
                    case KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_APP_SWITCH:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_HOME:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_LOCK_SCREEN:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_BACK:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_DESKTOP_MODE:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_BRIGHTNESS_UP:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_BRIGHTNESS_DOWN:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_ALL_APPS:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SEARCH:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_LANGUAGE_SWITCH:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_SHORTCUT:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_CLOSE_ALL_DIALOGS:
                        return true;
                    case KeyGestureEvent.KEY_GESTURE_TYPE_SCREENSHOT_CHORD:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_RINGER_TOGGLE_CHORD:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_GLOBAL_ACTIONS:
                    case KeyGestureEvent.KEY_GESTURE_TYPE_TV_TRIGGER_BUG_REPORT:
                        return mDefaultDisplayPolicy.isAwake();
                    case KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_SHORTCUT_CHORD:
                        return mDefaultDisplayPolicy.isAwake() && mAccessibilityShortcutController
                                .isAccessibilityShortcutAvailable(isKeyguardLocked());
                    case KeyGestureEvent.KEY_GESTURE_TYPE_TV_ACCESSIBILITY_SHORTCUT_CHORD:
                        return mDefaultDisplayPolicy.isAwake() && mAccessibilityShortcutController
                                .isAccessibilityShortcutAvailable(false);
                    case KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_TALKBACK:
                        return keyboardA11yShortcutControl();
                    default:
                        return false;
                }
            }
        });
    }

    @VisibleForTesting
    boolean handleKeyGestureEvent(KeyGestureEvent event, IBinder focusedToken) {
        boolean start = event.getAction() == KeyGestureEvent.ACTION_GESTURE_START;
        boolean complete = event.getAction() == KeyGestureEvent.ACTION_GESTURE_COMPLETE
                && !event.isCancelled();
        int deviceId = event.getDeviceId();
        int gestureType = event.getKeyGestureType();
        int displayId = event.getDisplayId();
        int modifierState = event.getModifierState();
        boolean keyguardOn = keyguardOn();
        switch (gestureType) {
            case KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS:
                if (complete) {
                    showRecentApps(false);
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_APP_SWITCH:
                if (!keyguardOn) {
                    if (start) {
                        preloadRecentApps();
                    } else if (complete) {
                        toggleRecentApps();
                    }
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT:
                if (complete) {
                    launchAssistAction(Intent.EXTRA_ASSIST_INPUT_HINT_KEYBOARD,
                            deviceId, SystemClock.uptimeMillis(),
                            AssistUtils.INVOCATION_TYPE_UNKNOWN);
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_HOME:
                if (complete) {
                    // Post to main thread to avoid blocking input pipeline.
                    mHandler.post(() -> handleShortPressOnHome(displayId));
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS:
                if (complete) {
                    showSystemSettings();
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_LOCK_SCREEN:
                if (complete) {
                    lockNow(null /* options */);
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL:
                if (complete) {
                    toggleNotificationPanel();
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT:
                if (complete) {
                    interceptScreenshotChord(SCREENSHOT_KEY_OTHER, 0 /*pressDelay*/);
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT:
                if (complete && mEnableBugReportKeyboardShortcut) {
                    try {
                        mActivityManagerService.requestInteractiveBugReport();
                    } catch (RemoteException e) {
                        Slog.d(TAG, "Error taking bugreport", e);
                    }
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_BACK:
                if (complete) {
                    injectBackGesture(SystemClock.uptimeMillis());
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION:
                if (complete) {
                    StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
                    if (statusbar != null) {
                        statusbar.moveFocusedTaskToFullscreen(
                                getTargetDisplayIdForKeyGestureEvent(event));
                    }
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_DESKTOP_MODE:
                if (complete) {
                    StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
                    if (statusbar != null) {
                        statusbar.moveFocusedTaskToDesktop(
                                getTargetDisplayIdForKeyGestureEvent(event));
                    }
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT:
                if (complete) {
                    moveFocusedTaskToStageSplit(getTargetDisplayIdForKeyGestureEvent(event),
                            true /* leftOrTop */);
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT:
                if (complete) {
                    setSplitscreenFocus(true /* leftOrTop */);
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT:
                if (complete) {
                    moveFocusedTaskToStageSplit(getTargetDisplayIdForKeyGestureEvent(event),
                            false /* leftOrTop */);
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT:
                if (complete) {
                    setSplitscreenFocus(false /* leftOrTop */);
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER:
                if (complete) {
                    toggleKeyboardShortcutsMenu(deviceId);
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_BRIGHTNESS_UP:
            case KeyGestureEvent.KEY_GESTURE_TYPE_BRIGHTNESS_DOWN:
                if (complete) {
                    int direction =
                            gestureType == KeyGestureEvent.KEY_GESTURE_TYPE_BRIGHTNESS_UP ? 1 : -1;
                    changeDisplayBrightnessValue(displayId, direction);
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER:
                if (start) {
                    showRecentApps(true);
                } else {
                    hideRecentApps(true, false);
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS:
            case KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_ALL_APPS:
                if (complete && isKeyEventForCurrentUser(event.getDisplayId(),
                        event.getKeycodes()[0], "launchAllAppsViaA11y")) {
                    launchAllAppsAction();
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SEARCH:
                if (complete) {
                    launchTargetSearchActivity();
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_LANGUAGE_SWITCH:
                if (complete) {
                    int direction = (modifierState & KeyEvent.META_SHIFT_MASK) != 0 ? -1 : 1;
                    sendSwitchKeyboardLayout(displayId, focusedToken, direction);
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_SCREENSHOT_CHORD:
                if (start) {
                    // Screenshot chord is pressed: Wait for long press delay before taking
                    // screenshot
                    interceptScreenshotChord(SCREENSHOT_KEY_CHORD,
                            getScreenshotChordLongPressDelay());
                } else {
                    cancelPendingScreenshotChordAction();
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_SHORTCUT_CHORD:
                if (start) {
                    interceptAccessibilityShortcutChord();
                } else {
                    cancelPendingAccessibilityShortcutAction();
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_RINGER_TOGGLE_CHORD:
                if (start) {
                    interceptRingerToggleChord();
                } else {
                    cancelPendingRingerToggleChordAction();
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_GLOBAL_ACTIONS:
                if (start) {
                    performHapticFeedback(
                            HapticFeedbackConstants.LONG_PRESS_POWER_BUTTON,
                            "KEY_GESTURE_TYPE_GLOBAL_ACTIONS - Global Actions");
                    showGlobalActions();
                } else {
                    cancelGlobalActionsAction();
                }
                return true;
                // TODO (b/358569822): Consolidate TV and non-TV gestures into same KeyGestureEvent
            case KeyGestureEvent.KEY_GESTURE_TYPE_TV_ACCESSIBILITY_SHORTCUT_CHORD:
                if (start) {
                    interceptAccessibilityGestureTv();
                } else {
                    cancelAccessibilityGestureTv();
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_TV_TRIGGER_BUG_REPORT:
                if (start) {
                    interceptBugreportGestureTv();
                } else {
                    cancelBugreportGestureTv();
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_SHORTCUT:
                if (complete && mAccessibilityShortcutController.isAccessibilityShortcutAvailable(
                        isKeyguardLocked())) {
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_ACCESSIBILITY_SHORTCUT));
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_CLOSE_ALL_DIALOGS:
                if (complete) {
                    mContext.closeSystemDialogs();
                }
                return true;
            case KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_TALKBACK:
                if (keyboardA11yShortcutControl()) {
                    if (complete) {
                        mTalkbackShortcutController.toggleTalkback(mCurrentUserId,
                                TalkbackShortcutController.ShortcutSource.KEYBOARD);
                    }
                    return true;
                }
                break;
        }
        return false;
    }

    private void changeDisplayBrightnessValue(int displayId, int direction) {
        int screenDisplayId = displayId < 0 ? DEFAULT_DISPLAY : displayId;

        float minLinearBrightness = mPowerManager.getBrightnessConstraint(
                PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MINIMUM);
        float maxLinearBrightness = mPowerManager.getBrightnessConstraint(
                PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MAXIMUM);
        float linearBrightness = mDisplayManager.getBrightness(screenDisplayId);

        float gammaBrightness = BrightnessUtils.convertLinearToGamma(linearBrightness);
        float adjustedGammaBrightness = gammaBrightness + 1f / BRIGHTNESS_STEPS * direction;
        adjustedGammaBrightness = MathUtils.constrain(adjustedGammaBrightness, 0f, 1f);
        float adjustedLinearBrightness = BrightnessUtils.convertGammaToLinear(
                adjustedGammaBrightness);
        adjustedLinearBrightness = MathUtils.constrain(adjustedLinearBrightness,
                minLinearBrightness, maxLinearBrightness);
        mDisplayManager.setBrightness(screenDisplayId, adjustedLinearBrightness);

        Intent intent = new Intent(Intent.ACTION_SHOW_BRIGHTNESS_DIALOG);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        intent.putExtra(EXTRA_FROM_BRIGHTNESS_KEY, true);
        startActivityAsUser(intent, UserHandle.CURRENT_OR_SELF);
    }

    /**
     * In this function, we check whether a system key should be sent to the application. We also
     * detect the key gesture on this key, even if the key will be sent to the app. The gesture
     * action, if any, will not be executed immediately. It will be queued and execute only after
     * the application tells us that it didn't handle this key.
     *
     * @return true if this key should be sent to the application. This also means that the target
     *     application has the necessary permissions to receive this key. Return false otherwise.
     */
    private boolean prepareToSendSystemKeyToApplication(IBinder focusedToken, KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (!event.isSystem()) {
            Log.wtf(
                    TAG,
                    "Illegal keycode provided to prepareToSendSystemKeyToApplication: "
                            + KeyEvent.keyCodeToString(keyCode));
            return false;
        }
        final boolean isDown = event.getAction() == KeyEvent.ACTION_DOWN;
        if (isDown && event.getRepeatCount() == 0) {
            // This happens at the initial DOWN event. Check focused window permission now.
            final KeyInterceptionInfo info =
                    mWindowManagerInternal.getKeyInterceptionInfoFromToken(focusedToken);
            if (info != null
                    && mButtonOverridePermissionChecker.canAppOverrideSystemKey(
                            mContext, info.windowOwnerUid)) {
                // Focused window has the permission. Pass the event to it.
                return true;
            } else {
                // Focused window doesn't have the permission. Intercept the event.
                // If the initial DOWN event is intercepted, follow-up events will be intercepted
                // too. So we know the gesture won't be handled by app, and can handle the gesture
                // in system.
                setDeferredKeyActionsExecutableAsync(keyCode, event.getDownTime());
                return false;
            }
        } else {
            // This happens after the initial DOWN event. We will just reuse the initial decision.
            // I.e., if the initial DOWN event was dispatched, follow-up events should be
            // dispatched. Otherwise, follow-up events should be consumed.
            final Set<Integer> consumedKeys = mConsumedKeysForDevice.get(event.getDeviceId());
            final boolean wasConsumed = consumedKeys != null && consumedKeys.contains(keyCode);
            return !wasConsumed;
        }
    }

    private void setDeferredKeyActionsExecutableAsync(int keyCode, long downTime) {
        Message msg = Message.obtain(mHandler, MSG_SET_DEFERRED_KEY_ACTIONS_EXECUTABLE);
        msg.arg1 = keyCode;
        msg.obj = downTime;
        msg.setAsynchronous(true);
        msg.sendToTarget();
    }

    @SuppressLint("MissingPermission")
    private void injectBackGesture(long downtime) {
        // Create and inject down event
        KeyEvent downEvent = new KeyEvent(downtime, downtime, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_BACK, 0 /* repeat */, 0 /* metaState */,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /* scancode */,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);
        mInputManager.injectInputEvent(downEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);


        // Create and inject up event
        KeyEvent upEvent = KeyEvent.changeAction(downEvent, KeyEvent.ACTION_UP);
        mInputManager.injectInputEvent(upEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);

        downEvent.recycle();
        upEvent.recycle();
    }

    private boolean handleHomeShortcuts(IBinder focusedToken, KeyEvent event) {
        // First we always handle the home key here, so applications
        // can never break it, although if keyguard is on, we do let
        // it handle it, because that gives us the correct 5 second
        // timeout.
        DisplayHomeButtonHandler handler = mDisplayHomeButtonHandlers.get(event.getDisplayId());
        if (handler == null) {
            handler = new DisplayHomeButtonHandler(event.getDisplayId());
            mDisplayHomeButtonHandlers.put(event.getDisplayId(), handler);
        }
        return handler.handleHomeButton(focusedToken, event);
    }

    private void toggleMicrophoneMuteFromKey() {
        if (mSensorPrivacyManager.supportsSensorToggle(
                SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE,
                SensorPrivacyManager.Sensors.MICROPHONE)) {
            boolean isEnabled = mSensorPrivacyManager.isSensorPrivacyEnabled(
                    SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE,
                    SensorPrivacyManager.Sensors.MICROPHONE);

            mSensorPrivacyManager.setSensorPrivacy(SensorPrivacyManager.Sensors.MICROPHONE,
                    !isEnabled);

            int toastTextResId;
            if (isEnabled) {
                toastTextResId = R.string.mic_access_on_toast;
            } else {
                toastTextResId = R.string.mic_access_off_toast;
            }

            Toast.makeText(
                mContext,
                UiThread.get().getLooper(),
                mContext.getString(toastTextResId),
                Toast.LENGTH_SHORT)
                .show();
        }
    }

    /**
     * TV only: recognizes a remote control gesture for capturing a bug report.
     */
    private void interceptBugreportGestureTv() {
        mHandler.removeMessages(MSG_BUGREPORT_TV);
        // The bugreport capture chord is a long press on DPAD CENTER and BACK simultaneously.
        Message msg = Message.obtain(mHandler, MSG_BUGREPORT_TV);
        msg.setAsynchronous(true);
        mHandler.sendMessageDelayed(msg, BUGREPORT_TV_GESTURE_TIMEOUT_MILLIS);
    }

    private void cancelBugreportGestureTv() {
        mHandler.removeMessages(MSG_BUGREPORT_TV);
    }

    /**
     * TV only: recognizes a remote control gesture as Accessibility shortcut.
     * Shortcut: Long press (BACK + DPAD_DOWN)
     */
    private void interceptAccessibilityGestureTv() {
        mHandler.removeMessages(MSG_ACCESSIBILITY_TV);
        Message msg = Message.obtain(mHandler, MSG_ACCESSIBILITY_TV);
        msg.setAsynchronous(true);
        mHandler.sendMessageDelayed(msg, getAccessibilityShortcutTimeout());
    }
    private void cancelAccessibilityGestureTv() {
        mHandler.removeMessages(MSG_ACCESSIBILITY_TV);
    }

    @VisibleForTesting
    void requestBugreportForTv() {
        try {
            if (!ActivityManager.getService().launchBugReportHandlerApp()) {
                ActivityManager.getService().requestInteractiveBugReport();
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Error taking bugreport", e);
        }
    }

    // TODO(b/117479243): handle it in InputPolicy
    /** {@inheritDoc} */
    @Override
    public boolean interceptUnhandledKey(KeyEvent event, IBinder focusedToken) {
        // Note: This method is only called if the initial down was unhandled.
        if (DEBUG_INPUT) {
            final KeyInterceptionInfo info =
                    mWindowManagerInternal.getKeyInterceptionInfoFromToken(focusedToken);
            final String title = info == null ? "<unknown>" : info.windowTitle;
            Slog.d(TAG, "Unhandled key: inputToken=" + focusedToken
                    + ", title=" + title
                    + ", action=" + event.getAction()
                    + ", flags=" + event.getFlags()
                    + ", keyCode=" + event.getKeyCode()
                    + ", scanCode=" + event.getScanCode()
                    + ", metaState=" + event.getMetaState()
                    + ", repeatCount=" + event.getRepeatCount());
        }

        final int keyCode = event.getKeyCode();
        final int repeatCount = event.getRepeatCount();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final int metaState = event.getModifiers();

        // TODO(b/358569822): Shift to KeyGestureEvent based handling
        if (keyCode == KeyEvent.KEYCODE_STEM_PRIMARY) {
            handleUnhandledSystemKey(event);
            sendSystemKeyToStatusBarAsync(event);
            return true;
        }

        if (useKeyGestureEventHandler()) {
            return false;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE:
                if (down && repeatCount == 0) {
                    // Handle keyboard layout switching. (CTRL + SPACE)
                    if (KeyEvent.metaStateHasModifiers(metaState & ~KeyEvent.META_SHIFT_MASK,
                            KeyEvent.META_CTRL_ON)) {
                        int direction = (metaState & KeyEvent.META_SHIFT_MASK) != 0 ? -1 : 1;
                        sendSwitchKeyboardLayout(event.getDisplayId(), focusedToken, direction);
                        return true;
                    }
                }
                break;
            case KeyEvent.KEYCODE_Z:
                if (down && KeyEvent.metaStateHasModifiers(metaState,
                        KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON)) {
                    // Intercept the Accessibility keychord (CTRL + ALT + Z) for keyboard users.
                    if (mAccessibilityShortcutController
                            .isAccessibilityShortcutAvailable(isKeyguardLocked())) {
                        mHandler.sendMessage(mHandler.obtainMessage(MSG_ACCESSIBILITY_SHORTCUT));
                        return true;
                    }
                }
                break;
            case KeyEvent.KEYCODE_SYSRQ:
                if (down && repeatCount == 0) {
                    interceptScreenshotChord(SCREENSHOT_KEY_OTHER, 0 /*pressDelay*/);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_ESCAPE:
                if (down
                        && KeyEvent.metaStateHasNoModifiers(metaState)
                        && repeatCount == 0) {
                    mContext.closeSystemDialogs();
                    return true;
                }
                break;
        }

        return false;
    }

    /**
     * Called when a system key was sent to application and was unhandled. We will execute any
     * queued actions associated with this key code at this point.
     */
    private void handleUnhandledSystemKey(KeyEvent event) {
        if (!event.isSystem()) {
            Log.wtf(
                    TAG,
                    "Illegal keycode provided to handleUnhandledSystemKey: "
                            + KeyEvent.keyCodeToString(event.getKeyCode()));
            return;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            // If the initial DOWN event is unhandled by app, follow-up events will also be
            // unhandled by app. So we can handle the key event in system.
            setDeferredKeyActionsExecutableAsync(event.getKeyCode(), event.getDownTime());
        }
    }

    private void sendSwitchKeyboardLayout(int displayId, @Nullable IBinder focusedToken,
            int direction) {
        SwitchKeyboardLayoutMessageObject object =
                new SwitchKeyboardLayoutMessageObject(displayId, focusedToken, direction);
        mHandler.obtainMessage(MSG_SWITCH_KEYBOARD_LAYOUT, object).sendToTarget();
    }

    private void handleSwitchKeyboardLayout(int displayId, int direction, IBinder focusedToken) {
        IBinder targetWindowToken =
                mWindowManagerInternal.getTargetWindowTokenFromInputToken(focusedToken);
        InputMethodManagerInternal.get().onSwitchKeyboardLayoutShortcut(direction, displayId,
                targetWindowToken);
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
            mModifierShortcutManager.registerShortcutKey(shortcutCode, shortcutService);
        }
    }

    @Override
    public void onKeyguardOccludedChangedLw(boolean occluded) {
        if (mKeyguardDelegate != null) {
            mPendingKeyguardOccluded = occluded;
            mKeyguardOccludedChanged = true;
        }
    }

    @Override
    public int applyKeyguardOcclusionChange() {
        if (DEBUG_KEYGUARD) Slog.d(TAG, "transition/occluded commit occluded="
                + mPendingKeyguardOccluded + " changed=" + mKeyguardOccludedChanged);

        // TODO(b/276433230): Explicitly save before/after for occlude state in each
        // Transition so we don't need to update SysUI every time.
        if (setKeyguardOccludedLw(mPendingKeyguardOccluded)) {
            return FINISH_LAYOUT_REDO_LAYOUT | FINISH_LAYOUT_REDO_WALLPAPER;
        } else {
            return 0;
        }
    }

    /**
     * Called when keyguard related app transition starts, or cancelled.
     *
     * @param startKeyguardExitAnimation Trigger IKeyguardService#startKeyguardExitAnimation to
     *                                  start keyguard exit animation.
     * @param notifyOccluded Trigger IKeyguardService#setOccluded binder call to notify whether
     *                      the top activity can occlude the keyguard or not.
     *
     * @return Whether the flags have changed and we have to redo the layout.
     */
    private int handleTransitionForKeyguardLw(boolean startKeyguardExitAnimation,
            boolean notifyOccluded) {
        int redoLayout = 0;
        if (notifyOccluded) {
            redoLayout = applyKeyguardOcclusionChange();
        }
        if (startKeyguardExitAnimation) {
            if (DEBUG_KEYGUARD) Slog.d(TAG, "Starting keyguard exit animation");
            startKeyguardExitAnimation(SystemClock.uptimeMillis());
        }
        return redoLayout;
    }

    /**
     * Shows the keyguard without immediately locking the device.
     */
    @Override
    public void showDismissibleKeyguard() {
        mKeyguardDelegate.showDismissibleKeyguard();
    }

    // There are several different flavors of "assistant" that can be launched from
    // various parts of the UI.

    /** Asks the status bar to startAssist(), usually a full "assistant" interface */
    private void launchAssistAction(String hint, int deviceId, long eventTime,
            int invocationType) {
        sendCloseSystemWindows(SYSTEM_DIALOG_REASON_ASSIST);
        if (!isUserSetupComplete()) {
            // Disable opening assist window during setup
            return;
        }

        // Add Intent Extra data.
        Bundle args = null;
        args = new Bundle();
        if (deviceId != INVALID_INPUT_DEVICE_ID) {
            args.putInt(Intent.EXTRA_ASSIST_INPUT_DEVICE_ID, deviceId);
        }
        if (hint != null) {
            args.putBoolean(hint, true);
        }
        args.putLong(Intent.EXTRA_TIME, eventTime);
        args.putInt(AssistUtils.INVOCATION_TYPE_KEY, invocationType);

        SearchManager searchManager = mContext.getSystemService(SearchManager.class);
        if (searchManager != null) {
            searchManager.launchAssist(args);
        } else {
            // Fallback to status bar if search manager doesn't exist (e.g. on wear).
            StatusBarManagerInternal statusBar = getStatusBarManagerInternal();
            if (statusBar != null) {
                statusBar.startAssist(args);
            }
        }
    }

    /**
     * Launches ACTION_VOICE_ASSIST_RETAIL if in retail mode, or ACTION_VOICE_ASSIST otherwise
     * Does nothing on keyguard except for watches. Delegates it to keyguard if present on watch.
     */
    private void launchVoiceAssist(boolean allowDuringSetup) {
        final boolean keyguardActive =
                mKeyguardDelegate != null && mKeyguardDelegate.isShowing();
        if (!keyguardActive) {
            startActivityAsUser(
                    new Intent(Intent.ACTION_VOICE_ASSIST),
                    /* bundle= */ null,
                    UserHandle.CURRENT_OR_SELF,
                    allowDuringSetup);
        } else {
            mKeyguardDelegate.dismissKeyguardToLaunch(new Intent(Intent.ACTION_VOICE_ASSIST));
        }
    }

    private boolean isInRetailMode() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_DEMO_MODE, 0) == 1;
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
            dismissKeyboardShortcutsMenu();
        } else {
            Slog.i(TAG, "Not starting activity because user setup is in progress: " + intent);
        }
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

    private void toggleTaskbar() {
        StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
        if (statusbar != null) {
            statusbar.toggleTaskbar();
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
        dismissKeyboardShortcutsMenu();
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

    private void moveFocusedTaskToStageSplit(int displayId, boolean leftOrTop) {
        StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
        if (statusbar != null) {
            statusbar.moveFocusedTaskToStageSplit(displayId, leftOrTop);
        }
    }

    private void setSplitscreenFocus(boolean leftOrTop) {
        StatusBarManagerInternal statusbar = getStatusBarManagerInternal();
        if (statusbar != null) {
            statusbar.setSplitscreenFocus(leftOrTop);
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

            if (!isKeyguardOccluded() && mKeyguardDelegate.isInputRestricted()) {
                // when in keyguard restricted mode, must first verify unlock
                // before launching home
                mKeyguardDelegate.verifyUnlock(new OnKeyguardExitResult() {
                    @Override
                    public void onKeyguardExitResult(boolean success) {
                        if (success) {
                            final long origId = Binder.clearCallingIdentity();
                            try {
                                startDockOrHome(displayId, true /*fromHomeKey*/, awakenFromDreams);
                            } finally {
                                Binder.restoreCallingIdentity(origId);
                            }
                        }
                    }
                });
                return;
            }
        }

        // no keyguard stuff to worry about, just launch home!
        // If Recents is visible and the action is not from visible background users,
        // hide Recents and notify it to launch Home.
        if (mRecentsVisible
                && (!mVisibleBackgroundUsersEnabled || displayId == DEFAULT_DISPLAY)) {
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

    /**
     * Updates the occluded state of the Keyguard immediately via
     * {@link com.android.internal.policy.IKeyguardService}.
     *
     * @param isOccluded Whether the Keyguard is occluded by another window.
     * @return Whether the flags have changed and we have to redo the layout.
     */
    private boolean setKeyguardOccludedLw(boolean isOccluded) {
        if (DEBUG_KEYGUARD) Slog.d(TAG, "setKeyguardOccluded occluded=" + isOccluded);
        mKeyguardOccludedChanged = false;
        mPendingKeyguardOccluded = isOccluded;
        mKeyguardDelegate.setOccluded(isOccluded, true /* notify */);
        return mKeyguardDelegate.isShowing();
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
            mWindowWakeUpPolicy.wakeUpFromLid();
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
        if (!mContext.getResources().getBoolean(
                R.bool.config_launchCameraOnCameraLensCoverToggle)) {
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
            mWindowWakeUpPolicy.wakeUpFromCameraCover(whenNanos / 1000000);
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
        } else {
            final List<ExtconUEventObserver.ExtconInfo> extcons =
                    ExtconUEventObserver.ExtconInfo.getExtconInfoForTypes(
                            new String[] {ExtconUEventObserver.ExtconInfo.EXTCON_HDMI});
            if (!extcons.isEmpty()) {
                // TODO: handle more than one HDMI
                HdmiVideoExtconUEventObserver observer = new HdmiVideoExtconUEventObserver();
                plugged = observer.init(extcons.get(0));
                mHDMIObserver = observer;
            } else if (localLOGV) {
                Slog.v(TAG, "Not observing HDMI plug state because HDMI was not found.");
            }
        }

        // This dance forces the code in setHdmiPlugged to run.
        // Always do this so the sticky intent is stuck (to false) if there is no hdmi.
        mDefaultDisplayPolicy.setHdmiPlugged(plugged, true /* force */);
    }

    // TODO(b/117479243): handle it in InputPolicy
    /** {@inheritDoc} */
    @Override
    public int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags) {
        final int keyCode = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean isWakeKey = (policyFlags & WindowManagerPolicy.FLAG_WAKE) != 0
                || event.isWakeKey();
        boolean isKeyGestureTriggered = (policyFlags & FLAG_KEY_GESTURE_TRIGGERED) != 0;

        // There are key events that perform the operation as the current user,
        // and these should be ignored for visible background users.
        if (mVisibleBackgroundUsersEnabled
                && KEY_CODES_IGNORED_FOR_VISIBLE_BACKGROUND_USERS.contains(keyCode)
                && !isKeyEventForCurrentUser(event.getDisplayId(), keyCode, null)) {
            return 0;
        }

        if (!mSystemBooted) {
            // If we have not yet booted, don't let key events do anything.
            // Exception: Wake and power key events are forwarded to PowerManager to allow it to
            // wake from quiescent mode during boot. On these key events we also explicitly turn on
            // the connected TV and switch HDMI input if we're a HDMI playback device.
            boolean shouldTurnOnTv = false;
            if (down && (keyCode == KeyEvent.KEYCODE_POWER
                    || keyCode == KeyEvent.KEYCODE_TV_POWER)) {
                wakeUpFromWakeKey(event);
                shouldTurnOnTv = true;
            } else if (down && (isWakeKey || keyCode == KeyEvent.KEYCODE_WAKEUP)
                    && isWakeKeyWhenScreenOff(keyCode)) {
                wakeUpFromWakeKey(event);
                shouldTurnOnTv = true;
            }
            if (shouldTurnOnTv) {
                final HdmiControl hdmiControl = getHdmiControl();
                if (hdmiControl != null) {
                    hdmiControl.turnOnTv();
                }
            }
            return 0;
        }

        final boolean interactive = (policyFlags & FLAG_INTERACTIVE) != 0;
        final boolean canceled = event.isCanceled();
        final int displayId = event.getDisplayId();
        final boolean isInjected = (policyFlags & WindowManagerPolicy.FLAG_INJECTED) != 0;

        if (DEBUG_INPUT) {
            // If screen is off then we treat the case where the keyguard is open but hidden
            // the same as if it were open and in front.
            // This will prevent any keys other than the power button from waking the screen
            // when the keyguard is hidden by another activity.
            final boolean keyguardActive = (mKeyguardDelegate != null
                    && (interactive ? isKeyguardShowingAndNotOccluded() :
                    mKeyguardDelegate.isShowing()));
            Log.d(TAG, "interceptKeyTq keycode=" + keyCode
                    + " interactive=" + interactive + " keyguardActive=" + keyguardActive
                    + " policyFlags=" + Integer.toHexString(policyFlags));
        }

        // Basic policy based on interactive state.
        int result;
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
        } else if (shouldDispatchInputWhenNonInteractive(displayId, keyCode)) {
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
                && mGlobalKeyManager.shouldHandleGlobalKey(keyCode)) {
            // Dispatch if global key defined dispatchWhenNonInteractive.
            if (!interactive && isWakeKey && down
                    && mGlobalKeyManager.shouldDispatchFromNonInteractive(keyCode)) {
                mGlobalKeyManager.setBeganFromNonInteractive();
                result = ACTION_PASS_TO_USER;
                // Since we're dispatching the input, reset the pending key
                mPendingWakeKey = PENDING_KEY_NULL;
            }

            if (isWakeKey) {
                wakeUpFromWakeKey(event);
            }
            return result;
        }

        // Alternate TV power to power key for Android TV device.
        final HdmiControlManager hdmiControlManager = getHdmiControlManager();
        if (keyCode == KeyEvent.KEYCODE_TV_POWER && mHasFeatureLeanback
                && (hdmiControlManager == null || !hdmiControlManager.shouldHandleTvPowerKey())) {
            event = KeyEvent.obtain(
                    event.getDownTime(), event.getEventTime(),
                    event.getAction(), KeyEvent.KEYCODE_POWER,
                    event.getRepeatCount(), event.getMetaState(),
                    event.getDeviceId(), event.getScanCode(),
                    event.getFlags(), event.getSource(), event.getDisplayId(), null);
            return interceptKeyBeforeQueueing(event, policyFlags);
        }

        // This could prevent some wrong state in multi-displays environment,
        // the default display may turned off but interactive is true.
        final boolean isDefaultDisplayOn = Display.isOnState(mDefaultDisplay.getState());
        final boolean isDefaultDisplayAwake = mDefaultDisplayPolicy.isAwake();
        final boolean interactiveAndAwake = interactive && isDefaultDisplayAwake;
        if (isKeyGestureTriggered) {
            // If key gesture is triggered outside policy, reset gesture handlers here
            mSingleKeyGestureDetector.reset();
        } else {
            if ((event.getFlags() & KeyEvent.FLAG_FALLBACK) == 0) {
                handleKeyGesture(event, interactiveAndAwake, isDefaultDisplayOn);
            }
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
                notifyKeyGestureCompletedOnActionUp(event,
                        KeyGestureEvent.KEY_GESTURE_TYPE_BACK);
                if (down) {
                    // There may have other embedded activities on the same Task. Try to move the
                    // focus before processing the back event.
                    mWindowManagerInternal.moveFocusToAdjacentEmbeddedActivityIfNeeded();
                    mBackKeyHandled = false;
                } else {
                    if (!hasLongPressOnBackBehavior()) {
                        mBackKeyHandled |= backKeyPress();
                    }
                    // Don't pass back press to app if we've already handled it via long press
                    if (mBackKeyHandled) {
                        result &= ~ACTION_PASS_TO_USER;
                    }
                }
                break;
            }

            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_MUTE: {
                int gestureType = keyCode == KEYCODE_VOLUME_DOWN
                        ? KeyGestureEvent.KEY_GESTURE_TYPE_VOLUME_DOWN
                        : keyCode == KEYCODE_VOLUME_UP
                                ? KeyGestureEvent.KEY_GESTURE_TYPE_VOLUME_UP
                                : KeyGestureEvent.KEY_GESTURE_TYPE_VOLUME_MUTE;
                notifyKeyGestureCompletedOnActionDown(event, gestureType);
                if (down) {
                    sendSystemKeyToStatusBarAsync(event);

                    NotificationManager nm = getNotificationService();
                    if (nm != null && !mHandleVolumeKeysInWM) {
                        nm.silenceNotificationSound();
                    }

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
                                sleepDefaultDisplay(event.getEventTime(),
                                        PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, 0);
                                isWakeKey = false;
                            }
                        }
                    }
                }
                break;
            }

            case KeyEvent.KEYCODE_TV_POWER: {
                notifyKeyGestureCompletedOnActionUp(event,
                        KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_POWER);
                result &= ~ACTION_PASS_TO_USER;
                isWakeKey = false; // wake-up will be handled separately
                if (down && hdmiControlManager != null) {
                    hdmiControlManager.toggleAndFollowTvPower();
                }
                break;
            }

            case KeyEvent.KEYCODE_POWER: {
                notifyKeyGestureCompletedOnActionUp(event,
                        KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_POWER);
                EventLogTags.writeInterceptPower(
                        KeyEvent.actionToString(event.getAction()),
                        mPowerKeyHandled ? 1 : 0,
                        mSingleKeyGestureDetector.getKeyPressCounter(KeyEvent.KEYCODE_POWER));
                // Any activity on the power button stops the accessibility shortcut
                result &= ~ACTION_PASS_TO_USER;
                isWakeKey = false; // wake-up will be handled separately
                if (down) {
                    interceptPowerKeyDown(event, interactiveAndAwake, isKeyGestureTriggered);
                } else {
                    interceptPowerKeyUp(event, canceled);
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
                notifyKeyGestureCompletedOnActionUp(event,
                        KeyGestureEvent.KEY_GESTURE_TYPE_SYSTEM_NAVIGATION);
                result &= ~ACTION_PASS_TO_USER;
                interceptSystemNavigationKey(event);
                break;
            }

            case KeyEvent.KEYCODE_SLEEP: {
                notifyKeyGestureCompletedOnActionUp(event,
                        KeyGestureEvent.KEY_GESTURE_TYPE_SLEEP);
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
                sendSystemKeyToStatusBarAsync(event);
                break;
            }

            case KeyEvent.KEYCODE_SOFT_SLEEP: {
                notifyKeyGestureCompletedOnActionUp(event,
                        KeyGestureEvent.KEY_GESTURE_TYPE_SLEEP);
                result &= ~ACTION_PASS_TO_USER;
                isWakeKey = false;
                if (!down) {
                    mPowerManagerInternal.setUserInactiveOverrideFromWindowManager();
                }
                sendSystemKeyToStatusBarAsync(event);
                break;
            }

            case KeyEvent.KEYCODE_WAKEUP: {
                notifyKeyGestureCompletedOnActionUp(event,
                        KeyGestureEvent.KEY_GESTURE_TYPE_WAKEUP);
                result &= ~ACTION_PASS_TO_USER;
                isWakeKey = true;
                break;
            }

            case KeyEvent.KEYCODE_MUTE:
                result &= ~ACTION_PASS_TO_USER;
                if (down && event.getRepeatCount() == 0) {
                    notifyKeyGestureCompleted(event,
                            KeyGestureEvent.KEY_GESTURE_TYPE_SYSTEM_MUTE);
                    toggleMicrophoneMuteFromKey();
                }
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK: {
                notifyKeyGestureCompletedOnActionUp(event,
                        KeyGestureEvent.KEY_GESTURE_TYPE_MEDIA_KEY);
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
                if (down && !longPressed) {
                    Message msg = mHandler.obtainMessage(MSG_LAUNCH_ASSIST, event.getDeviceId(),
                            0 /* unused */, event.getEventTime() /* eventTime */);
                    msg.setAsynchronous(true);
                    msg.sendToTarget();
                    notifyKeyGestureCompleted(event,
                            KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT);
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
                    notifyKeyGestureCompleted(event,
                            KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT);
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
            case KeyEvent.KEYCODE_STEM_PRIMARY: {
                if (down && event.getRepeatCount() == 0 && (result & ACTION_PASS_TO_USER) == 0) {
                    // We've decided not to pass key to user at queueing stage. Make the gesture
                    // executable.
                    setDeferredKeyActionsExecutableAsync(keyCode, event.getDownTime());
                }
                break;
            }
            case KeyEvent.KEYCODE_VIDEO_APP_1:
            case KeyEvent.KEYCODE_VIDEO_APP_2:
            case KeyEvent.KEYCODE_VIDEO_APP_3:
            case KeyEvent.KEYCODE_VIDEO_APP_4:
            case KeyEvent.KEYCODE_VIDEO_APP_5:
            case KeyEvent.KEYCODE_VIDEO_APP_6:
            case KeyEvent.KEYCODE_VIDEO_APP_7:
            case KeyEvent.KEYCODE_VIDEO_APP_8:
            case KeyEvent.KEYCODE_FEATURED_APP_1:
            case KeyEvent.KEYCODE_FEATURED_APP_2:
            case KeyEvent.KEYCODE_FEATURED_APP_3:
            case KeyEvent.KEYCODE_FEATURED_APP_4:
            case KeyEvent.KEYCODE_DEMO_APP_1:
            case KeyEvent.KEYCODE_DEMO_APP_2:
            case KeyEvent.KEYCODE_DEMO_APP_3:
            case KeyEvent.KEYCODE_DEMO_APP_4: {
                // Just drop if keys are not intercepted for direct key.
                result &= ~ACTION_PASS_TO_USER;
                break;
            }
            case KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY:
            case KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY:
            case KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY:
            case KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL: {
                Slog.i(TAG, "Stylus buttons event: " + keyCode + " received. Should handle event? "
                        + mStylusButtonsEnabled);
                if (mStylusButtonsEnabled) {
                    sendSystemKeyToStatusBarAsync(event);
                }
                result &= ~ACTION_PASS_TO_USER;
                break;
            }
            case KeyEvent.KEYCODE_MACRO_1:
            case KeyEvent.KEYCODE_MACRO_2:
            case KeyEvent.KEYCODE_MACRO_3:
            case KeyEvent.KEYCODE_MACRO_4:
                result &= ~ACTION_PASS_TO_USER;
                break;
            case KeyEvent.KEYCODE_EMOJI_PICKER:
                if (!emojiAndScreenshotKeycodesAvailable()) {
                    // Don't allow EMOJI_PICKER key to be dispatched until flag is released.
                    result &= ~ACTION_PASS_TO_USER;
                }
                break;
        }

        if (useHapticFeedback) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, "Virtual Key - Press");
        }

        if (isWakeKey) {
            wakeUpFromWakeKey(event);
        }

        // If the key event is targeted to a specific display, then the user is interacting with
        // that display. Therefore, try to give focus to the display that the user is interacting
        // with.
        if ((result & ACTION_PASS_TO_USER) != 0 && displayId != INVALID_DISPLAY
                && displayId != mTopFocusedDisplayId) {
            // An event is targeting a non-focused display. Move the display to top so that
            // it can become the focused display to interact with the user.
            // This should be done asynchronously, once the focus logic is fully moved to input
            // from windowmanager. Currently, we need to ensure the setInputWindows completes,
            // which would force the focus event to be queued before the current key event.
            // TODO(b/70668286): post call to 'moveDisplayToTop' to mHandler instead
            Log.i(TAG, "Attempting to move non-focused display " + displayId + " to top "
                    + "because a key is targeting it");
            mWindowManagerFuncs.moveDisplayToTopIfAllowed(displayId);
        }

        return result;
    }

    private void handleKeyGesture(KeyEvent event, boolean interactive, boolean defaultDisplayOn) {
        if (mKeyCombinationManager.interceptKey(event, interactive)) {
            // handled by combo keys manager.
            mSingleKeyGestureDetector.reset();
            return;
        }

        if (event.getKeyCode() == KEYCODE_POWER && event.getAction() == KeyEvent.ACTION_DOWN) {
            mPowerKeyHandled = handleCameraGesture(event, interactive);
            if (mPowerKeyHandled) {
                // handled by camera gesture.
                mSingleKeyGestureDetector.reset();
                return;
            }
        }

        mSingleKeyGestureDetector.interceptKey(event, interactive, defaultDisplayOn);
    }

    // The camera gesture will be detected by GestureLauncherService.
    private boolean handleCameraGesture(KeyEvent event, boolean interactive) {
        // camera gesture.
        if (mGestureLauncherService == null) {
            return false;
        }
        mCameraGestureTriggered = false;
        final MutableBoolean outLaunched = new MutableBoolean(false);
        final boolean intercept =
                mGestureLauncherService.interceptPowerKeyDown(event, interactive, outLaunched);
        if (!outLaunched.value) {
            // If GestureLauncherService intercepted the power key, but didn't launch camera app,
            // we should still return the intercept result. This prevents the single key gesture
            // detector from processing the power key later on.
            return intercept;
        }
        mCameraGestureTriggered = true;
        if (mRequestedOrSleepingDefaultDisplay) {
            mCameraGestureTriggeredDuringGoingToSleep = true;
            // Wake device up early to prevent display doing redundant turning off/on stuff.
            mWindowWakeUpPolicy.wakeUpFromPowerKeyCameraGesture();
        }
        return true;
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
                    sendSystemKeyToStatusBarAsync(event);
                }
            }
        }
    }

    /**
     * Notify the StatusBar that a system key was pressed.
     */
    private void sendSystemKeyToStatusBar(KeyEvent key) {
        if (!isKeyEventForCurrentUser(key.getDisplayId(), key.getKeyCode(), "handleSystemKey")) {
            return;
        }
        IStatusBarService statusBar = getStatusBarService();
        if (statusBar != null) {
            try {
                statusBar.handleSystemKey(key);
            } catch (RemoteException e) {
                // Oh well.
            }
        }
    }

    /**
     * Notify the StatusBar that a system key was pressed without blocking the current thread.
     */
    private void sendSystemKeyToStatusBarAsync(KeyEvent keyEvent) {
        // Make a copy because the event may be recycled.
        Message message = mHandler.obtainMessage(MSG_SYSTEM_KEY_PRESS, KeyEvent.obtain(keyEvent));
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
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return mWakeOnDpadKeyPress;

            case KeyEvent.KEYCODE_ASSIST:
                return mWakeOnAssistKeyPress;

            case KeyEvent.KEYCODE_BACK:
                return mWakeOnBackKeyPress;

            case KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY:
            case KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY:
            case KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY:
            case KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL:
                return mStylusButtonsEnabled;
        }

        return true;
    }

    // TODO(b/117479243): handle it in InputPolicy
    /** {@inheritDoc} */
    @Override
    public int interceptMotionBeforeQueueingNonInteractive(int displayId, int source, int action,
            long whenNanos, int policyFlags) {
        if ((policyFlags & FLAG_WAKE) != 0) {
            if (mWindowWakeUpPolicy.wakeUpFromMotion(
                        whenNanos / 1000000, source, action == MotionEvent.ACTION_DOWN)) {
                // Woke up. Pass motion events to user.
                return ACTION_PASS_TO_USER;
            }
        }

        if (shouldDispatchInputWhenNonInteractive(displayId, KEYCODE_UNKNOWN)) {
            return ACTION_PASS_TO_USER;
        }

        // If we have not passed the action up and we are in theater mode without dreaming,
        // there will be no dream to intercept the touch and wake into ambient.  The device should
        // wake up in this case.
        if (isTheaterModeEnabled() && (policyFlags & FLAG_WAKE) != 0) {
            if (mWindowWakeUpPolicy.wakeUpFromMotion(
                        whenNanos / 1000000, source, action == MotionEvent.ACTION_DOWN)) {
                // Woke up. Pass motion events to user.
                return ACTION_PASS_TO_USER;
            }
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

        if (displayOff) {
            return false;
        }

        // Send events to keyguard while the screen is on and it's showing.
        if (isKeyguardShowingAndNotOccluded()) {
            return true;
        }

        // TODO(b/123372519): Refine when dream can support multi display.
        if (isDefaultDisplay) {
            // Send events to a dozing dream since the dream is in control of the state of the
            // screen.
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

    // pre-condition: event.getKeyCode() is one of KeyEvent.KEYCODE_VOLUME_UP,
    //                                   KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE
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
        try {
            getAudioService().handleVolumeKey(event, mUseTvRouting,
                    mContext.getOpPackageName(), TAG);
        } catch (Exception e) {
            Log.e(TAG, "Error dispatching volume key in handleVolumeKey for event:"
                    + event, e);
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
            DeviceIdleManager dim = mContext.getSystemService(DeviceIdleManager.class);
            if (dim != null) {
                dim.endIdle("voice-search");
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

    @Override
    public void startedWakingUpGlobal(@WakeReason int reason) {

    }

    @Override
    public void finishedWakingUpGlobal(@WakeReason int reason) {

    }

    @Override
    public void startedGoingToSleepGlobal(@PowerManager.GoToSleepReason int reason) {
        mDeviceGoingToSleep = true;
    }

    @Override
    public void finishedGoingToSleepGlobal(@PowerManager.GoToSleepReason int reason) {
        mDeviceGoingToSleep = false;
    }

    // Called on the PowerManager's Notifier thread.
    @Override
    public void startedGoingToSleep(int displayGroupId,
            @PowerManager.GoToSleepReason int pmSleepReason) {
        if (DEBUG_WAKEUP) {
            Slog.i(TAG, "Started going to sleep... (groupId=" + displayGroupId + " why="
                    + WindowManagerPolicyConstants.offReasonToString(
                            WindowManagerPolicyConstants.translateSleepReasonToOffReason(
                                    pmSleepReason)) + ")");
        }
        if (displayGroupId != Display.DEFAULT_DISPLAY_GROUP) {
            return;
        }

        mRequestedOrSleepingDefaultDisplay = true;
        mIsGoingToSleepDefaultDisplay = true;

        if (mKeyguardDelegate != null) {
            mKeyguardDelegate.onStartedGoingToSleep(pmSleepReason);
        }
    }

    // Called on the PowerManager's Notifier thread.
    @Override
    public void finishedGoingToSleep(int displayGroupId,
            @PowerManager.GoToSleepReason int pmSleepReason) {
        if (displayGroupId != Display.DEFAULT_DISPLAY_GROUP) {
            return;
        }
        EventLogTags.writeScreenToggled(0);
        if (DEBUG_WAKEUP) {
            Slog.i(TAG, "Finished going to sleep... (groupId=" + displayGroupId + " why="
                    + WindowManagerPolicyConstants.offReasonToString(
                            WindowManagerPolicyConstants.translateSleepReasonToOffReason(
                                    pmSleepReason)) + ")");
        }
        MetricsLogger.histogram(mContext, "screen_timeout", mLockScreenTimeout / 1000);

        mRequestedOrSleepingDefaultDisplay = false;
        mIsGoingToSleepDefaultDisplay = false;
        mDefaultDisplayPolicy.setAwake(false);

        // We must get this work done here because the power manager will drop
        // the wake lock and let the system suspend once this function returns.
        synchronized (mLock) {
            updateWakeGestureListenerLp();
            updateLockScreenTimeout();
        }
        mDefaultDisplayRotation.updateOrientationListener();

        if (mKeyguardDelegate != null) {
            mKeyguardDelegate.onFinishedGoingToSleep(pmSleepReason,
                    mCameraGestureTriggeredDuringGoingToSleep);
        }
        if (mDisplayFoldController != null) {
            mDisplayFoldController.finishedGoingToSleep();
        }
        mCameraGestureTriggeredDuringGoingToSleep = false;
        mCameraGestureTriggered = false;
    }

    // Called on the PowerManager's Notifier thread.
    @Override
    public void startedWakingUp(int displayGroupId, @WakeReason int pmWakeReason) {
        if (DEBUG_WAKEUP) {
            Slog.i(TAG, "Started waking up... (groupId=" + displayGroupId + " why="
                    + WindowManagerPolicyConstants.onReasonToString(
                    WindowManagerPolicyConstants.translateWakeReasonToOnReason(
                            pmWakeReason)) + ")");
        }
        if (displayGroupId != Display.DEFAULT_DISPLAY_GROUP) {
            return;
        }
        EventLogTags.writeScreenToggled(1);

        mIsGoingToSleepDefaultDisplay = false;
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
            mKeyguardDelegate.onStartedWakingUp(pmWakeReason, mCameraGestureTriggered);
        }

        mCameraGestureTriggered = false;
    }

    // Called on the PowerManager's Notifier thread.
    @Override
    public void finishedWakingUp(int displayGroupId, @WakeReason int pmWakeReason) {
        if (DEBUG_WAKEUP) {
            Slog.i(TAG, "Finished waking up... (groupId=" + displayGroupId + " why="
                    + WindowManagerPolicyConstants.onReasonToString(
                            WindowManagerPolicyConstants.translateWakeReasonToOnReason(
                                    pmWakeReason)) + ")");
        }
        if (displayGroupId != Display.DEFAULT_DISPLAY_GROUP) {
            return;
        }

        if (mKeyguardDelegate != null) {
            mKeyguardDelegate.onFinishedWakingUp();
        }
        if (mDisplayFoldController != null) {
            mDisplayFoldController.finishedWakingUp();
        }
    }

    private boolean shouldWakeUpWithHomeIntent() {
        if (mWakeUpToLastStateTimeout <= 0) {
            return false;
        }

        final long sleepDurationRealtime =
                mPowerManagerInternal.getLastWakeup().sleepDurationRealtime;
        if (DEBUG_WAKEUP) {
            Log.i(TAG, "shouldWakeUpWithHomeIntent: sleepDurationRealtime= " + sleepDurationRealtime
                    + " mWakeUpToLastStateTimeout= " + mWakeUpToLastStateTimeout);
        }
        return sleepDurationRealtime > mWakeUpToLastStateTimeout;
    }

    private void wakeUpFromWakeKey(KeyEvent event) {
        if (!isKeyEventForCurrentUser(
                event.getDisplayId(), event.getKeyCode(), "wakeUpFromWakeKey")) {
            return;
        }
        wakeUpFromWakeKey(
                event.getEventTime(),
                event.getKeyCode(),
                event.getAction() == KeyEvent.ACTION_DOWN);
    }

    private void wakeUpFromWakeKey(long eventTime, int keyCode, boolean isDown) {
        if (mWindowWakeUpPolicy.wakeUpFromKey(eventTime, keyCode, isDown)) {
            final boolean keyCanLaunchHome = keyCode == KEYCODE_HOME || keyCode == KEYCODE_POWER;
            // Start HOME with "reason" extra if sleeping for more than mWakeUpToLastStateTimeout
            if (shouldWakeUpWithHomeIntent() &&  keyCanLaunchHome) {
                startDockOrHome(
                        DEFAULT_DISPLAY,
                        /*fromHomeKey*/ keyCode == KEYCODE_HOME,
                        /*wakenFromDreams*/ true,
                        "Wake from " + KeyEvent. keyCodeToString(keyCode));
            }
        }
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
        Trace.asyncTraceBegin(Trace.TRACE_TAG_WINDOW_MANAGER,
                TRACE_WAIT_FOR_ALL_WINDOWS_DRAWN_METHOD, INVALID_DISPLAY /* cookie */);
        mWindowManagerInternal.waitForAllWindowsDrawn(mHandler.obtainMessage(
                MSG_WINDOW_MANAGER_DRAWN_COMPLETE, INVALID_DISPLAY, 0),
                WAITING_FOR_DRAWN_TIMEOUT, INVALID_DISPLAY);
    }

    // Called on the DisplayManager's DisplayPowerController thread.
    @Override
    public void screenTurnedOff(int displayId, boolean isSwappingDisplay) {
        if (DEBUG_WAKEUP) Slog.i(TAG, "Display" + displayId + " turned off...");

        if (displayId == DEFAULT_DISPLAY) {
            final boolean acquireSleepToken = !isSwappingDisplay || mIsGoingToSleepDefaultDisplay;
            mRequestedOrSleepingDefaultDisplay = false;
            mDefaultDisplayPolicy.screenTurnedOff(acquireSleepToken);
            synchronized (mLock) {
                if (mKeyguardDelegate != null) {
                    mKeyguardDelegate.onScreenTurnedOff();
                }
            }
            mDefaultDisplayRotation.updateOrientationListener();
            reportScreenStateToVrManager(false);
        }
    }

    @Override
    public void onDisplaySwitchStart(int displayId) {
        if (displayId == DEFAULT_DISPLAY) {
            mDefaultDisplayPolicy.onDisplaySwitchStart();
        }
    }

    private long getKeyguardDrawnTimeout() {
        final boolean bootCompleted =
                LocalServices.getService(SystemServiceManager.class).isBootCompleted();
        // Set longer timeout if it has not booted yet to prevent showing empty window.
        return bootCompleted ? mKeyguardDrawnTimeout : 5000;
    }

    @Nullable
    private WallpaperManagerInternal getWallpaperManagerInternal() {
        if (mWallpaperManagerInternal == null) {
            mWallpaperManagerInternal = LocalServices.getService(WallpaperManagerInternal.class);
        }
        return mWallpaperManagerInternal;
    }

    private void reportScreenTurningOnToWallpaper(int displayId) {
        WallpaperManagerInternal wallpaperManagerInternal = getWallpaperManagerInternal();
        if (wallpaperManagerInternal != null) {
            wallpaperManagerInternal.onScreenTurningOn(displayId);
        }
    }

    private void reportScreenTurnedOnToWallpaper(int displayId) {
        WallpaperManagerInternal wallpaperManagerInternal = getWallpaperManagerInternal();
        if (wallpaperManagerInternal != null) {
            wallpaperManagerInternal.onScreenTurnedOn(displayId);
        }
    }

    // Called on the DisplayManager's DisplayPowerController thread.
    @Override
    public void screenTurningOn(int displayId, final ScreenOnListener screenOnListener) {
        if (DEBUG_WAKEUP) Slog.i(TAG, "Display " + displayId + " turning on...");

        reportScreenTurningOnToWallpaper(displayId);
        if (displayId == DEFAULT_DISPLAY) {
            Trace.asyncTraceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "screenTurningOn",
                    0 /* cookie */);
            mDefaultDisplayPolicy.screenTurningOn(screenOnListener);
            mBootAnimationDismissable = false;

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
        } else {
            mScreenOnListeners.put(displayId, screenOnListener);

            Trace.asyncTraceBegin(Trace.TRACE_TAG_WINDOW_MANAGER,
                    TRACE_WAIT_FOR_ALL_WINDOWS_DRAWN_METHOD, displayId /* cookie */);
            mWindowManagerInternal.waitForAllWindowsDrawn(mHandler.obtainMessage(
                    MSG_WINDOW_MANAGER_DRAWN_COMPLETE, displayId, 0),
                    WAITING_FOR_DRAWN_TIMEOUT, displayId);
        }
    }

    // Called on the DisplayManager's DisplayPowerController thread.
    @Override
    public void screenTurnedOn(int displayId) {
        if (DEBUG_WAKEUP) Slog.i(TAG, "Display " + displayId + " turned on...");

        reportScreenTurnedOnToWallpaper(displayId);

        if (displayId != DEFAULT_DISPLAY) {
            return;
        }

        synchronized (mLock) {
            if (mKeyguardDelegate != null) {
                mKeyguardDelegate.onScreenTurnedOn();
            }
        }
        mDefaultDisplayPolicy.screenTurnedOn();
        reportScreenStateToVrManager(true);
    }

    @Override
    public void screenTurningOff(int displayId, ScreenOffListener screenOffListener) {
        mWindowManagerFuncs.screenTurningOff(displayId, screenOffListener);
        if (displayId != DEFAULT_DISPLAY) {
            return;
        }

        mRequestedOrSleepingDefaultDisplay = true;
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

    private void finishWindowsDrawn(int displayId) {
        if (displayId != DEFAULT_DISPLAY && displayId != INVALID_DISPLAY) {
            final ScreenOnListener screenOnListener = mScreenOnListeners.removeReturnOld(displayId);
            if (screenOnListener != null) {
                screenOnListener.onScreenOn();
            }
            return;
        }

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
        Trace.asyncTraceEnd(Trace.TRACE_TAG_WINDOW_MANAGER, "screenTurningOn", 0 /* cookie */);

        enableScreen(listener, true /* report */);
    }

    private void enableScreen(ScreenOnListener listener, boolean report) {
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

        if (report) {
            if (listener != null) {
                listener.onScreenOn();
            }
        }

        if (enableScreen) {
            mWindowManagerFuncs.enableScreenIfNeeded();
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
    public boolean okToAnimate(boolean ignoreScreenOn) {
        return (ignoreScreenOn || isScreenOn()) && !mDeviceGoingToSleep;
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
    public boolean isKeyguardShowing() {
        if (mKeyguardDelegate == null) return false;
        return mKeyguardDelegate.isShowing();
    }

    @Override
    public boolean isKeyguardShowingAndNotOccluded() {
        if (mKeyguardDelegate == null) return false;
        return mKeyguardDelegate.isShowing() && !isKeyguardOccluded();
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
        return mKeyguardDelegate.isOccluded();
    }

    /** {@inheritDoc} */
    @Override
    public boolean inKeyguardRestrictedKeyInputMode() {
        if (mKeyguardDelegate == null) return false;
        return mKeyguardDelegate.isInputRestricted();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isKeyguardUnoccluding() {
        return keyguardOn() && !mWindowManagerFuncs.isAppTransitionStateIdle();
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
    public void startKeyguardExitAnimation(long startTime) {
        if (mKeyguardDelegate != null) {
            if (DEBUG_KEYGUARD) Slog.d(TAG, "PWM.startKeyguardExitAnimation");
            mKeyguardDelegate.startKeyguardExitAnimation(startTime);
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
            performHapticFeedback(
                    HapticFeedbackConstants.SAFE_MODE_ENABLED,
                    "Safe Mode Enabled" /* reason */,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
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
            updateSettings(mHandler);
            // If this happens, for whatever reason, systemReady came later than systemBooted.
            // And keyguard should be already bound from systemBooted
            if (mSystemBooted) {
                mKeyguardDelegate.onBootCompleted();
            }
        }

        mAutofillManagerInternal = LocalServices.getService(AutofillManagerInternal.class);
        mGestureLauncherService = LocalServices.getService(GestureLauncherService.class);
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
        mSideFpsEventHandler.onFingerprintSensorReady();
        startedWakingUp(Display.DEFAULT_DISPLAY_GROUP, PowerManager.WAKE_REASON_UNKNOWN);
        finishedWakingUp(Display.DEFAULT_DISPLAY_GROUP, PowerManager.WAKE_REASON_UNKNOWN);

        int defaultDisplayState = mDisplayManager.getDisplay(DEFAULT_DISPLAY).getState();
        boolean defaultDisplayOn = defaultDisplayState == Display.STATE_ON;
        boolean defaultScreenTurningOn = mDefaultDisplayPolicy.getScreenOnListener() != null;
        if (defaultDisplayOn || defaultScreenTurningOn) {
            // Now that system is booted, wait for keyguard and windows to be drawn before
            // updating the orientation listener, stopping the boot animation and enabling screen.
            screenTurningOn(DEFAULT_DISPLAY, mDefaultDisplayPolicy.getScreenOnListener());
            screenTurnedOn(DEFAULT_DISPLAY);
        } else {
            // We're not turning the screen on, so don't wait for keyguard to be drawn
            // to dismiss the boot animation and finish booting
            mBootAnimationDismissable = true;
            enableScreen(null, false /* report */);
        }
    }

    @Override
    public boolean canDismissBootAnimation() {
        // Allow to dismiss the boot animation if the keyguard has finished drawing,
        // or mBootAnimationDismissable has been set
        return mDefaultDisplayPolicy.isKeyguardDrawComplete() || mBootAnimationDismissable;
    }

    ProgressDialog mBootMsgDialog = null;

    /** {@inheritDoc} */
    @Override
    public void showBootMessage(final CharSequence msg, final boolean always) {
        mHandler.post(new Runnable() {
            @Override public void run() {
                if (mBootMsgDialog == null) {
                    int theme;
                    if (mPackageManager.hasSystemFeature(FEATURE_LEANBACK)) {
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
                    if (mPackageManager.isDeviceUpgrading()) {
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
                    lp.setFitInsetsTypes(0 /* types */);
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

    /** {@inheritDoc} */
    @Override
    public void userActivity(int displayGroupId, int event) {
        if (displayGroupId == DEFAULT_DISPLAY && event == PowerManager.USER_ACTIVITY_EVENT_TOUCH) {
            mDefaultDisplayPolicy.onUserActivityEventTouch();
        }
        synchronized (mScreenLockTimeout) {
            if (mLockScreenTimerActive) {
                // reset the timer
                mHandler.removeCallbacks(mScreenLockTimeout);
                mHandler.postDelayed(mScreenLockTimeout, mLockScreenTimeout);
            }
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
        // We should ignore this operation for visible background users
        // until lockscreen supports multi-display.
        if (mVisibleBackgroundUsersEnabled
                && mUserManagerInternal.getUserAssignedToDisplay(displayId) != mCurrentUserId) {
            return;
        }
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

    /** {@inheritDoc} */
    @Override
    public void enableScreenAfterBoot() {
        readLidState();
        applyLidSwitchState();
        updateRotation(true);
    }

    private void applyLidSwitchState() {
        final int lidState = mDefaultDisplayPolicy.getLidState();
        if (lidState == LID_CLOSED) {
            int lidBehavior = getLidBehavior();
            switch (lidBehavior) {
                case LID_BEHAVIOR_LOCK:
                    mWindowManagerFuncs.lockDeviceNow();
                    break;
                case LID_BEHAVIOR_SLEEP:
                    sleepDefaultDisplay(SystemClock.uptimeMillis(),
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
        mWindowManagerFuncs.updateRotation(alwaysSendConfiguration, false /* forceRelayout */);
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
        ResolveInfo info = mPackageManager.resolveActivityAsUser(
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

    void startDockOrHome(int displayId, boolean fromHomeKey, boolean awakenFromDreams,
            String startReason) {
        try {
            ActivityManager.getService().stopAppSwitches();
        } catch (RemoteException e) {}
        sendCloseSystemWindows(SYSTEM_DIALOG_REASON_HOME_KEY);

        if (awakenFromDreams) {
            awakenDreams();
        }

        if (!mHasFeatureAuto && !isUserSetupComplete()) {
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

        if (DEBUG_WAKEUP) {
            Log.d(TAG, "startDockOrHome: startReason= " + startReason);
        }

        int userId = mUserManagerInternal.getUserAssignedToDisplay(displayId);
        // Start home.
        mActivityTaskManagerInternal.startHomeOnDisplay(userId, startReason,
                displayId, true /* allowInstrumenting */, fromHomeKey);
    }

    void startDockOrHome(int displayId, boolean fromHomeKey, boolean awakenFromDreams) {
        startDockOrHome(displayId, fromHomeKey, awakenFromDreams, /*startReason*/
                "startDockOrHome");
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
                                .startActivityAsUser(null, mContext.getOpPackageName(),
                                        mContext.getAttributionTag(), dock,
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
                        .startActivityAsUser(null, mContext.getOpPackageName(),
                                mContext.getAttributionTag(), mHomeIntent,
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

    private void performHapticFeedback(int effectId, String reason) {
        performHapticFeedback(effectId, reason, 0 /* flags */);
    }

    private void performHapticFeedback(
            int effectId, String reason, @HapticFeedbackConstants.Flags int flags) {
        mVibrator.performHapticFeedback(effectId, reason, flags, 0 /* privFlags */);
    }

    @Override
    public boolean isGlobalKey(int keyCode) {
        return mGlobalKeyManager.shouldHandleGlobalKey(keyCode);
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
        if (modifierShortcutManagerMultiuser()) {
            mModifierShortcutManager.setCurrentUser(UserHandle.of(newUserId));
        }
    }

    @Override
    public void setSwitchingUser(boolean switching) {
        mKeyguardDelegate.setSwitchingUser(switching);
        if (switching) {
            dismissKeyboardShortcutsMenu();
        }
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(ROTATION_MODE, mDefaultDisplayRotation.getUserRotationMode());
        proto.write(ROTATION, mDefaultDisplayRotation.getUserRotation());
        proto.write(ORIENTATION, mDefaultDisplayRotation.getCurrentAppOrientation());
        proto.write(SCREEN_ON_FULLY, mDefaultDisplayPolicy.isScreenOnFully());
        proto.write(KEYGUARD_DRAW_COMPLETE, mDefaultDisplayPolicy.isKeyguardDrawComplete());
        proto.write(WINDOW_MANAGER_DRAW_COMPLETE,
                mDefaultDisplayPolicy.isWindowManagerDrawComplete());
        proto.write(KEYGUARD_OCCLUDED, isKeyguardOccluded());
        proto.write(KEYGUARD_OCCLUDED_CHANGED, mKeyguardOccludedChanged);
        proto.write(KEYGUARD_OCCLUDED_PENDING, mPendingKeyguardOccluded);
        if (mKeyguardDelegate != null) {
            mKeyguardDelegate.dumpDebug(proto, KEYGUARD_DELEGATE);
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
        pw.print("mSettingsKeyBehavior=");
        pw.println(settingsKeyBehaviorToString(mSettingsKeyBehavior));
        pw.print(prefix);
        pw.print("mLongPressOnPowerAssistantTimeoutMs=");
        pw.println(mLongPressOnPowerAssistantTimeoutMs);
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
                pw.print("mSupportShortPressPowerWhenDefaultDisplayOn=");
                pw.println(mSupportShortPressPowerWhenDefaultDisplayOn);
        pw.print(prefix);
        pw.print("mPowerVolUpBehavior=");
        pw.println(powerVolumeUpBehaviorToString(mPowerVolUpBehavior));
        pw.print(prefix);
                pw.print("mShortPressOnSleepBehavior=");
                pw.println(shortPressOnSleepBehaviorToString(mShortPressOnSleepBehavior));
        pw.print(prefix);
                pw.print("mShortPressOnWindowBehavior=");
                pw.println(shortPressOnWindowBehaviorToString(mShortPressOnWindowBehavior));
        pw.print(prefix);
                pw.print("mShortPressOnStemPrimaryBehavior=");
                pw.println(shortPressOnStemPrimaryBehaviorToString(
                    mShortPressOnStemPrimaryBehavior));
        pw.print(prefix);
                pw.print("mDoublePressOnStemPrimaryBehavior=");
                pw.println(doublePressOnStemPrimaryBehaviorToString(
                    mDoublePressOnStemPrimaryBehavior));
        pw.print(prefix);
                pw.print("mTriplePressOnStemPrimaryBehavior=");
                pw.println(triplePressOnStemPrimaryBehaviorToString(
                    mTriplePressOnStemPrimaryBehavior));
        pw.print(prefix);
                pw.print("mLongPressOnStemPrimaryBehavior=");
                pw.println(longPressOnStemPrimaryBehaviorToString(
                    mLongPressOnStemPrimaryBehavior));
        pw.print(prefix);
                pw.print("mAllowStartActivityForLongPressOnPowerDuringSetup=");
                pw.println(mAllowStartActivityForLongPressOnPowerDuringSetup);
        pw.print(prefix);
                pw.print("mHasSoftInput="); pw.println(mHasSoftInput);
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
        pw.println("mDisplayHomeButtonHandlers=");
        for (int i = 0; i < mDisplayHomeButtonHandlers.size(); i++) {
            final int key = mDisplayHomeButtonHandlers.keyAt(i);
            pw.print(prefix); pw.print("  "); pw.println(mDisplayHomeButtonHandlers.get(key));
        }
        pw.print(prefix); pw.print("mKeyguardOccluded="); pw.print(isKeyguardOccluded());
                pw.print(" mKeyguardOccludedChanged="); pw.print(mKeyguardOccludedChanged);
                pw.print(" mPendingKeyguardOccluded="); pw.println(mPendingKeyguardOccluded);
        pw.print(prefix); pw.print("mAllowLockscreenWhenOnDisplays=");
                pw.print(!mAllowLockscreenWhenOnDisplays.isEmpty());
                pw.print(" mLockScreenTimeout="); pw.print(mLockScreenTimeout);
                pw.print(" mLockScreenTimerActive="); pw.println(mLockScreenTimerActive);
        pw.print(prefix); pw.print("mKidsModeEnabled="); pw.println(mKidsModeEnabled);

        mGlobalKeyManager.dump(prefix, pw);
        mKeyCombinationManager.dump(prefix, pw);
        mSingleKeyGestureDetector.dump(prefix, pw);
        mDeferredKeyActionExecutor.dump(prefix, pw);

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
        if (modifierShortcutDump()) {
            mModifierShortcutManager.dump(prefix, pw);
        }
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
            case LONG_PRESS_HOME_NOTIFICATION_PANEL:
                return "LONG_PRESS_HOME_NOTIFICATION_PANEL";
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
            case DOUBLE_TAP_HOME_PIP_MENU:
                return "DOUBLE_TAP_HOME_PIP_MENU";
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

    private static String settingsKeyBehaviorToString(int behavior) {
        switch (behavior) {
            case SETTINGS_KEY_BEHAVIOR_SETTINGS_ACTIVITY:
                return "SETTINGS_KEY_BEHAVIOR_SETTINGS_ACTIVITY";
            case SETTINGS_KEY_BEHAVIOR_NOTIFICATION_PANEL:
                return "SETTINGS_KEY_BEHAVIOR_NOTIFICATION_PANEL";
            case SETTINGS_KEY_BEHAVIOR_NOTHING:
                return "SETTINGS_KEY_BEHAVIOR_NOTHING";
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

    private static String powerVolumeUpBehaviorToString(int behavior) {
        switch (behavior) {
            case POWER_VOLUME_UP_BEHAVIOR_NOTHING:
                return "POWER_VOLUME_UP_BEHAVIOR_NOTHING";
            case POWER_VOLUME_UP_BEHAVIOR_MUTE:
                return "POWER_VOLUME_UP_BEHAVIOR_MUTE";
            case POWER_VOLUME_UP_BEHAVIOR_GLOBAL_ACTIONS:
                return "POWER_VOLUME_UP_BEHAVIOR_GLOBAL_ACTIONS";
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
            case MULTI_PRESS_POWER_LAUNCH_TARGET_ACTIVITY:
                return "MULTI_PRESS_POWER_LAUNCH_TARGET_ACTIVITY";
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

    private static String shortPressOnStemPrimaryBehaviorToString(int behavior) {
        switch (behavior) {
            case SHORT_PRESS_PRIMARY_NOTHING:
                return "SHORT_PRESS_PRIMARY_NOTHING";
            case SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS:
                return "SHORT_PRESS_PRIMARY_LAUNCH_ALL_APPS";
            case SHORT_PRESS_PRIMARY_LAUNCH_TARGET_ACTIVITY:
                return "SHORT_PRESS_PRIMARY_LAUNCH_TARGET_ACTIVITY";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String doublePressOnStemPrimaryBehaviorToString(int behavior) {
        switch (behavior) {
            case DOUBLE_PRESS_PRIMARY_NOTHING:
                return "DOUBLE_PRESS_PRIMARY_NOTHING";
            case DOUBLE_PRESS_PRIMARY_SWITCH_RECENT_APP:
                return "DOUBLE_PRESS_PRIMARY_SWITCH_RECENT_APP";
            case DOUBLE_PRESS_PRIMARY_LAUNCH_DEFAULT_FITNESS_APP:
                return "DOUBLE_PRESS_PRIMARY_LAUNCH_DEFAULT_FITNESS_APP";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String triplePressOnStemPrimaryBehaviorToString(int behavior) {
        switch (behavior) {
            case TRIPLE_PRESS_PRIMARY_NOTHING:
                return "TRIPLE_PRESS_PRIMARY_NOTHING";
            case TRIPLE_PRESS_PRIMARY_TOGGLE_ACCESSIBILITY:
                return "TRIPLE_PRESS_PRIMARY_TOGGLE_ACCESSIBILITY";
            default:
                return Integer.toString(behavior);
        }
    }

    private static String longPressOnStemPrimaryBehaviorToString(int behavior) {
        switch (behavior) {
            case LONG_PRESS_PRIMARY_NOTHING:
                return "LONG_PRESS_PRIMARY_NOTHING";
            case LONG_PRESS_PRIMARY_LAUNCH_VOICE_ASSISTANT:
                return "LONG_PRESS_PRIMARY_LAUNCH_VOICE_ASSISTANT";
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

    public static boolean isLongPressToAssistantEnabled(Context context) {
        ContentResolver resolver = context.getContentResolver();
        int longPressToAssistant = Settings.System.getIntForUser(resolver,
                Settings.Global.Wearable.CLOCKWORK_LONG_PRESS_TO_ASSISTANT_ENABLED,
                /* def= */ 1,
                UserHandle.USER_CURRENT);
        if(Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "longPressToAssistant = " + longPressToAssistant);
        }
        return (longPressToAssistant == 1);
    }

    private class HdmiVideoExtconUEventObserver extends ExtconStateObserver<Boolean> {
        private static final String HDMI_EXIST = "HDMI=1";
        private static final String DP_EXIST = "DP=1";
        private static final String NAME = "hdmi";

        private boolean init(ExtconInfo hdmi) {
            boolean plugged = false;
            try {
                plugged = parseStateFromFile(hdmi);
            } catch (FileNotFoundException e) {
                Slog.w(TAG,
                        hdmi.getStatePath()
                                + " not found while attempting to determine initial state",
                        e);
            } catch (IOException e) {
                Slog.e(TAG,
                        "Error reading " + hdmi.getStatePath()
                                + " while attempting to determine initial state",
                        e);
            }
            startObserving(hdmi);
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
            // or like STATE=DP=1 for newer kernel
            return state.contains(HDMI_EXIST) || state.contains(DP_EXIST);
        }
    }

    private void launchTargetSearchActivity() {
        Intent intent;
        if (mSearchKeyTargetActivity != null) {
            intent = new Intent();
            intent.setComponent(mSearchKeyTargetActivity);
        } else {
            intent = new Intent(Intent.ACTION_WEB_SEARCH);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivityAsUser(intent, UserHandle.CURRENT_OR_SELF);
        } catch (ActivityNotFoundException ignore) {
            Slog.e(TAG, "Could not resolve activity with : "
                    + intent.getComponent().flattenToString()
                    + " name.");
        }
    }

    /** A helper class to check button override permission. */
    static class ButtonOverridePermissionChecker {
        boolean canAppOverrideSystemKey(Context context, int uid) {
            return PermissionChecker.checkPermissionForDataDelivery(
                    context,
                    OVERRIDE_SYSTEM_KEY_BEHAVIOR_IN_FOCUSED_WINDOW,
                    PID_UNKNOWN,
                    uid,
                    null,
                    null,
                    null)
                    == PERMISSION_GRANTED;
        }
    }

    private int getTargetDisplayIdForKeyEvent(KeyEvent event) {
        if (event.getDisplayId() != INVALID_DISPLAY) {
            return event.getDisplayId();
        }
        if (mTopFocusedDisplayId != INVALID_DISPLAY) {
            return mTopFocusedDisplayId;
        }
        return DEFAULT_DISPLAY;
    }

    private int getTargetDisplayIdForKeyGestureEvent(KeyGestureEvent event) {
        if (event.getDisplayId() != INVALID_DISPLAY) {
            return event.getDisplayId();
        }
        if (mTopFocusedDisplayId != INVALID_DISPLAY) {
            return mTopFocusedDisplayId;
        }
        return DEFAULT_DISPLAY;
    }

    /**
     * This method is intended to prevent key events for visible background users
     * from interfering with the current user's experience in MUMD environment.
     *
     * @param displayId the displayId of the key event.
     * @param keyCode the key code of the event.
     *
     * @return false if the key event is for a visible background user.
     */
    private boolean isKeyEventForCurrentUser(int displayId, int keyCode, @Nullable String purpose) {
        if (!mVisibleBackgroundUsersEnabled) {
            return true;
        }
        int assignedUser = mUserManagerInternal.getUserAssignedToDisplay(displayId);
        if (assignedUser == mCurrentUserId) {
            return true;
        }
        if (DEBUG_INPUT) {
            Slog.w(TAG, "Cannot handle " + KeyEvent.keyCodeToString(keyCode)
                    + (purpose != null ? " to " + purpose : "")
                    + " for visible background user(u" + assignedUser + ")");
        }
        return false;
    }
}
