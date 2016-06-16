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

package com.android.server.wm;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManagerInternal;
import android.app.ActivityManagerNative;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.SystemService;
import android.os.Trace;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.TypedValue;
import android.view.AppTransitionAnimationSpec;
import android.view.Choreographer;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.PointerIcon;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.IApplicationToken;
import android.view.IDockedStackListener;
import android.view.IInputFilter;
import android.view.IOnKeyguardExitResult;
import android.view.IRotationWatcher;
import android.view.IWindow;
import android.view.IWindowId;
import android.view.IWindowManager;
import android.view.IWindowSession;
import android.view.IWindowSessionCallback;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.KeyEvent;
import android.view.MagnificationSpec;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.View;
import android.view.WindowContentFrameStats;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerInternal;
import android.view.WindowManagerPolicy;
import android.view.WindowManagerPolicy.PointerEventListener;
import android.view.animation.Animation;
import android.view.inputmethod.InputMethodManagerInternal;

import com.android.internal.R;
import com.android.internal.app.IAssistScreenshotReceiver;
import com.android.internal.os.IResultReceiver;
import com.android.internal.policy.IShortcutService;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.view.IInputMethodManager;
import com.android.internal.view.WindowManagerPolicyThread;
import com.android.server.AttributeCache;
import com.android.server.DisplayThread;
import com.android.server.EventLogTags;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.UiThread;
import com.android.server.Watchdog;
import com.android.server.input.InputManagerService;
import com.android.server.policy.PhoneWindowManager;
import com.android.server.power.ShutdownThread;

import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.Socket;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import static android.app.ActivityManager.DOCKED_STACK_CREATE_MODE_TOP_OR_LEFT;
import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.app.StatusBarManager.DISABLE_MASK;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.WindowManager.DOCKED_BOTTOM;
import static android.view.WindowManager.DOCKED_INVALID;
import static android.view.WindowManager.DOCKED_TOP;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_BOOT_PROGRESS;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_DREAM;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_QS_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManagerGlobal.RELAYOUT_DEFER_SURFACE_DESTROY;
import static android.view.WindowManagerGlobal.RELAYOUT_RES_SURFACE_CHANGED;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static android.view.WindowManagerPolicy.TRANSIT_EXIT;
import static android.view.WindowManagerPolicy.TRANSIT_PREVIEW_DONE;
import static com.android.server.wm.AppWindowAnimator.PROLONG_ANIMATION_AT_END;
import static com.android.server.wm.AppWindowAnimator.PROLONG_ANIMATION_AT_START;
import static com.android.server.wm.DragResizeMode.DRAG_RESIZE_MODE_DOCKED_DIVIDER;
import static com.android.server.wm.DragResizeMode.DRAG_RESIZE_MODE_FREEFORM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_APP_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_BOOT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_CONFIGURATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DISPLAY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DRAG;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_INPUT_METHOD;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_KEYGUARD;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_KEEP_SCREEN_ON;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_RESIZE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SCREENSHOT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SCREEN_ON;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SURFACE_TRACE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TASK_POSITIONING;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TOKEN_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_TRACE;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_STACK_CRAWLS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_SURFACE_ALLOC;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_VERBOSE_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_KEEP_SCREEN_ON;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowStateAnimator.DRAW_PENDING;

/** {@hide} */
public class WindowManagerService extends IWindowManager.Stub
        implements Watchdog.Monitor, WindowManagerPolicy.WindowManagerFuncs {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "WindowManagerService" : TAG_WM;

    static final int LAYOUT_REPEAT_THRESHOLD = 4;

    static final boolean PROFILE_ORIENTATION = false;
    static final boolean localLOGV = DEBUG;

    /** How much to multiply the policy's type layer, to reserve room
     * for multiple windows of the same type and Z-ordering adjustment
     * with TYPE_LAYER_OFFSET. */
    static final int TYPE_LAYER_MULTIPLIER = 10000;

    /** Offset from TYPE_LAYER_MULTIPLIER for moving a group of windows above
     * or below others in the same layer. */
    static final int TYPE_LAYER_OFFSET = 1000;

    /** How much to increment the layer for each window, to reserve room
     * for effect surfaces between them.
     */
    static final int WINDOW_LAYER_MULTIPLIER = 5;

    /**
     * Dim surface layer is immediately below target window.
     */
    static final int LAYER_OFFSET_DIM = 1;

    /**
     * Animation thumbnail is as far as possible below the window above
     * the thumbnail (or in other words as far as possible above the window
     * below it).
     */
    static final int LAYER_OFFSET_THUMBNAIL = WINDOW_LAYER_MULTIPLIER - 1;

    /** The maximum length we will accept for a loaded animation duration:
     * this is 10 seconds.
     */
    static final int MAX_ANIMATION_DURATION = 10 * 1000;

    /** Amount of time (in milliseconds) to delay before declaring a window freeze timeout. */
    static final int WINDOW_FREEZE_TIMEOUT_DURATION = 2000;

    /** Amount of time (in milliseconds) to delay before declaring a window replacement timeout. */
    static final int WINDOW_REPLACEMENT_TIMEOUT_DURATION = 2000;

    /** Amount of time to allow a last ANR message to exist before freeing the memory. */
    static final int LAST_ANR_LIFETIME_DURATION_MSECS = 2 * 60 * 60 * 1000; // Two hours
    /**
     * If true, the window manager will do its own custom freezing and general
     * management of the screen during rotation.
     */
    static final boolean CUSTOM_SCREEN_ROTATION = true;

    // Maximum number of milliseconds to wait for input devices to be enumerated before
    // proceding with safe mode detection.
    private static final int INPUT_DEVICES_READY_FOR_SAFE_MODE_DETECTION_TIMEOUT_MILLIS = 1000;

    // Default input dispatching timeout in nanoseconds.
    static final long DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS = 5000 * 1000000L;

    // Poll interval in milliseconds for watching boot animation finished.
    private static final int BOOT_ANIMATION_POLL_INTERVAL = 200;

    // The name of the boot animation service in init.rc.
    private static final String BOOT_ANIMATION_SERVICE = "bootanim";

    static final int UPDATE_FOCUS_NORMAL = 0;
    static final int UPDATE_FOCUS_WILL_ASSIGN_LAYERS = 1;
    static final int UPDATE_FOCUS_PLACING_SURFACES = 2;
    static final int UPDATE_FOCUS_WILL_PLACE_SURFACES = 3;

    private static final String SYSTEM_SECURE = "ro.secure";
    private static final String SYSTEM_DEBUGGABLE = "ro.debuggable";

    private static final String DENSITY_OVERRIDE = "ro.config.density_override";
    private static final String SIZE_OVERRIDE = "ro.config.size_override";

    private static final int MAX_SCREENSHOT_RETRIES = 3;

    private static final String PROPERTY_EMULATOR_CIRCULAR = "ro.emulator.circular";

    // Used to indicate that if there is already a transition set, it should be preserved when
    // trying to apply a new one.
    private static final boolean ALWAYS_KEEP_CURRENT = true;

    private static final float DRAG_SHADOW_ALPHA_TRANSPARENT = .7071f;

    private static final String PROPERTY_BUILD_DATE_UTC = "ro.build.date.utc";

    // Enums for animation scale update types.
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({WINDOW_ANIMATION_SCALE, TRANSITION_ANIMATION_SCALE, ANIMATION_DURATION_SCALE})
    private @interface UpdateAnimationScaleMode {};
    private static final int WINDOW_ANIMATION_SCALE = 0;
    private static final int TRANSITION_ANIMATION_SCALE = 1;
    private static final int ANIMATION_DURATION_SCALE = 2;

    final private KeyguardDisableHandler mKeyguardDisableHandler;

    final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED.equals(action)) {
                mKeyguardDisableHandler.sendEmptyMessage(
                    KeyguardDisableHandler.KEYGUARD_POLICY_CHANGED);
            }
        }
    };
    final WindowSurfacePlacer mWindowPlacerLocked;

    /**
     * Current user when multi-user is enabled. Don't show windows of
     * non-current user. Also see mCurrentProfileIds.
     */
    int mCurrentUserId;
    /**
     * Users that are profiles of the current user. These are also allowed to show windows
     * on the current user.
     */
    int[] mCurrentProfileIds = new int[] {};

    final Context mContext;

    final boolean mHaveInputMethods;

    final boolean mHasPermanentDpad;
    final long mDrawLockTimeoutMillis;
    final boolean mAllowAnimationsInLowPowerMode;

    final boolean mAllowBootMessages;

    final boolean mLimitedAlphaCompositing;

    final WindowManagerPolicy mPolicy = new PhoneWindowManager();

    final IActivityManager mActivityManager;
    final ActivityManagerInternal mAmInternal;

    final AppOpsManager mAppOps;

    final DisplaySettings mDisplaySettings;

    /**
     * All currently active sessions with clients.
     */
    final ArraySet<Session> mSessions = new ArraySet<>();

    /**
     * Mapping from an IWindow IBinder to the server's Window object.
     * This is also used as the lock for all of our state.
     * NOTE: Never call into methods that lock ActivityManagerService while holding this object.
     */
    final HashMap<IBinder, WindowState> mWindowMap = new HashMap<>();

    /**
     * Mapping from a token IBinder to a WindowToken object.
     */
    final HashMap<IBinder, WindowToken> mTokenMap = new HashMap<>();

    /**
     * List of window tokens that have finished starting their application,
     * and now need to have the policy remove their windows.
     */
    final ArrayList<AppWindowToken> mFinishedStarting = new ArrayList<>();

    /**
     * List of window tokens that have finished drawing their own windows and
     * no longer need to show any saved surfaces. Windows that's still showing
     * saved surfaces will be cleaned up after next animation pass.
     */
    final ArrayList<AppWindowToken> mFinishedEarlyAnim = new ArrayList<>();

    /**
     * List of app window tokens that are waiting for replacing windows. If the
     * replacement doesn't come in time the stale windows needs to be disposed of.
     */
    final ArrayList<AppWindowToken> mReplacingWindowTimeouts = new ArrayList<>();

    /**
     * The input consumer added to the window manager which consumes input events to windows below
     * it.
     */
    InputConsumerImpl mInputConsumer;

    /**
     * The input consumer added to the window manager before all wallpaper windows.
     */
    InputConsumerImpl mWallpaperInputConsumer;

    /**
     * Windows that are being resized.  Used so we can tell the client about
     * the resize after closing the transaction in which we resized the
     * underlying surface.
     */
    final ArrayList<WindowState> mResizingWindows = new ArrayList<>();

    /**
     * Windows whose animations have ended and now must be removed.
     */
    final ArrayList<WindowState> mPendingRemove = new ArrayList<>();

    /**
     * Used when processing mPendingRemove to avoid working on the original array.
     */
    WindowState[] mPendingRemoveTmp = new WindowState[20];

    /**
     * Windows whose surface should be destroyed.
     */
    final ArrayList<WindowState> mDestroySurface = new ArrayList<>();

    /**
     * Windows with a preserved surface waiting to be destroyed. These windows
     * are going through a surface change. We keep the old surface around until
     * the first frame on the new surface finishes drawing.
     */
    final ArrayList<WindowState> mDestroyPreservedSurface = new ArrayList<>();

    /**
     * Windows that have lost input focus and are waiting for the new
     * focus window to be displayed before they are told about this.
     */
    ArrayList<WindowState> mLosingFocus = new ArrayList<>();

    /**
     * This is set when we have run out of memory, and will either be an empty
     * list or contain windows that need to be force removed.
     */
    final ArrayList<WindowState> mForceRemoves = new ArrayList<>();

    /**
     * Windows that clients are waiting to have drawn.
     */
    ArrayList<WindowState> mWaitingForDrawn = new ArrayList<>();
    /**
     * And the callback to make when they've all been drawn.
     */
    Runnable mWaitingForDrawnCallback;

    /**
     * Used when rebuilding window list to keep track of windows that have
     * been removed.
     */
    WindowState[] mRebuildTmp = new WindowState[20];

    /**
     * Stores for each user whether screencapture is disabled
     * This array is essentially a cache for all userId for
     * {@link android.app.admin.DevicePolicyManager#getScreenCaptureDisabled}
     */
    SparseArray<Boolean> mScreenCaptureDisabled = new SparseArray<>();

    IInputMethodManager mInputMethodManager;

    AccessibilityController mAccessibilityController;

    final SurfaceSession mFxSession;
    Watermark mWatermark;
    StrictModeFlash mStrictModeFlash;
    CircularDisplayMask mCircularDisplayMask;
    EmulatorDisplayOverlay mEmulatorDisplayOverlay;

    final float[] mTmpFloats = new float[9];
    final Rect mTmpRect = new Rect();
    final Rect mTmpRect2 = new Rect();
    final Rect mTmpRect3 = new Rect();

    boolean mDisplayReady;
    boolean mSafeMode;
    boolean mDisplayEnabled = false;
    boolean mSystemBooted = false;
    boolean mForceDisplayEnabled = false;
    boolean mShowingBootMessages = false;
    boolean mBootAnimationStopped = false;

    // Following variables are for debugging screen wakelock only.
    WindowState mLastWakeLockHoldingWindow = null;
    WindowState mLastWakeLockObscuringWindow = null;

    /** Dump of the windows and app tokens at the time of the last ANR. Cleared after
     * LAST_ANR_LIFETIME_DURATION_MSECS */
    String mLastANRState;

    /** All DisplayContents in the world, kept here */
    SparseArray<DisplayContent> mDisplayContents = new SparseArray<>(2);

    int mRotation = 0;
    int mForcedAppOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    boolean mAltOrientation = false;

    private boolean mKeyguardWaitingForActivityDrawn;

    int mDockedStackCreateMode = DOCKED_STACK_CREATE_MODE_TOP_OR_LEFT;
    Rect mDockedStackCreateBounds;

    private final SparseIntArray mTmpTaskIds = new SparseIntArray();

    private final ArrayList<Integer> mChangedStackList = new ArrayList();

    boolean mForceResizableTasks = false;

    int getDragLayerLocked() {
        return mPolicy.windowTypeToLayerLw(LayoutParams.TYPE_DRAG) * TYPE_LAYER_MULTIPLIER
                + TYPE_LAYER_OFFSET;
    }

    class RotationWatcher {
        IRotationWatcher watcher;
        IBinder.DeathRecipient deathRecipient;
        RotationWatcher(IRotationWatcher w, IBinder.DeathRecipient d) {
            watcher = w;
            deathRecipient = d;
        }
    }
    ArrayList<RotationWatcher> mRotationWatchers = new ArrayList<>();
    int mDeferredRotationPauseCount;

    int mSystemDecorLayer = 0;
    final Rect mScreenRect = new Rect();

    boolean mDisplayFrozen = false;
    long mDisplayFreezeTime = 0;
    int mLastDisplayFreezeDuration = 0;
    Object mLastFinishedFreezeSource = null;
    boolean mWaitingForConfig = false;

    final static int WINDOWS_FREEZING_SCREENS_NONE = 0;
    final static int WINDOWS_FREEZING_SCREENS_ACTIVE = 1;
    final static int WINDOWS_FREEZING_SCREENS_TIMEOUT = 2;
    int mWindowsFreezingScreen = WINDOWS_FREEZING_SCREENS_NONE;

    boolean mClientFreezingScreen = false;
    int mAppsFreezingScreen = 0;
    int mLastWindowForcedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    int mLastKeyguardForcedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    int mLayoutSeq = 0;

    // Last systemUiVisibility we received from status bar.
    int mLastStatusBarVisibility = 0;
    // Last systemUiVisibility we dispatched to windows.
    int mLastDispatchedSystemUiVisibility = 0;

    // State while inside of layoutAndPlaceSurfacesLocked().
    boolean mFocusMayChange;

    Configuration mCurConfiguration = new Configuration();

    // This is held as long as we have the screen frozen, to give us time to
    // perform a rotation animation when turning off shows the lock screen which
    // changes the orientation.
    private final PowerManager.WakeLock mScreenFrozenLock;

    final AppTransition mAppTransition;
    boolean mSkipAppTransitionAnimation = false;

    final ArraySet<AppWindowToken> mOpeningApps = new ArraySet<>();
    final ArraySet<AppWindowToken> mClosingApps = new ArraySet<>();

    boolean mIsTouchDevice;

    final DisplayMetrics mDisplayMetrics = new DisplayMetrics();
    final DisplayMetrics mRealDisplayMetrics = new DisplayMetrics();
    final DisplayMetrics mTmpDisplayMetrics = new DisplayMetrics();
    final DisplayMetrics mCompatDisplayMetrics = new DisplayMetrics();

    final H mH = new H();

    final Choreographer mChoreographer = Choreographer.getInstance();

    WindowState mCurrentFocus = null;
    WindowState mLastFocus = null;

    /** This just indicates the window the input method is on top of, not
     * necessarily the window its input is going to. */
    WindowState mInputMethodTarget = null;

    /** If true hold off on modifying the animation layer of mInputMethodTarget */
    boolean mInputMethodTargetWaitingAnim;

    WindowState mInputMethodWindow = null;
    final ArrayList<WindowState> mInputMethodDialogs = new ArrayList<>();

    /** Temporary list for comparison. Always clear this after use so we don't end up with
     * orphaned windows references */
    final ArrayList<WindowState> mTmpWindows = new ArrayList<>();

    boolean mHardKeyboardAvailable;
    WindowManagerInternal.OnHardKeyboardStatusChangeListener mHardKeyboardStatusChangeListener;
    SettingsObserver mSettingsObserver;

    private final class SettingsObserver extends ContentObserver {
        private final Uri mDisplayInversionEnabledUri =
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED);
        private final Uri mWindowAnimationScaleUri =
                Settings.Global.getUriFor(Settings.Global.WINDOW_ANIMATION_SCALE);
        private final Uri mTransitionAnimationScaleUri =
                Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE);
        private final Uri mAnimationDurationScaleUri =
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE);

        public SettingsObserver() {
            super(new Handler());
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(mDisplayInversionEnabledUri, false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(mWindowAnimationScaleUri, false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(mTransitionAnimationScaleUri, false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(mAnimationDurationScaleUri, false, this,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri == null) {
                return;
            }

            if (mDisplayInversionEnabledUri.equals(uri)) {
                updateCircularDisplayMaskIfNeeded();
            } else {
                @UpdateAnimationScaleMode
                final int mode;
                if (mWindowAnimationScaleUri.equals(uri)) {
                    mode = WINDOW_ANIMATION_SCALE;
                } else if (mTransitionAnimationScaleUri.equals(uri)) {
                    mode = TRANSITION_ANIMATION_SCALE;
                } else if (mAnimationDurationScaleUri.equals(uri)) {
                    mode = ANIMATION_DURATION_SCALE;
                } else {
                    // Ignoring unrecognized content changes
                    return;
                }
                Message m = mH.obtainMessage(H.UPDATE_ANIMATION_SCALE, mode, 0);
                mH.sendMessage(m);
            }
        }
    }

    WallpaperController mWallpaperControllerLocked;

    final WindowLayersController mLayersController;

    boolean mAnimateWallpaperWithTarget;

    AppWindowToken mFocusedApp = null;

    PowerManager mPowerManager;
    PowerManagerInternal mPowerManagerInternal;

    float mWindowAnimationScaleSetting = 1.0f;
    float mTransitionAnimationScaleSetting = 1.0f;
    float mAnimatorDurationScaleSetting = 1.0f;
    boolean mAnimationsDisabled = false;

    final InputManagerService mInputManager;
    final DisplayManagerInternal mDisplayManagerInternal;
    final DisplayManager mDisplayManager;
    final Display[] mDisplays;

    // Who is holding the screen on.
    Session mHoldingScreenOn;
    PowerManager.WakeLock mHoldingScreenWakeLock;

    boolean mTurnOnScreen;

    // Whether or not a layout can cause a wake up when theater mode is enabled.
    boolean mAllowTheaterModeWakeFromLayout;

    TaskPositioner mTaskPositioner;
    DragState mDragState = null;

    // For frozen screen animations.
    int mExitAnimId, mEnterAnimId;

    boolean mAnimationScheduled;

    /** Skip repeated AppWindowTokens initialization. Note that AppWindowsToken's version of this
     * is a long initialized to Long.MIN_VALUE so that it doesn't match this value on startup. */
    int mTransactionSequence;

    final WindowAnimator mAnimator;

    private final BoundsAnimationController mBoundsAnimationController;

    SparseArray<Task> mTaskIdToTask = new SparseArray<>();

    /** All of the TaskStacks in the window manager, unordered. For an ordered list call
     * DisplayContent.getStacks(). */
    SparseArray<TaskStack> mStackIdToStack = new SparseArray<>();

    private final PointerEventDispatcher mPointerEventDispatcher;

    private WindowContentFrameStats mTempWindowRenderStats;

    final class DragInputEventReceiver extends InputEventReceiver {
        // Set, if stylus button was down at the start of the drag.
        private boolean mStylusButtonDownAtStart;
        // Indicates the first event to check for button state.
        private boolean mIsStartEvent = true;

        public DragInputEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            boolean handled = false;
            try {
                if (event instanceof MotionEvent
                        && (event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0
                        && mDragState != null) {
                    final MotionEvent motionEvent = (MotionEvent)event;
                    boolean endDrag = false;
                    final float newX = motionEvent.getRawX();
                    final float newY = motionEvent.getRawY();
                    final boolean isStylusButtonDown =
                            (motionEvent.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0;

                    if (mIsStartEvent) {
                        if (isStylusButtonDown) {
                            // First event and the button was down, check for the button being
                            // lifted in the future, if that happens we'll drop the item.
                            mStylusButtonDownAtStart = true;
                        }
                        mIsStartEvent = false;
                    }

                    switch (motionEvent.getAction()) {
                    case MotionEvent.ACTION_DOWN: {
                        if (DEBUG_DRAG) {
                            Slog.w(TAG_WM, "Unexpected ACTION_DOWN in drag layer");
                        }
                    } break;

                    case MotionEvent.ACTION_MOVE: {
                        if (mStylusButtonDownAtStart && !isStylusButtonDown) {
                            if (DEBUG_DRAG) Slog.d(TAG_WM, "Button no longer pressed; dropping at "
                                    + newX + "," + newY);
                            synchronized (mWindowMap) {
                                endDrag = mDragState.notifyDropLw(newX, newY);
                            }
                        } else {
                            synchronized (mWindowMap) {
                                // move the surface and tell the involved window(s) where we are
                                mDragState.notifyMoveLw(newX, newY);
                            }
                        }
                    } break;

                    case MotionEvent.ACTION_UP: {
                        if (DEBUG_DRAG) Slog.d(TAG_WM, "Got UP on move channel; dropping at "
                                + newX + "," + newY);
                        synchronized (mWindowMap) {
                            endDrag = mDragState.notifyDropLw(newX, newY);
                        }
                    } break;

                    case MotionEvent.ACTION_CANCEL: {
                        if (DEBUG_DRAG) Slog.d(TAG_WM, "Drag cancelled!");
                        endDrag = true;
                    } break;
                    }

                    if (endDrag) {
                        if (DEBUG_DRAG) Slog.d(TAG_WM, "Drag ended; tearing down state");
                        // tell all the windows that the drag has ended
                        synchronized (mWindowMap) {
                            mDragState.endDragLw();
                        }
                        mStylusButtonDownAtStart = false;
                        mIsStartEvent = true;
                    }

                    handled = true;
                }
            } catch (Exception e) {
                Slog.e(TAG_WM, "Exception caught by drag handleMotion", e);
            } finally {
                finishInputEvent(event, handled);
            }
        }
    }

    /**
     * Whether the UI is currently running in touch mode (not showing
     * navigational focus because the user is directly pressing the screen).
     */
    boolean mInTouchMode;

    private ViewServer mViewServer;
    final ArrayList<WindowChangeListener> mWindowChangeListeners = new ArrayList<>();
    boolean mWindowsChanged = false;

    public interface WindowChangeListener {
        public void windowsChanged();
        public void focusChanged();
    }

    final Configuration mTempConfiguration = new Configuration();

    // The desired scaling factor for compatible apps.
    float mCompatibleScreenScale;

    // If true, only the core apps and services are being launched because the device
    // is in a special boot mode, such as being encrypted or waiting for a decryption password.
    // For example, when this flag is true, there will be no wallpaper service.
    final boolean mOnlyCore;

    // List of clients without a transtiton animation that we notify once we are done transitioning
    // since they won't be notified through the app window animator.
    final List<IBinder> mNoAnimationNotifyOnTransitionFinished = new ArrayList<>();

    // List of displays to reconfigure after configuration changes.
    // Some of the information reported for a display is dependent on resources to do the right
    // calculations. For example, {@link DisplayInfo#smallestNominalAppWidth} and company are
    // dependent on the height and width of the status and nav bar which change depending on the
    // current configuration.
    private final DisplayContentList mReconfigureOnConfigurationChanged = new DisplayContentList();

    /** Listener to notify activity manager about app transitions. */
    private final WindowManagerInternal.AppTransitionListener mActivityManagerAppTransitionNotifier
            = new WindowManagerInternal.AppTransitionListener() {

        @Override
        public void onAppTransitionCancelledLocked() {
            mH.sendEmptyMessage(H.NOTIFY_APP_TRANSITION_CANCELLED);
        }

        @Override
        public void onAppTransitionFinishedLocked(IBinder token) {
            mH.sendEmptyMessage(H.NOTIFY_APP_TRANSITION_FINISHED);
            AppWindowToken atoken = findAppWindowToken(token);
            if (atoken == null) {
                return;
            }
            if (atoken.mLaunchTaskBehind) {
                try {
                    mActivityManager.notifyLaunchTaskBehindComplete(atoken.token);
                } catch (RemoteException e) {
                }
                atoken.mLaunchTaskBehind = false;
            } else {
                atoken.updateReportedVisibilityLocked();
                if (atoken.mEnteringAnimation) {
                    atoken.mEnteringAnimation = false;
                    try {
                        mActivityManager.notifyEnterAnimationComplete(atoken.token);
                    } catch (RemoteException e) {
                    }
                }
            }
        }
    };

    public static WindowManagerService main(final Context context,
            final InputManagerService im,
            final boolean haveInputMethods, final boolean showBootMsgs,
            final boolean onlyCore) {
        final WindowManagerService[] holder = new WindowManagerService[1];
        DisplayThread.getHandler().runWithScissors(new Runnable() {
            @Override
            public void run() {
                holder[0] = new WindowManagerService(context, im,
                        haveInputMethods, showBootMsgs, onlyCore);
            }
        }, 0);
        return holder[0];
    }

    private void initPolicy() {
        UiThread.getHandler().runWithScissors(new Runnable() {
            @Override
            public void run() {
                WindowManagerPolicyThread.set(Thread.currentThread(), Looper.myLooper());

                mPolicy.init(mContext, WindowManagerService.this, WindowManagerService.this);
            }
        }, 0);
    }

    private WindowManagerService(Context context, InputManagerService inputManager,
            boolean haveInputMethods, boolean showBootMsgs, boolean onlyCore) {
        mContext = context;
        mHaveInputMethods = haveInputMethods;
        mAllowBootMessages = showBootMsgs;
        mOnlyCore = onlyCore;
        mLimitedAlphaCompositing = context.getResources().getBoolean(
                com.android.internal.R.bool.config_sf_limitedAlpha);
        mHasPermanentDpad = context.getResources().getBoolean(
                com.android.internal.R.bool.config_hasPermanentDpad);
        mInTouchMode = context.getResources().getBoolean(
                com.android.internal.R.bool.config_defaultInTouchMode);
        mDrawLockTimeoutMillis = context.getResources().getInteger(
                com.android.internal.R.integer.config_drawLockTimeoutMillis);
        mAllowAnimationsInLowPowerMode = context.getResources().getBoolean(
                com.android.internal.R.bool.config_allowAnimationsInLowPowerMode);
        mInputManager = inputManager; // Must be before createDisplayContentLocked.
        mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
        mDisplaySettings = new DisplaySettings();
        mDisplaySettings.readSettingsLocked();

        mWallpaperControllerLocked = new WallpaperController(this);
        mWindowPlacerLocked = new WindowSurfacePlacer(this);
        mLayersController = new WindowLayersController(this);

        LocalServices.addService(WindowManagerPolicy.class, mPolicy);

        mPointerEventDispatcher = new PointerEventDispatcher(mInputManager.monitorInput(TAG_WM));

        mFxSession = new SurfaceSession();
        mDisplayManager = (DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);
        mDisplays = mDisplayManager.getDisplays();
        for (Display display : mDisplays) {
            createDisplayContentLocked(display);
        }

        mKeyguardDisableHandler = new KeyguardDisableHandler(mContext, mPolicy);

        mPowerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
        mPowerManagerInternal.registerLowPowerModeObserver(
                new PowerManagerInternal.LowPowerModeListener() {
            @Override
            public void onLowPowerModeChanged(boolean enabled) {
                synchronized (mWindowMap) {
                    if (mAnimationsDisabled != enabled && !mAllowAnimationsInLowPowerMode) {
                        mAnimationsDisabled = enabled;
                        dispatchNewAnimatorScaleLocked(null);
                    }
                }
            }
        });
        mAnimationsDisabled = mPowerManagerInternal.getLowPowerModeEnabled();
        mScreenFrozenLock = mPowerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "SCREEN_FROZEN");
        mScreenFrozenLock.setReferenceCounted(false);

        mAppTransition = new AppTransition(context, this);
        mAppTransition.registerListenerLocked(mActivityManagerAppTransitionNotifier);

        mBoundsAnimationController =
                new BoundsAnimationController(mAppTransition, UiThread.getHandler());

        mActivityManager = ActivityManagerNative.getDefault();
        mAmInternal = LocalServices.getService(ActivityManagerInternal.class);
        mAppOps = (AppOpsManager)context.getSystemService(Context.APP_OPS_SERVICE);
        AppOpsManager.OnOpChangedInternalListener opListener =
                new AppOpsManager.OnOpChangedInternalListener() {
                    @Override public void onOpChanged(int op, String packageName) {
                        updateAppOpsState();
                    }
                };
        mAppOps.startWatchingMode(AppOpsManager.OP_SYSTEM_ALERT_WINDOW, null, opListener);
        mAppOps.startWatchingMode(AppOpsManager.OP_TOAST_WINDOW, null, opListener);

        // Get persisted window scale setting
        mWindowAnimationScaleSetting = Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.WINDOW_ANIMATION_SCALE, mWindowAnimationScaleSetting);
        mTransitionAnimationScaleSetting = Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.TRANSITION_ANIMATION_SCALE, mTransitionAnimationScaleSetting);
        setAnimatorDurationScale(Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, mAnimatorDurationScaleSetting));

        // Track changes to DevicePolicyManager state so we can enable/disable keyguard.
        IntentFilter filter = new IntentFilter();
        filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        mContext.registerReceiver(mBroadcastReceiver, filter);

        mSettingsObserver = new SettingsObserver();

        mHoldingScreenWakeLock = mPowerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, TAG_WM);
        mHoldingScreenWakeLock.setReferenceCounted(false);

        mAnimator = new WindowAnimator(this);

        mAllowTheaterModeWakeFromLayout = context.getResources().getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromWindowLayout);


        LocalServices.addService(WindowManagerInternal.class, new LocalService());
        initPolicy();

        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);

        SurfaceControl.openTransaction();
        try {
            createWatermarkInTransaction();
        } finally {
            SurfaceControl.closeTransaction();
        }

        showEmulatorDisplayOverlayIfNeeded();
    }

    public InputMonitor getInputMonitor() {
        return mInputMonitor;
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            // The window manager only throws security exceptions, so let's
            // log all others.
            if (!(e instanceof SecurityException)) {
                Slog.wtf(TAG_WM, "Window Manager Crash", e);
            }
            throw e;
        }
    }

    private void placeWindowAfter(WindowState pos, WindowState window) {
        final WindowList windows = pos.getWindowList();
        final int i = windows.indexOf(pos);
        if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(
            TAG_WM, "Adding window " + window + " at "
            + (i+1) + " of " + windows.size() + " (after " + pos + ")");
        windows.add(i+1, window);
        mWindowsChanged = true;
    }

    private void placeWindowBefore(WindowState pos, WindowState window) {
        final WindowList windows = pos.getWindowList();
        int i = windows.indexOf(pos);
        if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(
            TAG_WM, "Adding window " + window + " at "
            + i + " of " + windows.size() + " (before " + pos + ")");
        if (i < 0) {
            Slog.w(TAG_WM, "placeWindowBefore: Unable to find " + pos + " in " + windows);
            i = 0;
        }
        windows.add(i, window);
        mWindowsChanged = true;
    }

    //This method finds out the index of a window that has the same app token as
    //win. used for z ordering the windows in mWindows
    private int findIdxBasedOnAppTokens(WindowState win) {
        WindowList windows = win.getWindowList();
        for(int j = windows.size() - 1; j >= 0; j--) {
            WindowState wentry = windows.get(j);
            if(wentry.mAppToken == win.mAppToken) {
                return j;
            }
        }
        return -1;
    }

    /**
     * Return the list of Windows from the passed token on the given Display.
     * @param token The token with all the windows.
     * @param displayContent The display we are interested in.
     * @return List of windows from token that are on displayContent.
     */
    private WindowList getTokenWindowsOnDisplay(WindowToken token, DisplayContent displayContent) {
        final WindowList windowList = new WindowList();
        final int count = token.windows.size();
        for (int i = 0; i < count; i++) {
            final WindowState win = token.windows.get(i);
            if (win.getDisplayContent() == displayContent) {
                windowList.add(win);
            }
        }
        return windowList;
    }

    /**
     * Recursive search through a WindowList and all of its windows' children.
     * @param targetWin The window to search for.
     * @param windows The list to search.
     * @return The index of win in windows or of the window that is an ancestor of win.
     */
    private int indexOfWinInWindowList(WindowState targetWin, WindowList windows) {
        for (int i = windows.size() - 1; i >= 0; i--) {
            final WindowState w = windows.get(i);
            if (w == targetWin) {
                return i;
            }
            if (!w.mChildWindows.isEmpty()) {
                if (indexOfWinInWindowList(targetWin, w.mChildWindows) >= 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int addAppWindowToListLocked(final WindowState win) {
        final DisplayContent displayContent = win.getDisplayContent();
        if (displayContent == null) {
            // It doesn't matter this display is going away.
            return 0;
        }
        final IWindow client = win.mClient;
        final WindowToken token = win.mToken;

        final WindowList windows = displayContent.getWindowList();
        WindowList tokenWindowList = getTokenWindowsOnDisplay(token, displayContent);
        int tokenWindowsPos = 0;
        if (!tokenWindowList.isEmpty()) {
            return addAppWindowToTokenListLocked(win, token, windows, tokenWindowList);
        }

        // No windows from this token on this display
        if (localLOGV) Slog.v(TAG_WM, "Figuring out where to add app window " + client.asBinder()
                + " (token=" + token + ")");
        // Figure out where the window should go, based on the
        // order of applications.
        WindowState pos = null;

        final ArrayList<Task> tasks = displayContent.getTasks();
        int taskNdx;
        int tokenNdx = -1;
        for (taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
            AppTokenList tokens = tasks.get(taskNdx).mAppTokens;
            for (tokenNdx = tokens.size() - 1; tokenNdx >= 0; --tokenNdx) {
                final AppWindowToken t = tokens.get(tokenNdx);
                if (t == token) {
                    --tokenNdx;
                    if (tokenNdx < 0) {
                        --taskNdx;
                        if (taskNdx >= 0) {
                            tokenNdx = tasks.get(taskNdx).mAppTokens.size() - 1;
                        }
                    }
                    break;
                }

                // We haven't reached the token yet; if this token
                // is not going to the bottom and has windows on this display, we can
                // use it as an anchor for when we do reach the token.
                tokenWindowList = getTokenWindowsOnDisplay(t, displayContent);
                if (!t.sendingToBottom && tokenWindowList.size() > 0) {
                    pos = tokenWindowList.get(0);
                }
            }
            if (tokenNdx >= 0) {
                // early exit
                break;
            }
        }

        // We now know the index into the apps.  If we found
        // an app window above, that gives us the position; else
        // we need to look some more.
        if (pos != null) {
            // Move behind any windows attached to this one.
            WindowToken atoken = mTokenMap.get(pos.mClient.asBinder());
            if (atoken != null) {
                tokenWindowList =
                        getTokenWindowsOnDisplay(atoken, displayContent);
                final int NC = tokenWindowList.size();
                if (NC > 0) {
                    WindowState bottom = tokenWindowList.get(0);
                    if (bottom.mSubLayer < 0) {
                        pos = bottom;
                    }
                }
            }
            placeWindowBefore(pos, win);
            return tokenWindowsPos;
        }

        // Continue looking down until we find the first
        // token that has windows on this display.
        for ( ; taskNdx >= 0; --taskNdx) {
            AppTokenList tokens = tasks.get(taskNdx).mAppTokens;
            for ( ; tokenNdx >= 0; --tokenNdx) {
                final AppWindowToken t = tokens.get(tokenNdx);
                tokenWindowList = getTokenWindowsOnDisplay(t, displayContent);
                final int NW = tokenWindowList.size();
                if (NW > 0) {
                    pos = tokenWindowList.get(NW-1);
                    break;
                }
            }
            if (tokenNdx >= 0) {
                // found
                break;
            }
        }

        if (pos != null) {
            // Move in front of any windows attached to this
            // one.
            WindowToken atoken = mTokenMap.get(pos.mClient.asBinder());
            if (atoken != null) {
                final int NC = atoken.windows.size();
                if (NC > 0) {
                    WindowState top = atoken.windows.get(NC-1);
                    if (top.mSubLayer >= 0) {
                        pos = top;
                    }
                }
            }
            placeWindowAfter(pos, win);
            return tokenWindowsPos;
        }

        // Just search for the start of this layer.
        final int myLayer = win.mBaseLayer;
        int i;
        for (i = windows.size() - 1; i >= 0; --i) {
            WindowState w = windows.get(i);
            // Dock divider shares the base layer with application windows, but we want to always
            // keep it above the application windows. The sharing of the base layer is intended
            // for window animations, which need to be above the dock divider for the duration
            // of the animation.
            if (w.mBaseLayer <= myLayer && w.mAttrs.type != TYPE_DOCK_DIVIDER) {
                break;
            }
        }
        if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                "Based on layer: Adding window " + win + " at " + (i + 1) + " of "
                        + windows.size());
        windows.add(i + 1, win);
        mWindowsChanged = true;
        return tokenWindowsPos;
    }

    private int addAppWindowToTokenListLocked(WindowState win, WindowToken token,
            WindowList windows, WindowList tokenWindowList) {
        int tokenWindowsPos;
        // If this application has existing windows, we
        // simply place the new window on top of them... but
        // keep the starting window on top.
        if (win.mAttrs.type == TYPE_BASE_APPLICATION) {
            // Base windows go behind everything else.
            WindowState lowestWindow = tokenWindowList.get(0);
            placeWindowBefore(lowestWindow, win);
            tokenWindowsPos = indexOfWinInWindowList(lowestWindow, token.windows);
        } else {
            AppWindowToken atoken = win.mAppToken;
            final int windowListPos = tokenWindowList.size();
            WindowState lastWindow = tokenWindowList.get(windowListPos - 1);
            if (atoken != null && lastWindow == atoken.startingWindow) {
                placeWindowBefore(lastWindow, win);
                tokenWindowsPos = indexOfWinInWindowList(lastWindow, token.windows);
            } else {
                int newIdx = findIdxBasedOnAppTokens(win);
                //there is a window above this one associated with the same
                //apptoken note that the window could be a floating window
                //that was created later or a window at the top of the list of
                //windows associated with this token.
                if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                        "not Base app: Adding window " + win + " at " + (newIdx + 1) + " of "
                                + windows.size());
                windows.add(newIdx + 1, win);
                if (newIdx < 0) {
                    // No window from token found on win's display.
                    tokenWindowsPos = 0;
                } else {
                    tokenWindowsPos = indexOfWinInWindowList(
                            windows.get(newIdx), token.windows) + 1;
                }
                mWindowsChanged = true;
            }
        }
        return tokenWindowsPos;
    }

    private void addFreeWindowToListLocked(final WindowState win) {
        final WindowList windows = win.getWindowList();

        // Figure out where window should go, based on layer.
        final int myLayer = win.mBaseLayer;
        int i;
        for (i = windows.size() - 1; i >= 0; i--) {
            final WindowState otherWin = windows.get(i);
            if (otherWin.getBaseType() != TYPE_WALLPAPER && otherWin.mBaseLayer <= myLayer) {
                // Wallpaper wanders through the window list, for example to position itself
                // directly behind keyguard. Because of this it will break the ordering based on
                // WindowState.mBaseLayer. There might windows with higher mBaseLayer behind it and
                // we don't want the new window to appear above them. An example of this is adding
                // of the docked stack divider. Consider a scenario with the following ordering (top
                // to bottom): keyguard, wallpaper, assist preview, apps. We want the dock divider
                // to land below the assist preview, so the dock divider must ignore the wallpaper,
                // with which it shares the base layer.
                break;
            }
        }
        i++;
        if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                "Free window: Adding window " + win + " at " + i + " of " + windows.size());
        windows.add(i, win);
        mWindowsChanged = true;
    }

    private void addAttachedWindowToListLocked(final WindowState win, boolean addToToken) {
        final WindowToken token = win.mToken;
        final DisplayContent displayContent = win.getDisplayContent();
        if (displayContent == null) {
            return;
        }
        final WindowState attached = win.mAttachedWindow;

        WindowList tokenWindowList = getTokenWindowsOnDisplay(token, displayContent);

        // Figure out this window's ordering relative to the window
        // it is attached to.
        final int NA = tokenWindowList.size();
        final int sublayer = win.mSubLayer;
        int largestSublayer = Integer.MIN_VALUE;
        WindowState windowWithLargestSublayer = null;
        int i;
        for (i = 0; i < NA; i++) {
            WindowState w = tokenWindowList.get(i);
            final int wSublayer = w.mSubLayer;
            if (wSublayer >= largestSublayer) {
                largestSublayer = wSublayer;
                windowWithLargestSublayer = w;
            }
            if (sublayer < 0) {
                // For negative sublayers, we go below all windows
                // in the same sublayer.
                if (wSublayer >= sublayer) {
                    if (addToToken) {
                        if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "Adding " + win + " to " + token);
                        token.windows.add(i, win);
                    }
                    placeWindowBefore(wSublayer >= 0 ? attached : w, win);
                    break;
                }
            } else {
                // For positive sublayers, we go above all windows
                // in the same sublayer.
                if (wSublayer > sublayer) {
                    if (addToToken) {
                        if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "Adding " + win + " to " + token);
                        token.windows.add(i, win);
                    }
                    placeWindowBefore(w, win);
                    break;
                }
            }
        }
        if (i >= NA) {
            if (addToToken) {
                if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "Adding " + win + " to " + token);
                token.windows.add(win);
            }
            if (sublayer < 0) {
                placeWindowBefore(attached, win);
            } else {
                placeWindowAfter(largestSublayer >= 0
                                 ? windowWithLargestSublayer
                                 : attached,
                                 win);
            }
        }
    }

    private void addWindowToListInOrderLocked(final WindowState win, boolean addToToken) {
        if (DEBUG_FOCUS) Slog.d(TAG_WM, "addWindowToListInOrderLocked: win=" + win +
                " Callers=" + Debug.getCallers(4));
        if (win.mAttachedWindow == null) {
            final WindowToken token = win.mToken;
            int tokenWindowsPos = 0;
            if (token.appWindowToken != null) {
                tokenWindowsPos = addAppWindowToListLocked(win);
            } else {
                addFreeWindowToListLocked(win);
            }
            if (addToToken) {
                if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "Adding " + win + " to " + token);
                token.windows.add(tokenWindowsPos, win);
            }
        } else {
            addAttachedWindowToListLocked(win, addToToken);
        }

        final AppWindowToken appToken = win.mAppToken;
        if (appToken != null) {
            if (addToToken) {
                appToken.addWindow(win);
            }
        }
    }

    static boolean canBeImeTarget(WindowState w) {
        final int fl = w.mAttrs.flags
                & (FLAG_NOT_FOCUSABLE|FLAG_ALT_FOCUSABLE_IM);
        final int type = w.mAttrs.type;
        if (fl == 0 || fl == (FLAG_NOT_FOCUSABLE|FLAG_ALT_FOCUSABLE_IM)
                || type == TYPE_APPLICATION_STARTING) {
            if (DEBUG_INPUT_METHOD) {
                Slog.i(TAG_WM, "isVisibleOrAdding " + w + ": " + w.isVisibleOrAdding());
                if (!w.isVisibleOrAdding()) {
                    Slog.i(TAG_WM, "  mSurfaceController=" + w.mWinAnimator.mSurfaceController
                            + " relayoutCalled=" + w.mRelayoutCalled
                            + " viewVis=" + w.mViewVisibility
                            + " policyVis=" + w.mPolicyVisibility
                            + " policyVisAfterAnim=" + w.mPolicyVisibilityAfterAnim
                            + " attachHid=" + w.mAttachedHidden
                            + " exiting=" + w.mAnimatingExit + " destroying=" + w.mDestroying);
                    if (w.mAppToken != null) {
                        Slog.i(TAG_WM, "  mAppToken.hiddenRequested=" + w.mAppToken.hiddenRequested);
                    }
                }
            }
            return w.isVisibleOrAdding();
        }
        return false;
    }

    /**
     * Dig through the WindowStates and find the one that the Input Method will target.
     * @param willMove
     * @return The index+1 in mWindows of the discovered target.
     */
    int findDesiredInputMethodWindowIndexLocked(boolean willMove) {
        // TODO(multidisplay): Needs some serious rethought when the target and IME are not on the
        // same display. Or even when the current IME/target are not on the same screen as the next
        // IME/target. For now only look for input windows on the main screen.
        WindowList windows = getDefaultWindowListLocked();
        WindowState w = null;
        int i;
        for (i = windows.size() - 1; i >= 0; --i) {
            WindowState win = windows.get(i);

            if (DEBUG_INPUT_METHOD && willMove) Slog.i(TAG_WM, "Checking window @" + i
                    + " " + win + " fl=0x" + Integer.toHexString(win.mAttrs.flags));
            if (canBeImeTarget(win)) {
                w = win;
                //Slog.i(TAG_WM, "Putting input method here!");

                // Yet more tricksyness!  If this window is a "starting"
                // window, we do actually want to be on top of it, but
                // it is not -really- where input will go.  So if the caller
                // is not actually looking to move the IME, look down below
                // for a real window to target...
                if (!willMove
                        && w.mAttrs.type == TYPE_APPLICATION_STARTING
                        && i > 0) {
                    WindowState wb = windows.get(i-1);
                    if (wb.mAppToken == w.mAppToken && canBeImeTarget(wb)) {
                        i--;
                        w = wb;
                    }
                }
                break;
            }
        }

        // Now w is either mWindows[0] or an IME (or null if mWindows is empty).

        if (DEBUG_INPUT_METHOD && willMove) Slog.v(TAG_WM, "Proposed new IME target: " + w);

        // Now, a special case -- if the last target's window is in the
        // process of exiting, and is above the new target, keep on the
        // last target to avoid flicker.  Consider for example a Dialog with
        // the IME shown: when the Dialog is dismissed, we want to keep
        // the IME above it until it is completely gone so it doesn't drop
        // behind the dialog or its full-screen scrim.
        final WindowState curTarget = mInputMethodTarget;
        if (curTarget != null
                && curTarget.isDisplayedLw()
                && curTarget.isClosing()
                && (w == null || curTarget.mWinAnimator.mAnimLayer > w.mWinAnimator.mAnimLayer)) {
            if (DEBUG_INPUT_METHOD) Slog.v(TAG_WM, "Current target higher, not changing");
            return windows.indexOf(curTarget) + 1;
        }

        if (DEBUG_INPUT_METHOD) Slog.v(TAG_WM, "Desired input method target="
                + w + " willMove=" + willMove);

        if (willMove && w != null) {
            AppWindowToken token = curTarget == null ? null : curTarget.mAppToken;
            if (token != null) {

                // Now some fun for dealing with window animations that
                // modify the Z order.  We need to look at all windows below
                // the current target that are in this app, finding the highest
                // visible one in layering.
                WindowState highestTarget = null;
                int highestPos = 0;
                if (token.mAppAnimator.animating || token.mAppAnimator.animation != null) {
                    WindowList curWindows = curTarget.getWindowList();
                    int pos = curWindows.indexOf(curTarget);
                    while (pos >= 0) {
                        WindowState win = curWindows.get(pos);
                        if (win.mAppToken != token) {
                            break;
                        }
                        if (!win.mRemoved) {
                            if (highestTarget == null || win.mWinAnimator.mAnimLayer >
                                    highestTarget.mWinAnimator.mAnimLayer) {
                                highestTarget = win;
                                highestPos = pos;
                            }
                        }
                        pos--;
                    }
                }

                if (highestTarget != null) {
                    if (DEBUG_INPUT_METHOD) Slog.v(TAG_WM, mAppTransition + " " + highestTarget
                            + " animating=" + highestTarget.mWinAnimator.isAnimationSet()
                            + " layer=" + highestTarget.mWinAnimator.mAnimLayer
                            + " new layer=" + w.mWinAnimator.mAnimLayer);

                    if (mAppTransition.isTransitionSet()) {
                        // If we are currently setting up for an animation,
                        // hold everything until we can find out what will happen.
                        mInputMethodTargetWaitingAnim = true;
                        mInputMethodTarget = highestTarget;
                        return highestPos + 1;
                    } else if (highestTarget.mWinAnimator.isAnimationSet() &&
                            highestTarget.mWinAnimator.mAnimLayer > w.mWinAnimator.mAnimLayer) {
                        // If the window we are currently targeting is involved
                        // with an animation, and it is on top of the next target
                        // we will be over, then hold off on moving until
                        // that is done.
                        mInputMethodTargetWaitingAnim = true;
                        mInputMethodTarget = highestTarget;
                        return highestPos + 1;
                    }
                }
            }
        }

        //Slog.i(TAG_WM, "Placing input method @" + (i+1));
        if (w != null) {
            if (willMove) {
                if (DEBUG_INPUT_METHOD) Slog.w(TAG_WM, "Moving IM target from " + curTarget + " to "
                        + w + (SHOW_STACK_CRAWLS ? " Callers=" + Debug.getCallers(4) : ""));
                mInputMethodTarget = w;
                mInputMethodTargetWaitingAnim = false;
                if (w.mAppToken != null) {
                    mLayersController.setInputMethodAnimLayerAdjustment(
                            w.mAppToken.mAppAnimator.animLayerAdjustment);
                } else {
                    mLayersController.setInputMethodAnimLayerAdjustment(0);
                }
            }

            // If the docked divider is visible, we still need to go through this whole
            // excercise to find the appropriate input method target (used for animations
            // and dialog adjustments), but for purposes of Z ordering we simply wish to
            // place it above the docked divider. Unless it is already above the divider.
            WindowState dockedDivider = w.mDisplayContent.mDividerControllerLocked.getWindow();
            if (dockedDivider != null && dockedDivider.isVisibleLw()) {
                int dividerIndex = windows.indexOf(dockedDivider);
                if (dividerIndex > 0 && dividerIndex > i) {
                    return dividerIndex + 1;
                }
            }
            return i+1;
        }
        if (willMove) {
            if (DEBUG_INPUT_METHOD) Slog.w(TAG_WM, "Moving IM target from " + curTarget + " to null."
                    + (SHOW_STACK_CRAWLS ? " Callers=" + Debug.getCallers(4) : ""));
            mInputMethodTarget = null;
            mLayersController.setInputMethodAnimLayerAdjustment(0);
        }
        return -1;
    }

    void addInputMethodWindowToListLocked(WindowState win) {
        int pos = findDesiredInputMethodWindowIndexLocked(true);
        if (pos >= 0) {
            win.mTargetAppToken = mInputMethodTarget.mAppToken;
            if (DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(
                    TAG_WM, "Adding input method window " + win + " at " + pos);
            // TODO(multidisplay): IMEs are only supported on the default display.
            getDefaultWindowListLocked().add(pos, win);
            mWindowsChanged = true;
            moveInputMethodDialogsLocked(pos + 1);
            return;
        }
        win.mTargetAppToken = null;
        addWindowToListInOrderLocked(win, true);
        moveInputMethodDialogsLocked(pos);
    }

    private int tmpRemoveWindowLocked(int interestingPos, WindowState win) {
        WindowList windows = win.getWindowList();
        int wpos = windows.indexOf(win);
        if (wpos >= 0) {
            if (wpos < interestingPos) interestingPos--;
            if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG_WM, "Temp removing at " + wpos + ": " + win);
            windows.remove(wpos);
            mWindowsChanged = true;
            int NC = win.mChildWindows.size();
            while (NC > 0) {
                NC--;
                WindowState cw = win.mChildWindows.get(NC);
                int cpos = windows.indexOf(cw);
                if (cpos >= 0) {
                    if (cpos < interestingPos) interestingPos--;
                    if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG_WM, "Temp removing child at "
                            + cpos + ": " + cw);
                    windows.remove(cpos);
                }
            }
        }
        return interestingPos;
    }

    private void reAddWindowToListInOrderLocked(WindowState win) {
        addWindowToListInOrderLocked(win, false);
        // This is a hack to get all of the child windows added as well
        // at the right position.  Child windows should be rare and
        // this case should be rare, so it shouldn't be that big a deal.
        WindowList windows = win.getWindowList();
        int wpos = windows.indexOf(win);
        if (wpos >= 0) {
            if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG_WM, "ReAdd removing from " + wpos + ": " + win);
            windows.remove(wpos);
            mWindowsChanged = true;
            reAddWindowLocked(wpos, win);
        }
    }

    void logWindowList(final WindowList windows, String prefix) {
        int N = windows.size();
        while (N > 0) {
            N--;
            Slog.v(TAG_WM, prefix + "#" + N + ": " + windows.get(N));
        }
    }

    void moveInputMethodDialogsLocked(int pos) {
        ArrayList<WindowState> dialogs = mInputMethodDialogs;

        // TODO(multidisplay): IMEs are only supported on the default display.
        WindowList windows = getDefaultWindowListLocked();
        final int N = dialogs.size();
        if (DEBUG_INPUT_METHOD) Slog.v(TAG_WM, "Removing " + N + " dialogs w/pos=" + pos);
        for (int i=0; i<N; i++) {
            pos = tmpRemoveWindowLocked(pos, dialogs.get(i));
        }
        if (DEBUG_INPUT_METHOD) {
            Slog.v(TAG_WM, "Window list w/pos=" + pos);
            logWindowList(windows, "  ");
        }

        if (pos >= 0) {
            final AppWindowToken targetAppToken = mInputMethodTarget.mAppToken;
            // Skip windows owned by the input method.
            if (mInputMethodWindow != null) {
                while (pos < windows.size()) {
                    WindowState wp = windows.get(pos);
                    if (wp == mInputMethodWindow || wp.mAttachedWindow == mInputMethodWindow) {
                        pos++;
                        continue;
                    }
                    break;
                }
            }
            if (DEBUG_INPUT_METHOD) Slog.v(TAG_WM, "Adding " + N + " dialogs at pos=" + pos);
            for (int i=0; i<N; i++) {
                WindowState win = dialogs.get(i);
                win.mTargetAppToken = targetAppToken;
                pos = reAddWindowLocked(pos, win);
            }
            if (DEBUG_INPUT_METHOD) {
                Slog.v(TAG_WM, "Final window list:");
                logWindowList(windows, "  ");
            }
            return;
        }
        for (int i=0; i<N; i++) {
            WindowState win = dialogs.get(i);
            win.mTargetAppToken = null;
            reAddWindowToListInOrderLocked(win);
            if (DEBUG_INPUT_METHOD) {
                Slog.v(TAG_WM, "No IM target, final list:");
                logWindowList(windows, "  ");
            }
        }
    }

    boolean moveInputMethodWindowsIfNeededLocked(boolean needAssignLayers) {
        final WindowState imWin = mInputMethodWindow;
        final int DN = mInputMethodDialogs.size();
        if (imWin == null && DN == 0) {
            return false;
        }

        // TODO(multidisplay): IMEs are only supported on the default display.
        WindowList windows = getDefaultWindowListLocked();

        int imPos = findDesiredInputMethodWindowIndexLocked(true);
        if (imPos >= 0) {
            // In this case, the input method windows are to be placed
            // immediately above the window they are targeting.

            // First check to see if the input method windows are already
            // located here, and contiguous.
            final int N = windows.size();
            WindowState firstImWin = imPos < N
                    ? windows.get(imPos) : null;

            // Figure out the actual input method window that should be
            // at the bottom of their stack.
            WindowState baseImWin = imWin != null
                    ? imWin : mInputMethodDialogs.get(0);
            if (baseImWin.mChildWindows.size() > 0) {
                WindowState cw = baseImWin.mChildWindows.get(0);
                if (cw.mSubLayer < 0) baseImWin = cw;
            }

            if (firstImWin == baseImWin) {
                // The windows haven't moved...  but are they still contiguous?
                // First find the top IM window.
                int pos = imPos+1;
                while (pos < N) {
                    if (!(windows.get(pos)).mIsImWindow) {
                        break;
                    }
                    pos++;
                }
                pos++;
                // Now there should be no more input method windows above.
                while (pos < N) {
                    if ((windows.get(pos)).mIsImWindow) {
                        break;
                    }
                    pos++;
                }
                if (pos >= N) {
                    // Z order is good.
                    // The IM target window may be changed, so update the mTargetAppToken.
                    if (imWin != null) {
                        imWin.mTargetAppToken = mInputMethodTarget.mAppToken;
                    }
                    return false;
                }
            }

            if (imWin != null) {
                if (DEBUG_INPUT_METHOD) {
                    Slog.v(TAG_WM, "Moving IM from " + imPos);
                    logWindowList(windows, "  ");
                }
                imPos = tmpRemoveWindowLocked(imPos, imWin);
                if (DEBUG_INPUT_METHOD) {
                    Slog.v(TAG_WM, "List after removing with new pos " + imPos + ":");
                    logWindowList(windows, "  ");
                }
                imWin.mTargetAppToken = mInputMethodTarget.mAppToken;
                reAddWindowLocked(imPos, imWin);
                if (DEBUG_INPUT_METHOD) {
                    Slog.v(TAG_WM, "List after moving IM to " + imPos + ":");
                    logWindowList(windows, "  ");
                }
                if (DN > 0) moveInputMethodDialogsLocked(imPos+1);
            } else {
                moveInputMethodDialogsLocked(imPos);
            }

        } else {
            // In this case, the input method windows go in a fixed layer,
            // because they aren't currently associated with a focus window.

            if (imWin != null) {
                if (DEBUG_INPUT_METHOD) Slog.v(TAG_WM, "Moving IM from " + imPos);
                tmpRemoveWindowLocked(0, imWin);
                imWin.mTargetAppToken = null;
                reAddWindowToListInOrderLocked(imWin);
                if (DEBUG_INPUT_METHOD) {
                    Slog.v(TAG_WM, "List with no IM target:");
                    logWindowList(windows, "  ");
                }
                if (DN > 0) moveInputMethodDialogsLocked(-1);
            } else {
                moveInputMethodDialogsLocked(-1);
            }

        }

        if (needAssignLayers) {
            mLayersController.assignLayersLocked(windows);
        }

        return true;
    }

    private static boolean excludeWindowTypeFromTapOutTask(int windowType) {
        switch (windowType) {
            case TYPE_STATUS_BAR:
            case TYPE_NAVIGATION_BAR:
            case TYPE_INPUT_METHOD_DIALOG:
                return true;
        }
        return false;
    }

    public int addWindow(Session session, IWindow client, int seq,
            WindowManager.LayoutParams attrs, int viewVisibility, int displayId,
            Rect outContentInsets, Rect outStableInsets, Rect outOutsets,
            InputChannel outInputChannel) {
        int[] appOp = new int[1];
        int res = mPolicy.checkAddPermission(attrs, appOp);
        if (res != WindowManagerGlobal.ADD_OKAY) {
            return res;
        }

        boolean reportNewConfig = false;
        WindowState attachedWindow = null;
        long origId;
        final int type = attrs.type;

        synchronized(mWindowMap) {
            if (!mDisplayReady) {
                throw new IllegalStateException("Display has not been initialialized");
            }

            final DisplayContent displayContent = getDisplayContentLocked(displayId);
            if (displayContent == null) {
                Slog.w(TAG_WM, "Attempted to add window to a display that does not exist: "
                        + displayId + ".  Aborting.");
                return WindowManagerGlobal.ADD_INVALID_DISPLAY;
            }
            if (!displayContent.hasAccess(session.mUid)) {
                Slog.w(TAG_WM, "Attempted to add window to a display for which the application "
                        + "does not have access: " + displayId + ".  Aborting.");
                return WindowManagerGlobal.ADD_INVALID_DISPLAY;
            }

            if (mWindowMap.containsKey(client.asBinder())) {
                Slog.w(TAG_WM, "Window " + client + " is already added");
                return WindowManagerGlobal.ADD_DUPLICATE_ADD;
            }

            if (type >= FIRST_SUB_WINDOW && type <= LAST_SUB_WINDOW) {
                attachedWindow = windowForClientLocked(null, attrs.token, false);
                if (attachedWindow == null) {
                    Slog.w(TAG_WM, "Attempted to add window with token that is not a window: "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_SUBWINDOW_TOKEN;
                }
                if (attachedWindow.mAttrs.type >= FIRST_SUB_WINDOW
                        && attachedWindow.mAttrs.type <= LAST_SUB_WINDOW) {
                    Slog.w(TAG_WM, "Attempted to add window with token that is a sub-window: "
                            + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_SUBWINDOW_TOKEN;
                }
            }

            if (type == TYPE_PRIVATE_PRESENTATION && !displayContent.isPrivate()) {
                Slog.w(TAG_WM, "Attempted to add private presentation window to a non-private display.  Aborting.");
                return WindowManagerGlobal.ADD_PERMISSION_DENIED;
            }

            boolean addToken = false;
            WindowToken token = mTokenMap.get(attrs.token);
            AppWindowToken atoken = null;
            if (token == null) {
                if (type >= FIRST_APPLICATION_WINDOW && type <= LAST_APPLICATION_WINDOW) {
                    Slog.w(TAG_WM, "Attempted to add application window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
                if (type == TYPE_INPUT_METHOD) {
                    Slog.w(TAG_WM, "Attempted to add input method window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
                if (type == TYPE_VOICE_INTERACTION) {
                    Slog.w(TAG_WM, "Attempted to add voice interaction window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
                if (type == TYPE_WALLPAPER) {
                    Slog.w(TAG_WM, "Attempted to add wallpaper window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
                if (type == TYPE_DREAM) {
                    Slog.w(TAG_WM, "Attempted to add Dream window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
                if (type == TYPE_QS_DIALOG) {
                    Slog.w(TAG_WM, "Attempted to add QS dialog window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
                if (type == TYPE_ACCESSIBILITY_OVERLAY) {
                    Slog.w(TAG_WM, "Attempted to add Accessibility overlay window with unknown token "
                            + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
                token = new WindowToken(this, attrs.token, -1, false);
                addToken = true;
            } else if (type >= FIRST_APPLICATION_WINDOW && type <= LAST_APPLICATION_WINDOW) {
                atoken = token.appWindowToken;
                if (atoken == null) {
                    Slog.w(TAG_WM, "Attempted to add window with non-application token "
                          + token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_NOT_APP_TOKEN;
                } else if (atoken.removed) {
                    Slog.w(TAG_WM, "Attempted to add window with exiting application token "
                          + token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_APP_EXITING;
                }
                if (type == TYPE_APPLICATION_STARTING && atoken.firstWindowDrawn) {
                    // No need for this guy!
                    if (DEBUG_STARTING_WINDOW || localLOGV) Slog.v(
                            TAG_WM, "**** NO NEED TO START: " + attrs.getTitle());
                    return WindowManagerGlobal.ADD_STARTING_NOT_NEEDED;
                }
            } else if (type == TYPE_INPUT_METHOD) {
                if (token.windowType != TYPE_INPUT_METHOD) {
                    Slog.w(TAG_WM, "Attempted to add input method window with bad token "
                            + attrs.token + ".  Aborting.");
                      return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (type == TYPE_VOICE_INTERACTION) {
                if (token.windowType != TYPE_VOICE_INTERACTION) {
                    Slog.w(TAG_WM, "Attempted to add voice interaction window with bad token "
                            + attrs.token + ".  Aborting.");
                      return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (type == TYPE_WALLPAPER) {
                if (token.windowType != TYPE_WALLPAPER) {
                    Slog.w(TAG_WM, "Attempted to add wallpaper window with bad token "
                            + attrs.token + ".  Aborting.");
                      return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (type == TYPE_DREAM) {
                if (token.windowType != TYPE_DREAM) {
                    Slog.w(TAG_WM, "Attempted to add Dream window with bad token "
                            + attrs.token + ".  Aborting.");
                      return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (type == TYPE_ACCESSIBILITY_OVERLAY) {
                if (token.windowType != TYPE_ACCESSIBILITY_OVERLAY) {
                    Slog.w(TAG_WM, "Attempted to add Accessibility overlay window with bad token "
                            + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (type == TYPE_QS_DIALOG) {
                if (token.windowType != TYPE_QS_DIALOG) {
                    Slog.w(TAG_WM, "Attempted to add QS dialog window with bad token "
                            + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (token.appWindowToken != null) {
                Slog.w(TAG_WM, "Non-null appWindowToken for system window of type=" + type);
                // It is not valid to use an app token with other system types; we will
                // instead make a new token for it (as if null had been passed in for the token).
                attrs.token = null;
                token = new WindowToken(this, null, -1, false);
                addToken = true;
            }

            WindowState win = new WindowState(this, session, client, token,
                    attachedWindow, appOp[0], seq, attrs, viewVisibility, displayContent);
            if (win.mDeathRecipient == null) {
                // Client has apparently died, so there is no reason to
                // continue.
                Slog.w(TAG_WM, "Adding window client " + client.asBinder()
                        + " that is dead, aborting.");
                return WindowManagerGlobal.ADD_APP_EXITING;
            }

            if (win.getDisplayContent() == null) {
                Slog.w(TAG_WM, "Adding window to Display that has been removed.");
                return WindowManagerGlobal.ADD_INVALID_DISPLAY;
            }

            mPolicy.adjustWindowParamsLw(win.mAttrs);
            win.setShowToOwnerOnlyLocked(mPolicy.checkShowToOwnerOnly(attrs));

            res = mPolicy.prepareAddWindowLw(win, attrs);
            if (res != WindowManagerGlobal.ADD_OKAY) {
                return res;
            }

            final boolean openInputChannels = (outInputChannel != null
                    && (attrs.inputFeatures & INPUT_FEATURE_NO_INPUT_CHANNEL) == 0);
            if  (openInputChannels) {
                win.openInputChannel(outInputChannel);
            }

            // From now on, no exceptions or errors allowed!

            res = WindowManagerGlobal.ADD_OKAY;

            if (excludeWindowTypeFromTapOutTask(type)) {
                displayContent.mTapExcludedWindows.add(win);
            }

            origId = Binder.clearCallingIdentity();

            if (addToken) {
                mTokenMap.put(attrs.token, token);
            }
            win.attach();
            mWindowMap.put(client.asBinder(), win);
            if (win.mAppOp != AppOpsManager.OP_NONE) {
                int startOpResult = mAppOps.startOpNoThrow(win.mAppOp, win.getOwningUid(),
                        win.getOwningPackage());
                if ((startOpResult != AppOpsManager.MODE_ALLOWED) &&
                        (startOpResult != AppOpsManager.MODE_DEFAULT)) {
                    win.setAppOpVisibilityLw(false);
                }
            }

            if (type == TYPE_APPLICATION_STARTING && token.appWindowToken != null) {
                token.appWindowToken.startingWindow = win;
                if (DEBUG_STARTING_WINDOW) Slog.v (TAG_WM, "addWindow: " + token.appWindowToken
                        + " startingWindow=" + win);
            }

            boolean imMayMove = true;

            if (type == TYPE_INPUT_METHOD) {
                win.mGivenInsetsPending = true;
                mInputMethodWindow = win;
                addInputMethodWindowToListLocked(win);
                imMayMove = false;
            } else if (type == TYPE_INPUT_METHOD_DIALOG) {
                mInputMethodDialogs.add(win);
                addWindowToListInOrderLocked(win, true);
                moveInputMethodDialogsLocked(findDesiredInputMethodWindowIndexLocked(true));
                imMayMove = false;
            } else {
                addWindowToListInOrderLocked(win, true);
                if (type == TYPE_WALLPAPER) {
                    mWallpaperControllerLocked.clearLastWallpaperTimeoutTime();
                    displayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
                } else if ((attrs.flags&FLAG_SHOW_WALLPAPER) != 0) {
                    displayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
                } else if (mWallpaperControllerLocked.isBelowWallpaperTarget(win)) {
                    // If there is currently a wallpaper being shown, and
                    // the base layer of the new window is below the current
                    // layer of the target window, then adjust the wallpaper.
                    // This is to avoid a new window being placed between the
                    // wallpaper and its target.
                    displayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
                }
            }

            // If the window is being added to a task that's docked but non-resizeable,
            // we need to update this new window's scroll position when it's added.
            win.applyScrollIfNeeded();

            // If the window is being added to a stack that's currently adjusted for IME,
            // make sure to apply the same adjust to this new window.
            win.applyAdjustForImeIfNeeded();

            if (type == TYPE_DOCK_DIVIDER) {
                getDefaultDisplayContentLocked().getDockedDividerController().setWindow(win);
            }

            final WindowStateAnimator winAnimator = win.mWinAnimator;
            winAnimator.mEnterAnimationPending = true;
            winAnimator.mEnteringAnimation = true;
            // Check if we need to prepare a transition for replacing window first.
            if (atoken != null && !prepareWindowReplacementTransition(atoken)) {
                // If not, check if need to set up a dummy transition during display freeze
                // so that the unfreeze wait for the apps to draw. This might be needed if
                // the app is relaunching.
                prepareNoneTransitionForRelaunching(atoken);
            }

            if (displayContent.isDefaultDisplay) {
                final DisplayInfo displayInfo = displayContent.getDisplayInfo();
                final Rect taskBounds;
                if (atoken != null && atoken.mTask != null) {
                    taskBounds = mTmpRect;
                    atoken.mTask.getBounds(mTmpRect);
                } else {
                    taskBounds = null;
                }
                if (mPolicy.getInsetHintLw(win.mAttrs, taskBounds, mRotation,
                        displayInfo.logicalWidth, displayInfo.logicalHeight, outContentInsets,
                        outStableInsets, outOutsets)) {
                    res |= WindowManagerGlobal.ADD_FLAG_ALWAYS_CONSUME_NAV_BAR;
                }
            } else {
                outContentInsets.setEmpty();
                outStableInsets.setEmpty();
            }

            if (mInTouchMode) {
                res |= WindowManagerGlobal.ADD_FLAG_IN_TOUCH_MODE;
            }
            if (win.mAppToken == null || !win.mAppToken.clientHidden) {
                res |= WindowManagerGlobal.ADD_FLAG_APP_VISIBLE;
            }

            mInputMonitor.setUpdateInputWindowsNeededLw();

            boolean focusChanged = false;
            if (win.canReceiveKeys()) {
                focusChanged = updateFocusedWindowLocked(UPDATE_FOCUS_WILL_ASSIGN_LAYERS,
                        false /*updateInputWindows*/);
                if (focusChanged) {
                    imMayMove = false;
                }
            }

            if (imMayMove) {
                moveInputMethodWindowsIfNeededLocked(false);
            }

            mLayersController.assignLayersLocked(displayContent.getWindowList());
            // Don't do layout here, the window must call
            // relayout to be displayed, so we'll do it there.

            if (focusChanged) {
                mInputMonitor.setInputFocusLw(mCurrentFocus, false /*updateInputWindows*/);
            }
            mInputMonitor.updateInputWindowsLw(false /*force*/);

            if (localLOGV || DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "addWindow: New client "
                    + client.asBinder() + ": window=" + win + " Callers=" + Debug.getCallers(5));

            if (win.isVisibleOrAdding() && updateOrientationFromAppTokensLocked(false)) {
                reportNewConfig = true;
            }
            if (attrs.removeTimeoutMilliseconds > 0) {
                mH.sendMessageDelayed(
                        mH.obtainMessage(H.WINDOW_REMOVE_TIMEOUT, win),
                        attrs.removeTimeoutMilliseconds);
            }
        }

        if (reportNewConfig) {
            sendNewConfiguration();
        }

        Binder.restoreCallingIdentity(origId);

        return res;
    }

    /**
     * Returns true if we're done setting up any transitions.
     */
    private boolean prepareWindowReplacementTransition(AppWindowToken atoken) {
        atoken.clearAllDrawn();
        WindowState replacedWindow = null;
        for (int i = atoken.windows.size() - 1; i >= 0 && replacedWindow == null; i--) {
            WindowState candidate = atoken.windows.get(i);
            if (candidate.mAnimatingExit && candidate.mWillReplaceWindow
                    && candidate.mAnimateReplacingWindow) {
                replacedWindow = candidate;
            }
        }
        if (replacedWindow == null) {
            // We expect to already receive a request to remove the old window. If it did not
            // happen, let's just simply add a window.
            return false;
        }
        // We use the visible frame, because we want the animation to morph the window from what
        // was visible to the user to the final destination of the new window.
        Rect frame = replacedWindow.mVisibleFrame;
        // We treat this as if this activity was opening, so we can trigger the app transition
        // animation and piggy-back on existing transition animation infrastructure.
        mOpeningApps.add(atoken);
        prepareAppTransition(AppTransition.TRANSIT_ACTIVITY_RELAUNCH, ALWAYS_KEEP_CURRENT);
        mAppTransition.overridePendingAppTransitionClipReveal(frame.left, frame.top,
                frame.width(), frame.height());
        executeAppTransition();
        return true;
    }

    private void prepareNoneTransitionForRelaunching(AppWindowToken atoken) {
        // Set up a none-transition and add the app to opening apps, so that the display
        // unfreeze wait for the apps to be drawn.
        // Note that if the display unfroze already because app unfreeze timed out,
        // we don't set up the transition anymore and just let it go.
        if (mDisplayFrozen && !mOpeningApps.contains(atoken) && atoken.isRelaunching()) {
            mOpeningApps.add(atoken);
            prepareAppTransition(AppTransition.TRANSIT_NONE, !ALWAYS_KEEP_CURRENT);
            executeAppTransition();
        }
    }

    /**
     * Returns whether screen capture is disabled for all windows of a specific user.
     */
    boolean isScreenCaptureDisabledLocked(int userId) {
        Boolean disabled = mScreenCaptureDisabled.get(userId);
        if (disabled == null) {
            return false;
        }
        return disabled;
    }

    boolean isSecureLocked(WindowState w) {
        if ((w.mAttrs.flags&WindowManager.LayoutParams.FLAG_SECURE) != 0) {
            return true;
        }
        if (isScreenCaptureDisabledLocked(UserHandle.getUserId(w.mOwnerUid))) {
            return true;
        }
        return false;
    }

    /**
     * Set mScreenCaptureDisabled for specific user
     */
    @Override
    public void setScreenCaptureDisabled(int userId, boolean disabled) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID) {
            throw new SecurityException("Only system can call setScreenCaptureDisabled.");
        }

        synchronized(mWindowMap) {
            mScreenCaptureDisabled.put(userId, disabled);
            // Update secure surface for all windows belonging to this user.
            for (int displayNdx = mDisplayContents.size() - 1; displayNdx >= 0; --displayNdx) {
                WindowList windows = mDisplayContents.valueAt(displayNdx).getWindowList();
                for (int winNdx = windows.size() - 1; winNdx >= 0; --winNdx) {
                    final WindowState win = windows.get(winNdx);
                    if (win.mHasSurface && userId == UserHandle.getUserId(win.mOwnerUid)) {
                        win.mWinAnimator.setSecureLocked(disabled);
                    }
                }
            }
        }
    }

    private void setupWindowForRemoveOnExit(WindowState win) {
        win.mRemoveOnExit = true;
        win.setDisplayLayoutNeeded();
        // Request a focus update as this window's input channel is already gone. Otherwise
        // we could have no focused window in input manager.
        final boolean focusChanged = updateFocusedWindowLocked(
                UPDATE_FOCUS_WILL_PLACE_SURFACES, false /*updateInputWindows*/);
        mWindowPlacerLocked.performSurfacePlacement();
        if (focusChanged) {
            mInputMonitor.updateInputWindowsLw(false /*force*/);
        }
    }

    public void removeWindow(Session session, IWindow client) {
        synchronized(mWindowMap) {
            WindowState win = windowForClientLocked(session, client, false);
            if (win == null) {
                return;
            }
            removeWindowLocked(win);
        }
    }

    void removeWindowLocked(WindowState win) {
        removeWindowLocked(win, false);
    }

    void removeWindowLocked(WindowState win, boolean keepVisibleDeadWindow) {
        win.mWindowRemovalAllowed = true;
        if (DEBUG_ADD_REMOVE) Slog.v(TAG,
                "removeWindowLocked: " + win + " callers=" + Debug.getCallers(4));

        final boolean startingWindow = win.mAttrs.type == TYPE_APPLICATION_STARTING;
        if (startingWindow) {
            if (DEBUG_STARTING_WINDOW) Slog.d(TAG_WM, "Starting window removed " + win);
        }

        if (localLOGV || DEBUG_FOCUS || DEBUG_FOCUS_LIGHT && win == mCurrentFocus) Slog.v(
                TAG_WM, "Remove " + win + " client="
                + Integer.toHexString(System.identityHashCode(win.mClient.asBinder()))
                + ", surfaceController=" + win.mWinAnimator.mSurfaceController + " Callers="
                + Debug.getCallers(4));

        final long origId = Binder.clearCallingIdentity();

        win.disposeInputChannel();

        if (DEBUG_APP_TRANSITIONS) Slog.v(TAG_WM,
                "Remove " + win + ": mSurfaceController=" + win.mWinAnimator.mSurfaceController
                + " mAnimatingExit=" + win.mAnimatingExit
                + " mRemoveOnExit=" + win.mRemoveOnExit
                + " mHasSurface=" + win.mHasSurface
                + " surfaceShowing=" + win.mWinAnimator.getShown()
                + " isAnimationSet=" + win.mWinAnimator.isAnimationSet()
                + " app-animation="
                + (win.mAppToken != null ? win.mAppToken.mAppAnimator.animation : null)
                + " mWillReplaceWindow=" + win.mWillReplaceWindow
                + " inPendingTransaction="
                + (win.mAppToken != null ? win.mAppToken.inPendingTransaction : false)
                + " mDisplayFrozen=" + mDisplayFrozen
                + " callers=" + Debug.getCallers(6));
        // Visibility of the removed window. Will be used later to update orientation later on.
        boolean wasVisible = false;
        // First, see if we need to run an animation. If we do, we have to hold off on removing the
        // window until the animation is done. If the display is frozen, just remove immediately,
        // since the animation wouldn't be seen.
        if (win.mHasSurface && okToDisplay()) {
            final AppWindowToken appToken = win.mAppToken;
            if (win.mWillReplaceWindow) {
                // This window is going to be replaced. We need to keep it around until the new one
                // gets added, then we will get rid of this one.
                if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "Preserving " + win + " until the new one is "
                        + "added");
                // TODO: We are overloading mAnimatingExit flag to prevent the window state from
                // been removed. We probably need another flag to indicate that window removal
                // should be deffered vs. overloading the flag that says we are playing an exit
                // animation.
                win.mAnimatingExit = true;
                win.mReplacingRemoveRequested = true;
                Binder.restoreCallingIdentity(origId);
                return;
            }

            if (win.isAnimatingWithSavedSurface() && !appToken.allDrawnExcludingSaved) {
                // We started enter animation early with a saved surface, now the app asks to remove
                // this window. If we remove it now and the app is not yet drawn, we'll show a
                // flicker. Delay the removal now until it's really drawn.
                if (DEBUG_ADD_REMOVE) {
                    Slog.d(TAG_WM, "removeWindowLocked: delay removal of " + win
                            + " due to early animation");
                }
                // Do not set mAnimatingExit to true here, it will cause the surface to be hidden
                // immediately after the enter animation is done. If the app is not yet drawn then
                // it will show up as a flicker.
                setupWindowForRemoveOnExit(win);
                Binder.restoreCallingIdentity(origId);
                return;
            }
            // If we are not currently running the exit animation, we need to see about starting one
            wasVisible = win.isWinVisibleLw();

            if (keepVisibleDeadWindow) {
                if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                        "Not removing " + win + " because app died while it's visible");

                win.mAppDied = true;
                win.setDisplayLayoutNeeded();
                mWindowPlacerLocked.performSurfacePlacement();

                // Set up a replacement input channel since the app is now dead.
                // We need to catch tapping on the dead window to restart the app.
                win.openInputChannel(null);
                mInputMonitor.updateInputWindowsLw(true /*force*/);

                Binder.restoreCallingIdentity(origId);
                return;
            }

            final WindowStateAnimator winAnimator = win.mWinAnimator;
            if (wasVisible) {
                final int transit = (!startingWindow) ? TRANSIT_EXIT : TRANSIT_PREVIEW_DONE;

                // Try starting an animation.
                if (winAnimator.applyAnimationLocked(transit, false)) {
                    win.mAnimatingExit = true;
                }
                //TODO (multidisplay): Magnification is supported only for the default display.
                if (mAccessibilityController != null
                        && win.getDisplayId() == Display.DEFAULT_DISPLAY) {
                    mAccessibilityController.onWindowTransitionLocked(win, transit);
                }
            }
            final boolean isAnimating =
                    winAnimator.isAnimationSet() && !winAnimator.isDummyAnimation();
            final boolean lastWindowIsStartingWindow = startingWindow && appToken != null
                    && appToken.allAppWindows.size() == 1;
            // We delay the removal of a window if it has a showing surface that can be used to run
            // exit animation and it is marked as exiting.
            // Also, If isn't the an animating starting window that is the last window in the app.
            // We allow the removal of the non-animating starting window now as there is no
            // additional window or animation that will trigger its removal.
            if (winAnimator.getShown() && win.mAnimatingExit
                    && (!lastWindowIsStartingWindow || isAnimating)) {
                // The exit animation is running or should run... wait for it!
                if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                        "Not removing " + win + " due to exit animation ");
                setupWindowForRemoveOnExit(win);
                if (appToken != null) {
                    appToken.updateReportedVisibilityLocked();
                }
                Binder.restoreCallingIdentity(origId);
                return;
            }
        }

        removeWindowInnerLocked(win);
        // Removing a visible window will effect the computed orientation
        // So just update orientation if needed.
        if (wasVisible && updateOrientationFromAppTokensLocked(false)) {
            mH.sendEmptyMessage(H.SEND_NEW_CONFIGURATION);
        }
        updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, true /*updateInputWindows*/);
        Binder.restoreCallingIdentity(origId);
    }

    void removeWindowInnerLocked(WindowState win) {
        if (win.mRemoved) {
            // Nothing to do.
            if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                    "removeWindowInnerLocked: " + win + " Already removed...");
            return;
        }

        for (int i = win.mChildWindows.size() - 1; i >= 0; i--) {
            WindowState cwin = win.mChildWindows.get(i);
            Slog.w(TAG_WM, "Force-removing child win " + cwin + " from container " + win);
            removeWindowInnerLocked(cwin);
        }

        win.mRemoved = true;

        if (mInputMethodTarget == win) {
            moveInputMethodWindowsIfNeededLocked(false);
        }

        if (false) {
            RuntimeException e = new RuntimeException("here");
            e.fillInStackTrace();
            Slog.w(TAG_WM, "Removing window " + win, e);
        }

        final int type = win.mAttrs.type;
        if (excludeWindowTypeFromTapOutTask(type)) {
            final DisplayContent displaycontent = win.getDisplayContent();
            displaycontent.mTapExcludedWindows.remove(win);
        }
        mPolicy.removeWindowLw(win);
        win.removeLocked();

        if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "removeWindowInnerLocked: " + win);
        mWindowMap.remove(win.mClient.asBinder());
        if (win.mAppOp != AppOpsManager.OP_NONE) {
            mAppOps.finishOp(win.mAppOp, win.getOwningUid(), win.getOwningPackage());
        }

        mPendingRemove.remove(win);
        mResizingWindows.remove(win);
        mWindowsChanged = true;
        if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG_WM, "Final remove of window: " + win);

        if (mInputMethodWindow == win) {
            mInputMethodWindow = null;
        } else if (win.mAttrs.type == TYPE_INPUT_METHOD_DIALOG) {
            mInputMethodDialogs.remove(win);
        }

        final WindowToken token = win.mToken;
        final AppWindowToken atoken = win.mAppToken;
        if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "Removing " + win + " from " + token);
        token.windows.remove(win);
        if (atoken != null) {
            atoken.allAppWindows.remove(win);
        }
        if (localLOGV) Slog.v(
                TAG_WM, "**** Removing window " + win + ": count="
                + token.windows.size());
        if (token.windows.size() == 0) {
            if (!token.explicit) {
                mTokenMap.remove(token.token);
            } else if (atoken != null) {
                atoken.firstWindowDrawn = false;
                atoken.clearAllDrawn();
            }
        }

        if (atoken != null) {
            if (atoken.startingWindow == win) {
                if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Notify removed startingWindow " + win);
                scheduleRemoveStartingWindowLocked(atoken);
            } else
            if (atoken.allAppWindows.size() == 0 && atoken.startingData != null) {
                // If this is the last window and we had requested a starting
                // transition window, well there is no point now.
                if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Nulling last startingWindow");
                atoken.startingData = null;
            } else if (atoken.allAppWindows.size() == 1 && atoken.startingView != null) {
                // If this is the last window except for a starting transition
                // window, we need to get rid of the starting transition.
                scheduleRemoveStartingWindowLocked(atoken);
            }
        }

        if (type == TYPE_WALLPAPER) {
            mWallpaperControllerLocked.clearLastWallpaperTimeoutTime();
            getDefaultDisplayContentLocked().pendingLayoutChanges |=
                    WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
        } else if ((win.mAttrs.flags&FLAG_SHOW_WALLPAPER) != 0) {
            getDefaultDisplayContentLocked().pendingLayoutChanges |=
                    WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
        }

        final WindowList windows = win.getWindowList();
        if (windows != null) {
            windows.remove(win);
            if (!mWindowPlacerLocked.isInLayout()) {
                mLayersController.assignLayersLocked(windows);
                win.setDisplayLayoutNeeded();
                mWindowPlacerLocked.performSurfacePlacement();
                if (win.mAppToken != null) {
                    win.mAppToken.updateReportedVisibilityLocked();
                }
            }
        }

        mInputMonitor.updateInputWindowsLw(true /*force*/);
    }

    public void updateAppOpsState() {
        synchronized(mWindowMap) {
            final int numDisplays = mDisplayContents.size();
            for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                final WindowList windows = mDisplayContents.valueAt(displayNdx).getWindowList();
                final int numWindows = windows.size();
                for (int winNdx = 0; winNdx < numWindows; ++winNdx) {
                    final WindowState win = windows.get(winNdx);
                    if (win.mAppOp != AppOpsManager.OP_NONE) {
                        final int mode = mAppOps.checkOpNoThrow(win.mAppOp, win.getOwningUid(),
                                win.getOwningPackage());
                        win.setAppOpVisibilityLw(mode == AppOpsManager.MODE_ALLOWED ||
                                mode == AppOpsManager.MODE_DEFAULT);
                    }
                }
            }
        }
    }

    static void logSurface(WindowState w, String msg, boolean withStackTrace) {
        String str = "  SURFACE " + msg + ": " + w;
        if (withStackTrace) {
            logWithStack(TAG, str);
        } else {
            Slog.i(TAG_WM, str);
        }
    }

    static void logSurface(SurfaceControl s, String title, String msg) {
        String str = "  SURFACE " + s + ": " + msg + " / " + title;
        Slog.i(TAG_WM, str);
    }

    static void logWithStack(String tag, String s) {
        RuntimeException e = null;
        if (SHOW_STACK_CRAWLS) {
            e = new RuntimeException();
            e.fillInStackTrace();
        }
        Slog.i(tag, s, e);
    }

    void setTransparentRegionWindow(Session session, IWindow client, Region region) {
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mWindowMap) {
                WindowState w = windowForClientLocked(session, client, false);
                if (SHOW_TRANSACTIONS) WindowManagerService.logSurface(w,
                        "transparentRegionHint=" + region, false);

                if ((w != null) && w.mHasSurface) {
                    w.mWinAnimator.setTransparentRegionHintLocked(region);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void setInsetsWindow(Session session, IWindow client, int touchableInsets, Rect contentInsets,
            Rect visibleInsets, Region touchableRegion) {
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mWindowMap) {
                WindowState w = windowForClientLocked(session, client, false);
                if (DEBUG_LAYOUT) Slog.d(TAG, "setInsetsWindow " + w
                        + ", contentInsets=" + w.mGivenContentInsets + " -> " + contentInsets
                        + ", visibleInsets=" + w.mGivenVisibleInsets + " -> " + visibleInsets
                        + ", touchableRegion=" + w.mGivenTouchableRegion + " -> " + touchableRegion
                        + ", touchableInsets " + w.mTouchableInsets + " -> " + touchableInsets);
                if (w != null) {
                    w.mGivenInsetsPending = false;
                    w.mGivenContentInsets.set(contentInsets);
                    w.mGivenVisibleInsets.set(visibleInsets);
                    w.mGivenTouchableRegion.set(touchableRegion);
                    w.mTouchableInsets = touchableInsets;
                    if (w.mGlobalScale != 1) {
                        w.mGivenContentInsets.scale(w.mGlobalScale);
                        w.mGivenVisibleInsets.scale(w.mGlobalScale);
                        w.mGivenTouchableRegion.scale(w.mGlobalScale);
                    }
                    w.setDisplayLayoutNeeded();
                    mWindowPlacerLocked.performSurfacePlacement();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void getWindowDisplayFrame(Session session, IWindow client,
            Rect outDisplayFrame) {
        synchronized(mWindowMap) {
            WindowState win = windowForClientLocked(session, client, false);
            if (win == null) {
                outDisplayFrame.setEmpty();
                return;
            }
            outDisplayFrame.set(win.mDisplayFrame);
        }
    }

    public void onRectangleOnScreenRequested(IBinder token, Rect rectangle) {
        synchronized (mWindowMap) {
            if (mAccessibilityController != null) {
                WindowState window = mWindowMap.get(token);
                //TODO (multidisplay): Magnification is supported only for the default display.
                if (window != null && window.getDisplayId() == Display.DEFAULT_DISPLAY) {
                    mAccessibilityController.onRectangleOnScreenRequestedLocked(rectangle);
                }
            }
        }
    }

    public IWindowId getWindowId(IBinder token) {
        synchronized (mWindowMap) {
            WindowState window = mWindowMap.get(token);
            return window != null ? window.mWindowId : null;
        }
    }

    public void pokeDrawLock(Session session, IBinder token) {
        synchronized (mWindowMap) {
            WindowState window = windowForClientLocked(session, token, false);
            if (window != null) {
                window.pokeDrawLockLw(mDrawLockTimeoutMillis);
            }
        }
    }

    void repositionChild(Session session, IWindow client,
            int left, int top, int right, int bottom,
            long frameNumber, Rect outFrame) {
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "repositionChild");
        long origId = Binder.clearCallingIdentity();

        try {
            synchronized(mWindowMap) {
                WindowState win = windowForClientLocked(session, client, false);
                if (win == null) {
                    return;
                }
                if (win.mAttachedWindow == null) {
                    throw new IllegalArgumentException(
                            "repositionChild called but window is not"
                            + "attached to a parent win=" + win);
                }

                win.mAttrs.x = left;
                win.mAttrs.y = top;
                win.mAttrs.width = right - left;
                win.mAttrs.height = bottom - top;
                win.setWindowScale(win.mRequestedWidth, win.mRequestedHeight);

                if (win.mHasSurface) {
                    if (SHOW_TRANSACTIONS) {
                        Slog.i(TAG_WM, ">>> OPEN TRANSACTION repositionChild");
                    }

                    SurfaceControl.openTransaction();

                    try {

                        win.applyGravityAndUpdateFrame(win.mContainingFrame, win.mDisplayFrame);
                        win.mWinAnimator.computeShownFrameLocked();

                        win.mWinAnimator.setSurfaceBoundariesLocked(false);

                        if (frameNumber > 0) {
                            win.mWinAnimator.deferTransactionUntilParentFrame(frameNumber);
                        }

                    } finally {
                        SurfaceControl.closeTransaction();
                        if (SHOW_TRANSACTIONS) {
                            Slog.i(TAG_WM, "<<< CLOSE TRANSACTION repositionChild");
                        }
                    }
                }

                outFrame = win.mCompatFrame;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
        }
    }

    public int relayoutWindow(Session session, IWindow client, int seq,
            WindowManager.LayoutParams attrs, int requestedWidth,
            int requestedHeight, int viewVisibility, int flags,
            Rect outFrame, Rect outOverscanInsets, Rect outContentInsets,
            Rect outVisibleInsets, Rect outStableInsets, Rect outOutsets, Rect outBackdropFrame,
            Configuration outConfig, Surface outSurface) {
        int result = 0;
        boolean configChanged;
        boolean hasStatusBarPermission =
                mContext.checkCallingOrSelfPermission(android.Manifest.permission.STATUS_BAR)
                        == PackageManager.PERMISSION_GRANTED;

        long origId = Binder.clearCallingIdentity();
        synchronized(mWindowMap) {
            WindowState win = windowForClientLocked(session, client, false);
            if (win == null) {
                return 0;
            }

            WindowStateAnimator winAnimator = win.mWinAnimator;
            if (viewVisibility != View.GONE) {
                win.setRequestedSize(requestedWidth, requestedHeight);
            }

            int attrChanges = 0;
            int flagChanges = 0;
            if (attrs != null) {
                mPolicy.adjustWindowParamsLw(attrs);
                // if they don't have the permission, mask out the status bar bits
                if (seq == win.mSeq) {
                    int systemUiVisibility = attrs.systemUiVisibility
                            | attrs.subtreeSystemUiVisibility;
                    if ((systemUiVisibility & DISABLE_MASK) != 0) {
                        if (!hasStatusBarPermission) {
                            systemUiVisibility &= ~DISABLE_MASK;
                        }
                    }
                    win.mSystemUiVisibility = systemUiVisibility;
                }
                if (win.mAttrs.type != attrs.type) {
                    throw new IllegalArgumentException(
                            "Window type can not be changed after the window is added.");
                }

                // Odd choice but less odd than embedding in copyFrom()
                if ((attrs.privateFlags & WindowManager.LayoutParams.PRIVATE_FLAG_PRESERVE_GEOMETRY)
                        != 0) {
                    attrs.x = win.mAttrs.x;
                    attrs.y = win.mAttrs.y;
                    attrs.width = win.mAttrs.width;
                    attrs.height = win.mAttrs.height;
                }

                flagChanges = win.mAttrs.flags ^= attrs.flags;
                attrChanges = win.mAttrs.copyFrom(attrs);
                if ((attrChanges & (WindowManager.LayoutParams.LAYOUT_CHANGED
                        | WindowManager.LayoutParams.SYSTEM_UI_VISIBILITY_CHANGED)) != 0) {
                    win.mLayoutNeeded = true;
                }
            }

            if (DEBUG_LAYOUT) Slog.v(TAG_WM, "Relayout " + win + ": viewVisibility=" + viewVisibility
                    + " req=" + requestedWidth + "x" + requestedHeight + " " + win.mAttrs);
            winAnimator.mSurfaceDestroyDeferred = (flags & RELAYOUT_DEFER_SURFACE_DESTROY) != 0;
            win.mEnforceSizeCompat =
                    (win.mAttrs.privateFlags & PRIVATE_FLAG_COMPATIBLE_WINDOW) != 0;
            if ((attrChanges & WindowManager.LayoutParams.ALPHA_CHANGED) != 0) {
                winAnimator.mAlpha = attrs.alpha;
            }
            win.setWindowScale(win.mRequestedWidth, win.mRequestedHeight);

            if (win.mAttrs.surfaceInsets.left != 0
                    || win.mAttrs.surfaceInsets.top != 0
                    || win.mAttrs.surfaceInsets.right != 0
                    || win.mAttrs.surfaceInsets.bottom != 0) {
                winAnimator.setOpaqueLocked(false);
            }

            boolean imMayMove = (flagChanges & (FLAG_ALT_FOCUSABLE_IM | FLAG_NOT_FOCUSABLE)) != 0;
            final boolean isDefaultDisplay = win.isDefaultDisplay();
            boolean focusMayChange = isDefaultDisplay && (win.mViewVisibility != viewVisibility
                    || ((flagChanges & FLAG_NOT_FOCUSABLE) != 0)
                    || (!win.mRelayoutCalled));

            boolean wallpaperMayMove = win.mViewVisibility != viewVisibility
                    && (win.mAttrs.flags & FLAG_SHOW_WALLPAPER) != 0;
            wallpaperMayMove |= (flagChanges & FLAG_SHOW_WALLPAPER) != 0;
            if ((flagChanges & FLAG_SECURE) != 0 && winAnimator.mSurfaceController != null) {
                winAnimator.mSurfaceController.setSecure(isSecureLocked(win));
            }

            win.mRelayoutCalled = true;
            win.mInRelayout = true;

            final int oldVisibility = win.mViewVisibility;
            win.mViewVisibility = viewVisibility;
            if (DEBUG_SCREEN_ON) {
                RuntimeException stack = new RuntimeException();
                stack.fillInStackTrace();
                Slog.i(TAG_WM, "Relayout " + win + ": oldVis=" + oldVisibility
                        + " newVis=" + viewVisibility, stack);
            }
            if (viewVisibility == View.VISIBLE &&
                    (win.mAppToken == null || !win.mAppToken.clientHidden)) {
                result = relayoutVisibleWindow(outConfig, result, win, winAnimator, attrChanges,
                        oldVisibility);
                try {
                    result = createSurfaceControl(outSurface, result, win, winAnimator);
                } catch (Exception e) {
                    mInputMonitor.updateInputWindowsLw(true /*force*/);

                    Slog.w(TAG_WM, "Exception thrown when creating surface for client "
                             + client + " (" + win.mAttrs.getTitle() + ")",
                             e);
                    Binder.restoreCallingIdentity(origId);
                    return 0;
                }
                if ((result & WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME) != 0) {
                    focusMayChange = isDefaultDisplay;
                }
                if (win.mAttrs.type == TYPE_INPUT_METHOD && mInputMethodWindow == null) {
                    mInputMethodWindow = win;
                    imMayMove = true;
                }
                win.adjustStartingWindowFlags();
            } else {
                winAnimator.mEnterAnimationPending = false;
                winAnimator.mEnteringAnimation = false;
                final boolean usingSavedSurfaceBeforeVisible =
                        oldVisibility != View.VISIBLE && win.isAnimatingWithSavedSurface();
                if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) {
                    if (winAnimator.hasSurface() && !win.mAnimatingExit
                            && usingSavedSurfaceBeforeVisible) {
                        Slog.d(TAG, "Ignoring layout to invisible when using saved surface " + win);
                    }
                }

                if (winAnimator.hasSurface() && !win.mAnimatingExit
                        && !usingSavedSurfaceBeforeVisible) {
                    if (DEBUG_VISIBILITY) Slog.i(TAG_WM, "Relayout invis " + win
                            + ": mAnimatingExit=" + win.mAnimatingExit);
                    // If we are not currently running the exit animation, we
                    // need to see about starting one.
                    // We don't want to animate visibility of windows which are pending
                    // replacement. In the case of activity relaunch child windows
                    // could request visibility changes as they are detached from the main
                    // application window during the tear down process. If we satisfied
                    // these visibility changes though, we would cause a visual glitch
                    // hiding the window before it's replacement was available.
                    // So we just do nothing on our side.
                    if (!win.mWillReplaceWindow) {
                        focusMayChange = tryStartExitingAnimation(
                                win, winAnimator, isDefaultDisplay, focusMayChange);
                    }
                    result |= RELAYOUT_RES_SURFACE_CHANGED;
                }

                outSurface.release();
                if (DEBUG_VISIBILITY) Slog.i(TAG_WM, "Releasing surface in: " + win);
            }

            if (focusMayChange) {
                //System.out.println("Focus may change: " + win.mAttrs.getTitle());
                if (updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                        false /*updateInputWindows*/)) {
                    imMayMove = false;
                }
                //System.out.println("Relayout " + win + ": focus=" + mCurrentFocus);
            }

            // updateFocusedWindowLocked() already assigned layers so we only need to
            // reassign them at this point if the IM window state gets shuffled
            boolean toBeDisplayed = (result & WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME) != 0;
            if (imMayMove && (moveInputMethodWindowsIfNeededLocked(false) || toBeDisplayed)) {
                // Little hack here -- we -should- be able to rely on the
                // function to return true if the IME has moved and needs
                // its layer recomputed.  However, if the IME was hidden
                // and isn't actually moved in the list, its layer may be
                // out of data so we make sure to recompute it.
                mLayersController.assignLayersLocked(win.getWindowList());
            }

            if (wallpaperMayMove) {
                getDefaultDisplayContentLocked().pendingLayoutChanges |=
                        WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
            }

            win.setDisplayLayoutNeeded();
            win.mGivenInsetsPending = (flags&WindowManagerGlobal.RELAYOUT_INSETS_PENDING) != 0;
            configChanged = updateOrientationFromAppTokensLocked(false);
            mWindowPlacerLocked.performSurfacePlacement();
            if (toBeDisplayed && win.mIsWallpaper) {
                DisplayInfo displayInfo = getDefaultDisplayInfoLocked();
                mWallpaperControllerLocked.updateWallpaperOffset(
                        win, displayInfo.logicalWidth, displayInfo.logicalHeight, false);
            }
            if (win.mAppToken != null) {
                win.mAppToken.updateReportedVisibilityLocked();
            }
            if (winAnimator.mReportSurfaceResized) {
                winAnimator.mReportSurfaceResized = false;
                result |= WindowManagerGlobal.RELAYOUT_RES_SURFACE_RESIZED;
            }
            if (mPolicy.isNavBarForcedShownLw(win)) {
                result |= WindowManagerGlobal.RELAYOUT_RES_CONSUME_ALWAYS_NAV_BAR;
            }
            if (!win.isGoneForLayoutLw()) {
                win.mResizedWhileGone = false;
            }
            outFrame.set(win.mCompatFrame);
            outOverscanInsets.set(win.mOverscanInsets);
            outContentInsets.set(win.mContentInsets);
            outVisibleInsets.set(win.mVisibleInsets);
            outStableInsets.set(win.mStableInsets);
            outOutsets.set(win.mOutsets);
            outBackdropFrame.set(win.getBackdropFrame(win.mFrame));
            if (localLOGV) Slog.v(
                TAG_WM, "Relayout given client " + client.asBinder()
                + ", requestedWidth=" + requestedWidth
                + ", requestedHeight=" + requestedHeight
                + ", viewVisibility=" + viewVisibility
                + "\nRelayout returning frame=" + outFrame
                + ", surface=" + outSurface);

            if (localLOGV || DEBUG_FOCUS) Slog.v(
                TAG_WM, "Relayout of " + win + ": focusMayChange=" + focusMayChange);

            result |= mInTouchMode ? WindowManagerGlobal.RELAYOUT_RES_IN_TOUCH_MODE : 0;

            mInputMonitor.updateInputWindowsLw(true /*force*/);

            if (DEBUG_LAYOUT) {
                Slog.v(TAG_WM, "Relayout complete " + win + ": outFrame=" + outFrame.toShortString());
            }
            win.mInRelayout = false;
        }

        if (configChanged) {
            sendNewConfiguration();
        }
        Binder.restoreCallingIdentity(origId);
        return result;
    }

    private boolean tryStartExitingAnimation(WindowState win, WindowStateAnimator winAnimator,
            boolean isDefaultDisplay, boolean focusMayChange) {
        // Try starting an animation; if there isn't one, we
        // can destroy the surface right away.
        int transit = WindowManagerPolicy.TRANSIT_EXIT;
        if (win.mAttrs.type == TYPE_APPLICATION_STARTING) {
            transit = WindowManagerPolicy.TRANSIT_PREVIEW_DONE;
        }
        if (win.isWinVisibleLw() && winAnimator.applyAnimationLocked(transit, false)) {
            focusMayChange = isDefaultDisplay;
            win.mAnimatingExit = true;
            win.mWinAnimator.mAnimating = true;
        } else if (win.mWinAnimator.isAnimationSet()) {
            // Currently in a hide animation... turn this into
            // an exit.
            win.mAnimatingExit = true;
            win.mWinAnimator.mAnimating = true;
        } else if (mWallpaperControllerLocked.isWallpaperTarget(win)) {
            // If the wallpaper is currently behind this
            // window, we need to change both of them inside
            // of a transaction to avoid artifacts.
            win.mAnimatingExit = true;
            win.mWinAnimator.mAnimating = true;
        } else {
            if (mInputMethodWindow == win) {
                mInputMethodWindow = null;
            }
            win.destroyOrSaveSurface();
        }
        //TODO (multidisplay): Magnification is supported only for the default
        if (mAccessibilityController != null
                && win.getDisplayId() == Display.DEFAULT_DISPLAY) {
            mAccessibilityController.onWindowTransitionLocked(win, transit);
        }
        return focusMayChange;
    }

    private int createSurfaceControl(Surface outSurface, int result, WindowState win,
            WindowStateAnimator winAnimator) {
        if (!win.mHasSurface) {
            result |= RELAYOUT_RES_SURFACE_CHANGED;
        }
        WindowSurfaceController surfaceController = winAnimator.createSurfaceLocked();
        if (surfaceController != null) {
            surfaceController.getSurface(outSurface);
            if (SHOW_TRANSACTIONS) Slog.i(TAG_WM, "  OUT SURFACE " + outSurface + ": copied");
        } else {
            // For some reason there isn't a surface.  Clear the
            // caller's object so they see the same state.
            outSurface.release();
        }
        return result;
    }

    private int relayoutVisibleWindow(Configuration outConfig, int result, WindowState win,
            WindowStateAnimator winAnimator, int attrChanges, int oldVisibility) {
        result |= !win.isVisibleLw() ? WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME : 0;
        if (win.mAnimatingExit) {
            Slog.d(TAG, "relayoutVisibleWindow: " + win + " mAnimatingExit=true, mRemoveOnExit="
                    + win.mRemoveOnExit + ", mDestroying=" + win.mDestroying);

            winAnimator.cancelExitAnimationForNextAnimationLocked();
            win.mAnimatingExit = false;
        }
        if (win.mDestroying) {
            win.mDestroying = false;
            mDestroySurface.remove(win);
        }
        if (oldVisibility == View.GONE) {
            winAnimator.mEnterAnimationPending = true;
        }
        winAnimator.mEnteringAnimation = true;
        if ((result & WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME) != 0) {
            win.prepareWindowToDisplayDuringRelayout(outConfig);
        }
        if ((attrChanges & LayoutParams.FORMAT_CHANGED) != 0) {
            // If the format can't be changed in place, preserve the old surface until the app draws
            // on the new one. This prevents blinking when we change elevation of freeform and
            // pinned windows.
            if (!winAnimator.tryChangeFormatInPlaceLocked()) {
                winAnimator.preserveSurfaceLocked();
                result |= RELAYOUT_RES_SURFACE_CHANGED
                        | WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME;
            }
        }

        // If we're starting a drag-resize, we'll be changing the surface size as well as
        // notifying the client to render to with an offset from the surface's top-left.
        if (win.isDragResizeChanged() || win.isResizedWhileNotDragResizing()) {
            win.setDragResizing();
            win.setResizedWhileNotDragResizing(false);
            // We can only change top level windows to the full-screen surface when
            // resizing (as we only have one full-screen surface). So there is no need
            // to preserve and destroy windows which are attached to another, they
            // will keep their surface and its size may change over time.
            if (win.mHasSurface && win.mAttachedWindow == null) {
                winAnimator.preserveSurfaceLocked();
                result |= WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME;
            }
        }
        final boolean freeformResizing = win.isDragResizing()
                && win.getResizeMode() == DRAG_RESIZE_MODE_FREEFORM;
        final boolean dockedResizing = win.isDragResizing()
                && win.getResizeMode() == DRAG_RESIZE_MODE_DOCKED_DIVIDER;
        result |= freeformResizing ? WindowManagerGlobal.RELAYOUT_RES_DRAG_RESIZING_FREEFORM : 0;
        result |= dockedResizing ? WindowManagerGlobal.RELAYOUT_RES_DRAG_RESIZING_DOCKED : 0;
        if (win.isAnimatingWithSavedSurface()) {
            // If we're animating with a saved surface now, request client to report draw.
            // We still need to know when the real thing is drawn.
            result |= WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME;
        }
        return result;
    }

    public void performDeferredDestroyWindow(Session session, IWindow client) {
        long origId = Binder.clearCallingIdentity();

        try {
            synchronized (mWindowMap) {
                WindowState win = windowForClientLocked(session, client, false);
                if (win == null || win.mWillReplaceWindow) {
                    return;
                }

                win.mWinAnimator.destroyDeferredSurfaceLocked();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public boolean outOfMemoryWindow(Session session, IWindow client) {
        long origId = Binder.clearCallingIdentity();

        try {
            synchronized (mWindowMap) {
                WindowState win = windowForClientLocked(session, client, false);
                if (win == null) {
                    return false;
                }
                return reclaimSomeSurfaceMemoryLocked(win.mWinAnimator, "from-client", false);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void finishDrawingWindow(Session session, IWindow client) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mWindowMap) {
                WindowState win = windowForClientLocked(session, client, false);
                if (DEBUG_ADD_REMOVE) Slog.d(TAG_WM, "finishDrawingWindow: " + win + " mDrawState="
                        + (win != null ? win.mWinAnimator.drawStateToString() : "null"));
                if (win != null && win.mWinAnimator.finishDrawingLocked()) {
                    if ((win.mAttrs.flags & FLAG_SHOW_WALLPAPER) != 0) {
                        getDefaultDisplayContentLocked().pendingLayoutChanges |=
                                WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
                    }
                    win.setDisplayLayoutNeeded();
                    mWindowPlacerLocked.requestTraversal();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private boolean applyAnimationLocked(AppWindowToken atoken, WindowManager.LayoutParams lp,
            int transit, boolean enter, boolean isVoiceInteraction) {
        // Only apply an animation if the display isn't frozen.  If it is
        // frozen, there is no reason to animate and it can cause strange
        // artifacts when we unfreeze the display if some different animation
        // is running.
        if (okToDisplay()) {
            DisplayInfo displayInfo = getDefaultDisplayInfoLocked();
            final int width = displayInfo.appWidth;
            final int height = displayInfo.appHeight;
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG_WM,
                    "applyAnimation: atoken=" + atoken);

            // Determine the visible rect to calculate the thumbnail clip
            final WindowState win = atoken.findMainWindow();
            final Rect frame = new Rect(0, 0, width, height);
            final Rect displayFrame = new Rect(0, 0,
                    displayInfo.logicalWidth, displayInfo.logicalHeight);
            final Rect insets = new Rect();
            Rect surfaceInsets = null;
            final boolean freeform = win != null && win.inFreeformWorkspace();
            if (win != null) {
                // Containing frame will usually cover the whole screen, including dialog windows.
                // For freeform workspace windows it will not cover the whole screen and it also
                // won't exactly match the final freeform window frame (e.g. when overlapping with
                // the status bar). In that case we need to use the final frame.
                if (freeform) {
                    frame.set(win.mFrame);
                } else {
                    frame.set(win.mContainingFrame);
                }
                surfaceInsets = win.getAttrs().surfaceInsets;
                insets.set(win.mContentInsets);
            }

            if (atoken.mLaunchTaskBehind) {
                // Differentiate the two animations. This one which is briefly on the screen
                // gets the !enter animation, and the other activity which remains on the
                // screen gets the enter animation. Both appear in the mOpeningApps set.
                enter = false;
            }
            if (DEBUG_APP_TRANSITIONS) Slog.d(TAG_WM, "Loading animation for app transition."
                    + " transit=" + AppTransition.appTransitionToString(transit) + " enter=" + enter
                    + " frame=" + frame + " insets=" + insets + " surfaceInsets=" + surfaceInsets);
            Animation a = mAppTransition.loadAnimation(lp, transit, enter, mCurConfiguration.uiMode,
                    mCurConfiguration.orientation, frame, displayFrame, insets, surfaceInsets,
                    isVoiceInteraction, freeform, atoken.mTask.mTaskId);
            if (a != null) {
                if (DEBUG_ANIM) logWithStack(TAG, "Loaded animation " + a + " for " + atoken);
                final int containingWidth = frame.width();
                final int containingHeight = frame.height();
                atoken.mAppAnimator.setAnimation(a, containingWidth, containingHeight,
                        mAppTransition.canSkipFirstFrame(), mAppTransition.getAppStackClipMode());
            }
        } else {
            atoken.mAppAnimator.clearAnimation();
        }

        return atoken.mAppAnimator.animation != null;
    }

    // -------------------------------------------------------------
    // Application Window Tokens
    // -------------------------------------------------------------

    public void validateAppTokens(int stackId, List<TaskGroup> tasks) {
        synchronized (mWindowMap) {
            int t = tasks.size() - 1;
            if (t < 0) {
                Slog.w(TAG_WM, "validateAppTokens: empty task list");
                return;
            }

            TaskGroup task = tasks.get(0);
            int taskId = task.taskId;
            Task targetTask = mTaskIdToTask.get(taskId);
            DisplayContent displayContent = targetTask.getDisplayContent();
            if (displayContent == null) {
                Slog.w(TAG_WM, "validateAppTokens: no Display for taskId=" + taskId);
                return;
            }

            final ArrayList<Task> localTasks = mStackIdToStack.get(stackId).getTasks();
            int taskNdx;
            for (taskNdx = localTasks.size() - 1; taskNdx >= 0 && t >= 0; --taskNdx, --t) {
                AppTokenList localTokens = localTasks.get(taskNdx).mAppTokens;
                task = tasks.get(t);
                List<IApplicationToken> tokens = task.tokens;

                DisplayContent lastDisplayContent = displayContent;
                displayContent = mTaskIdToTask.get(taskId).getDisplayContent();
                if (displayContent != lastDisplayContent) {
                    Slog.w(TAG_WM, "validateAppTokens: displayContent changed in TaskGroup list!");
                    return;
                }

                int tokenNdx;
                int v;
                for (tokenNdx = localTokens.size() - 1, v = task.tokens.size() - 1;
                        tokenNdx >= 0 && v >= 0; ) {
                    final AppWindowToken atoken = localTokens.get(tokenNdx);
                    if (atoken.removed) {
                        --tokenNdx;
                        continue;
                    }
                    if (tokens.get(v) != atoken.token) {
                        break;
                    }
                    --tokenNdx;
                    v--;
                }

                if (tokenNdx >= 0 || v >= 0) {
                    break;
                }
            }

            if (taskNdx >= 0 || t >= 0) {
                Slog.w(TAG_WM, "validateAppTokens: Mismatch! ActivityManager=" + tasks);
                Slog.w(TAG_WM, "validateAppTokens: Mismatch! WindowManager=" + localTasks);
                Slog.w(TAG_WM, "validateAppTokens: Mismatch! Callers=" + Debug.getCallers(4));
            }
        }
    }

    public void validateStackOrder(Integer[] remoteStackIds) {
        // TODO:
    }

    private boolean checkCallingPermission(String permission, String func) {
        // Quick check: if the calling permission is me, it's all okay.
        if (Binder.getCallingPid() == Process.myPid()) {
            return true;
        }

        if (mContext.checkCallingPermission(permission)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        String msg = "Permission Denial: " + func + " from pid="
                + Binder.getCallingPid()
                + ", uid=" + Binder.getCallingUid()
                + " requires " + permission;
        Slog.w(TAG_WM, msg);
        return false;
    }

    boolean okToDisplay() {
        return !mDisplayFrozen && mDisplayEnabled && mPolicy.isScreenOn();
    }

    AppWindowToken findAppWindowToken(IBinder token) {
        WindowToken wtoken = mTokenMap.get(token);
        if (wtoken == null) {
            return null;
        }
        return wtoken.appWindowToken;
    }

    @Override
    public void addWindowToken(IBinder token, int type) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "addWindowToken()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            WindowToken wtoken = mTokenMap.get(token);
            if (wtoken != null) {
                Slog.w(TAG_WM, "Attempted to add existing input method token: " + token);
                return;
            }
            wtoken = new WindowToken(this, token, type, true);
            mTokenMap.put(token, wtoken);
            if (type == TYPE_WALLPAPER) {
                mWallpaperControllerLocked.addWallpaperToken(wtoken);
            }
        }
    }

    @Override
    public void removeWindowToken(IBinder token) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "removeWindowToken()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        final long origId = Binder.clearCallingIdentity();
        synchronized(mWindowMap) {
            DisplayContent displayContent = null;
            WindowToken wtoken = mTokenMap.remove(token);
            if (wtoken != null) {
                boolean delayed = false;
                if (!wtoken.hidden) {
                    final int N = wtoken.windows.size();
                    boolean changed = false;

                    for (int i=0; i<N; i++) {
                        WindowState win = wtoken.windows.get(i);
                        displayContent = win.getDisplayContent();

                        if (win.mWinAnimator.isAnimationSet()) {
                            delayed = true;
                        }

                        if (win.isVisibleNow()) {
                            win.mWinAnimator.applyAnimationLocked(WindowManagerPolicy.TRANSIT_EXIT,
                                    false);
                            //TODO (multidisplay): Magnification is supported only for the default
                            if (mAccessibilityController != null && win.isDefaultDisplay()) {
                                mAccessibilityController.onWindowTransitionLocked(win,
                                        WindowManagerPolicy.TRANSIT_EXIT);
                            }
                            changed = true;
                            if (displayContent != null) {
                                displayContent.layoutNeeded = true;
                            }
                        }
                    }

                    wtoken.hidden = true;

                    if (changed) {
                        mWindowPlacerLocked.performSurfacePlacement();
                        updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL,
                                false /*updateInputWindows*/);
                    }

                    if (delayed && displayContent != null) {
                        displayContent.mExitingTokens.add(wtoken);
                    } else if (wtoken.windowType == TYPE_WALLPAPER) {
                        mWallpaperControllerLocked.removeWallpaperToken(wtoken);
                    }
                } else if (wtoken.windowType == TYPE_WALLPAPER) {
                    mWallpaperControllerLocked.removeWallpaperToken(wtoken);
                }

                mInputMonitor.updateInputWindowsLw(true /*force*/);
            } else {
                Slog.w(TAG_WM, "Attempted to remove non-existing token: " + token);
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    private Task createTaskLocked(int taskId, int stackId, int userId, AppWindowToken atoken,
            Rect bounds, Configuration config) {
        if (DEBUG_STACK) Slog.i(TAG_WM, "createTaskLocked: taskId=" + taskId + " stackId=" + stackId
                + " atoken=" + atoken + " bounds=" + bounds);
        final TaskStack stack = mStackIdToStack.get(stackId);
        if (stack == null) {
            throw new IllegalArgumentException("addAppToken: invalid stackId=" + stackId);
        }
        EventLog.writeEvent(EventLogTags.WM_TASK_CREATED, taskId, stackId);
        Task task = new Task(taskId, stack, userId, this, bounds, config);
        mTaskIdToTask.put(taskId, task);
        stack.addTask(task, !atoken.mLaunchTaskBehind /* toTop */, atoken.showForAllUsers);
        return task;
    }

    @Override
    public void addAppToken(int addPos, IApplicationToken token, int taskId, int stackId,
            int requestedOrientation, boolean fullscreen, boolean showForAllUsers, int userId,
            int configChanges, boolean voiceInteraction, boolean launchTaskBehind,
            Rect taskBounds, Configuration config, int taskResizeMode, boolean alwaysFocusable,
            boolean homeTask, int targetSdkVersion) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "addAppToken()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        // Get the dispatching timeout here while we are not holding any locks so that it
        // can be cached by the AppWindowToken.  The timeout value is used later by the
        // input dispatcher in code that does hold locks.  If we did not cache the value
        // here we would run the chance of introducing a deadlock between the window manager
        // (which holds locks while updating the input dispatcher state) and the activity manager
        // (which holds locks while querying the application token).
        long inputDispatchingTimeoutNanos;
        try {
            inputDispatchingTimeoutNanos = token.getKeyDispatchingTimeout() * 1000000L;
        } catch (RemoteException ex) {
            Slog.w(TAG_WM, "Could not get dispatching timeout.", ex);
            inputDispatchingTimeoutNanos = DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;
        }

        synchronized(mWindowMap) {
            AppWindowToken atoken = findAppWindowToken(token.asBinder());
            if (atoken != null) {
                Slog.w(TAG_WM, "Attempted to add existing app token: " + token);
                return;
            }
            atoken = new AppWindowToken(this, token, voiceInteraction);
            atoken.inputDispatchingTimeoutNanos = inputDispatchingTimeoutNanos;
            atoken.appFullscreen = fullscreen;
            atoken.showForAllUsers = showForAllUsers;
            atoken.targetSdk = targetSdkVersion;
            atoken.requestedOrientation = requestedOrientation;
            atoken.layoutConfigChanges = (configChanges &
                    (ActivityInfo.CONFIG_SCREEN_SIZE | ActivityInfo.CONFIG_ORIENTATION)) != 0;
            atoken.mLaunchTaskBehind = launchTaskBehind;
            atoken.mAlwaysFocusable = alwaysFocusable;
            if (DEBUG_TOKEN_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "addAppToken: " + atoken
                    + " to stack=" + stackId + " task=" + taskId + " at " + addPos);

            Task task = mTaskIdToTask.get(taskId);
            if (task == null) {
                task = createTaskLocked(taskId, stackId, userId, atoken, taskBounds, config);
            }
            task.addAppToken(addPos, atoken, taskResizeMode, homeTask);

            mTokenMap.put(token.asBinder(), atoken);

            // Application tokens start out hidden.
            atoken.hidden = true;
            atoken.hiddenRequested = true;
        }
    }

    @Override
    public void setAppTask(IBinder token, int taskId, int stackId, Rect taskBounds,
            Configuration config, int taskResizeMode, boolean homeTask) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppTask()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            final AppWindowToken atoken = findAppWindowToken(token);
            if (atoken == null) {
                Slog.w(TAG_WM, "Attempted to set task id of non-existing app token: " + token);
                return;
            }
            final Task oldTask = atoken.mTask;
            oldTask.removeAppToken(atoken);

            Task newTask = mTaskIdToTask.get(taskId);
            if (newTask == null) {
                newTask = createTaskLocked(
                        taskId, stackId, oldTask.mUserId, atoken, taskBounds, config);
            }
            newTask.addAppToken(Integer.MAX_VALUE /* at top */, atoken, taskResizeMode, homeTask);
        }
    }

    public int getOrientationLocked() {
        if (mDisplayFrozen) {
            if (mLastWindowForcedOrientation != SCREEN_ORIENTATION_UNSPECIFIED) {
                if (DEBUG_ORIENTATION) Slog.v(TAG_WM,
                        "Display is frozen, return " + mLastWindowForcedOrientation);
                // If the display is frozen, some activities may be in the middle
                // of restarting, and thus have removed their old window.  If the
                // window has the flag to hide the lock screen, then the lock screen
                // can re-appear and inflict its own orientation on us.  Keep the
                // orientation stable until this all settles down.
                return mLastWindowForcedOrientation;
            }
        } else {
            // TODO(multidisplay): Change to the correct display.
            final WindowList windows = getDefaultWindowListLocked();
            for (int pos = windows.size() - 1; pos >= 0; --pos) {
                WindowState win = windows.get(pos);
                if (win.mAppToken != null) {
                    // We hit an application window. so the orientation will be determined by the
                    // app window. No point in continuing further.
                    break;
                }
                if (!win.isVisibleLw() || !win.mPolicyVisibilityAfterAnim) {
                    continue;
                }
                int req = win.mAttrs.screenOrientation;
                if(req == SCREEN_ORIENTATION_UNSPECIFIED || req == SCREEN_ORIENTATION_BEHIND) {
                    continue;
                }

                if (DEBUG_ORIENTATION) Slog.v(TAG_WM, win + " forcing orientation to " + req);
                if (mPolicy.isKeyguardHostWindow(win.mAttrs)) {
                    mLastKeyguardForcedOrientation = req;
                }
                return (mLastWindowForcedOrientation = req);
            }
            mLastWindowForcedOrientation = SCREEN_ORIENTATION_UNSPECIFIED;

            if (mPolicy.isKeyguardLocked()) {
                // The screen is locked and no top system window is requesting an orientation.
                // Return either the orientation of the show-when-locked app (if there is any) or
                // the orientation of the keyguard. No point in searching from the rest of apps.
                WindowState winShowWhenLocked = (WindowState) mPolicy.getWinShowWhenLockedLw();
                AppWindowToken appShowWhenLocked = winShowWhenLocked == null ?
                        null : winShowWhenLocked.mAppToken;
                if (appShowWhenLocked != null) {
                    int req = appShowWhenLocked.requestedOrientation;
                    if (req == SCREEN_ORIENTATION_BEHIND) {
                        req = mLastKeyguardForcedOrientation;
                    }
                    if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Done at " + appShowWhenLocked
                            + " -- show when locked, return " + req);
                    return req;
                }
                if (DEBUG_ORIENTATION) Slog.v(TAG_WM,
                        "No one is requesting an orientation when the screen is locked");
                return mLastKeyguardForcedOrientation;
            }
        }

        // Top system windows are not requesting an orientation. Start searching from apps.
        return getAppSpecifiedOrientation();
    }

    private int getAppSpecifiedOrientation() {
        int lastOrientation = SCREEN_ORIENTATION_UNSPECIFIED;
        boolean findingBehind = false;
        boolean lastFullscreen = false;
        DisplayContent displayContent = getDefaultDisplayContentLocked();
        final ArrayList<Task> tasks = displayContent.getTasks();
        final boolean inMultiWindow = isStackVisibleLocked(DOCKED_STACK_ID)
                || isStackVisibleLocked(FREEFORM_WORKSPACE_STACK_ID);
        final boolean dockMinimized =
                getDefaultDisplayContentLocked().mDividerControllerLocked.isMinimizedDock();
        for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
            AppTokenList tokens = tasks.get(taskNdx).mAppTokens;
            final int firstToken = tokens.size() - 1;
            for (int tokenNdx = firstToken; tokenNdx >= 0; --tokenNdx) {
                final AppWindowToken atoken = tokens.get(tokenNdx);

                if (DEBUG_APP_ORIENTATION) Slog.v(TAG_WM, "Checking app orientation: " + atoken);

                // if we're about to tear down this window and not seek for
                // the behind activity, don't use it for orientation
                if (!findingBehind && !atoken.hidden && atoken.hiddenRequested) {
                    if (DEBUG_ORIENTATION) Slog.v(TAG_WM,
                            "Skipping " + atoken + " -- going to hide");
                    continue;
                }

                if (tokenNdx == firstToken) {
                    // If we have hit a new Task, and the bottom of the previous group didn't
                    // explicitly say to use the orientation behind it, and the last app was
                    // full screen, then we'll stick with the user's orientation.
                    if (lastOrientation != SCREEN_ORIENTATION_BEHIND && lastFullscreen) {
                        if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Done at " + atoken
                                + " -- end of group, return " + lastOrientation);
                        return lastOrientation;
                    }
                }

                // We ignore any hidden applications on the top.
                if (atoken.hiddenRequested) {
                    if (DEBUG_ORIENTATION) Slog.v(TAG_WM,
                            "Skipping " + atoken + " -- hidden on top");
                    continue;
                }

                // No app except the home app may specify the screen orientation in multi-window,
                // and only if the docked stack is minimized to avoid weirdness when home task
                // temporarily gets moved to the front.
                if (inMultiWindow && (!atoken.mTask.isHomeTask() || !dockMinimized)) {
                    continue;
                }

                if (tokenNdx == 0) {
                    // Last token in this task.
                    lastOrientation = atoken.requestedOrientation;
                }

                int or = atoken.requestedOrientation;
                // If this application is fullscreen, and didn't explicitly say
                // to use the orientation behind it, then just take whatever
                // orientation it has and ignores whatever is under it.
                lastFullscreen = atoken.appFullscreen;
                if (lastFullscreen && or != SCREEN_ORIENTATION_BEHIND) {
                    if (DEBUG_ORIENTATION) Slog.v(TAG_WM,
                            "Done at " + atoken + " -- full screen, return " + or);
                    return or;
                }
                // If this application has requested an explicit orientation, then use it.
                if (or != SCREEN_ORIENTATION_UNSPECIFIED && or != SCREEN_ORIENTATION_BEHIND) {
                    if (DEBUG_ORIENTATION) Slog.v(TAG_WM,
                            "Done at " + atoken + " -- explicitly set, return " + or);
                    return or;
                }
                findingBehind |= (or == SCREEN_ORIENTATION_BEHIND);
            }
        }
        if (DEBUG_ORIENTATION) Slog.v(TAG_WM,
                "No app is requesting an orientation, return " + mForcedAppOrientation);
        // The next app has not been requested to be visible, so we keep the current orientation
        // to prevent freezing/unfreezing the display too early unless we are in multi-window, in
        // which we don't let the app customize the orientation unless it was the home task that
        // is handled above.
        return inMultiWindow ? SCREEN_ORIENTATION_UNSPECIFIED : mForcedAppOrientation;
    }

    @Override
    public Configuration updateOrientationFromAppTokens(
            Configuration currentConfig, IBinder freezeThisOneIfNeeded) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "updateOrientationFromAppTokens()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        Configuration config = null;
        long ident = Binder.clearCallingIdentity();

        synchronized(mWindowMap) {
            config = updateOrientationFromAppTokensLocked(currentConfig,
                    freezeThisOneIfNeeded);
        }

        Binder.restoreCallingIdentity(ident);
        return config;
    }

    private Configuration updateOrientationFromAppTokensLocked(
            Configuration currentConfig, IBinder freezeThisOneIfNeeded) {
        if (!mDisplayReady) {
            return null;
        }
        Configuration config = null;

        if (updateOrientationFromAppTokensLocked(false)) {
            if (freezeThisOneIfNeeded != null) {
                AppWindowToken atoken = findAppWindowToken(freezeThisOneIfNeeded);
                if (atoken != null) {
                    startAppFreezingScreenLocked(atoken);
                }
            }
            config = computeNewConfigurationLocked();

        } else if (currentConfig != null) {
            // No obvious action we need to take, but if our current
            // state mismatches the activity manager's, update it,
            // disregarding font scale, which should remain set to
            // the value of the previous configuration.
            mTempConfiguration.setToDefaults();
            mTempConfiguration.updateFrom(currentConfig);
            computeScreenConfigurationLocked(mTempConfiguration);
            if (currentConfig.diff(mTempConfiguration) != 0) {
                mWaitingForConfig = true;
                final DisplayContent displayContent = getDefaultDisplayContentLocked();
                displayContent.layoutNeeded = true;
                int anim[] = new int[2];
                if (displayContent.isDimming()) {
                    anim[0] = anim[1] = 0;
                } else {
                    mPolicy.selectRotationAnimationLw(anim);
                }
                startFreezingDisplayLocked(false, anim[0], anim[1]);
                config = new Configuration(mTempConfiguration);
            }
        }

        return config;
    }

    /*
     * Determine the new desired orientation of the display, returning
     * a non-null new Configuration if it has changed from the current
     * orientation.  IF TRUE IS RETURNED SOMEONE MUST CALL
     * setNewConfiguration() TO TELL THE WINDOW MANAGER IT CAN UNFREEZE THE
     * SCREEN.  This will typically be done for you if you call
     * sendNewConfiguration().
     *
     * The orientation is computed from non-application windows first. If none of
     * the non-application windows specify orientation, the orientation is computed from
     * application tokens.
     * @see android.view.IWindowManager#updateOrientationFromAppTokens(
     * android.os.IBinder)
     */
    boolean updateOrientationFromAppTokensLocked(boolean inTransaction) {
        long ident = Binder.clearCallingIdentity();
        try {
            int req = getOrientationLocked();
            if (req != mForcedAppOrientation) {
                mForcedAppOrientation = req;
                //send a message to Policy indicating orientation change to take
                //action like disabling/enabling sensors etc.,
                mPolicy.setCurrentOrientationLw(req);
                if (updateRotationUncheckedLocked(inTransaction)) {
                    // changed
                    return true;
                }
            }

            return false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public int[] setNewConfiguration(Configuration config) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setNewConfiguration()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            if (mWaitingForConfig) {
                mWaitingForConfig = false;
                mLastFinishedFreezeSource = "new-config";
            }
            boolean configChanged = mCurConfiguration.diff(config) != 0;
            if (!configChanged) {
                return null;
            }
            prepareFreezingAllTaskBounds();
            mCurConfiguration = new Configuration(config);
            return onConfigurationChanged();
        }
    }

    @Override
    public Rect getBoundsForNewConfiguration(int stackId) {
        synchronized(mWindowMap) {
            final TaskStack stack = mStackIdToStack.get(stackId);
            final Rect outBounds = new Rect();
            stack.getBoundsForNewConfiguration(outBounds);
            return outBounds;
        }
    }

    private void prepareFreezingAllTaskBounds() {
        for (int i = mDisplayContents.size() - 1; i >= 0; i--) {
            ArrayList<TaskStack> stacks = mDisplayContents.valueAt(i).getStacks();
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final TaskStack stack = stacks.get(stackNdx);
                stack.prepareFreezingTaskBounds();
            }
        }

    }
    private int[] onConfigurationChanged() {
        mPolicy.onConfigurationChanged();

        final DisplayContent defaultDisplayContent = getDefaultDisplayContentLocked();
        if (!mReconfigureOnConfigurationChanged.contains(defaultDisplayContent)) {
            // The default display size information is heavily dependent on the resources in the
            // current configuration, so we need to reconfigure it everytime the configuration
            // changes. See {@link PhoneWindowManager#setInitialDisplaySize}...sigh...
            mReconfigureOnConfigurationChanged.add(defaultDisplayContent);
        }
        for (int i = mReconfigureOnConfigurationChanged.size() - 1; i >= 0; i--) {
            reconfigureDisplayLocked(mReconfigureOnConfigurationChanged.remove(i));
        }

        defaultDisplayContent.getDockedDividerController().onConfigurationChanged();
        mChangedStackList.clear();
        for (int stackNdx = mStackIdToStack.size() - 1; stackNdx >= 0; stackNdx--) {
            final TaskStack stack = mStackIdToStack.valueAt(stackNdx);
            if (stack.onConfigurationChanged()) {
                mChangedStackList.add(stack.mStackId);
            }
        }
        return mChangedStackList.isEmpty() ?
                null : ArrayUtils.convertToIntArray(mChangedStackList);
    }

    @Override
    public void setAppOrientation(IApplicationToken token, int requestedOrientation) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppOrientation()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            AppWindowToken atoken = findAppWindowToken(token.asBinder());
            if (atoken == null) {
                Slog.w(TAG_WM, "Attempted to set orientation of non-existing app token: " + token);
                return;
            }

            atoken.requestedOrientation = requestedOrientation;
        }
    }

    @Override
    public int getAppOrientation(IApplicationToken token) {
        synchronized(mWindowMap) {
            AppWindowToken wtoken = findAppWindowToken(token.asBinder());
            if (wtoken == null) {
                return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            }

            return wtoken.requestedOrientation;
        }
    }

    void setFocusTaskRegionLocked() {
        if (mFocusedApp != null) {
            final Task task = mFocusedApp.mTask;
            final DisplayContent displayContent = task.getDisplayContent();
            if (displayContent != null) {
                displayContent.setTouchExcludeRegion(task);
            }
        }
    }

    @Override
    public void setFocusedApp(IBinder token, boolean moveFocusNow) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setFocusedApp()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            final AppWindowToken newFocus;
            if (token == null) {
                if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM, "Clearing focused app, was " + mFocusedApp);
                newFocus = null;
            } else {
                newFocus = findAppWindowToken(token);
                if (newFocus == null) {
                    Slog.w(TAG_WM, "Attempted to set focus to non-existing app token: " + token);
                }
                if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM, "Set focused app to: " + newFocus
                        + " old focus=" + mFocusedApp + " moveFocusNow=" + moveFocusNow);
            }

            final boolean changed = mFocusedApp != newFocus;
            if (changed) {
                mFocusedApp = newFocus;
                mInputMonitor.setFocusedAppLw(newFocus);
                setFocusTaskRegionLocked();
            }

            if (moveFocusNow && changed) {
                final long origId = Binder.clearCallingIdentity();
                updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, true /*updateInputWindows*/);
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    /**
     * @param transit What kind of transition is happening. Use one of the constants
     *                AppTransition.TRANSIT_*.
     * @param alwaysKeepCurrent If true and a transition is already set, new transition will NOT
     *                          be set.
     */
    @Override
    public void prepareAppTransition(int transit, boolean alwaysKeepCurrent) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "prepareAppTransition()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }
        synchronized(mWindowMap) {
            boolean prepared = mAppTransition.prepareAppTransitionLocked(
                    transit, alwaysKeepCurrent);
            if (prepared && okToDisplay()) {
                mSkipAppTransitionAnimation = false;
            }
        }
    }

    @Override
    public int getPendingAppTransition() {
        return mAppTransition.getAppTransition();
    }

    @Override
    public void overridePendingAppTransition(String packageName,
            int enterAnim, int exitAnim, IRemoteCallback startedCallback) {
        synchronized(mWindowMap) {
            mAppTransition.overridePendingAppTransition(packageName, enterAnim, exitAnim,
                    startedCallback);
        }
    }

    @Override
    public void overridePendingAppTransitionScaleUp(int startX, int startY, int startWidth,
            int startHeight) {
        synchronized(mWindowMap) {
            mAppTransition.overridePendingAppTransitionScaleUp(startX, startY, startWidth,
                    startHeight);
        }
    }

    @Override
    public void overridePendingAppTransitionClipReveal(int startX, int startY,
            int startWidth, int startHeight) {
        synchronized(mWindowMap) {
            mAppTransition.overridePendingAppTransitionClipReveal(startX, startY, startWidth,
                    startHeight);
        }
    }

    @Override
    public void overridePendingAppTransitionThumb(Bitmap srcThumb, int startX,
            int startY, IRemoteCallback startedCallback, boolean scaleUp) {
        synchronized(mWindowMap) {
            mAppTransition.overridePendingAppTransitionThumb(srcThumb, startX, startY,
                    startedCallback, scaleUp);
        }
    }

    @Override
    public void overridePendingAppTransitionAspectScaledThumb(Bitmap srcThumb, int startX,
            int startY, int targetWidth, int targetHeight, IRemoteCallback startedCallback,
            boolean scaleUp) {
        synchronized(mWindowMap) {
            mAppTransition.overridePendingAppTransitionAspectScaledThumb(srcThumb, startX, startY,
                    targetWidth, targetHeight, startedCallback, scaleUp);
        }
    }

    @Override
    public void overridePendingAppTransitionMultiThumb(AppTransitionAnimationSpec[] specs,
            IRemoteCallback onAnimationStartedCallback, IRemoteCallback onAnimationFinishedCallback,
            boolean scaleUp) {
        synchronized (mWindowMap) {
            mAppTransition.overridePendingAppTransitionMultiThumb(specs, onAnimationStartedCallback,
                    onAnimationFinishedCallback, scaleUp);
            prolongAnimationsFromSpecs(specs, scaleUp);

        }
    }

    void prolongAnimationsFromSpecs(@NonNull AppTransitionAnimationSpec[] specs, boolean scaleUp) {
        // This is used by freeform <-> recents windows transition. We need to synchronize
        // the animation with the appearance of the content of recents, so we will make
        // animation stay on the first or last frame a little longer.
        mTmpTaskIds.clear();
        for (int i = specs.length - 1; i >= 0; i--) {
            mTmpTaskIds.put(specs[i].taskId, 0);
        }
        for (final WindowState win : mWindowMap.values()) {
            final Task task = win.getTask();
            if (task != null && mTmpTaskIds.get(task.mTaskId, -1) != -1
                    && task.inFreeformWorkspace()) {
                final AppWindowToken appToken = win.mAppToken;
                if (appToken != null && appToken.mAppAnimator != null) {
                    appToken.mAppAnimator.startProlongAnimation(scaleUp ?
                            PROLONG_ANIMATION_AT_START : PROLONG_ANIMATION_AT_END);
                }
            }
        }
    }

    @Override
    public void overridePendingAppTransitionInPlace(String packageName, int anim) {
        synchronized(mWindowMap) {
            mAppTransition.overrideInPlaceAppTransition(packageName, anim);
        }
    }

    @Override
    public void overridePendingAppTransitionMultiThumbFuture(
            IAppTransitionAnimationSpecsFuture specsFuture, IRemoteCallback callback,
            boolean scaleUp) {
        synchronized(mWindowMap) {
            mAppTransition.overridePendingAppTransitionMultiThumbFuture(specsFuture, callback,
                    scaleUp);
        }
    }

    @Override
    public void endProlongedAnimations() {
        synchronized (mWindowMap) {
            for (final WindowState win : mWindowMap.values()) {
                final AppWindowToken appToken = win.mAppToken;
                if (appToken != null && appToken.mAppAnimator != null) {
                    appToken.mAppAnimator.endProlongedAnimation();
                }
            }
            mAppTransition.notifyProlongedAnimationsEnded();
        }
    }

    @Override
    public void executeAppTransition() {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "executeAppTransition()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            if (DEBUG_APP_TRANSITIONS) Slog.w(TAG_WM, "Execute app transition: " + mAppTransition
                    + " Callers=" + Debug.getCallers(5));
            if (mAppTransition.isTransitionSet()) {
                mAppTransition.setReady();
                final long origId = Binder.clearCallingIdentity();
                try {
                    mWindowPlacerLocked.performSurfacePlacement();
                } finally {
                    Binder.restoreCallingIdentity(origId);
                }
            }
        }
    }

    @Override
    public boolean setAppStartingWindow(IBinder token, String pkg,
            int theme, CompatibilityInfo compatInfo,
            CharSequence nonLocalizedLabel, int labelRes, int icon, int logo,
            int windowFlags, IBinder transferFrom, boolean createIfNeeded) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppStartingWindow()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            if (DEBUG_STARTING_WINDOW) Slog.v(
                    TAG_WM, "setAppStartingWindow: token=" + token + " pkg=" + pkg
                    + " transferFrom=" + transferFrom);

            AppWindowToken wtoken = findAppWindowToken(token);
            if (wtoken == null) {
                Slog.w(TAG_WM, "Attempted to set icon of non-existing app token: " + token);
                return false;
            }

            // If the display is frozen, we won't do anything until the
            // actual window is displayed so there is no reason to put in
            // the starting window.
            if (!okToDisplay()) {
                return false;
            }

            if (wtoken.startingData != null) {
                return false;
            }

            // If this is a translucent window, then don't
            // show a starting window -- the current effect (a full-screen
            // opaque starting window that fades away to the real contents
            // when it is ready) does not work for this.
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Checking theme of starting window: 0x"
                    + Integer.toHexString(theme));
            if (theme != 0) {
                AttributeCache.Entry ent = AttributeCache.instance().get(pkg, theme,
                        com.android.internal.R.styleable.Window, mCurrentUserId);
                if (ent == null) {
                    // Whoops!  App doesn't exist.  Um.  Okay.  We'll just
                    // pretend like we didn't see that.
                    return false;
                }
                final boolean windowIsTranslucent = ent.array.getBoolean(
                        com.android.internal.R.styleable.Window_windowIsTranslucent, false);
                final boolean windowIsFloating = ent.array.getBoolean(
                        com.android.internal.R.styleable.Window_windowIsFloating, false);
                final boolean windowShowWallpaper = ent.array.getBoolean(
                        com.android.internal.R.styleable.Window_windowShowWallpaper, false);
                final boolean windowDisableStarting = ent.array.getBoolean(
                        com.android.internal.R.styleable.Window_windowDisablePreview, false);
                if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Translucent=" + windowIsTranslucent
                        + " Floating=" + windowIsFloating
                        + " ShowWallpaper=" + windowShowWallpaper);
                if (windowIsTranslucent) {
                    return false;
                }
                if (windowIsFloating || windowDisableStarting) {
                    return false;
                }
                if (windowShowWallpaper) {
                    if (mWallpaperControllerLocked.getWallpaperTarget() == null) {
                        // If this theme is requesting a wallpaper, and the wallpaper
                        // is not currently visible, then this effectively serves as
                        // an opaque window and our starting window transition animation
                        // can still work.  We just need to make sure the starting window
                        // is also showing the wallpaper.
                        windowFlags |= FLAG_SHOW_WALLPAPER;
                    } else {
                        return false;
                    }
                }
            }

            if (transferStartingWindow(transferFrom, wtoken)) {
                return true;
            }

            // There is no existing starting window, and the caller doesn't
            // want us to create one, so that's it!
            if (!createIfNeeded) {
                return false;
            }

            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Creating StartingData");
            wtoken.startingData = new StartingData(pkg, theme, compatInfo, nonLocalizedLabel,
                    labelRes, icon, logo, windowFlags);
            Message m = mH.obtainMessage(H.ADD_STARTING, wtoken);
            // Note: we really want to do sendMessageAtFrontOfQueue() because we
            // want to process the message ASAP, before any other queued
            // messages.
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Enqueueing ADD_STARTING");
            mH.sendMessageAtFrontOfQueue(m);
        }
        return true;
    }

    private boolean transferStartingWindow(IBinder transferFrom, AppWindowToken wtoken) {
        if (transferFrom == null) {
            return false;
        }
        AppWindowToken ttoken = findAppWindowToken(transferFrom);
        if (ttoken == null) {
            return false;
        }
        WindowState startingWindow = ttoken.startingWindow;
        if (startingWindow != null && ttoken.startingView != null) {
            // In this case, the starting icon has already been displayed, so start
            // letting windows get shown immediately without any more transitions.
            mSkipAppTransitionAnimation = true;

            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM,
                    "Moving existing starting " + startingWindow + " from " + ttoken
                            + " to " + wtoken);
            final long origId = Binder.clearCallingIdentity();

            // Transfer the starting window over to the new token.
            wtoken.startingData = ttoken.startingData;
            wtoken.startingView = ttoken.startingView;
            wtoken.startingDisplayed = ttoken.startingDisplayed;
            ttoken.startingDisplayed = false;
            wtoken.startingWindow = startingWindow;
            wtoken.reportedVisible = ttoken.reportedVisible;
            ttoken.startingData = null;
            ttoken.startingView = null;
            ttoken.startingWindow = null;
            ttoken.startingMoved = true;
            startingWindow.mToken = wtoken;
            startingWindow.mRootToken = wtoken;
            startingWindow.mAppToken = wtoken;

            if (DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE || DEBUG_STARTING_WINDOW) {
                Slog.v(TAG_WM, "Removing starting window: " + startingWindow);
            }
            startingWindow.getWindowList().remove(startingWindow);
            mWindowsChanged = true;
            if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                    "Removing starting " + startingWindow + " from " + ttoken);
            ttoken.windows.remove(startingWindow);
            ttoken.allAppWindows.remove(startingWindow);
            addWindowToListInOrderLocked(startingWindow, true);

            // Propagate other interesting state between the
            // tokens.  If the old token is displayed, we should
            // immediately force the new one to be displayed.  If
            // it is animating, we need to move that animation to
            // the new one.
            if (ttoken.allDrawn) {
                wtoken.allDrawn = true;
                wtoken.deferClearAllDrawn = ttoken.deferClearAllDrawn;
            }
            if (ttoken.firstWindowDrawn) {
                wtoken.firstWindowDrawn = true;
            }
            if (!ttoken.hidden) {
                wtoken.hidden = false;
                wtoken.hiddenRequested = false;
            }
            if (wtoken.clientHidden != ttoken.clientHidden) {
                wtoken.clientHidden = ttoken.clientHidden;
                wtoken.sendAppVisibilityToClients();
            }
            ttoken.mAppAnimator.transferCurrentAnimation(
                    wtoken.mAppAnimator, startingWindow.mWinAnimator);

            updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                    true /*updateInputWindows*/);
            getDefaultDisplayContentLocked().layoutNeeded = true;
            mWindowPlacerLocked.performSurfacePlacement();
            Binder.restoreCallingIdentity(origId);
            return true;
        } else if (ttoken.startingData != null) {
            // The previous app was getting ready to show a
            // starting window, but hasn't yet done so.  Steal it!
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Moving pending starting from " + ttoken
                    + " to " + wtoken);
            wtoken.startingData = ttoken.startingData;
            ttoken.startingData = null;
            ttoken.startingMoved = true;
            Message m = mH.obtainMessage(H.ADD_STARTING, wtoken);
            // Note: we really want to do sendMessageAtFrontOfQueue() because we
            // want to process the message ASAP, before any other queued
            // messages.
            mH.sendMessageAtFrontOfQueue(m);
            return true;
        }
        final AppWindowAnimator tAppAnimator = ttoken.mAppAnimator;
        final AppWindowAnimator wAppAnimator = wtoken.mAppAnimator;
        if (tAppAnimator.thumbnail != null) {
            // The old token is animating with a thumbnail, transfer that to the new token.
            if (wAppAnimator.thumbnail != null) {
                wAppAnimator.thumbnail.destroy();
            }
            wAppAnimator.thumbnail = tAppAnimator.thumbnail;
            wAppAnimator.thumbnailLayer = tAppAnimator.thumbnailLayer;
            wAppAnimator.thumbnailAnimation = tAppAnimator.thumbnailAnimation;
            tAppAnimator.thumbnail = null;
        }
        return false;
    }

    public void removeAppStartingWindow(IBinder token) {
        synchronized (mWindowMap) {
            final AppWindowToken wtoken = mTokenMap.get(token).appWindowToken;
            scheduleRemoveStartingWindowLocked(wtoken);
        }
    }

    public void setAppFullscreen(IBinder token, boolean toOpaque) {
        synchronized (mWindowMap) {
            AppWindowToken atoken = findAppWindowToken(token);
            if (atoken != null) {
                atoken.appFullscreen = toOpaque;
                setWindowOpaqueLocked(token, toOpaque);
                mWindowPlacerLocked.requestTraversal();
            }
        }
    }

    public void setWindowOpaque(IBinder token, boolean isOpaque) {
        synchronized (mWindowMap) {
            setWindowOpaqueLocked(token, isOpaque);
        }
    }

    public void setWindowOpaqueLocked(IBinder token, boolean isOpaque) {
        AppWindowToken wtoken = findAppWindowToken(token);
        if (wtoken != null) {
            WindowState win = wtoken.findMainWindow();
            if (win != null) {
                win.mWinAnimator.setOpaqueLocked(isOpaque);
            }
        }
    }

    boolean setTokenVisibilityLocked(AppWindowToken wtoken, WindowManager.LayoutParams lp,
            boolean visible, int transit, boolean performLayout, boolean isVoiceInteraction) {
        boolean delayed = false;

        if (wtoken.clientHidden == visible) {
            wtoken.clientHidden = !visible;
            wtoken.sendAppVisibilityToClients();
        }

        // Allow for state changes and animation to be applied if:
        // * token is transitioning visibility state
        // * or the token was marked as hidden and is exiting before we had a chance to play the
        // transition animation
        // * or this is an opening app and windows are being replaced.
        boolean visibilityChanged = false;
        if (wtoken.hidden == visible || (wtoken.hidden && wtoken.mIsExiting) ||
                (visible && wtoken.waitingForReplacement())) {
            boolean changed = false;
            if (DEBUG_APP_TRANSITIONS) Slog.v(
                TAG_WM, "Changing app " + wtoken + " hidden=" + wtoken.hidden
                + " performLayout=" + performLayout);

            boolean runningAppAnimation = false;

            if (transit != AppTransition.TRANSIT_UNSET) {
                if (wtoken.mAppAnimator.animation == AppWindowAnimator.sDummyAnimation) {
                    wtoken.mAppAnimator.setNullAnimation();
                }
                if (applyAnimationLocked(wtoken, lp, transit, visible, isVoiceInteraction)) {
                    delayed = runningAppAnimation = true;
                }
                WindowState window = wtoken.findMainWindow();
                //TODO (multidisplay): Magnification is supported only for the default display.
                if (window != null && mAccessibilityController != null
                        && window.getDisplayId() == Display.DEFAULT_DISPLAY) {
                    mAccessibilityController.onAppWindowTransitionLocked(window, transit);
                }
                changed = true;
            }

            final int windowsCount = wtoken.allAppWindows.size();
            for (int i = 0; i < windowsCount; i++) {
                WindowState win = wtoken.allAppWindows.get(i);
                if (win == wtoken.startingWindow) {
                    // Starting window that's exiting will be removed when the animation
                    // finishes. Mark all relevant flags for that finishExit will proceed
                    // all the way to actually remove it.
                    if (!visible && win.isVisibleNow() && wtoken.mAppAnimator.isAnimating()) {
                        win.mAnimatingExit = true;
                        win.mRemoveOnExit = true;
                        win.mWindowRemovalAllowed = true;
                    }
                    continue;
                }

                //Slog.i(TAG_WM, "Window " + win + ": vis=" + win.isVisible());
                //win.dump("  ");
                if (visible) {
                    if (!win.isVisibleNow()) {
                        if (!runningAppAnimation) {
                            win.mWinAnimator.applyAnimationLocked(
                                    WindowManagerPolicy.TRANSIT_ENTER, true);
                            //TODO (multidisplay): Magnification is supported only for the default
                            if (mAccessibilityController != null
                                    && win.getDisplayId() == Display.DEFAULT_DISPLAY) {
                                mAccessibilityController.onWindowTransitionLocked(win,
                                        WindowManagerPolicy.TRANSIT_ENTER);
                            }
                        }
                        changed = true;
                        win.setDisplayLayoutNeeded();
                    }
                } else if (win.isVisibleNow()) {
                    if (!runningAppAnimation) {
                        win.mWinAnimator.applyAnimationLocked(
                                WindowManagerPolicy.TRANSIT_EXIT, false);
                        //TODO (multidisplay): Magnification is supported only for the default
                        if (mAccessibilityController != null
                                && win.getDisplayId() == Display.DEFAULT_DISPLAY) {
                            mAccessibilityController.onWindowTransitionLocked(win,
                                    WindowManagerPolicy.TRANSIT_EXIT);
                        }
                    }
                    changed = true;
                    win.setDisplayLayoutNeeded();
                }
            }

            wtoken.hidden = wtoken.hiddenRequested = !visible;
            visibilityChanged = true;
            if (!visible) {
                unsetAppFreezingScreenLocked(wtoken, true, true);
            } else {
                // If we are being set visible, and the starting window is
                // not yet displayed, then make sure it doesn't get displayed.
                WindowState swin = wtoken.startingWindow;
                if (swin != null && !swin.isDrawnLw()) {
                    swin.mPolicyVisibility = false;
                    swin.mPolicyVisibilityAfterAnim = false;
                 }
            }

            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG_WM, "setTokenVisibilityLocked: " + wtoken
                      + ": hidden=" + wtoken.hidden + " hiddenRequested="
                      + wtoken.hiddenRequested);

            if (changed) {
                mInputMonitor.setUpdateInputWindowsNeededLw();
                if (performLayout) {
                    updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                            false /*updateInputWindows*/);
                    mWindowPlacerLocked.performSurfacePlacement();
                }
                mInputMonitor.updateInputWindowsLw(false /*force*/);
            }
        }

        if (wtoken.mAppAnimator.animation != null) {
            delayed = true;
        }

        for (int i = wtoken.allAppWindows.size() - 1; i >= 0 && !delayed; i--) {
            if (wtoken.allAppWindows.get(i).mWinAnimator.isWindowAnimationSet()) {
                delayed = true;
            }
        }

        if (visibilityChanged) {
            if (visible && !delayed) {
                // The token was made immediately visible, there will be no entrance animation.
                // We need to inform the client the enter animation was finished.
                wtoken.mEnteringAnimation = true;
                mActivityManagerAppTransitionNotifier.onAppTransitionFinishedLocked(wtoken.token);
            }

            if (!mClosingApps.contains(wtoken) && !mOpeningApps.contains(wtoken)) {
                // The token is not closing nor opening, so even if there is an animation set, that
                // doesn't mean that it goes through the normal app transition cycle so we have
                // to inform the docked controller about visibility change.
                getDefaultDisplayContentLocked().getDockedDividerController()
                        .notifyAppVisibilityChanged();
            }
        }

        return delayed;
    }

    void updateTokenInPlaceLocked(AppWindowToken wtoken, int transit) {
        if (transit != AppTransition.TRANSIT_UNSET) {
            if (wtoken.mAppAnimator.animation == AppWindowAnimator.sDummyAnimation) {
                wtoken.mAppAnimator.setNullAnimation();
            }
            applyAnimationLocked(wtoken, null, transit, false, false);
        }
    }

    @Override
    public void notifyAppStopped(IBinder token, boolean stopped) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "notifyAppStopped()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            final AppWindowToken wtoken;
            wtoken = findAppWindowToken(token);
            if (wtoken == null) {
                Slog.w(TAG_WM, "Attempted to set visibility of non-existing app token: " + token);
                return;
            }
            wtoken.notifyAppStopped(stopped);
        }
    }

    @Override
    public void setAppVisibility(IBinder token, boolean visible) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppVisibility()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        AppWindowToken wtoken;

        synchronized(mWindowMap) {
            wtoken = findAppWindowToken(token);
            if (wtoken == null) {
                Slog.w(TAG_WM, "Attempted to set visibility of non-existing app token: " + token);
                return;
            }

            if (DEBUG_APP_TRANSITIONS || DEBUG_ORIENTATION) Slog.v(TAG_WM, "setAppVisibility(" +
                    token + ", visible=" + visible + "): " + mAppTransition +
                    " hidden=" + wtoken.hidden + " hiddenRequested=" +
                    wtoken.hiddenRequested + " Callers=" + Debug.getCallers(6));

            mOpeningApps.remove(wtoken);
            mClosingApps.remove(wtoken);
            wtoken.waitingToShow = false;
            wtoken.hiddenRequested = !visible;

            if (!visible) {
                // If the app is dead while it was visible, we kept its dead window on screen.
                // Now that the app is going invisible, we can remove it. It will be restarted
                // if made visible again.
                wtoken.removeAllDeadWindows();
                wtoken.setVisibleBeforeClientHidden();
            } else if (visible) {
                if (!mAppTransition.isTransitionSet() && mAppTransition.isReady()) {
                    // Add the app mOpeningApps if transition is unset but ready. This means
                    // we're doing a screen freeze, and the unfreeze will wait for all opening
                    // apps to be ready.
                    mOpeningApps.add(wtoken);
                }
                wtoken.startingMoved = false;
                // If the token is currently hidden (should be the common case), or has been
                // stopped, then we need to set up to wait for its windows to be ready.
                if (wtoken.hidden || wtoken.mAppStopped) {
                    wtoken.clearAllDrawn();

                    // If the app was already visible, don't reset the waitingToShow state.
                    if (wtoken.hidden) {
                        wtoken.waitingToShow = true;
                    }

                    if (wtoken.clientHidden) {
                        // In the case where we are making an app visible
                        // but holding off for a transition, we still need
                        // to tell the client to make its windows visible so
                        // they get drawn.  Otherwise, we will wait on
                        // performing the transition until all windows have
                        // been drawn, they never will be, and we are sad.
                        wtoken.clientHidden = false;
                        wtoken.sendAppVisibilityToClients();
                    }
                }
                wtoken.requestUpdateWallpaperIfNeeded();

                if (DEBUG_ADD_REMOVE) Slog.v(
                        TAG_WM, "No longer Stopped: " + wtoken);
                wtoken.mAppStopped = false;
            }

            // If we are preparing an app transition, then delay changing
            // the visibility of this token until we execute that transition.
            if (okToDisplay() && mAppTransition.isTransitionSet()) {
                // A dummy animation is a placeholder animation which informs others that an
                // animation is going on (in this case an application transition). If the animation
                // was transferred from another application/animator, no dummy animator should be
                // created since an animation is already in progress.
                if (wtoken.mAppAnimator.usingTransferredAnimation
                        && wtoken.mAppAnimator.animation == null) {
                    Slog.wtf(TAG_WM, "Will NOT set dummy animation on: " + wtoken
                            + ", using null transfered animation!");
                }
                if (!wtoken.mAppAnimator.usingTransferredAnimation &&
                        (!wtoken.startingDisplayed || mSkipAppTransitionAnimation)) {
                    if (DEBUG_APP_TRANSITIONS) Slog.v(
                            TAG_WM, "Setting dummy animation on: " + wtoken);
                    wtoken.mAppAnimator.setDummyAnimation();
                }
                wtoken.inPendingTransaction = true;
                if (visible) {
                    mOpeningApps.add(wtoken);
                    wtoken.mEnteringAnimation = true;
                } else {
                    mClosingApps.add(wtoken);
                    wtoken.mEnteringAnimation = false;
                }
                if (mAppTransition.getAppTransition() == AppTransition.TRANSIT_TASK_OPEN_BEHIND) {
                    // We're launchingBehind, add the launching activity to mOpeningApps.
                    final WindowState win =
                            findFocusedWindowLocked(getDefaultDisplayContentLocked());
                    if (win != null) {
                        final AppWindowToken focusedToken = win.mAppToken;
                        if (focusedToken != null) {
                            if (DEBUG_APP_TRANSITIONS) Slog.d(TAG_WM, "TRANSIT_TASK_OPEN_BEHIND, " +
                                    " adding " + focusedToken + " to mOpeningApps");
                            // Force animation to be loaded.
                            focusedToken.hidden = true;
                            mOpeningApps.add(focusedToken);
                        }
                    }
                }
                return;
            }

            final long origId = Binder.clearCallingIdentity();
            wtoken.inPendingTransaction = false;
            setTokenVisibilityLocked(wtoken, null, visible, AppTransition.TRANSIT_UNSET,
                    true, wtoken.voiceInteraction);
            wtoken.updateReportedVisibilityLocked();
            Binder.restoreCallingIdentity(origId);
        }
    }

    void unsetAppFreezingScreenLocked(AppWindowToken wtoken,
            boolean unfreezeSurfaceNow, boolean force) {
        if (wtoken.mAppAnimator.freezingScreen) {
            if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Clear freezing of " + wtoken
                    + " force=" + force);
            final int N = wtoken.allAppWindows.size();
            boolean unfrozeWindows = false;
            for (int i=0; i<N; i++) {
                WindowState w = wtoken.allAppWindows.get(i);
                if (w.mAppFreezing) {
                    w.mAppFreezing = false;
                    if (w.mHasSurface && !w.mOrientationChanging
                            && mWindowsFreezingScreen != WINDOWS_FREEZING_SCREENS_TIMEOUT) {
                        if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "set mOrientationChanging of " + w);
                        w.mOrientationChanging = true;
                        mWindowPlacerLocked.mOrientationChangeComplete = false;
                    }
                    w.mLastFreezeDuration = 0;
                    unfrozeWindows = true;
                    w.setDisplayLayoutNeeded();
                }
            }
            if (force || unfrozeWindows) {
                if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "No longer freezing: " + wtoken);
                wtoken.mAppAnimator.freezingScreen = false;
                wtoken.mAppAnimator.lastFreezeDuration = (int)(SystemClock.elapsedRealtime()
                        - mDisplayFreezeTime);
                mAppsFreezingScreen--;
                mLastFinishedFreezeSource = wtoken;
            }
            if (unfreezeSurfaceNow) {
                if (unfrozeWindows) {
                    mWindowPlacerLocked.performSurfacePlacement();
                }
                stopFreezingDisplayLocked();
            }
        }
    }

    private void startAppFreezingScreenLocked(AppWindowToken wtoken) {
        if (DEBUG_ORIENTATION) logWithStack(TAG, "Set freezing of " + wtoken.appToken + ": hidden="
                + wtoken.hidden + " freezing=" + wtoken.mAppAnimator.freezingScreen);
        if (!wtoken.hiddenRequested) {
            if (!wtoken.mAppAnimator.freezingScreen) {
                wtoken.mAppAnimator.freezingScreen = true;
                wtoken.mAppAnimator.lastFreezeDuration = 0;
                mAppsFreezingScreen++;
                if (mAppsFreezingScreen == 1) {
                    startFreezingDisplayLocked(false, 0, 0);
                    mH.removeMessages(H.APP_FREEZE_TIMEOUT);
                    mH.sendEmptyMessageDelayed(H.APP_FREEZE_TIMEOUT, 2000);
                }
            }
            final int N = wtoken.allAppWindows.size();
            for (int i=0; i<N; i++) {
                WindowState w = wtoken.allAppWindows.get(i);
                w.mAppFreezing = true;
            }
        }
    }

    @Override
    public void startAppFreezingScreen(IBinder token, int configChanges) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppFreezingScreen()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            if (configChanges == 0 && okToDisplay()) {
                if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Skipping set freeze of " + token);
                return;
            }

            AppWindowToken wtoken = findAppWindowToken(token);
            if (wtoken == null || wtoken.appToken == null) {
                Slog.w(TAG_WM, "Attempted to freeze screen with non-existing app token: " + wtoken);
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            startAppFreezingScreenLocked(wtoken);
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void stopAppFreezingScreen(IBinder token, boolean force) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppFreezingScreen()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            AppWindowToken wtoken = findAppWindowToken(token);
            if (wtoken == null || wtoken.appToken == null) {
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Clear freezing of " + token
                    + ": hidden=" + wtoken.hidden + " freezing=" + wtoken.mAppAnimator.freezingScreen);
            unsetAppFreezingScreenLocked(wtoken, true, force);
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void removeAppToken(IBinder token) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "removeAppToken()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        AppWindowToken wtoken = null;
        AppWindowToken startingToken = null;
        boolean delayed = false;

        final long origId = Binder.clearCallingIdentity();
        synchronized(mWindowMap) {
            WindowToken basewtoken = mTokenMap.remove(token);
            if (basewtoken != null && (wtoken=basewtoken.appWindowToken) != null) {
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG_WM, "Removing app token: " + wtoken);
                delayed = setTokenVisibilityLocked(wtoken, null, false,
                        AppTransition.TRANSIT_UNSET, true, wtoken.voiceInteraction);
                wtoken.inPendingTransaction = false;
                mOpeningApps.remove(wtoken);
                wtoken.waitingToShow = false;
                if (mClosingApps.contains(wtoken)) {
                    delayed = true;
                } else if (mAppTransition.isTransitionSet()) {
                    mClosingApps.add(wtoken);
                    delayed = true;
                }
                if (DEBUG_APP_TRANSITIONS) Slog.v(
                        TAG_WM, "Removing app " + wtoken + " delayed=" + delayed
                        + " animation=" + wtoken.mAppAnimator.animation
                        + " animating=" + wtoken.mAppAnimator.animating);
                if (DEBUG_ADD_REMOVE || DEBUG_TOKEN_MOVEMENT) Slog.v(TAG_WM, "removeAppToken: "
                        + wtoken + " delayed=" + delayed + " Callers=" + Debug.getCallers(4));
                final TaskStack stack = wtoken.mTask.mStack;
                if (delayed && !wtoken.allAppWindows.isEmpty()) {
                    // set the token aside because it has an active animation to be finished
                    if (DEBUG_ADD_REMOVE || DEBUG_TOKEN_MOVEMENT) Slog.v(TAG_WM,
                            "removeAppToken make exiting: " + wtoken);
                    stack.mExitingAppTokens.add(wtoken);
                    wtoken.mIsExiting = true;
                } else {
                    // Make sure there is no animation running on this token,
                    // so any windows associated with it will be removed as
                    // soon as their animations are complete
                    wtoken.mAppAnimator.clearAnimation();
                    wtoken.mAppAnimator.animating = false;
                    wtoken.removeAppFromTaskLocked();
                }

                wtoken.removed = true;
                if (wtoken.startingData != null) {
                    startingToken = wtoken;
                }
                unsetAppFreezingScreenLocked(wtoken, true, true);
                if (mFocusedApp == wtoken) {
                    if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM, "Removing focused app token:" + wtoken);
                    mFocusedApp = null;
                    updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, true /*updateInputWindows*/);
                    mInputMonitor.setFocusedAppLw(null);
                }
            } else {
                Slog.w(TAG_WM, "Attempted to remove non-existing app token: " + token);
            }

            if (!delayed && wtoken != null) {
                wtoken.updateReportedVisibilityLocked();
            }

            // Will only remove if startingToken non null.
            scheduleRemoveStartingWindowLocked(startingToken);
        }
        Binder.restoreCallingIdentity(origId);

    }

    void scheduleRemoveStartingWindowLocked(AppWindowToken wtoken) {
        if (wtoken == null) {
            return;
        }
        if (mH.hasMessages(H.REMOVE_STARTING, wtoken)) {
            // Already scheduled.
            return;
        }

        if (wtoken.startingWindow == null) {
            if (wtoken.startingData != null) {
                // Starting window has not been added yet, but it is scheduled to be added.
                // Go ahead and cancel the request.
                if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM,
                        "Clearing startingData for token=" + wtoken);
                wtoken.startingData = null;
            }
            return;
        }

        if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, Debug.getCallers(1) +
                ": Schedule remove starting " + wtoken + (wtoken != null ?
                " startingWindow=" + wtoken.startingWindow : ""));
        Message m = mH.obtainMessage(H.REMOVE_STARTING, wtoken);
        mH.sendMessage(m);
    }

    void dumpAppTokensLocked() {
        final int numStacks = mStackIdToStack.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final TaskStack stack = mStackIdToStack.valueAt(stackNdx);
            Slog.v(TAG_WM, "  Stack #" + stack.mStackId + " tasks from bottom to top:");
            final ArrayList<Task> tasks = stack.getTasks();
            final int numTasks = tasks.size();
            for (int taskNdx = 0; taskNdx < numTasks; ++taskNdx) {
                final Task task = tasks.get(taskNdx);
                Slog.v(TAG_WM, "    Task #" + task.mTaskId + " activities from bottom to top:");
                AppTokenList tokens = task.mAppTokens;
                final int numTokens = tokens.size();
                for (int tokenNdx = 0; tokenNdx < numTokens; ++tokenNdx) {
                    Slog.v(TAG_WM, "      activity #" + tokenNdx + ": " + tokens.get(tokenNdx).token);
                }
            }
        }
    }

    void dumpWindowsLocked() {
        final int numDisplays = mDisplayContents.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mDisplayContents.valueAt(displayNdx);
            Slog.v(TAG_WM, " Display #" + displayContent.getDisplayId());
            final WindowList windows = displayContent.getWindowList();
            for (int winNdx = windows.size() - 1; winNdx >= 0; --winNdx) {
                Slog.v(TAG_WM, "  #" + winNdx + ": " + windows.get(winNdx));
            }
        }
    }

    private final int reAddWindowLocked(int index, WindowState win) {
        final WindowList windows = win.getWindowList();
        // Adding child windows relies on mChildWindows being ordered by mSubLayer.
        final int NCW = win.mChildWindows.size();
        boolean winAdded = false;
        for (int j=0; j<NCW; j++) {
            WindowState cwin = win.mChildWindows.get(j);
            if (!winAdded && cwin.mSubLayer >= 0) {
                if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG_WM, "Re-adding child window at "
                        + index + ": " + cwin);
                win.mRebuilding = false;
                windows.add(index, win);
                index++;
                winAdded = true;
            }
            if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG_WM, "Re-adding window at "
                    + index + ": " + cwin);
            cwin.mRebuilding = false;
            windows.add(index, cwin);
            index++;
        }
        if (!winAdded) {
            if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG_WM, "Re-adding window at "
                    + index + ": " + win);
            win.mRebuilding = false;
            windows.add(index, win);
            index++;
        }
        mWindowsChanged = true;
        return index;
    }

    private final int reAddAppWindowsLocked(final DisplayContent displayContent, int index,
                                            WindowToken token) {
        final int NW = token.windows.size();
        for (int i=0; i<NW; i++) {
            final WindowState win = token.windows.get(i);
            final DisplayContent winDisplayContent = win.getDisplayContent();
            if (winDisplayContent == displayContent || winDisplayContent == null) {
                win.mDisplayContent = displayContent;
                index = reAddWindowLocked(index, win);
            }
        }
        return index;
    }


    void moveStackWindowsLocked(DisplayContent displayContent) {
        final WindowList windows = displayContent.getWindowList();
        mTmpWindows.addAll(windows);

        rebuildAppWindowListLocked(displayContent);

        // Set displayContent.layoutNeeded if window order changed.
        final int tmpSize = mTmpWindows.size();
        final int winSize = windows.size();
        int tmpNdx = 0, winNdx = 0;
        while (tmpNdx < tmpSize && winNdx < winSize) {
            // Skip over all exiting windows, they've been moved out of order.
            WindowState tmp;
            do {
                tmp = mTmpWindows.get(tmpNdx++);
            } while (tmpNdx < tmpSize && tmp.mAppToken != null && tmp.mAppToken.mIsExiting);

            WindowState win;
            do {
                win = windows.get(winNdx++);
            } while (winNdx < winSize && win.mAppToken != null && win.mAppToken.mIsExiting);

            if (tmp != win) {
                // Window order changed.
                displayContent.layoutNeeded = true;
                break;
            }
        }
        if (tmpNdx != winNdx) {
            // One list was different from the other.
            displayContent.layoutNeeded = true;
        }
        mTmpWindows.clear();

        if (!updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                false /*updateInputWindows*/)) {
            mLayersController.assignLayersLocked(displayContent.getWindowList());
        }

        mInputMonitor.setUpdateInputWindowsNeededLw();
        mWindowPlacerLocked.performSurfacePlacement();
        mInputMonitor.updateInputWindowsLw(false /*force*/);
        //dump();
    }

    public void moveTaskToTop(int taskId) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized(mWindowMap) {
                Task task = mTaskIdToTask.get(taskId);
                if (task == null) {
                    // Normal behavior, addAppToken will be called next and task will be created.
                    return;
                }
                final TaskStack stack = task.mStack;
                final DisplayContent displayContent = task.getDisplayContent();
                displayContent.moveStack(stack, true);
                if (displayContent.isDefaultDisplay) {
                    final TaskStack homeStack = displayContent.getHomeStack();
                    if (homeStack != stack) {
                        // When a non-home stack moves to the top, the home stack moves to the
                        // bottom.
                        displayContent.moveStack(homeStack, false);
                    }
                }
                stack.moveTaskToTop(task);
                if (mAppTransition.isTransitionSet()) {
                    task.setSendingToBottom(false);
                }
                moveStackWindowsLocked(displayContent);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void moveTaskToBottom(int taskId) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized(mWindowMap) {
                Task task = mTaskIdToTask.get(taskId);
                if (task == null) {
                    Slog.e(TAG_WM, "moveTaskToBottom: taskId=" + taskId
                            + " not found in mTaskIdToTask");
                    return;
                }
                final TaskStack stack = task.mStack;
                stack.moveTaskToBottom(task);
                if (mAppTransition.isTransitionSet()) {
                    task.setSendingToBottom(true);
                }
                moveStackWindowsLocked(stack.getDisplayContent());
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    boolean isStackVisibleLocked(int stackId) {
        final TaskStack stack = mStackIdToStack.get(stackId);
        return (stack != null && stack.isVisibleLocked());
    }

    public void setDockedStackCreateState(int mode, Rect bounds) {
        synchronized (mWindowMap) {
            setDockedStackCreateStateLocked(mode, bounds);
        }
    }

    void setDockedStackCreateStateLocked(int mode, Rect bounds) {
        mDockedStackCreateMode = mode;
        mDockedStackCreateBounds = bounds;
    }

    /**
     * Create a new TaskStack and place it on a DisplayContent.
     * @param stackId The unique identifier of the new stack.
     * @param displayId The unique identifier of the DisplayContent.
     * @param onTop If true the stack will be place at the top of the display,
     *              else at the bottom
     * @return The initial bounds the stack was created with. null means fullscreen.
     */
    public Rect attachStack(int stackId, int displayId, boolean onTop) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mWindowMap) {
                final DisplayContent displayContent = mDisplayContents.get(displayId);
                if (displayContent != null) {
                    TaskStack stack = mStackIdToStack.get(stackId);
                    if (stack == null) {
                        if (DEBUG_STACK) Slog.d(TAG_WM, "attachStack: stackId=" + stackId);
                        stack = new TaskStack(this, stackId);
                        mStackIdToStack.put(stackId, stack);
                        if (stackId == DOCKED_STACK_ID) {
                            getDefaultDisplayContentLocked().mDividerControllerLocked
                                    .notifyDockedStackExistsChanged(true);
                        }
                    }
                    stack.attachDisplayContent(displayContent);
                    displayContent.attachStack(stack, onTop);
                    if (stack.getRawFullscreen()) {
                        return null;
                    }
                    Rect bounds = new Rect();
                    stack.getRawBounds(bounds);
                    return bounds;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
        return null;
    }

    void detachStackLocked(DisplayContent displayContent, TaskStack stack) {
        displayContent.detachStack(stack);
        stack.detachDisplay();
        if (stack.mStackId == DOCKED_STACK_ID) {
            getDefaultDisplayContentLocked().mDividerControllerLocked
                    .notifyDockedStackExistsChanged(false);
        }
    }

    public void detachStack(int stackId) {
        synchronized (mWindowMap) {
            TaskStack stack = mStackIdToStack.get(stackId);
            if (stack != null) {
                final DisplayContent displayContent = stack.getDisplayContent();
                if (displayContent != null) {
                    if (stack.isAnimating()) {
                        stack.mDeferDetach = true;
                        return;
                    }
                    detachStackLocked(displayContent, stack);
                }
            }
        }
    }

    public void removeStack(int stackId) {
        synchronized (mWindowMap) {
            mStackIdToStack.remove(stackId);
        }
    }

    public void removeTask(int taskId) {
        synchronized (mWindowMap) {
            Task task = mTaskIdToTask.get(taskId);
            if (task == null) {
                if (DEBUG_STACK) Slog.i(TAG_WM, "removeTask: could not find taskId=" + taskId);
                return;
            }
            task.removeLocked();
        }
    }

    @Override
    public void cancelTaskWindowTransition(int taskId) {
        synchronized (mWindowMap) {
            Task task = mTaskIdToTask.get(taskId);
            if (task != null) {
                task.cancelTaskWindowTransition();
            }
        }
    }

    @Override
    public void cancelTaskThumbnailTransition(int taskId) {
        synchronized (mWindowMap) {
            Task task = mTaskIdToTask.get(taskId);
            if (task != null) {
                task.cancelTaskThumbnailTransition();
            }
        }
    }

    public void addTask(int taskId, int stackId, boolean toTop) {
        synchronized (mWindowMap) {
            if (DEBUG_STACK) Slog.i(TAG_WM, "addTask: adding taskId=" + taskId
                    + " to " + (toTop ? "top" : "bottom"));
            Task task = mTaskIdToTask.get(taskId);
            if (task == null) {
                if (DEBUG_STACK) Slog.i(TAG_WM, "addTask: could not find taskId=" + taskId);
                return;
            }
            TaskStack stack = mStackIdToStack.get(stackId);
            stack.addTask(task, toTop);
            final DisplayContent displayContent = stack.getDisplayContent();
            displayContent.layoutNeeded = true;
            mWindowPlacerLocked.performSurfacePlacement();
        }
    }

    public void moveTaskToStack(int taskId, int stackId, boolean toTop) {
        synchronized (mWindowMap) {
            if (DEBUG_STACK) Slog.i(TAG_WM, "moveTaskToStack: moving taskId=" + taskId
                    + " to stackId=" + stackId + " at " + (toTop ? "top" : "bottom"));
            Task task = mTaskIdToTask.get(taskId);
            if (task == null) {
                if (DEBUG_STACK) Slog.i(TAG_WM, "moveTaskToStack: could not find taskId=" + taskId);
                return;
            }
            TaskStack stack = mStackIdToStack.get(stackId);
            if (stack == null) {
                if (DEBUG_STACK) Slog.i(TAG_WM, "moveTaskToStack: could not find stackId=" + stackId);
                return;
            }
            task.moveTaskToStack(stack, toTop);
            final DisplayContent displayContent = stack.getDisplayContent();
            displayContent.layoutNeeded = true;
            mWindowPlacerLocked.performSurfacePlacement();
        }
    }

    public void getStackDockedModeBounds(int stackId, Rect bounds, boolean ignoreVisibility) {
        synchronized (mWindowMap) {
            final TaskStack stack = mStackIdToStack.get(stackId);
            if (stack != null) {
                stack.getStackDockedModeBoundsLocked(bounds, ignoreVisibility);
                return;
            }
            bounds.setEmpty();
        }
    }

    @Override
    public void getStackBounds(int stackId, Rect bounds) {
        synchronized (mWindowMap) {
            final TaskStack stack = mStackIdToStack.get(stackId);
            if (stack != null) {
                stack.getBounds(bounds);
                return;
            }
            bounds.setEmpty();
        }
    }

    /**
     * Re-sizes a stack and its containing tasks.
     * @param stackId Id of stack to resize.
     * @param bounds New stack bounds. Passing in null sets the bounds to fullscreen.
     * @param configs Configurations for tasks in the resized stack, keyed by task id.
     * @param taskBounds Bounds for tasks in the resized stack, keyed by task id.
     * @return True if the stack is now fullscreen.
     * */
    public boolean resizeStack(int stackId, Rect bounds,
            SparseArray<Configuration> configs, SparseArray<Rect> taskBounds,
            SparseArray<Rect> taskTempInsetBounds) {
        synchronized (mWindowMap) {
            final TaskStack stack = mStackIdToStack.get(stackId);
            if (stack == null) {
                throw new IllegalArgumentException("resizeStack: stackId " + stackId
                        + " not found.");
            }
            if (stack.setBounds(bounds, configs, taskBounds, taskTempInsetBounds)
                    && stack.isVisibleLocked()) {
                stack.getDisplayContent().layoutNeeded = true;
                mWindowPlacerLocked.performSurfacePlacement();
            }
            return stack.getRawFullscreen();
        }
    }

    public void prepareFreezingTaskBounds(int stackId) {
        synchronized (mWindowMap) {
            final TaskStack stack = mStackIdToStack.get(stackId);
            if (stack == null) {
                throw new IllegalArgumentException("prepareFreezingTaskBounds: stackId " + stackId
                        + " not found.");
            }
            stack.prepareFreezingTaskBounds();
        }
    }

    public void positionTaskInStack(int taskId, int stackId, int position, Rect bounds,
            Configuration config) {
        synchronized (mWindowMap) {
            if (DEBUG_STACK) Slog.i(TAG_WM, "positionTaskInStack: positioning taskId=" + taskId
                    + " in stackId=" + stackId + " at " + position);
            Task task = mTaskIdToTask.get(taskId);
            if (task == null) {
                if (DEBUG_STACK) Slog.i(TAG_WM,
                        "positionTaskInStack: could not find taskId=" + taskId);
                return;
            }
            TaskStack stack = mStackIdToStack.get(stackId);
            if (stack == null) {
                if (DEBUG_STACK) Slog.i(TAG_WM,
                        "positionTaskInStack: could not find stackId=" + stackId);
                return;
            }
            task.positionTaskInStack(stack, position, bounds, config);
            final DisplayContent displayContent = stack.getDisplayContent();
            displayContent.layoutNeeded = true;
            mWindowPlacerLocked.performSurfacePlacement();
        }
    }

    /**
     * Re-sizes the specified task and its containing windows.
     * Returns a {@link Configuration} object that contains configurations settings
     * that should be overridden due to the operation.
     */
    public void resizeTask(int taskId, Rect bounds, Configuration configuration,
            boolean relayout, boolean forced) {
        synchronized (mWindowMap) {
            Task task = mTaskIdToTask.get(taskId);
            if (task == null) {
                throw new IllegalArgumentException("resizeTask: taskId " + taskId
                        + " not found.");
            }

            if (task.resizeLocked(bounds, configuration, forced) && relayout) {
                task.getDisplayContent().layoutNeeded = true;
                mWindowPlacerLocked.performSurfacePlacement();
            }
        }
    }

    /**
     * Puts a specific task into docked drag resizing mode. See {@link DragResizeMode}.
     *
     * @param taskId The id of the task to put into drag resize mode.
     * @param resizing Whether to put the task into drag resize mode.
     */
    public void setTaskDockedResizing(int taskId, boolean resizing) {
        synchronized (mWindowMap) {
            Task task = mTaskIdToTask.get(taskId);
            if (task == null) {
                Slog.w(TAG, "setTaskDockedResizing: taskId " + taskId + " not found.");
                return;
            }
            task.setDragResizing(resizing, DRAG_RESIZE_MODE_DOCKED_DIVIDER);
        }
    }

    public void scrollTask(int taskId, Rect bounds) {
        synchronized (mWindowMap) {
            Task task = mTaskIdToTask.get(taskId);
            if (task == null) {
                throw new IllegalArgumentException("scrollTask: taskId " + taskId
                        + " not found.");
            }

            if (task.scrollLocked(bounds)) {
                task.getDisplayContent().layoutNeeded = true;
                mInputMonitor.setUpdateInputWindowsNeededLw();
                mWindowPlacerLocked.performSurfacePlacement();
            }
        }
    }
    /**
     * Starts deferring layout passes. Useful when doing multiple changes but to optimize
     * performance, only one layout pass should be done. This can be called multiple times, and
     * layouting will be resumed once the last caller has called {@link #continueSurfaceLayout}
     */
    public void deferSurfaceLayout() {
        synchronized (mWindowMap) {
            mWindowPlacerLocked.deferLayout();
        }
    }

    /**
     * Resumes layout passes after deferring them. See {@link #deferSurfaceLayout()}
     */
    public void continueSurfaceLayout() {
        synchronized (mWindowMap) {
            mWindowPlacerLocked.continueLayout();
        }
    }

    public void getTaskBounds(int taskId, Rect bounds) {
        synchronized (mWindowMap) {
            Task task = mTaskIdToTask.get(taskId);
            if (task != null) {
                task.getBounds(bounds);
                return;
            }
            bounds.setEmpty();
        }
    }

    /** Return true if the input task id represents a valid window manager task. */
    public boolean isValidTaskId(int taskId) {
        synchronized (mWindowMap) {
            return mTaskIdToTask.get(taskId) != null;
        }
    }

    // -------------------------------------------------------------
    // Misc IWindowSession methods
    // -------------------------------------------------------------

    @Override
    public void startFreezingScreen(int exitAnim, int enterAnim) {
        if (!checkCallingPermission(android.Manifest.permission.FREEZE_SCREEN,
                "startFreezingScreen()")) {
            throw new SecurityException("Requires FREEZE_SCREEN permission");
        }

        synchronized(mWindowMap) {
            if (!mClientFreezingScreen) {
                mClientFreezingScreen = true;
                final long origId = Binder.clearCallingIdentity();
                try {
                    startFreezingDisplayLocked(false, exitAnim, enterAnim);
                    mH.removeMessages(H.CLIENT_FREEZE_TIMEOUT);
                    mH.sendEmptyMessageDelayed(H.CLIENT_FREEZE_TIMEOUT, 5000);
                } finally {
                    Binder.restoreCallingIdentity(origId);
                }
            }
        }
    }

    @Override
    public void stopFreezingScreen() {
        if (!checkCallingPermission(android.Manifest.permission.FREEZE_SCREEN,
                "stopFreezingScreen()")) {
            throw new SecurityException("Requires FREEZE_SCREEN permission");
        }

        synchronized(mWindowMap) {
            if (mClientFreezingScreen) {
                mClientFreezingScreen = false;
                mLastFinishedFreezeSource = "client";
                final long origId = Binder.clearCallingIdentity();
                try {
                    stopFreezingDisplayLocked();
                } finally {
                    Binder.restoreCallingIdentity(origId);
                }
            }
        }
    }

    @Override
    public void disableKeyguard(IBinder token, String tag) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DISABLE_KEYGUARD)
            != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }
        // If this isn't coming from the system then don't allow disabling the lockscreen
        // to bypass security.
        if (Binder.getCallingUid() != Process.SYSTEM_UID && isKeyguardSecure()) {
            Log.d(TAG_WM, "current mode is SecurityMode, ignore disableKeyguard");
            return;
        }

        // If this isn't coming from the current user, ignore it.
        if (Binder.getCallingUserHandle().getIdentifier() != mCurrentUserId) {
            Log.d(TAG_WM, "non-current user, ignore disableKeyguard");
            return;
        }

        if (token == null) {
            throw new IllegalArgumentException("token == null");
        }

        mKeyguardDisableHandler.sendMessage(mKeyguardDisableHandler.obtainMessage(
                KeyguardDisableHandler.KEYGUARD_DISABLE, new Pair<IBinder, String>(token, tag)));
    }

    @Override
    public void reenableKeyguard(IBinder token) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DISABLE_KEYGUARD)
            != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }

        if (token == null) {
            throw new IllegalArgumentException("token == null");
        }

        mKeyguardDisableHandler.sendMessage(mKeyguardDisableHandler.obtainMessage(
                KeyguardDisableHandler.KEYGUARD_REENABLE, token));
    }

    /**
     * @see android.app.KeyguardManager#exitKeyguardSecurely
     */
    @Override
    public void exitKeyguardSecurely(final IOnKeyguardExitResult callback) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DISABLE_KEYGUARD)
            != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }

        if (callback == null) {
            throw new IllegalArgumentException("callback == null");
        }

        mPolicy.exitKeyguardSecurely(new WindowManagerPolicy.OnKeyguardExitResult() {
            @Override
            public void onKeyguardExitResult(boolean success) {
                try {
                    callback.onKeyguardExitResult(success);
                } catch (RemoteException e) {
                    // Client has died, we don't care.
                }
            }
        });
    }

    @Override
    public boolean inKeyguardRestrictedInputMode() {
        return mPolicy.inKeyguardRestrictedKeyInputMode();
    }

    @Override
    public boolean isKeyguardLocked() {
        return mPolicy.isKeyguardLocked();
    }

    @Override
    public boolean isKeyguardSecure() {
        int userId = UserHandle.getCallingUserId();
        long origId = Binder.clearCallingIdentity();
        try {
            return mPolicy.isKeyguardSecure(userId);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void dismissKeyguard() {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DISABLE_KEYGUARD)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }
        synchronized(mWindowMap) {
            mPolicy.dismissKeyguardLw();
        }
    }

    @Override
    public void keyguardGoingAway(int flags) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DISABLE_KEYGUARD)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }
        if (DEBUG_KEYGUARD) Slog.d(TAG_WM,
                "keyguardGoingAway: flags=0x" + Integer.toHexString(flags));
        synchronized (mWindowMap) {
            mAnimator.mKeyguardGoingAway = true;
            mAnimator.mKeyguardGoingAwayFlags = flags;
            mWindowPlacerLocked.requestTraversal();
        }
    }

    public void keyguardWaitingForActivityDrawn() {
        if (DEBUG_KEYGUARD) Slog.d(TAG_WM, "keyguardWaitingForActivityDrawn");
        synchronized (mWindowMap) {
            mKeyguardWaitingForActivityDrawn = true;
        }
    }

    public void notifyActivityDrawnForKeyguard() {
        if (DEBUG_KEYGUARD) Slog.d(TAG_WM, "notifyActivityDrawnForKeyguard: waiting="
                + mKeyguardWaitingForActivityDrawn + " Callers=" + Debug.getCallers(5));
        synchronized (mWindowMap) {
            if (mKeyguardWaitingForActivityDrawn) {
                mPolicy.notifyActivityDrawnForKeyguardLw();
                mKeyguardWaitingForActivityDrawn = false;
            }
        }
    }

    void showGlobalActions() {
        mPolicy.showGlobalActions();
    }

    @Override
    public void closeSystemDialogs(String reason) {
        synchronized(mWindowMap) {
            final int numDisplays = mDisplayContents.size();
            for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                final WindowList windows = mDisplayContents.valueAt(displayNdx).getWindowList();
                final int numWindows = windows.size();
                for (int winNdx = 0; winNdx < numWindows; ++winNdx) {
                    final WindowState w = windows.get(winNdx);
                    if (w.mHasSurface) {
                        try {
                            w.mClient.closeSystemDialogs(reason);
                        } catch (RemoteException e) {
                        }
                    }
                }
            }
        }
    }

    static float fixScale(float scale) {
        if (scale < 0) scale = 0;
        else if (scale > 20) scale = 20;
        return Math.abs(scale);
    }

    @Override
    public void setAnimationScale(int which, float scale) {
        if (!checkCallingPermission(android.Manifest.permission.SET_ANIMATION_SCALE,
                "setAnimationScale()")) {
            throw new SecurityException("Requires SET_ANIMATION_SCALE permission");
        }

        scale = fixScale(scale);
        switch (which) {
            case 0: mWindowAnimationScaleSetting = scale; break;
            case 1: mTransitionAnimationScaleSetting = scale; break;
            case 2: mAnimatorDurationScaleSetting = scale; break;
        }

        // Persist setting
        mH.sendEmptyMessage(H.PERSIST_ANIMATION_SCALE);
    }

    @Override
    public void setAnimationScales(float[] scales) {
        if (!checkCallingPermission(android.Manifest.permission.SET_ANIMATION_SCALE,
                "setAnimationScale()")) {
            throw new SecurityException("Requires SET_ANIMATION_SCALE permission");
        }

        if (scales != null) {
            if (scales.length >= 1) {
                mWindowAnimationScaleSetting = fixScale(scales[0]);
            }
            if (scales.length >= 2) {
                mTransitionAnimationScaleSetting = fixScale(scales[1]);
            }
            if (scales.length >= 3) {
                mAnimatorDurationScaleSetting = fixScale(scales[2]);
                dispatchNewAnimatorScaleLocked(null);
            }
        }

        // Persist setting
        mH.sendEmptyMessage(H.PERSIST_ANIMATION_SCALE);
    }

    private void setAnimatorDurationScale(float scale) {
        mAnimatorDurationScaleSetting = scale;
        ValueAnimator.setDurationScale(scale);
    }

    public float getWindowAnimationScaleLocked() {
        return mAnimationsDisabled ? 0 : mWindowAnimationScaleSetting;
    }

    public float getTransitionAnimationScaleLocked() {
        return mAnimationsDisabled ? 0 : mTransitionAnimationScaleSetting;
    }

    @Override
    public float getAnimationScale(int which) {
        switch (which) {
            case 0: return mWindowAnimationScaleSetting;
            case 1: return mTransitionAnimationScaleSetting;
            case 2: return mAnimatorDurationScaleSetting;
        }
        return 0;
    }

    @Override
    public float[] getAnimationScales() {
        return new float[] { mWindowAnimationScaleSetting, mTransitionAnimationScaleSetting,
                mAnimatorDurationScaleSetting };
    }

    @Override
    public float getCurrentAnimatorScale() {
        synchronized(mWindowMap) {
            return mAnimationsDisabled ? 0 : mAnimatorDurationScaleSetting;
        }
    }

    void dispatchNewAnimatorScaleLocked(Session session) {
        mH.obtainMessage(H.NEW_ANIMATOR_SCALE, session).sendToTarget();
    }

    @Override
    public void registerPointerEventListener(PointerEventListener listener) {
        mPointerEventDispatcher.registerInputEventListener(listener);
    }

    @Override
    public void unregisterPointerEventListener(PointerEventListener listener) {
        mPointerEventDispatcher.unregisterInputEventListener(listener);
    }

    // Called by window manager policy. Not exposed externally.
    @Override
    public int getLidState() {
        int sw = mInputManager.getSwitchState(-1, InputDevice.SOURCE_ANY,
                InputManagerService.SW_LID);
        if (sw > 0) {
            // Switch state: AKEY_STATE_DOWN or AKEY_STATE_VIRTUAL.
            return LID_CLOSED;
        } else if (sw == 0) {
            // Switch state: AKEY_STATE_UP.
            return LID_OPEN;
        } else {
            // Switch state: AKEY_STATE_UNKNOWN.
            return LID_ABSENT;
        }
    }

    // Called by window manager policy. Not exposed externally.
    @Override
    public void lockDeviceNow() {
        lockNow(null);
    }

    // Called by window manager policy. Not exposed externally.
    @Override
    public int getCameraLensCoverState() {
        int sw = mInputManager.getSwitchState(-1, InputDevice.SOURCE_ANY,
                InputManagerService.SW_CAMERA_LENS_COVER);
        if (sw > 0) {
            // Switch state: AKEY_STATE_DOWN or AKEY_STATE_VIRTUAL.
            return CAMERA_LENS_COVERED;
        } else if (sw == 0) {
            // Switch state: AKEY_STATE_UP.
            return CAMERA_LENS_UNCOVERED;
        } else {
            // Switch state: AKEY_STATE_UNKNOWN.
            return CAMERA_LENS_COVER_ABSENT;
        }
    }

    // Called by window manager policy.  Not exposed externally.
    @Override
    public void switchInputMethod(boolean forwardDirection) {
        final InputMethodManagerInternal inputMethodManagerInternal =
                LocalServices.getService(InputMethodManagerInternal.class);
        if (inputMethodManagerInternal != null) {
            inputMethodManagerInternal.switchInputMethod(forwardDirection);
        }
    }

    // Called by window manager policy.  Not exposed externally.
    @Override
    public void shutdown(boolean confirm) {
        ShutdownThread.shutdown(mContext, PowerManager.SHUTDOWN_USER_REQUESTED, confirm);
    }

    // Called by window manager policy.  Not exposed externally.
    @Override
    public void rebootSafeMode(boolean confirm) {
        ShutdownThread.rebootSafeMode(mContext, confirm);
    }

    public void setCurrentProfileIds(final int[] currentProfileIds) {
        synchronized (mWindowMap) {
            mCurrentProfileIds = currentProfileIds;
        }
    }

    public void setCurrentUser(final int newUserId, final int[] currentProfileIds) {
        synchronized (mWindowMap) {
            mCurrentUserId = newUserId;
            mCurrentProfileIds = currentProfileIds;
            mAppTransition.setCurrentUser(newUserId);
            mPolicy.setCurrentUserLw(newUserId);

            // If keyguard was disabled, re-enable it
            // TODO: Keep track of keyguardEnabled state per user and use here...
            // e.g. enabled = mKeyguardDisableHandler.getEnabledStateForUser(newUserId);
            mPolicy.enableKeyguard(true);

            // Hide windows that should not be seen by the new user.
            final int numDisplays = mDisplayContents.size();
            for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                final DisplayContent displayContent = mDisplayContents.valueAt(displayNdx);
                displayContent.switchUserStacks();
                rebuildAppWindowListLocked(displayContent);
            }
            mWindowPlacerLocked.performSurfacePlacement();

            // Notify whether the docked stack exists for the current user
            final DisplayContent displayContent = getDefaultDisplayContentLocked();
            displayContent.mDividerControllerLocked
                    .notifyDockedStackExistsChanged(hasDockedTasksForUser(newUserId));

            // If the display is already prepared, update the density.
            // Otherwise, we'll update it when it's prepared.
            if (mDisplayReady) {
                final int forcedDensity = getForcedDisplayDensityForUserLocked(newUserId);
                final int targetDensity = forcedDensity != 0 ? forcedDensity
                        : displayContent.mInitialDisplayDensity;
                setForcedDisplayDensityLocked(displayContent, targetDensity);
            }
        }
    }

    /**
     * Returns whether there is a docked task for the current user.
     */
    boolean hasDockedTasksForUser(int userId) {
        final TaskStack stack = mStackIdToStack.get(DOCKED_STACK_ID);
        if (stack == null) {
            return false;
        }

        final ArrayList<Task> tasks = stack.getTasks();
        boolean hasUserTask = false;
        for (int i = tasks.size() - 1; i >= 0 && !hasUserTask; i--) {
            final Task task = tasks.get(i);
            hasUserTask = (task.mUserId == userId);
        }
        return hasUserTask;
    }

    /* Called by WindowState */
    boolean isCurrentProfileLocked(int userId) {
        if (userId == mCurrentUserId) return true;
        for (int i = 0; i < mCurrentProfileIds.length; i++) {
            if (mCurrentProfileIds[i] == userId) return true;
        }
        return false;
    }

    public void enableScreenAfterBoot() {
        synchronized(mWindowMap) {
            if (DEBUG_BOOT) {
                RuntimeException here = new RuntimeException("here");
                here.fillInStackTrace();
                Slog.i(TAG_WM, "enableScreenAfterBoot: mDisplayEnabled=" + mDisplayEnabled
                        + " mForceDisplayEnabled=" + mForceDisplayEnabled
                        + " mShowingBootMessages=" + mShowingBootMessages
                        + " mSystemBooted=" + mSystemBooted, here);
            }
            if (mSystemBooted) {
                return;
            }
            mSystemBooted = true;
            hideBootMessagesLocked();
            // If the screen still doesn't come up after 30 seconds, give
            // up and turn it on.
            mH.sendEmptyMessageDelayed(H.BOOT_TIMEOUT, 30*1000);
        }

        mPolicy.systemBooted();

        performEnableScreen();
    }

    @Override
    public void enableScreenIfNeeded() {
        synchronized (mWindowMap) {
            enableScreenIfNeededLocked();
        }
    }

    void enableScreenIfNeededLocked() {
        if (DEBUG_BOOT) {
            RuntimeException here = new RuntimeException("here");
            here.fillInStackTrace();
            Slog.i(TAG_WM, "enableScreenIfNeededLocked: mDisplayEnabled=" + mDisplayEnabled
                    + " mForceDisplayEnabled=" + mForceDisplayEnabled
                    + " mShowingBootMessages=" + mShowingBootMessages
                    + " mSystemBooted=" + mSystemBooted, here);
        }
        if (mDisplayEnabled) {
            return;
        }
        if (!mSystemBooted && !mShowingBootMessages) {
            return;
        }
        mH.sendEmptyMessage(H.ENABLE_SCREEN);
    }

    public void performBootTimeout() {
        synchronized(mWindowMap) {
            if (mDisplayEnabled) {
                return;
            }
            Slog.w(TAG_WM, "***** BOOT TIMEOUT: forcing display enabled");
            mForceDisplayEnabled = true;
        }
        performEnableScreen();
    }

    private boolean checkWaitingForWindowsLocked() {

        boolean haveBootMsg = false;
        boolean haveApp = false;
        // if the wallpaper service is disabled on the device, we're never going to have
        // wallpaper, don't bother waiting for it
        boolean haveWallpaper = false;
        boolean wallpaperEnabled = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_enableWallpaperService)
                && !mOnlyCore;
        boolean haveKeyguard = true;
        // TODO(multidisplay): Expand to all displays?
        final WindowList windows = getDefaultWindowListLocked();
        final int N = windows.size();
        for (int i=0; i<N; i++) {
            WindowState w = windows.get(i);
            if (w.isVisibleLw() && !w.mObscured && !w.isDrawnLw()) {
                return true;
            }
            if (w.isDrawnLw()) {
                if (w.mAttrs.type == TYPE_BOOT_PROGRESS) {
                    haveBootMsg = true;
                } else if (w.mAttrs.type == TYPE_APPLICATION) {
                    haveApp = true;
                } else if (w.mAttrs.type == TYPE_WALLPAPER) {
                    haveWallpaper = true;
                } else if (w.mAttrs.type == TYPE_STATUS_BAR) {
                    haveKeyguard = mPolicy.isKeyguardDrawnLw();
                }
            }
        }

        if (DEBUG_SCREEN_ON || DEBUG_BOOT) {
            Slog.i(TAG_WM, "******** booted=" + mSystemBooted + " msg=" + mShowingBootMessages
                    + " haveBoot=" + haveBootMsg + " haveApp=" + haveApp
                    + " haveWall=" + haveWallpaper + " wallEnabled=" + wallpaperEnabled
                    + " haveKeyguard=" + haveKeyguard);
        }

        // If we are turning on the screen to show the boot message,
        // don't do it until the boot message is actually displayed.
        if (!mSystemBooted && !haveBootMsg) {
            return true;
        }

        // If we are turning on the screen after the boot is completed
        // normally, don't do so until we have the application and
        // wallpaper.
        if (mSystemBooted && ((!haveApp && !haveKeyguard) ||
                (wallpaperEnabled && !haveWallpaper))) {
            return true;
        }

        return false;
    }

    public void performEnableScreen() {
        synchronized(mWindowMap) {
            if (DEBUG_BOOT) Slog.i(TAG_WM, "performEnableScreen: mDisplayEnabled=" + mDisplayEnabled
                    + " mForceDisplayEnabled=" + mForceDisplayEnabled
                    + " mShowingBootMessages=" + mShowingBootMessages
                    + " mSystemBooted=" + mSystemBooted
                    + " mOnlyCore=" + mOnlyCore,
                    new RuntimeException("here").fillInStackTrace());
            if (mDisplayEnabled) {
                return;
            }
            if (!mSystemBooted && !mShowingBootMessages) {
                return;
            }

            // Don't enable the screen until all existing windows have been drawn.
            if (!mForceDisplayEnabled && checkWaitingForWindowsLocked()) {
                return;
            }

            if (!mBootAnimationStopped) {
                // Do this one time.
                Trace.asyncTraceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "Stop bootanim", 0);
                try {
                    IBinder surfaceFlinger = ServiceManager.getService("SurfaceFlinger");
                    if (surfaceFlinger != null) {
                        //Slog.i(TAG_WM, "******* TELLING SURFACE FLINGER WE ARE BOOTED!");
                        Parcel data = Parcel.obtain();
                        data.writeInterfaceToken("android.ui.ISurfaceComposer");
                        surfaceFlinger.transact(IBinder.FIRST_CALL_TRANSACTION, // BOOT_FINISHED
                                data, null, 0);
                        data.recycle();
                    }
                } catch (RemoteException ex) {
                    Slog.e(TAG_WM, "Boot completed: SurfaceFlinger is dead!");
                }
                mBootAnimationStopped = true;
            }

            if (!mForceDisplayEnabled && !checkBootAnimationCompleteLocked()) {
                if (DEBUG_BOOT) Slog.i(TAG_WM, "performEnableScreen: Waiting for anim complete");
                return;
            }

            EventLog.writeEvent(EventLogTags.WM_BOOT_ANIMATION_DONE, SystemClock.uptimeMillis());
            Trace.asyncTraceEnd(Trace.TRACE_TAG_WINDOW_MANAGER, "Stop bootanim", 0);
            mDisplayEnabled = true;
            if (DEBUG_SCREEN_ON || DEBUG_BOOT) Slog.i(TAG_WM, "******************** ENABLING SCREEN!");

            // Enable input dispatch.
            mInputMonitor.setEventDispatchingLw(mEventDispatchingEnabled);
        }

        try {
            mActivityManager.bootAnimationComplete();
        } catch (RemoteException e) {
        }

        mPolicy.enableScreenAfterBoot();

        // Make sure the last requested orientation has been applied.
        updateRotationUnchecked(false, false);
    }

    private boolean checkBootAnimationCompleteLocked() {
        if (SystemService.isRunning(BOOT_ANIMATION_SERVICE)) {
            mH.removeMessages(H.CHECK_IF_BOOT_ANIMATION_FINISHED);
            mH.sendEmptyMessageDelayed(H.CHECK_IF_BOOT_ANIMATION_FINISHED,
                    BOOT_ANIMATION_POLL_INTERVAL);
            if (DEBUG_BOOT) Slog.i(TAG_WM, "checkBootAnimationComplete: Waiting for anim complete");
            return false;
        }
        if (DEBUG_BOOT) Slog.i(TAG_WM, "checkBootAnimationComplete: Animation complete!");
        return true;
    }

    public void showBootMessage(final CharSequence msg, final boolean always) {
        boolean first = false;
        synchronized(mWindowMap) {
            if (DEBUG_BOOT) {
                RuntimeException here = new RuntimeException("here");
                here.fillInStackTrace();
                Slog.i(TAG_WM, "showBootMessage: msg=" + msg + " always=" + always
                        + " mAllowBootMessages=" + mAllowBootMessages
                        + " mShowingBootMessages=" + mShowingBootMessages
                        + " mSystemBooted=" + mSystemBooted, here);
            }
            if (!mAllowBootMessages) {
                return;
            }
            if (!mShowingBootMessages) {
                if (!always) {
                    return;
                }
                first = true;
            }
            if (mSystemBooted) {
                return;
            }
            mShowingBootMessages = true;
            mPolicy.showBootMessage(msg, always);
        }
        if (first) {
            performEnableScreen();
        }
    }

    public void hideBootMessagesLocked() {
        if (DEBUG_BOOT) {
            RuntimeException here = new RuntimeException("here");
            here.fillInStackTrace();
            Slog.i(TAG_WM, "hideBootMessagesLocked: mDisplayEnabled=" + mDisplayEnabled
                    + " mForceDisplayEnabled=" + mForceDisplayEnabled
                    + " mShowingBootMessages=" + mShowingBootMessages
                    + " mSystemBooted=" + mSystemBooted, here);
        }
        if (mShowingBootMessages) {
            mShowingBootMessages = false;
            mPolicy.hideBootMessages();
        }
    }

    @Override
    public void setInTouchMode(boolean mode) {
        synchronized(mWindowMap) {
            mInTouchMode = mode;
        }
    }

    private void updateCircularDisplayMaskIfNeeded() {
        // we're fullscreen and not hosted in an ActivityView
        if (mContext.getResources().getConfiguration().isScreenRound()
                && mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_windowShowCircularMask)) {
            final int currentUserId;
            synchronized(mWindowMap) {
                currentUserId = mCurrentUserId;
            }
            // Device configuration calls for a circular display mask, but we only enable the mask
            // if the accessibility color inversion feature is disabled, as the inverted mask
            // causes artifacts.
            int inversionState = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 0, currentUserId);
            int showMask = (inversionState == 1) ? 0 : 1;
            Message m = mH.obtainMessage(H.SHOW_CIRCULAR_DISPLAY_MASK);
            m.arg1 = showMask;
            mH.sendMessage(m);
        }
    }

    public void showEmulatorDisplayOverlayIfNeeded() {
        if (mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_windowEnableCircularEmulatorDisplayOverlay)
                && SystemProperties.getBoolean(PROPERTY_EMULATOR_CIRCULAR, false)
                && Build.IS_EMULATOR) {
            mH.sendMessage(mH.obtainMessage(H.SHOW_EMULATOR_DISPLAY_OVERLAY));
        }
    }

    public void showCircularMask(boolean visible) {
        synchronized(mWindowMap) {

            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG_WM,
                    ">>> OPEN TRANSACTION showCircularMask(visible=" + visible + ")");
            SurfaceControl.openTransaction();
            try {
                if (visible) {
                    // TODO(multi-display): support multiple displays
                    if (mCircularDisplayMask == null) {
                        int screenOffset = mContext.getResources().getInteger(
                                com.android.internal.R.integer.config_windowOutsetBottom);
                        int maskThickness = mContext.getResources().getDimensionPixelSize(
                                com.android.internal.R.dimen.circular_display_mask_thickness);

                        mCircularDisplayMask = new CircularDisplayMask(
                                getDefaultDisplayContentLocked().getDisplay(),
                                mFxSession,
                                mPolicy.windowTypeToLayerLw(
                                        WindowManager.LayoutParams.TYPE_POINTER)
                                        * TYPE_LAYER_MULTIPLIER + 10, screenOffset, maskThickness);
                    }
                    mCircularDisplayMask.setVisibility(true);
                } else if (mCircularDisplayMask != null) {
                    mCircularDisplayMask.setVisibility(false);
                    mCircularDisplayMask = null;
                }
            } finally {
                SurfaceControl.closeTransaction();
                if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG_WM,
                        "<<< CLOSE TRANSACTION showCircularMask(visible=" + visible + ")");
            }
        }
    }

    public void showEmulatorDisplayOverlay() {
        synchronized(mWindowMap) {

            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG_WM,
                    ">>> OPEN TRANSACTION showEmulatorDisplayOverlay");
            SurfaceControl.openTransaction();
            try {
                if (mEmulatorDisplayOverlay == null) {
                    mEmulatorDisplayOverlay = new EmulatorDisplayOverlay(
                            mContext,
                            getDefaultDisplayContentLocked().getDisplay(),
                            mFxSession,
                            mPolicy.windowTypeToLayerLw(
                                    WindowManager.LayoutParams.TYPE_POINTER)
                                    * TYPE_LAYER_MULTIPLIER + 10);
                }
                mEmulatorDisplayOverlay.setVisibility(true);
            } finally {
                SurfaceControl.closeTransaction();
                if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG_WM,
                        "<<< CLOSE TRANSACTION showEmulatorDisplayOverlay");
            }
        }
    }

    // TODO: more accounting of which pid(s) turned it on, keep count,
    // only allow disables from pids which have count on, etc.
    @Override
    public void showStrictModeViolation(boolean on) {
        int pid = Binder.getCallingPid();
        mH.sendMessage(mH.obtainMessage(H.SHOW_STRICT_MODE_VIOLATION, on ? 1 : 0, pid));
    }

    private void showStrictModeViolation(int arg, int pid) {
        final boolean on = arg != 0;
        synchronized(mWindowMap) {
            // Ignoring requests to enable the red border from clients
            // which aren't on screen.  (e.g. Broadcast Receivers in
            // the background..)
            if (on) {
                boolean isVisible = false;
                final int numDisplays = mDisplayContents.size();
                for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                    final WindowList windows = mDisplayContents.valueAt(displayNdx).getWindowList();
                    final int numWindows = windows.size();
                    for (int winNdx = 0; winNdx < numWindows; ++winNdx) {
                        final WindowState ws = windows.get(winNdx);
                        if (ws.mSession.mPid == pid && ws.isVisibleLw()) {
                            isVisible = true;
                            break;
                        }
                    }
                }
                if (!isVisible) {
                    return;
                }
            }

            if (SHOW_VERBOSE_TRANSACTIONS) Slog.i(TAG_WM,
                    ">>> OPEN TRANSACTION showStrictModeViolation");
            SurfaceControl.openTransaction();
            try {
                // TODO(multi-display): support multiple displays
                if (mStrictModeFlash == null) {
                    mStrictModeFlash = new StrictModeFlash(
                            getDefaultDisplayContentLocked().getDisplay(), mFxSession);
                }
                mStrictModeFlash.setVisibility(on);
            } finally {
                SurfaceControl.closeTransaction();
                if (SHOW_VERBOSE_TRANSACTIONS) Slog.i(TAG_WM,
                        "<<< CLOSE TRANSACTION showStrictModeViolation");
            }
        }
    }

    @Override
    public void setStrictModeVisualIndicatorPreference(String value) {
        SystemProperties.set(StrictMode.VISUAL_PROPERTY, value);
    }

    private static void convertCropForSurfaceFlinger(Rect crop, int rot, int dw, int dh) {
        if (rot == Surface.ROTATION_90) {
            final int tmp = crop.top;
            crop.top = dw - crop.right;
            crop.right = crop.bottom;
            crop.bottom = dw - crop.left;
            crop.left = tmp;
        } else if (rot == Surface.ROTATION_180) {
            int tmp = crop.top;
            crop.top = dh - crop.bottom;
            crop.bottom = dh - tmp;
            tmp = crop.right;
            crop.right = dw - crop.left;
            crop.left = dw - tmp;
        } else if (rot == Surface.ROTATION_270) {
            final int tmp = crop.top;
            crop.top = crop.left;
            crop.left = dh - crop.bottom;
            crop.bottom = crop.right;
            crop.right = dh - tmp;
        }
    }

    /**
     * Takes a snapshot of the screen.  In landscape mode this grabs the whole screen.
     * In portrait mode, it grabs the upper region of the screen based on the vertical dimension
     * of the target image.
     */
    @Override
    public boolean requestAssistScreenshot(final IAssistScreenshotReceiver receiver) {
        if (!checkCallingPermission(Manifest.permission.READ_FRAME_BUFFER,
                "requestAssistScreenshot()")) {
            throw new SecurityException("Requires READ_FRAME_BUFFER permission");
        }

        FgThread.getHandler().post(new Runnable() {
            @Override
            public void run() {
                Bitmap bm = screenshotApplicationsInner(null, Display.DEFAULT_DISPLAY, -1, -1,
                        true, 1f, Bitmap.Config.ARGB_8888);
                try {
                    receiver.send(bm);
                } catch (RemoteException e) {
                }
            }
        });

        return true;
    }

    /**
     * Takes a snapshot of the screen.  In landscape mode this grabs the whole screen.
     * In portrait mode, it grabs the full screenshot.
     *
     * @param displayId the Display to take a screenshot of.
     * @param width the width of the target bitmap
     * @param height the height of the target bitmap
     * @param frameScale the scale to apply to the frame, only used when width = -1 and height = -1
     */
    @Override
    public Bitmap screenshotApplications(IBinder appToken, int displayId, int width, int height,
            float frameScale) {
        if (!checkCallingPermission(Manifest.permission.READ_FRAME_BUFFER,
                "screenshotApplications()")) {
            throw new SecurityException("Requires READ_FRAME_BUFFER permission");
        }
        try {
            Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "screenshotApplications");
            return screenshotApplicationsInner(appToken, displayId, width, height, false,
                    frameScale, Bitmap.Config.RGB_565);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
        }
    }

    Bitmap screenshotApplicationsInner(IBinder appToken, int displayId, int width, int height,
            boolean includeFullDisplay, float frameScale, Bitmap.Config config) {
        final DisplayContent displayContent;
        synchronized(mWindowMap) {
            displayContent = getDisplayContentLocked(displayId);
            if (displayContent == null) {
                if (DEBUG_SCREENSHOT) Slog.i(TAG_WM, "Screenshot of " + appToken
                        + ": returning null. No Display for displayId=" + displayId);
                return null;
            }
        }
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        int dw = displayInfo.logicalWidth;
        int dh = displayInfo.logicalHeight;
        if (dw == 0 || dh == 0) {
            if (DEBUG_SCREENSHOT) Slog.i(TAG_WM, "Screenshot of " + appToken
                    + ": returning null. logical widthxheight=" + dw + "x" + dh);
            return null;
        }

        Bitmap bm = null;

        int maxLayer = 0;
        final Rect frame = new Rect();
        final Rect stackBounds = new Rect();

        boolean screenshotReady;
        int minLayer;
        if (appToken == null) {
            screenshotReady = true;
            minLayer = 0;
        } else {
            screenshotReady = false;
            minLayer = Integer.MAX_VALUE;
        }

        WindowState appWin = null;

        boolean includeImeInScreenshot;
        synchronized(mWindowMap) {
            final AppWindowToken imeTargetAppToken =
                    mInputMethodTarget != null ? mInputMethodTarget.mAppToken : null;
            // We only include the Ime in the screenshot if the app we are screenshoting is the IME
            // target and isn't in multi-window mode. We don't screenshot the IME in multi-window
            // mode because the frame of the IME might not overlap with that of the app.
            // E.g. IME target app at the top in split-screen mode and the IME at the bottom
            // overlapping with the bottom app.
            includeImeInScreenshot = imeTargetAppToken != null
                    && imeTargetAppToken.appToken != null
                    && imeTargetAppToken.appToken.asBinder() == appToken
                    && !mInputMethodTarget.isInMultiWindowMode();
        }

        final int aboveAppLayer = (mPolicy.windowTypeToLayerLw(TYPE_APPLICATION) + 1)
                * TYPE_LAYER_MULTIPLIER + TYPE_LAYER_OFFSET;

        synchronized(mWindowMap) {
            // Figure out the part of the screen that is actually the app.
            appWin = null;
            final WindowList windows = displayContent.getWindowList();
            for (int i = windows.size() - 1; i >= 0; i--) {
                WindowState ws = windows.get(i);
                if (!ws.mHasSurface) {
                    continue;
                }
                if (ws.mLayer >= aboveAppLayer) {
                    continue;
                }
                if (ws.mIsImWindow) {
                    if (!includeImeInScreenshot) {
                        continue;
                    }
                } else if (ws.mIsWallpaper) {
                    if (appWin == null) {
                        // We have not ran across the target window yet, so it is probably
                        // behind the wallpaper. This can happen when the keyguard is up and
                        // all windows are moved behind the wallpaper. We don't want to
                        // include the wallpaper layer in the screenshot as it will coverup
                        // the layer of the target window.
                        continue;
                    }
                    // Fall through. The target window is in front of the wallpaper. For this
                    // case we want to include the wallpaper layer in the screenshot because
                    // the target window might have some transparent areas.
                } else if (appToken != null) {
                    if (ws.mAppToken == null || ws.mAppToken.token != appToken) {
                        // This app window is of no interest if it is not associated with the
                        // screenshot app.
                        continue;
                    }
                    appWin = ws;
                }

                // Include this window.

                final WindowStateAnimator winAnim = ws.mWinAnimator;
                int layer = winAnim.mSurfaceController.getLayer();
                if (maxLayer < layer) {
                    maxLayer = layer;
                }
                if (minLayer > layer) {
                    minLayer = layer;
                }

                // Don't include wallpaper in bounds calculation
                if (!includeFullDisplay && !ws.mIsWallpaper) {
                    final Rect wf = ws.mFrame;
                    final Rect cr = ws.mContentInsets;
                    int left = wf.left + cr.left;
                    int top = wf.top + cr.top;
                    int right = wf.right - cr.right;
                    int bottom = wf.bottom - cr.bottom;
                    frame.union(left, top, right, bottom);
                    ws.getVisibleBounds(stackBounds);
                    if (!Rect.intersects(frame, stackBounds)) {
                        // Set frame empty if there's no intersection.
                        frame.setEmpty();
                    }
                }

                if (ws.mAppToken != null && ws.mAppToken.token == appToken &&
                        ws.isDisplayedLw() && winAnim.getShown()) {
                    screenshotReady = true;
                }

                if (ws.isObscuringFullscreen(displayInfo)){
                    break;
                }
            }

            if (appToken != null && appWin == null) {
                // Can't find a window to snapshot.
                if (DEBUG_SCREENSHOT) Slog.i(TAG_WM,
                        "Screenshot: Couldn't find a surface matching " + appToken);
                return null;
            }

            if (!screenshotReady) {
                Slog.i(TAG_WM, "Failed to capture screenshot of " + appToken +
                        " appWin=" + (appWin == null ? "null" : (appWin + " drawState=" +
                        appWin.mWinAnimator.mDrawState)));
                return null;
            }

            // Screenshot is ready to be taken. Everything from here below will continue
            // through the bottom of the loop and return a value. We only stay in the loop
            // because we don't want to release the mWindowMap lock until the screenshot is
            // taken.

            if (maxLayer == 0) {
                if (DEBUG_SCREENSHOT) Slog.i(TAG_WM, "Screenshot of " + appToken
                        + ": returning null maxLayer=" + maxLayer);
                return null;
            }

            if (!includeFullDisplay) {
                // Constrain frame to the screen size.
                if (!frame.intersect(0, 0, dw, dh)) {
                    frame.setEmpty();
                }
            } else {
                // Caller just wants entire display.
                frame.set(0, 0, dw, dh);
            }
            if (frame.isEmpty()) {
                return null;
            }

            if (width < 0) {
                width = (int) (frame.width() * frameScale);
            }
            if (height < 0) {
                height = (int) (frame.height() * frameScale);
            }

            // Tell surface flinger what part of the image to crop. Take the top
            // right part of the application, and crop the larger dimension to fit.
            Rect crop = new Rect(frame);
            if (width / (float) frame.width() < height / (float) frame.height()) {
                int cropWidth = (int)((float)width / (float)height * frame.height());
                crop.right = crop.left + cropWidth;
            } else {
                int cropHeight = (int)((float)height / (float)width * frame.width());
                crop.bottom = crop.top + cropHeight;
            }

            // The screenshot API does not apply the current screen rotation.
            int rot = getDefaultDisplayContentLocked().getDisplay().getRotation();

            if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
                rot = (rot == Surface.ROTATION_90) ? Surface.ROTATION_270 : Surface.ROTATION_90;
            }

            // Surfaceflinger is not aware of orientation, so convert our logical
            // crop to surfaceflinger's portrait orientation.
            convertCropForSurfaceFlinger(crop, rot, dw, dh);

            if (DEBUG_SCREENSHOT) {
                Slog.i(TAG_WM, "Screenshot: " + dw + "x" + dh + " from " + minLayer + " to "
                        + maxLayer + " appToken=" + appToken);
                for (int i = 0; i < windows.size(); i++) {
                    WindowState win = windows.get(i);
                    WindowSurfaceController controller = win.mWinAnimator.mSurfaceController;
                    Slog.i(TAG_WM, win + ": " + win.mLayer
                            + " animLayer=" + win.mWinAnimator.mAnimLayer
                            + " surfaceLayer=" + ((controller == null)
                                ? "null" : controller.getLayer()));
                }
            }

            ScreenRotationAnimation screenRotationAnimation =
                    mAnimator.getScreenRotationAnimationLocked(Display.DEFAULT_DISPLAY);
            final boolean inRotation = screenRotationAnimation != null &&
                    screenRotationAnimation.isAnimating();
            if (DEBUG_SCREENSHOT && inRotation) Slog.v(TAG_WM,
                    "Taking screenshot while rotating");

            // We force pending transactions to flush before taking
            // the screenshot by pushing an empty synchronous transaction.
            SurfaceControl.openTransaction();
            SurfaceControl.closeTransactionSync();

            bm = SurfaceControl.screenshot(crop, width, height, minLayer, maxLayer,
                    inRotation, rot);
            if (bm == null) {
                Slog.w(TAG_WM, "Screenshot failure taking screenshot for (" + dw + "x" + dh
                        + ") to layer " + maxLayer);
                return null;
            }
        }

        if (DEBUG_SCREENSHOT) {
            // TEST IF IT's ALL BLACK
            int[] buffer = new int[bm.getWidth() * bm.getHeight()];
            bm.getPixels(buffer, 0, bm.getWidth(), 0, 0, bm.getWidth(), bm.getHeight());
            boolean allBlack = true;
            final int firstColor = buffer[0];
            for (int i = 0; i < buffer.length; i++) {
                if (buffer[i] != firstColor) {
                    allBlack = false;
                    break;
                }
            }
            if (allBlack) {
                Slog.i(TAG_WM, "Screenshot " + appWin + " was monochrome(" +
                        Integer.toHexString(firstColor) + ")! mSurfaceLayer=" +
                        (appWin != null ?
                                appWin.mWinAnimator.mSurfaceController.getLayer() : "null") +
                        " minLayer=" + minLayer + " maxLayer=" + maxLayer);
            }
        }

        // Create a copy of the screenshot that is immutable and backed in ashmem.
        // This greatly reduces the overhead of passing the bitmap between processes.
        Bitmap ret = bm.createAshmemBitmap(config);
        bm.recycle();
        return ret;
    }

    /**
     * Freeze rotation changes.  (Enable "rotation lock".)
     * Persists across reboots.
     * @param rotation The desired rotation to freeze to, or -1 to use the
     * current rotation.
     */
    @Override
    public void freezeRotation(int rotation) {
        if (!checkCallingPermission(android.Manifest.permission.SET_ORIENTATION,
                "freezeRotation()")) {
            throw new SecurityException("Requires SET_ORIENTATION permission");
        }
        if (rotation < -1 || rotation > Surface.ROTATION_270) {
            throw new IllegalArgumentException("Rotation argument must be -1 or a valid "
                    + "rotation constant.");
        }

        if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "freezeRotation: mRotation=" + mRotation);

        long origId = Binder.clearCallingIdentity();
        try {
            mPolicy.setUserRotationMode(WindowManagerPolicy.USER_ROTATION_LOCKED,
                    rotation == -1 ? mRotation : rotation);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        updateRotationUnchecked(false, false);
    }

    /**
     * Thaw rotation changes.  (Disable "rotation lock".)
     * Persists across reboots.
     */
    @Override
    public void thawRotation() {
        if (!checkCallingPermission(android.Manifest.permission.SET_ORIENTATION,
                "thawRotation()")) {
            throw new SecurityException("Requires SET_ORIENTATION permission");
        }

        if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "thawRotation: mRotation=" + mRotation);

        long origId = Binder.clearCallingIdentity();
        try {
            mPolicy.setUserRotationMode(WindowManagerPolicy.USER_ROTATION_FREE,
                    777); // rot not used
        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        updateRotationUnchecked(false, false);
    }

    /**
     * Recalculate the current rotation.
     *
     * Called by the window manager policy whenever the state of the system changes
     * such that the current rotation might need to be updated, such as when the
     * device is docked or rotated into a new posture.
     */
    @Override
    public void updateRotation(boolean alwaysSendConfiguration, boolean forceRelayout) {
        updateRotationUnchecked(alwaysSendConfiguration, forceRelayout);
    }

    /**
     * Temporarily pauses rotation changes until resumed.
     *
     * This can be used to prevent rotation changes from occurring while the user is
     * performing certain operations, such as drag and drop.
     *
     * This call nests and must be matched by an equal number of calls to
     * {@link #resumeRotationLocked}.
     */
    void pauseRotationLocked() {
        mDeferredRotationPauseCount += 1;
    }

    /**
     * Resumes normal rotation changes after being paused.
     */
    void resumeRotationLocked() {
        if (mDeferredRotationPauseCount > 0) {
            mDeferredRotationPauseCount -= 1;
            if (mDeferredRotationPauseCount == 0) {
                boolean changed = updateRotationUncheckedLocked(false);
                if (changed) {
                    mH.sendEmptyMessage(H.SEND_NEW_CONFIGURATION);
                }
            }
        }
    }

    public void updateRotationUnchecked(boolean alwaysSendConfiguration, boolean forceRelayout) {
        if(DEBUG_ORIENTATION) Slog.v(TAG_WM, "updateRotationUnchecked("
                   + "alwaysSendConfiguration=" + alwaysSendConfiguration + ")");

        long origId = Binder.clearCallingIdentity();
        boolean changed;
        synchronized(mWindowMap) {
            changed = updateRotationUncheckedLocked(false);
            if (!changed || forceRelayout) {
                getDefaultDisplayContentLocked().layoutNeeded = true;
                mWindowPlacerLocked.performSurfacePlacement();
            }
        }

        if (changed || alwaysSendConfiguration) {
            sendNewConfiguration();
        }

        Binder.restoreCallingIdentity(origId);
    }

    // TODO(multidisplay): Rotate any display?
    /**
     * Updates the current rotation.
     *
     * Returns true if the rotation has been changed.  In this case YOU
     * MUST CALL sendNewConfiguration() TO UNFREEZE THE SCREEN.
     */
    public boolean updateRotationUncheckedLocked(boolean inTransaction) {
        if (mDeferredRotationPauseCount > 0) {
            // Rotation updates have been paused temporarily.  Defer the update until
            // updates have been resumed.
            if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Deferring rotation, rotation is paused.");
            return false;
        }

        ScreenRotationAnimation screenRotationAnimation =
                mAnimator.getScreenRotationAnimationLocked(Display.DEFAULT_DISPLAY);
        if (screenRotationAnimation != null && screenRotationAnimation.isAnimating()) {
            // Rotation updates cannot be performed while the previous rotation change
            // animation is still in progress.  Skip this update.  We will try updating
            // again after the animation is finished and the display is unfrozen.
            if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Deferring rotation, animation in progress.");
            return false;
        }

        if (!mDisplayEnabled) {
            // No point choosing a rotation if the display is not enabled.
            if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Deferring rotation, display is not enabled.");
            return false;
        }

        // TODO: Implement forced rotation changes.
        //       Set mAltOrientation to indicate that the application is receiving
        //       an orientation that has different metrics than it expected.
        //       eg. Portrait instead of Landscape.

        int rotation = mPolicy.rotationForOrientationLw(mForcedAppOrientation, mRotation);
        boolean altOrientation = !mPolicy.rotationHasCompatibleMetricsLw(
                mForcedAppOrientation, rotation);

        if (DEBUG_ORIENTATION) {
            Slog.v(TAG_WM, "Application requested orientation "
                    + mForcedAppOrientation + ", got rotation " + rotation
                    + " which has " + (altOrientation ? "incompatible" : "compatible")
                    + " metrics");
        }

        if (mRotation == rotation && mAltOrientation == altOrientation) {
            // No change.
            return false;
        }

        if (DEBUG_ORIENTATION) {
            Slog.v(TAG_WM,
                "Rotation changed to " + rotation + (altOrientation ? " (alt)" : "")
                + " from " + mRotation + (mAltOrientation ? " (alt)" : "")
                + ", forceApp=" + mForcedAppOrientation);
        }

        mRotation = rotation;
        mAltOrientation = altOrientation;
        mPolicy.setRotationLw(mRotation);

        mWindowsFreezingScreen = WINDOWS_FREEZING_SCREENS_ACTIVE;
        mH.removeMessages(H.WINDOW_FREEZE_TIMEOUT);
        mH.sendEmptyMessageDelayed(H.WINDOW_FREEZE_TIMEOUT, WINDOW_FREEZE_TIMEOUT_DURATION);
        mWaitingForConfig = true;
        final DisplayContent displayContent = getDefaultDisplayContentLocked();
        displayContent.layoutNeeded = true;
        final int[] anim = new int[2];
        if (displayContent.isDimming()) {
            anim[0] = anim[1] = 0;
        } else {
            mPolicy.selectRotationAnimationLw(anim);
        }
        startFreezingDisplayLocked(inTransaction, anim[0], anim[1]);
        // startFreezingDisplayLocked can reset the ScreenRotationAnimation.
        screenRotationAnimation =
                mAnimator.getScreenRotationAnimationLocked(Display.DEFAULT_DISPLAY);

        // We need to update our screen size information to match the new rotation. If the rotation
        // has actually changed then this method will return true and, according to the comment at
        // the top of the method, the caller is obligated to call computeNewConfigurationLocked().
        // By updating the Display info here it will be available to
        // computeScreenConfigurationLocked later.
        updateDisplayAndOrientationLocked(mCurConfiguration.uiMode);

        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        if (!inTransaction) {
            if (SHOW_TRANSACTIONS) {
                Slog.i(TAG_WM, ">>> OPEN TRANSACTION setRotationUnchecked");
            }
            SurfaceControl.openTransaction();
        }
        try {
            // NOTE: We disable the rotation in the emulator because
            //       it doesn't support hardware OpenGL emulation yet.
            if (CUSTOM_SCREEN_ROTATION && screenRotationAnimation != null
                    && screenRotationAnimation.hasScreenshot()) {
                if (screenRotationAnimation.setRotationInTransaction(
                        rotation, mFxSession,
                        MAX_ANIMATION_DURATION, getTransitionAnimationScaleLocked(),
                        displayInfo.logicalWidth, displayInfo.logicalHeight)) {
                    scheduleAnimationLocked();
                }
            }

            mDisplayManagerInternal.performTraversalInTransactionFromWindowManager();
        } finally {
            if (!inTransaction) {
                SurfaceControl.closeTransaction();
                if (SHOW_LIGHT_TRANSACTIONS) {
                    Slog.i(TAG_WM, "<<< CLOSE TRANSACTION setRotationUnchecked");
                }
            }
        }

        final WindowList windows = displayContent.getWindowList();
        for (int i = windows.size() - 1; i >= 0; i--) {
            WindowState w = windows.get(i);
            // Discard surface after orientation change, these can't be reused.
            if (w.mAppToken != null) {
                w.mAppToken.destroySavedSurfaces();
            }
            if (w.mHasSurface) {
                if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Set mOrientationChanging of " + w);
                w.mOrientationChanging = true;
                mWindowPlacerLocked.mOrientationChangeComplete = false;
            }
            w.mLastFreezeDuration = 0;
        }

        for (int i=mRotationWatchers.size()-1; i>=0; i--) {
            try {
                mRotationWatchers.get(i).watcher.onRotationChanged(rotation);
            } catch (RemoteException e) {
            }
        }

        //TODO (multidisplay): Magnification is supported only for the default display.
        // Announce rotation only if we will not animate as we already have the
        // windows in final state. Otherwise, we make this call at the rotation end.
        if (screenRotationAnimation == null && mAccessibilityController != null
                && displayContent.getDisplayId() == Display.DEFAULT_DISPLAY) {
            mAccessibilityController.onRotationChangedLocked(getDefaultDisplayContentLocked(),
                    rotation);
        }

        return true;
    }

    @Override
    public int getRotation() {
        return mRotation;
    }

    @Override
    public boolean isRotationFrozen() {
        return mPolicy.getUserRotationMode() == WindowManagerPolicy.USER_ROTATION_LOCKED;
    }

    @Override
    public int watchRotation(IRotationWatcher watcher) {
        final IBinder watcherBinder = watcher.asBinder();
        IBinder.DeathRecipient dr = new IBinder.DeathRecipient() {
            @Override
            public void binderDied() {
                synchronized (mWindowMap) {
                    for (int i=0; i<mRotationWatchers.size(); i++) {
                        if (watcherBinder == mRotationWatchers.get(i).watcher.asBinder()) {
                            RotationWatcher removed = mRotationWatchers.remove(i);
                            IBinder binder = removed.watcher.asBinder();
                            if (binder != null) {
                                binder.unlinkToDeath(this, 0);
                            }
                            i--;
                        }
                    }
                }
            }
        };

        synchronized (mWindowMap) {
            try {
                watcher.asBinder().linkToDeath(dr, 0);
                mRotationWatchers.add(new RotationWatcher(watcher, dr));
            } catch (RemoteException e) {
                // Client died, no cleanup needed.
            }

            return mRotation;
        }
    }

    @Override
    public void removeRotationWatcher(IRotationWatcher watcher) {
        final IBinder watcherBinder = watcher.asBinder();
        synchronized (mWindowMap) {
            for (int i=0; i<mRotationWatchers.size(); i++) {
                RotationWatcher rotationWatcher = mRotationWatchers.get(i);
                if (watcherBinder == rotationWatcher.watcher.asBinder()) {
                    RotationWatcher removed = mRotationWatchers.remove(i);
                    IBinder binder = removed.watcher.asBinder();
                    if (binder != null) {
                        binder.unlinkToDeath(removed.deathRecipient, 0);
                    }
                    i--;
                }
            }
        }
    }

    /**
     * Apps that use the compact menu panel (as controlled by the panelMenuIsCompact
     * theme attribute) on devices that feature a physical options menu key attempt to position
     * their menu panel window along the edge of the screen nearest the physical menu key.
     * This lowers the travel distance between invoking the menu panel and selecting
     * a menu option.
     *
     * This method helps control where that menu is placed. Its current implementation makes
     * assumptions about the menu key and its relationship to the screen based on whether
     * the device's natural orientation is portrait (width < height) or landscape.
     *
     * The menu key is assumed to be located along the bottom edge of natural-portrait
     * devices and along the right edge of natural-landscape devices. If these assumptions
     * do not hold for the target device, this method should be changed to reflect that.
     *
     * @return A {@link Gravity} value for placing the options menu window
     */
    @Override
    public int getPreferredOptionsPanelGravity() {
        synchronized (mWindowMap) {
            final int rotation = getRotation();

            // TODO(multidisplay): Assume that such devices physical keys are on the main screen.
            final DisplayContent displayContent = getDefaultDisplayContentLocked();
            if (displayContent.mInitialDisplayWidth < displayContent.mInitialDisplayHeight) {
                // On devices with a natural orientation of portrait
                switch (rotation) {
                    default:
                    case Surface.ROTATION_0:
                        return Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
                    case Surface.ROTATION_90:
                        return Gravity.RIGHT | Gravity.BOTTOM;
                    case Surface.ROTATION_180:
                        return Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
                    case Surface.ROTATION_270:
                        return Gravity.START | Gravity.BOTTOM;
                }
            }

            // On devices with a natural orientation of landscape
            switch (rotation) {
                default:
                case Surface.ROTATION_0:
                    return Gravity.RIGHT | Gravity.BOTTOM;
                case Surface.ROTATION_90:
                    return Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
                case Surface.ROTATION_180:
                    return Gravity.START | Gravity.BOTTOM;
                case Surface.ROTATION_270:
                    return Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
            }
        }
    }

    /**
     * Starts the view server on the specified port.
     *
     * @param port The port to listener to.
     *
     * @return True if the server was successfully started, false otherwise.
     *
     * @see com.android.server.wm.ViewServer
     * @see com.android.server.wm.ViewServer#VIEW_SERVER_DEFAULT_PORT
     */
    @Override
    public boolean startViewServer(int port) {
        if (isSystemSecure()) {
            return false;
        }

        if (!checkCallingPermission(Manifest.permission.DUMP, "startViewServer")) {
            return false;
        }

        if (port < 1024) {
            return false;
        }

        if (mViewServer != null) {
            if (!mViewServer.isRunning()) {
                try {
                    return mViewServer.start();
                } catch (IOException e) {
                    Slog.w(TAG_WM, "View server did not start");
                }
            }
            return false;
        }

        try {
            mViewServer = new ViewServer(this, port);
            return mViewServer.start();
        } catch (IOException e) {
            Slog.w(TAG_WM, "View server did not start");
        }
        return false;
    }

    private boolean isSystemSecure() {
        return "1".equals(SystemProperties.get(SYSTEM_SECURE, "1")) &&
                "0".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
    }

    /**
     * Stops the view server if it exists.
     *
     * @return True if the server stopped, false if it wasn't started or
     *         couldn't be stopped.
     *
     * @see com.android.server.wm.ViewServer
     */
    @Override
    public boolean stopViewServer() {
        if (isSystemSecure()) {
            return false;
        }

        if (!checkCallingPermission(Manifest.permission.DUMP, "stopViewServer")) {
            return false;
        }

        if (mViewServer != null) {
            return mViewServer.stop();
        }
        return false;
    }

    /**
     * Indicates whether the view server is running.
     *
     * @return True if the server is running, false otherwise.
     *
     * @see com.android.server.wm.ViewServer
     */
    @Override
    public boolean isViewServerRunning() {
        if (isSystemSecure()) {
            return false;
        }

        if (!checkCallingPermission(Manifest.permission.DUMP, "isViewServerRunning")) {
            return false;
        }

        return mViewServer != null && mViewServer.isRunning();
    }

    /**
     * Lists all availble windows in the system. The listing is written in the
     * specified Socket's output stream with the following syntax:
     * windowHashCodeInHexadecimal windowName
     * Each line of the ouput represents a different window.
     *
     * @param client The remote client to send the listing to.
     * @return False if an error occured, true otherwise.
     */
    boolean viewServerListWindows(Socket client) {
        if (isSystemSecure()) {
            return false;
        }

        boolean result = true;

        WindowList windows = new WindowList();
        synchronized (mWindowMap) {
            //noinspection unchecked
            final int numDisplays = mDisplayContents.size();
            for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                final DisplayContent displayContent = mDisplayContents.valueAt(displayNdx);
                windows.addAll(displayContent.getWindowList());
            }
        }

        BufferedWriter out = null;

        // Any uncaught exception will crash the system process
        try {
            OutputStream clientStream = client.getOutputStream();
            out = new BufferedWriter(new OutputStreamWriter(clientStream), 8 * 1024);

            final int count = windows.size();
            for (int i = 0; i < count; i++) {
                final WindowState w = windows.get(i);
                out.write(Integer.toHexString(System.identityHashCode(w)));
                out.write(' ');
                out.append(w.mAttrs.getTitle());
                out.write('\n');
            }

            out.write("DONE.\n");
            out.flush();
        } catch (Exception e) {
            result = false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    result = false;
                }
            }
        }

        return result;
    }

    // TODO(multidisplay): Extend to multiple displays.
    /**
     * Returns the focused window in the following format:
     * windowHashCodeInHexadecimal windowName
     *
     * @param client The remote client to send the listing to.
     * @return False if an error occurred, true otherwise.
     */
    boolean viewServerGetFocusedWindow(Socket client) {
        if (isSystemSecure()) {
            return false;
        }

        boolean result = true;

        WindowState focusedWindow = getFocusedWindow();

        BufferedWriter out = null;

        // Any uncaught exception will crash the system process
        try {
            OutputStream clientStream = client.getOutputStream();
            out = new BufferedWriter(new OutputStreamWriter(clientStream), 8 * 1024);

            if(focusedWindow != null) {
                out.write(Integer.toHexString(System.identityHashCode(focusedWindow)));
                out.write(' ');
                out.append(focusedWindow.mAttrs.getTitle());
            }
            out.write('\n');
            out.flush();
        } catch (Exception e) {
            result = false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    result = false;
                }
            }
        }

        return result;
    }

    /**
     * Sends a command to a target window. The result of the command, if any, will be
     * written in the output stream of the specified socket.
     *
     * The parameters must follow this syntax:
     * windowHashcode extra
     *
     * Where XX is the length in characeters of the windowTitle.
     *
     * The first parameter is the target window. The window with the specified hashcode
     * will be the target. If no target can be found, nothing happens. The extra parameters
     * will be delivered to the target window and as parameters to the command itself.
     *
     * @param client The remote client to sent the result, if any, to.
     * @param command The command to execute.
     * @param parameters The command parameters.
     *
     * @return True if the command was successfully delivered, false otherwise. This does
     *         not indicate whether the command itself was successful.
     */
    boolean viewServerWindowCommand(Socket client, String command, String parameters) {
        if (isSystemSecure()) {
            return false;
        }

        boolean success = true;
        Parcel data = null;
        Parcel reply = null;

        BufferedWriter out = null;

        // Any uncaught exception will crash the system process
        try {
            // Find the hashcode of the window
            int index = parameters.indexOf(' ');
            if (index == -1) {
                index = parameters.length();
            }
            final String code = parameters.substring(0, index);
            int hashCode = (int) Long.parseLong(code, 16);

            // Extract the command's parameter after the window description
            if (index < parameters.length()) {
                parameters = parameters.substring(index + 1);
            } else {
                parameters = "";
            }

            final WindowState window = findWindow(hashCode);
            if (window == null) {
                return false;
            }

            data = Parcel.obtain();
            data.writeInterfaceToken("android.view.IWindow");
            data.writeString(command);
            data.writeString(parameters);
            data.writeInt(1);
            ParcelFileDescriptor.fromSocket(client).writeToParcel(data, 0);

            reply = Parcel.obtain();

            final IBinder binder = window.mClient.asBinder();
            // TODO: GET THE TRANSACTION CODE IN A SAFER MANNER
            binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0);

            reply.readException();

            if (!client.isOutputShutdown()) {
                out = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
                out.write("DONE\n");
                out.flush();
            }

        } catch (Exception e) {
            Slog.w(TAG_WM, "Could not send command " + command + " with parameters " + parameters, e);
            success = false;
        } finally {
            if (data != null) {
                data.recycle();
            }
            if (reply != null) {
                reply.recycle();
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {

                }
            }
        }

        return success;
    }

    public void addWindowChangeListener(WindowChangeListener listener) {
        synchronized(mWindowMap) {
            mWindowChangeListeners.add(listener);
        }
    }

    public void removeWindowChangeListener(WindowChangeListener listener) {
        synchronized(mWindowMap) {
            mWindowChangeListeners.remove(listener);
        }
    }

    private void notifyWindowsChanged() {
        WindowChangeListener[] windowChangeListeners;
        synchronized(mWindowMap) {
            if(mWindowChangeListeners.isEmpty()) {
                return;
            }
            windowChangeListeners = new WindowChangeListener[mWindowChangeListeners.size()];
            windowChangeListeners = mWindowChangeListeners.toArray(windowChangeListeners);
        }
        int N = windowChangeListeners.length;
        for(int i = 0; i < N; i++) {
            windowChangeListeners[i].windowsChanged();
        }
    }

    private void notifyFocusChanged() {
        WindowChangeListener[] windowChangeListeners;
        synchronized(mWindowMap) {
            if(mWindowChangeListeners.isEmpty()) {
                return;
            }
            windowChangeListeners = new WindowChangeListener[mWindowChangeListeners.size()];
            windowChangeListeners = mWindowChangeListeners.toArray(windowChangeListeners);
        }
        int N = windowChangeListeners.length;
        for(int i = 0; i < N; i++) {
            windowChangeListeners[i].focusChanged();
        }
    }

    private WindowState findWindow(int hashCode) {
        if (hashCode == -1) {
            // TODO(multidisplay): Extend to multiple displays.
            return getFocusedWindow();
        }

        synchronized (mWindowMap) {
            final int numDisplays = mDisplayContents.size();
            for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                final WindowList windows = mDisplayContents.valueAt(displayNdx).getWindowList();
                final int numWindows = windows.size();
                for (int winNdx = 0; winNdx < numWindows; ++winNdx) {
                    final WindowState w = windows.get(winNdx);
                    if (System.identityHashCode(w) == hashCode) {
                        return w;
                    }
                }
            }
        }

        return null;
    }

    /*
     * Instruct the Activity Manager to fetch the current configuration and broadcast
     * that to config-changed listeners if appropriate.
     */
    void sendNewConfiguration() {
        try {
            mActivityManager.updateConfiguration(null);
        } catch (RemoteException e) {
        }
    }

    public Configuration computeNewConfiguration() {
        synchronized (mWindowMap) {
            return computeNewConfigurationLocked();
        }
    }

    private Configuration computeNewConfigurationLocked() {
        if (!mDisplayReady) {
            return null;
        }
        Configuration config = new Configuration();
        config.fontScale = 0;
        computeScreenConfigurationLocked(config);
        return config;
    }

    private void adjustDisplaySizeRanges(DisplayInfo displayInfo, int rotation, int uiMode,
            int dw, int dh) {
        // TODO: Multidisplay: for now only use with default display.
        final int width = mPolicy.getConfigDisplayWidth(dw, dh, rotation, uiMode);
        if (width < displayInfo.smallestNominalAppWidth) {
            displayInfo.smallestNominalAppWidth = width;
        }
        if (width > displayInfo.largestNominalAppWidth) {
            displayInfo.largestNominalAppWidth = width;
        }
        final int height = mPolicy.getConfigDisplayHeight(dw, dh, rotation, uiMode);
        if (height < displayInfo.smallestNominalAppHeight) {
            displayInfo.smallestNominalAppHeight = height;
        }
        if (height > displayInfo.largestNominalAppHeight) {
            displayInfo.largestNominalAppHeight = height;
        }
    }

    private int reduceConfigLayout(int curLayout, int rotation, float density,
            int dw, int dh, int uiMode) {
        // TODO: Multidisplay: for now only use with default display.
        // Get the app screen size at this rotation.
        int w = mPolicy.getNonDecorDisplayWidth(dw, dh, rotation, uiMode);
        int h = mPolicy.getNonDecorDisplayHeight(dw, dh, rotation, uiMode);

        // Compute the screen layout size class for this rotation.
        int longSize = w;
        int shortSize = h;
        if (longSize < shortSize) {
            int tmp = longSize;
            longSize = shortSize;
            shortSize = tmp;
        }
        longSize = (int)(longSize/density);
        shortSize = (int)(shortSize/density);
        return Configuration.reduceScreenLayout(curLayout, longSize, shortSize);
    }

    private void computeSizeRangesAndScreenLayout(DisplayInfo displayInfo, boolean rotated,
                  int uiMode, int dw, int dh, float density, Configuration outConfig) {
        // TODO: Multidisplay: for now only use with default display.

        // We need to determine the smallest width that will occur under normal
        // operation.  To this, start with the base screen size and compute the
        // width under the different possible rotations.  We need to un-rotate
        // the current screen dimensions before doing this.
        int unrotDw, unrotDh;
        if (rotated) {
            unrotDw = dh;
            unrotDh = dw;
        } else {
            unrotDw = dw;
            unrotDh = dh;
        }
        displayInfo.smallestNominalAppWidth = 1<<30;
        displayInfo.smallestNominalAppHeight = 1<<30;
        displayInfo.largestNominalAppWidth = 0;
        displayInfo.largestNominalAppHeight = 0;
        adjustDisplaySizeRanges(displayInfo, Surface.ROTATION_0, uiMode, unrotDw, unrotDh);
        adjustDisplaySizeRanges(displayInfo, Surface.ROTATION_90, uiMode, unrotDh, unrotDw);
        adjustDisplaySizeRanges(displayInfo, Surface.ROTATION_180, uiMode, unrotDw, unrotDh);
        adjustDisplaySizeRanges(displayInfo, Surface.ROTATION_270, uiMode, unrotDh, unrotDw);
        int sl = Configuration.resetScreenLayout(outConfig.screenLayout);
        sl = reduceConfigLayout(sl, Surface.ROTATION_0, density, unrotDw, unrotDh, uiMode);
        sl = reduceConfigLayout(sl, Surface.ROTATION_90, density, unrotDh, unrotDw, uiMode);
        sl = reduceConfigLayout(sl, Surface.ROTATION_180, density, unrotDw, unrotDh, uiMode);
        sl = reduceConfigLayout(sl, Surface.ROTATION_270, density, unrotDh, unrotDw, uiMode);
        outConfig.smallestScreenWidthDp = (int)(displayInfo.smallestNominalAppWidth / density);
        outConfig.screenLayout = sl;
    }

    private int reduceCompatConfigWidthSize(int curSize, int rotation, int uiMode,
            DisplayMetrics dm, int dw, int dh) {
        // TODO: Multidisplay: for now only use with default display.
        dm.noncompatWidthPixels = mPolicy.getNonDecorDisplayWidth(dw, dh, rotation, uiMode);
        dm.noncompatHeightPixels = mPolicy.getNonDecorDisplayHeight(dw, dh, rotation, uiMode);
        float scale = CompatibilityInfo.computeCompatibleScaling(dm, null);
        int size = (int)(((dm.noncompatWidthPixels / scale) / dm.density) + .5f);
        if (curSize == 0 || size < curSize) {
            curSize = size;
        }
        return curSize;
    }

    private int computeCompatSmallestWidth(boolean rotated, int uiMode, DisplayMetrics dm, int dw, int dh) {
        // TODO: Multidisplay: for now only use with default display.
        mTmpDisplayMetrics.setTo(dm);
        final DisplayMetrics tmpDm = mTmpDisplayMetrics;
        final int unrotDw, unrotDh;
        if (rotated) {
            unrotDw = dh;
            unrotDh = dw;
        } else {
            unrotDw = dw;
            unrotDh = dh;
        }
        int sw = reduceCompatConfigWidthSize(0, Surface.ROTATION_0, uiMode, tmpDm, unrotDw, unrotDh);
        sw = reduceCompatConfigWidthSize(sw, Surface.ROTATION_90, uiMode, tmpDm, unrotDh, unrotDw);
        sw = reduceCompatConfigWidthSize(sw, Surface.ROTATION_180, uiMode, tmpDm, unrotDw, unrotDh);
        sw = reduceCompatConfigWidthSize(sw, Surface.ROTATION_270, uiMode, tmpDm, unrotDh, unrotDw);
        return sw;
    }

    /** Do not call if mDisplayReady == false */
    DisplayInfo updateDisplayAndOrientationLocked(int uiMode) {
        // TODO(multidisplay): For now, apply Configuration to main screen only.
        final DisplayContent displayContent = getDefaultDisplayContentLocked();

        // Use the effective "visual" dimensions based on current rotation
        final boolean rotated = (mRotation == Surface.ROTATION_90
                || mRotation == Surface.ROTATION_270);
        final int realdw = rotated ?
                displayContent.mBaseDisplayHeight : displayContent.mBaseDisplayWidth;
        final int realdh = rotated ?
                displayContent.mBaseDisplayWidth : displayContent.mBaseDisplayHeight;
        int dw = realdw;
        int dh = realdh;

        if (mAltOrientation) {
            if (realdw > realdh) {
                // Turn landscape into portrait.
                int maxw = (int)(realdh/1.3f);
                if (maxw < realdw) {
                    dw = maxw;
                }
            } else {
                // Turn portrait into landscape.
                int maxh = (int)(realdw/1.3f);
                if (maxh < realdh) {
                    dh = maxh;
                }
            }
        }

        // Update application display metrics.
        final int appWidth = mPolicy.getNonDecorDisplayWidth(dw, dh, mRotation, uiMode);
        final int appHeight = mPolicy.getNonDecorDisplayHeight(dw, dh, mRotation, uiMode);
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        displayInfo.rotation = mRotation;
        displayInfo.logicalWidth = dw;
        displayInfo.logicalHeight = dh;
        displayInfo.logicalDensityDpi = displayContent.mBaseDisplayDensity;
        displayInfo.appWidth = appWidth;
        displayInfo.appHeight = appHeight;
        displayInfo.getLogicalMetrics(mRealDisplayMetrics,
                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null);
        displayInfo.getAppMetrics(mDisplayMetrics);
        if (displayContent.mDisplayScalingDisabled) {
            displayInfo.flags |= Display.FLAG_SCALING_DISABLED;
        } else {
            displayInfo.flags &= ~Display.FLAG_SCALING_DISABLED;
        }

        mDisplayManagerInternal.setDisplayInfoOverrideFromWindowManager(
                displayContent.getDisplayId(), displayInfo);

        displayContent.mBaseDisplayRect.set(0, 0, dw, dh);
        if (false) {
            Slog.i(TAG_WM, "Set app display size: " + appWidth + " x " + appHeight);
        }

        mCompatibleScreenScale = CompatibilityInfo.computeCompatibleScaling(mDisplayMetrics,
                mCompatDisplayMetrics);
        return displayInfo;
    }

    /** Do not call if mDisplayReady == false */
    void computeScreenConfigurationLocked(Configuration config) {
        final DisplayInfo displayInfo = updateDisplayAndOrientationLocked(
                config.uiMode);

        final int dw = displayInfo.logicalWidth;
        final int dh = displayInfo.logicalHeight;
        config.orientation = (dw <= dh) ? Configuration.ORIENTATION_PORTRAIT :
                Configuration.ORIENTATION_LANDSCAPE;
        config.screenWidthDp =
                (int)(mPolicy.getConfigDisplayWidth(dw, dh, mRotation, config.uiMode) /
                        mDisplayMetrics.density);
        config.screenHeightDp =
                (int)(mPolicy.getConfigDisplayHeight(dw, dh, mRotation, config.uiMode) /
                        mDisplayMetrics.density);
        final boolean rotated = (mRotation == Surface.ROTATION_90
                || mRotation == Surface.ROTATION_270);

        computeSizeRangesAndScreenLayout(displayInfo, rotated, config.uiMode, dw, dh,
                mDisplayMetrics.density, config);

        config.screenLayout = (config.screenLayout & ~Configuration.SCREENLAYOUT_ROUND_MASK)
                | ((displayInfo.flags & Display.FLAG_ROUND) != 0
                        ? Configuration.SCREENLAYOUT_ROUND_YES
                        : Configuration.SCREENLAYOUT_ROUND_NO);

        config.compatScreenWidthDp = (int)(config.screenWidthDp / mCompatibleScreenScale);
        config.compatScreenHeightDp = (int)(config.screenHeightDp / mCompatibleScreenScale);
        config.compatSmallestScreenWidthDp = computeCompatSmallestWidth(rotated, config.uiMode,
                mDisplayMetrics, dw, dh);
        config.densityDpi = displayInfo.logicalDensityDpi;

        // Update the configuration based on available input devices, lid switch,
        // and platform configuration.
        config.touchscreen = Configuration.TOUCHSCREEN_NOTOUCH;
        config.keyboard = Configuration.KEYBOARD_NOKEYS;
        config.navigation = Configuration.NAVIGATION_NONAV;

        int keyboardPresence = 0;
        int navigationPresence = 0;
        final InputDevice[] devices = mInputManager.getInputDevices();
        final int len = devices.length;
        for (int i = 0; i < len; i++) {
            InputDevice device = devices[i];
            if (!device.isVirtual()) {
                final int sources = device.getSources();
                final int presenceFlag = device.isExternal() ?
                        WindowManagerPolicy.PRESENCE_EXTERNAL :
                                WindowManagerPolicy.PRESENCE_INTERNAL;

                if (mIsTouchDevice) {
                    if ((sources & InputDevice.SOURCE_TOUCHSCREEN) ==
                            InputDevice.SOURCE_TOUCHSCREEN) {
                        config.touchscreen = Configuration.TOUCHSCREEN_FINGER;
                    }
                } else {
                    config.touchscreen = Configuration.TOUCHSCREEN_NOTOUCH;
                }

                if ((sources & InputDevice.SOURCE_TRACKBALL) == InputDevice.SOURCE_TRACKBALL) {
                    config.navigation = Configuration.NAVIGATION_TRACKBALL;
                    navigationPresence |= presenceFlag;
                } else if ((sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
                        && config.navigation == Configuration.NAVIGATION_NONAV) {
                    config.navigation = Configuration.NAVIGATION_DPAD;
                    navigationPresence |= presenceFlag;
                }

                if (device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                    config.keyboard = Configuration.KEYBOARD_QWERTY;
                    keyboardPresence |= presenceFlag;
                }
            }
        }

        if (config.navigation == Configuration.NAVIGATION_NONAV && mHasPermanentDpad) {
            config.navigation = Configuration.NAVIGATION_DPAD;
            navigationPresence |= WindowManagerPolicy.PRESENCE_INTERNAL;
        }

        // Determine whether a hard keyboard is available and enabled.
        boolean hardKeyboardAvailable = config.keyboard != Configuration.KEYBOARD_NOKEYS;
        if (hardKeyboardAvailable != mHardKeyboardAvailable) {
            mHardKeyboardAvailable = hardKeyboardAvailable;
            mH.removeMessages(H.REPORT_HARD_KEYBOARD_STATUS_CHANGE);
            mH.sendEmptyMessage(H.REPORT_HARD_KEYBOARD_STATUS_CHANGE);
        }

        // Let the policy update hidden states.
        config.keyboardHidden = Configuration.KEYBOARDHIDDEN_NO;
        config.hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO;
        config.navigationHidden = Configuration.NAVIGATIONHIDDEN_NO;
        mPolicy.adjustConfigurationLw(config, keyboardPresence, navigationPresence);
    }

    void notifyHardKeyboardStatusChange() {
        final boolean available;
        final WindowManagerInternal.OnHardKeyboardStatusChangeListener listener;
        synchronized (mWindowMap) {
            listener = mHardKeyboardStatusChangeListener;
            available = mHardKeyboardAvailable;
        }
        if (listener != null) {
            listener.onHardKeyboardStatusChange(available);
        }
    }

    boolean startMovingTask(IWindow window, float startX, float startY) {
        WindowState win = null;
        synchronized (mWindowMap) {
            win = windowForClientLocked(null, window, false);
            // win shouldn't be null here, pass it down to startPositioningLocked
            // to get warning if it's null.
            if (!startPositioningLocked(win, false /*resize*/, startX, startY)) {
                return false;
            }
        }
        try {
            mActivityManager.setFocusedTask(win.getTask().mTaskId);
        } catch(RemoteException e) {}
        return true;
    }

    private void startScrollingTask(DisplayContent displayContent, int startX, int startY) {
        if (DEBUG_TASK_POSITIONING) Slog.d(TAG_WM,
                "startScrollingTask: " + "{" + startX + ", " + startY + "}");

        Task task = null;
        synchronized (mWindowMap) {
            int taskId = displayContent.taskIdFromPoint(startX, startY);
            if (taskId >= 0) {
                task = mTaskIdToTask.get(taskId);
            }
            if (task == null || !task.isDockedInEffect() || !startPositioningLocked(
                    task.getTopVisibleAppMainWindow(), false /*resize*/, startX, startY)) {
                return;
            }
        }
        try {
            mActivityManager.setFocusedTask(task.mTaskId);
        } catch(RemoteException e) {}
    }

    private void handleTapOutsideTask(DisplayContent displayContent, int x, int y) {
        int taskId = -1;
        synchronized (mWindowMap) {
            final Task task = displayContent.findTaskForControlPoint(x, y);
            if (task != null) {
                if (!startPositioningLocked(
                        task.getTopVisibleAppMainWindow(), true /*resize*/, x, y)) {
                    return;
                }
                taskId = task.mTaskId;
            } else {
                taskId = displayContent.taskIdFromPoint(x, y);
            }
        }
        if (taskId >= 0) {
            try {
                mActivityManager.setFocusedTask(taskId);
            } catch(RemoteException e) {}
        }
    }

    private boolean startPositioningLocked(
            WindowState win, boolean resize, float startX, float startY) {
        if (DEBUG_TASK_POSITIONING) Slog.d(TAG_WM, "startPositioningLocked: "
            + "win=" + win + ", resize=" + resize + ", {" + startX + ", " + startY + "}");

        if (win == null || win.getAppToken() == null) {
            Slog.w(TAG_WM, "startPositioningLocked: Bad window " + win);
            return false;
        }
        if (win.mInputChannel == null) {
            Slog.wtf(TAG_WM, "startPositioningLocked: " + win + " has no input channel, "
                    + " probably being removed");
            return false;
        }

        final DisplayContent displayContent = win.getDisplayContent();
        if (displayContent == null) {
            Slog.w(TAG_WM, "startPositioningLocked: Invalid display content " + win);
            return false;
        }

        Display display = displayContent.getDisplay();
        mTaskPositioner = new TaskPositioner(this);
        mTaskPositioner.register(display);
        mInputMonitor.updateInputWindowsLw(true /*force*/);

        // We need to grab the touch focus so that the touch events during the
        // resizing/scrolling are not sent to the app. 'win' is the main window
        // of the app, it may not have focus since there might be other windows
        // on top (eg. a dialog window).
        WindowState transferFocusFromWin = win;
        if (mCurrentFocus != null && mCurrentFocus != win
                && mCurrentFocus.mAppToken == win.mAppToken) {
            transferFocusFromWin = mCurrentFocus;
        }
        if (!mInputManager.transferTouchFocus(
                transferFocusFromWin.mInputChannel, mTaskPositioner.mServerChannel)) {
            Slog.e(TAG_WM, "startPositioningLocked: Unable to transfer touch focus");
            mTaskPositioner.unregister();
            mTaskPositioner = null;
            mInputMonitor.updateInputWindowsLw(true /*force*/);
            return false;
        }

        mTaskPositioner.startDragLocked(win, resize, startX, startY);
        return true;
    }

    private void finishPositioning() {
        if (DEBUG_TASK_POSITIONING) {
            Slog.d(TAG_WM, "finishPositioning");
        }
        synchronized (mWindowMap) {
            if (mTaskPositioner != null) {
                mTaskPositioner.unregister();
                mTaskPositioner = null;
                mInputMonitor.updateInputWindowsLw(true /*force*/);
            }
        }
    }

    void adjustForImeIfNeeded(final DisplayContent displayContent) {
        final WindowState imeWin = mInputMethodWindow;
        final boolean imeVisible = imeWin != null && imeWin.isVisibleLw() && imeWin.isDisplayedLw()
                && !displayContent.mDividerControllerLocked.isImeHideRequested();
        final boolean dockVisible = isStackVisibleLocked(DOCKED_STACK_ID);
        final TaskStack imeTargetStack = getImeFocusStackLocked();
        final int imeDockSide = (dockVisible && imeTargetStack != null) ?
                imeTargetStack.getDockSide() : DOCKED_INVALID;
        final boolean imeOnTop = (imeDockSide == DOCKED_TOP);
        final boolean imeOnBottom = (imeDockSide == DOCKED_BOTTOM);
        final boolean dockMinimized = displayContent.mDividerControllerLocked.isMinimizedDock();
        final int imeHeight = mPolicy.getInputMethodWindowVisibleHeightLw();
        final boolean imeHeightChanged = imeVisible &&
                imeHeight != displayContent.mDividerControllerLocked.getImeHeightAdjustedFor();

        // The divider could be adjusted for IME position, or be thinner than usual,
        // or both. There are three possible cases:
        // - If IME is visible, and focus is on top, divider is not moved for IME but thinner.
        // - If IME is visible, and focus is on bottom, divider is moved for IME and thinner.
        // - If IME is not visible, divider is not moved and is normal width.

        if (imeVisible && dockVisible && (imeOnTop || imeOnBottom) && !dockMinimized) {
            final ArrayList<TaskStack> stacks = displayContent.getStacks();
            for (int i = stacks.size() - 1; i >= 0; --i) {
                final TaskStack stack = stacks.get(i);
                final boolean isDockedOnBottom = stack.getDockSide() == DOCKED_BOTTOM;
                if (stack.isVisibleLocked() && (imeOnBottom || isDockedOnBottom)) {
                    stack.setAdjustedForIme(imeWin, imeOnBottom && imeHeightChanged);
                } else {
                    stack.resetAdjustedForIme(false);
                }
            }
            displayContent.mDividerControllerLocked.setAdjustedForIme(
                    imeOnBottom /*ime*/, true /*divider*/, true /*animate*/, imeWin, imeHeight);
        } else {
            final ArrayList<TaskStack> stacks = displayContent.getStacks();
            for (int i = stacks.size() - 1; i >= 0; --i) {
                final TaskStack stack = stacks.get(i);
                stack.resetAdjustedForIme(!dockVisible);
            }
            displayContent.mDividerControllerLocked.setAdjustedForIme(
                    false /*ime*/, false /*divider*/, dockVisible /*animate*/, imeWin, imeHeight);
        }
    }

    // -------------------------------------------------------------
    // Drag and drop
    // -------------------------------------------------------------

    IBinder prepareDragSurface(IWindow window, SurfaceSession session,
            int flags, int width, int height, Surface outSurface) {
        if (DEBUG_DRAG) {
            Slog.d(TAG_WM, "prepare drag surface: w=" + width + " h=" + height
                    + " flags=" + Integer.toHexString(flags) + " win=" + window
                    + " asbinder=" + window.asBinder());
        }

        final int callerPid = Binder.getCallingPid();
        final int callerUid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        IBinder token = null;

        try {
            synchronized (mWindowMap) {
                try {
                    if (mDragState == null) {
                        // TODO(multi-display): support other displays
                        final DisplayContent displayContent = getDefaultDisplayContentLocked();
                        final Display display = displayContent.getDisplay();

                        SurfaceControl surface = new SurfaceControl(session, "drag surface",
                                width, height, PixelFormat.TRANSLUCENT, SurfaceControl.HIDDEN);
                        surface.setLayerStack(display.getLayerStack());
                        float alpha = 1;
                        if ((flags & View.DRAG_FLAG_OPAQUE) == 0) {
                            alpha = DRAG_SHADOW_ALPHA_TRANSPARENT;
                        }
                        surface.setAlpha(alpha);

                        if (SHOW_TRANSACTIONS) Slog.i(TAG_WM, "  DRAG "
                                + surface + ": CREATE");
                        outSurface.copyFrom(surface);
                        final IBinder winBinder = window.asBinder();
                        token = new Binder();
                        mDragState = new DragState(this, token, surface, flags, winBinder);
                        mDragState.mPid = callerPid;
                        mDragState.mUid = callerUid;
                        mDragState.mOriginalAlpha = alpha;
                        token = mDragState.mToken = new Binder();

                        // 5 second timeout for this window to actually begin the drag
                        mH.removeMessages(H.DRAG_START_TIMEOUT, winBinder);
                        Message msg = mH.obtainMessage(H.DRAG_START_TIMEOUT, winBinder);
                        mH.sendMessageDelayed(msg, 5000);
                    } else {
                        Slog.w(TAG_WM, "Drag already in progress");
                    }
                } catch (OutOfResourcesException e) {
                    Slog.e(TAG_WM, "Can't allocate drag surface w=" + width + " h=" + height, e);
                    if (mDragState != null) {
                        mDragState.reset();
                        mDragState = null;
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        return token;
    }

    // -------------------------------------------------------------
    // Input Events and Focus Management
    // -------------------------------------------------------------

    final InputMonitor mInputMonitor = new InputMonitor(this);
    private boolean mEventDispatchingEnabled;

    @Override
    public void pauseKeyDispatching(IBinder _token) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "pauseKeyDispatching()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized (mWindowMap) {
            WindowToken token = mTokenMap.get(_token);
            if (token != null) {
                mInputMonitor.pauseDispatchingLw(token);
            }
        }
    }

    @Override
    public void resumeKeyDispatching(IBinder _token) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "resumeKeyDispatching()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized (mWindowMap) {
            WindowToken token = mTokenMap.get(_token);
            if (token != null) {
                mInputMonitor.resumeDispatchingLw(token);
            }
        }
    }

    @Override
    public void setEventDispatching(boolean enabled) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setEventDispatching()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized (mWindowMap) {
            mEventDispatchingEnabled = enabled;
            if (mDisplayEnabled) {
                mInputMonitor.setEventDispatchingLw(enabled);
            }
        }
    }

    private WindowState getFocusedWindow() {
        synchronized (mWindowMap) {
            return getFocusedWindowLocked();
        }
    }

    private WindowState getFocusedWindowLocked() {
        return mCurrentFocus;
    }

    TaskStack getImeFocusStackLocked() {
        // Don't use mCurrentFocus.getStack() because it returns home stack for system windows.
        // Also don't use mInputMethodTarget's stack, because some window with FLAG_NOT_FOCUSABLE
        // and FLAG_ALT_FOCUSABLE_IM flags both set might be set to IME target so they're moved
        // to make room for IME, but the window is not the focused window that's taking input.
        return (mFocusedApp != null && mFocusedApp.mTask != null) ?
                mFocusedApp.mTask.mStack : null;
    }

    private void showAuditSafeModeNotification() {
        PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0,
                new Intent(Intent.ACTION_VIEW,
                           Uri.parse("https://support.google.com/nexus/answer/2852139")), 0);

        String title = mContext.getString(R.string.audit_safemode_notification);

        Notification notification = new Notification.Builder(mContext)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_warning)
                .setWhen(0)
                .setOngoing(true)
                .setTicker(title)
                .setLocalOnly(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setContentTitle(title)
                .setContentText(mContext.getString(R.string.audit_safemode_notification_details))
                .setContentIntent(pendingIntent)
                .build();

        NotificationManager notificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);

        notificationManager.notifyAsUser(null, R.string.audit_safemode_notification, notification,
                UserHandle.ALL);
    }

    public boolean detectSafeMode() {
        if (!mInputMonitor.waitForInputDevicesReady(
                INPUT_DEVICES_READY_FOR_SAFE_MODE_DETECTION_TIMEOUT_MILLIS)) {
            Slog.w(TAG_WM, "Devices still not ready after waiting "
                   + INPUT_DEVICES_READY_FOR_SAFE_MODE_DETECTION_TIMEOUT_MILLIS
                   + " milliseconds before attempting to detect safe mode.");
        }

        if (Settings.Global.getInt(
                mContext.getContentResolver(), Settings.Global.SAFE_BOOT_DISALLOWED, 0) != 0) {
            return false;
        }

        int menuState = mInputManager.getKeyCodeState(-1, InputDevice.SOURCE_ANY,
                KeyEvent.KEYCODE_MENU);
        int sState = mInputManager.getKeyCodeState(-1, InputDevice.SOURCE_ANY, KeyEvent.KEYCODE_S);
        int dpadState = mInputManager.getKeyCodeState(-1, InputDevice.SOURCE_DPAD,
                KeyEvent.KEYCODE_DPAD_CENTER);
        int trackballState = mInputManager.getScanCodeState(-1, InputDevice.SOURCE_TRACKBALL,
                InputManagerService.BTN_MOUSE);
        int volumeDownState = mInputManager.getKeyCodeState(-1, InputDevice.SOURCE_ANY,
                KeyEvent.KEYCODE_VOLUME_DOWN);
        mSafeMode = menuState > 0 || sState > 0 || dpadState > 0 || trackballState > 0
                || volumeDownState > 0;
        try {
            if (SystemProperties.getInt(ShutdownThread.REBOOT_SAFEMODE_PROPERTY, 0) != 0
                    || SystemProperties.getInt(ShutdownThread.RO_SAFEMODE_PROPERTY, 0) != 0) {
                int auditSafeMode = SystemProperties.getInt(ShutdownThread.AUDIT_SAFEMODE_PROPERTY, 0);

                if (auditSafeMode == 0) {
                    mSafeMode = true;
                    SystemProperties.set(ShutdownThread.REBOOT_SAFEMODE_PROPERTY, "");
                } else {
                    // stay in safe mode until we have updated to a newer build
                    int buildDate = SystemProperties.getInt(PROPERTY_BUILD_DATE_UTC, 0);

                    if (auditSafeMode >= buildDate) {
                        mSafeMode = true;
                        showAuditSafeModeNotification();
                    } else {
                        SystemProperties.set(ShutdownThread.REBOOT_SAFEMODE_PROPERTY, "");
                        SystemProperties.set(ShutdownThread.AUDIT_SAFEMODE_PROPERTY, "");
                    }
                }
            }
        } catch (IllegalArgumentException e) {
        }
        if (mSafeMode) {
            Log.i(TAG_WM, "SAFE MODE ENABLED (menu=" + menuState + " s=" + sState
                    + " dpad=" + dpadState + " trackball=" + trackballState + ")");
            SystemProperties.set(ShutdownThread.RO_SAFEMODE_PROPERTY, "1");
        } else {
            Log.i(TAG_WM, "SAFE MODE not enabled");
        }
        mPolicy.setSafeMode(mSafeMode);
        return mSafeMode;
    }

    public void displayReady() {
        for (Display display : mDisplays) {
            displayReady(display.getDisplayId());
        }

        synchronized(mWindowMap) {
            final DisplayContent displayContent = getDefaultDisplayContentLocked();
            readForcedDisplayPropertiesLocked(displayContent);
            mDisplayReady = true;
        }

        try {
            mActivityManager.updateConfiguration(null);
        } catch (RemoteException e) {
        }

        synchronized(mWindowMap) {
            mIsTouchDevice = mContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_TOUCHSCREEN);
            configureDisplayPolicyLocked(getDefaultDisplayContentLocked());
        }

        try {
            mActivityManager.updateConfiguration(null);
        } catch (RemoteException e) {
        }

        updateCircularDisplayMaskIfNeeded();
    }

    private void displayReady(int displayId) {
        synchronized(mWindowMap) {
            final DisplayContent displayContent = getDisplayContentLocked(displayId);
            if (displayContent != null) {
                mAnimator.addDisplayLocked(displayId);
                displayContent.initializeDisplayBaseInfo();
                if (displayContent.mTapDetector != null) {
                    displayContent.mTapDetector.init();
                }
            }
        }
    }

    public void systemReady() {
        mPolicy.systemReady();
    }

    // -------------------------------------------------------------
    // Async Handler
    // -------------------------------------------------------------

    final class H extends Handler {
        public static final int REPORT_FOCUS_CHANGE = 2;
        public static final int REPORT_LOSING_FOCUS = 3;
        public static final int DO_TRAVERSAL = 4;
        public static final int ADD_STARTING = 5;
        public static final int REMOVE_STARTING = 6;
        public static final int FINISHED_STARTING = 7;
        public static final int REPORT_APPLICATION_TOKEN_WINDOWS = 8;
        public static final int REPORT_APPLICATION_TOKEN_DRAWN = 9;
        public static final int WINDOW_FREEZE_TIMEOUT = 11;

        public static final int APP_TRANSITION_TIMEOUT = 13;
        public static final int PERSIST_ANIMATION_SCALE = 14;
        public static final int FORCE_GC = 15;
        public static final int ENABLE_SCREEN = 16;
        public static final int APP_FREEZE_TIMEOUT = 17;
        public static final int SEND_NEW_CONFIGURATION = 18;
        public static final int REPORT_WINDOWS_CHANGE = 19;
        public static final int DRAG_START_TIMEOUT = 20;
        public static final int DRAG_END_TIMEOUT = 21;
        public static final int REPORT_HARD_KEYBOARD_STATUS_CHANGE = 22;
        public static final int BOOT_TIMEOUT = 23;
        public static final int WAITING_FOR_DRAWN_TIMEOUT = 24;
        public static final int SHOW_STRICT_MODE_VIOLATION = 25;
        public static final int DO_ANIMATION_CALLBACK = 26;

        public static final int DO_DISPLAY_ADDED = 27;
        public static final int DO_DISPLAY_REMOVED = 28;
        public static final int DO_DISPLAY_CHANGED = 29;

        public static final int CLIENT_FREEZE_TIMEOUT = 30;
        public static final int TAP_OUTSIDE_TASK = 31;
        public static final int NOTIFY_ACTIVITY_DRAWN = 32;

        public static final int ALL_WINDOWS_DRAWN = 33;

        public static final int NEW_ANIMATOR_SCALE = 34;

        public static final int SHOW_CIRCULAR_DISPLAY_MASK = 35;
        public static final int SHOW_EMULATOR_DISPLAY_OVERLAY = 36;

        public static final int CHECK_IF_BOOT_ANIMATION_FINISHED = 37;
        public static final int RESET_ANR_MESSAGE = 38;
        public static final int WALLPAPER_DRAW_PENDING_TIMEOUT = 39;

        public static final int FINISH_TASK_POSITIONING = 40;

        public static final int UPDATE_DOCKED_STACK_DIVIDER = 41;

        public static final int RESIZE_STACK = 42;
        public static final int RESIZE_TASK = 43;

        public static final int TWO_FINGER_SCROLL_START = 44;

        public static final int WINDOW_REPLACEMENT_TIMEOUT = 46;

        public static final int NOTIFY_APP_TRANSITION_STARTING = 47;
        public static final int NOTIFY_APP_TRANSITION_CANCELLED = 48;
        public static final int NOTIFY_APP_TRANSITION_FINISHED = 49;
        public static final int NOTIFY_STARTING_WINDOW_DRAWN = 50;
        public static final int UPDATE_ANIMATION_SCALE = 51;
        public static final int WINDOW_REMOVE_TIMEOUT = 52;

        public static final int NOTIFY_DOCKED_STACK_MINIMIZED_CHANGED = 53;

        /**
         * Used to denote that an integer field in a message will not be used.
         */
        public static final int UNUSED = 0;

        @Override
        public void handleMessage(Message msg) {
            if (DEBUG_WINDOW_TRACE) {
                Slog.v(TAG_WM, "handleMessage: entry what=" + msg.what);
            }
            switch (msg.what) {
                case REPORT_FOCUS_CHANGE: {
                    WindowState lastFocus;
                    WindowState newFocus;

                    AccessibilityController accessibilityController = null;

                    synchronized(mWindowMap) {
                        // TODO(multidisplay): Accessibility supported only of default desiplay.
                        if (mAccessibilityController != null && getDefaultDisplayContentLocked()
                                .getDisplayId() == Display.DEFAULT_DISPLAY) {
                            accessibilityController = mAccessibilityController;
                        }

                        lastFocus = mLastFocus;
                        newFocus = mCurrentFocus;
                        if (lastFocus == newFocus) {
                            // Focus is not changing, so nothing to do.
                            return;
                        }
                        mLastFocus = newFocus;
                        if (DEBUG_FOCUS_LIGHT) Slog.i(TAG_WM, "Focus moving from " + lastFocus +
                                " to " + newFocus);
                        if (newFocus != null && lastFocus != null
                                && !newFocus.isDisplayedLw()) {
                            //Slog.i(TAG_WM, "Delaying loss of focus...");
                            mLosingFocus.add(lastFocus);
                            lastFocus = null;
                        }
                    }

                    // First notify the accessibility manager for the change so it has
                    // the windows before the newly focused one starts firing eventgs.
                    if (accessibilityController != null) {
                        accessibilityController.onWindowFocusChangedNotLocked();
                    }

                    //System.out.println("Changing focus from " + lastFocus
                    //                   + " to " + newFocus);
                    if (newFocus != null) {
                        if (DEBUG_FOCUS_LIGHT) Slog.i(TAG_WM, "Gaining focus: " + newFocus);
                        newFocus.reportFocusChangedSerialized(true, mInTouchMode);
                        notifyFocusChanged();
                    }

                    if (lastFocus != null) {
                        if (DEBUG_FOCUS_LIGHT) Slog.i(TAG_WM, "Losing focus: " + lastFocus);
                        lastFocus.reportFocusChangedSerialized(false, mInTouchMode);
                    }
                } break;

                case REPORT_LOSING_FOCUS: {
                    ArrayList<WindowState> losers;

                    synchronized(mWindowMap) {
                        losers = mLosingFocus;
                        mLosingFocus = new ArrayList<WindowState>();
                    }

                    final int N = losers.size();
                    for (int i=0; i<N; i++) {
                        if (DEBUG_FOCUS_LIGHT) Slog.i(TAG_WM, "Losing delayed focus: " +
                                losers.get(i));
                        losers.get(i).reportFocusChangedSerialized(false, mInTouchMode);
                    }
                } break;

                case DO_TRAVERSAL: {
                    synchronized(mWindowMap) {
                        mWindowPlacerLocked.performSurfacePlacement();
                    }
                } break;

                case ADD_STARTING: {
                    final AppWindowToken wtoken = (AppWindowToken)msg.obj;
                    final StartingData sd = wtoken.startingData;

                    if (sd == null) {
                        // Animation has been canceled... do nothing.
                        return;
                    }

                    if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Add starting "
                            + wtoken + ": pkg=" + sd.pkg);

                    View view = null;
                    try {
                        final Configuration overrideConfig = wtoken != null && wtoken.mTask != null
                                ? wtoken.mTask.mOverrideConfig : null;
                        view = mPolicy.addStartingWindow(wtoken.token, sd.pkg, sd.theme,
                            sd.compatInfo, sd.nonLocalizedLabel, sd.labelRes, sd.icon, sd.logo,
                            sd.windowFlags, overrideConfig);
                    } catch (Exception e) {
                        Slog.w(TAG_WM, "Exception when adding starting window", e);
                    }

                    if (view != null) {
                        boolean abort = false;

                        synchronized(mWindowMap) {
                            if (wtoken.removed || wtoken.startingData == null) {
                                // If the window was successfully added, then
                                // we need to remove it.
                                if (wtoken.startingWindow != null) {
                                    if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM,
                                            "Aborted starting " + wtoken
                                            + ": removed=" + wtoken.removed
                                            + " startingData=" + wtoken.startingData);
                                    wtoken.startingWindow = null;
                                    wtoken.startingData = null;
                                    abort = true;
                                }
                            } else {
                                wtoken.startingView = view;
                            }
                            if (DEBUG_STARTING_WINDOW && !abort) Slog.v(TAG_WM,
                                    "Added starting " + wtoken
                                    + ": startingWindow="
                                    + wtoken.startingWindow + " startingView="
                                    + wtoken.startingView);
                        }

                        if (abort) {
                            try {
                                mPolicy.removeStartingWindow(wtoken.token, view);
                            } catch (Exception e) {
                                Slog.w(TAG_WM, "Exception when removing starting window", e);
                            }
                        }
                    }
                } break;

                case REMOVE_STARTING: {
                    final AppWindowToken wtoken = (AppWindowToken)msg.obj;
                    IBinder token = null;
                    View view = null;
                    synchronized (mWindowMap) {
                        if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM, "Remove starting "
                                + wtoken + ": startingWindow="
                                + wtoken.startingWindow + " startingView="
                                + wtoken.startingView);
                        if (wtoken.startingWindow != null) {
                            view = wtoken.startingView;
                            token = wtoken.token;
                            wtoken.startingData = null;
                            wtoken.startingView = null;
                            wtoken.startingWindow = null;
                            wtoken.startingDisplayed = false;
                        }
                    }
                    if (view != null) {
                        try {
                            mPolicy.removeStartingWindow(token, view);
                        } catch (Exception e) {
                            Slog.w(TAG_WM, "Exception when removing starting window", e);
                        }
                    }
                } break;

                case FINISHED_STARTING: {
                    IBinder token = null;
                    View view = null;
                    while (true) {
                        synchronized (mWindowMap) {
                            final int N = mFinishedStarting.size();
                            if (N <= 0) {
                                break;
                            }
                            AppWindowToken wtoken = mFinishedStarting.remove(N-1);

                            if (DEBUG_STARTING_WINDOW) Slog.v(TAG_WM,
                                    "Finished starting " + wtoken
                                    + ": startingWindow=" + wtoken.startingWindow
                                    + " startingView=" + wtoken.startingView);

                            if (wtoken.startingWindow == null) {
                                continue;
                            }

                            view = wtoken.startingView;
                            token = wtoken.token;
                            wtoken.startingData = null;
                            wtoken.startingView = null;
                            wtoken.startingWindow = null;
                            wtoken.startingDisplayed = false;
                        }

                        try {
                            mPolicy.removeStartingWindow(token, view);
                        } catch (Exception e) {
                            Slog.w(TAG_WM, "Exception when removing starting window", e);
                        }
                    }
                } break;

                case REPORT_APPLICATION_TOKEN_DRAWN: {
                    final AppWindowToken wtoken = (AppWindowToken)msg.obj;

                    try {
                        if (DEBUG_VISIBILITY) Slog.v(
                                TAG_WM, "Reporting drawn in " + wtoken);
                        wtoken.appToken.windowsDrawn();
                    } catch (RemoteException ex) {
                    }
                } break;

                case REPORT_APPLICATION_TOKEN_WINDOWS: {
                    final AppWindowToken wtoken = (AppWindowToken)msg.obj;

                    boolean nowVisible = msg.arg1 != 0;
                    boolean nowGone = msg.arg2 != 0;

                    try {
                        if (DEBUG_VISIBILITY) Slog.v(
                                TAG_WM, "Reporting visible in " + wtoken
                                + " visible=" + nowVisible
                                + " gone=" + nowGone);
                        if (nowVisible) {
                            wtoken.appToken.windowsVisible();
                        } else {
                            wtoken.appToken.windowsGone();
                        }
                    } catch (RemoteException ex) {
                    }
                } break;

                case WINDOW_FREEZE_TIMEOUT: {
                    // TODO(multidisplay): Can non-default displays rotate?
                    synchronized (mWindowMap) {
                        Slog.w(TAG_WM, "Window freeze timeout expired.");
                        mWindowsFreezingScreen = WINDOWS_FREEZING_SCREENS_TIMEOUT;
                        final WindowList windows = getDefaultWindowListLocked();
                        int i = windows.size();
                        while (i > 0) {
                            i--;
                            WindowState w = windows.get(i);
                            if (w.mOrientationChanging) {
                                w.mOrientationChanging = false;
                                w.mLastFreezeDuration = (int)(SystemClock.elapsedRealtime()
                                        - mDisplayFreezeTime);
                                Slog.w(TAG_WM, "Force clearing orientation change: " + w);
                            }
                        }
                        mWindowPlacerLocked.performSurfacePlacement();
                    }
                    break;
                }

                case APP_TRANSITION_TIMEOUT: {
                    synchronized (mWindowMap) {
                        if (mAppTransition.isTransitionSet() || !mOpeningApps.isEmpty()
                                    || !mClosingApps.isEmpty()) {
                            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG_WM, "*** APP TRANSITION TIMEOUT."
                                    + " isTransitionSet()=" + mAppTransition.isTransitionSet()
                                    + " mOpeningApps.size()=" + mOpeningApps.size()
                                    + " mClosingApps.size()=" + mClosingApps.size());
                            mAppTransition.setTimeout();
                            mWindowPlacerLocked.performSurfacePlacement();
                        }
                    }
                    break;
                }

                case PERSIST_ANIMATION_SCALE: {
                    Settings.Global.putFloat(mContext.getContentResolver(),
                            Settings.Global.WINDOW_ANIMATION_SCALE, mWindowAnimationScaleSetting);
                    Settings.Global.putFloat(mContext.getContentResolver(),
                            Settings.Global.TRANSITION_ANIMATION_SCALE,
                            mTransitionAnimationScaleSetting);
                    Settings.Global.putFloat(mContext.getContentResolver(),
                            Settings.Global.ANIMATOR_DURATION_SCALE, mAnimatorDurationScaleSetting);
                    break;
                }

                case UPDATE_ANIMATION_SCALE: {
                    @UpdateAnimationScaleMode
                    final int mode = msg.arg1;
                    switch (mode) {
                        case WINDOW_ANIMATION_SCALE: {
                            mWindowAnimationScaleSetting = Settings.Global.getFloat(
                                    mContext.getContentResolver(),
                                    Settings.Global.WINDOW_ANIMATION_SCALE,
                                    mWindowAnimationScaleSetting);
                            break;
                        }
                        case TRANSITION_ANIMATION_SCALE: {
                            mTransitionAnimationScaleSetting = Settings.Global.getFloat(
                                    mContext.getContentResolver(),
                                    Settings.Global.TRANSITION_ANIMATION_SCALE,
                                    mTransitionAnimationScaleSetting);
                            break;
                        }
                        case ANIMATION_DURATION_SCALE: {
                            mAnimatorDurationScaleSetting = Settings.Global.getFloat(
                                    mContext.getContentResolver(),
                                    Settings.Global.ANIMATOR_DURATION_SCALE,
                                    mAnimatorDurationScaleSetting);
                            dispatchNewAnimatorScaleLocked(null);
                            break;
                        }
                    }
                    break;
                }

                case FORCE_GC: {
                    synchronized (mWindowMap) {
                        // Since we're holding both mWindowMap and mAnimator we don't need to
                        // hold mAnimator.mLayoutToAnim.
                        if (mAnimator.isAnimating() || mAnimationScheduled) {
                            // If we are animating, don't do the gc now but
                            // delay a bit so we don't interrupt the animation.
                            sendEmptyMessageDelayed(H.FORCE_GC, 2000);
                            return;
                        }
                        // If we are currently rotating the display, it will
                        // schedule a new message when done.
                        if (mDisplayFrozen) {
                            return;
                        }
                    }
                    Runtime.getRuntime().gc();
                    break;
                }

                case ENABLE_SCREEN: {
                    performEnableScreen();
                    break;
                }

                case APP_FREEZE_TIMEOUT: {
                    synchronized (mWindowMap) {
                        Slog.w(TAG_WM, "App freeze timeout expired.");
                        mWindowsFreezingScreen = WINDOWS_FREEZING_SCREENS_TIMEOUT;
                        final int numStacks = mStackIdToStack.size();
                        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
                            final TaskStack stack = mStackIdToStack.valueAt(stackNdx);
                            final ArrayList<Task> tasks = stack.getTasks();
                            for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                                AppTokenList tokens = tasks.get(taskNdx).mAppTokens;
                                for (int tokenNdx = tokens.size() - 1; tokenNdx >= 0; --tokenNdx) {
                                    AppWindowToken tok = tokens.get(tokenNdx);
                                    if (tok.mAppAnimator.freezingScreen) {
                                        Slog.w(TAG_WM, "Force clearing freeze: " + tok);
                                        unsetAppFreezingScreenLocked(tok, true, true);
                                    }
                                }
                            }
                        }
                    }
                    break;
                }

                case CLIENT_FREEZE_TIMEOUT: {
                    synchronized (mWindowMap) {
                        if (mClientFreezingScreen) {
                            mClientFreezingScreen = false;
                            mLastFinishedFreezeSource = "client-timeout";
                            stopFreezingDisplayLocked();
                        }
                    }
                    break;
                }

                case SEND_NEW_CONFIGURATION: {
                    removeMessages(SEND_NEW_CONFIGURATION);
                    sendNewConfiguration();
                    break;
                }

                case REPORT_WINDOWS_CHANGE: {
                    if (mWindowsChanged) {
                        synchronized (mWindowMap) {
                            mWindowsChanged = false;
                        }
                        notifyWindowsChanged();
                    }
                    break;
                }

                case DRAG_START_TIMEOUT: {
                    IBinder win = (IBinder)msg.obj;
                    if (DEBUG_DRAG) {
                        Slog.w(TAG_WM, "Timeout starting drag by win " + win);
                    }
                    synchronized (mWindowMap) {
                        // !!! TODO: ANR the app that has failed to start the drag in time
                        if (mDragState != null) {
                            mDragState.unregister();
                            mInputMonitor.updateInputWindowsLw(true /*force*/);
                            mDragState.reset();
                            mDragState = null;
                        }
                    }
                    break;
                }

                case DRAG_END_TIMEOUT: {
                    IBinder win = (IBinder)msg.obj;
                    if (DEBUG_DRAG) {
                        Slog.w(TAG_WM, "Timeout ending drag to win " + win);
                    }
                    synchronized (mWindowMap) {
                        // !!! TODO: ANR the drag-receiving app
                        if (mDragState != null) {
                            mDragState.mDragResult = false;
                            mDragState.endDragLw();
                        }
                    }
                    break;
                }

                case REPORT_HARD_KEYBOARD_STATUS_CHANGE: {
                    notifyHardKeyboardStatusChange();
                    break;
                }

                case BOOT_TIMEOUT: {
                    performBootTimeout();
                    break;
                }

                case WAITING_FOR_DRAWN_TIMEOUT: {
                    Runnable callback = null;
                    synchronized (mWindowMap) {
                        Slog.w(TAG_WM, "Timeout waiting for drawn: undrawn=" + mWaitingForDrawn);
                        mWaitingForDrawn.clear();
                        callback = mWaitingForDrawnCallback;
                        mWaitingForDrawnCallback = null;
                    }
                    if (callback != null) {
                        callback.run();
                    }
                    break;
                }

                case SHOW_STRICT_MODE_VIOLATION: {
                    showStrictModeViolation(msg.arg1, msg.arg2);
                    break;
                }

                case SHOW_CIRCULAR_DISPLAY_MASK: {
                    showCircularMask(msg.arg1 == 1);
                    break;
                }

                case SHOW_EMULATOR_DISPLAY_OVERLAY: {
                    showEmulatorDisplayOverlay();
                    break;
                }

                case DO_ANIMATION_CALLBACK: {
                    try {
                        ((IRemoteCallback)msg.obj).sendResult(null);
                    } catch (RemoteException e) {
                    }
                    break;
                }

                case DO_DISPLAY_ADDED:
                    handleDisplayAdded(msg.arg1);
                    break;

                case DO_DISPLAY_REMOVED:
                    synchronized (mWindowMap) {
                        handleDisplayRemovedLocked(msg.arg1);
                    }
                    break;

                case DO_DISPLAY_CHANGED:
                    synchronized (mWindowMap) {
                        handleDisplayChangedLocked(msg.arg1);
                    }
                    break;

                case TWO_FINGER_SCROLL_START: {
                    startScrollingTask((DisplayContent)msg.obj, msg.arg1, msg.arg2);
                }
                break;

                case TAP_OUTSIDE_TASK: {
                    handleTapOutsideTask((DisplayContent)msg.obj, msg.arg1, msg.arg2);
                }
                break;

                case FINISH_TASK_POSITIONING: {
                    finishPositioning();
                }
                break;

                case NOTIFY_ACTIVITY_DRAWN:
                    try {
                        mActivityManager.notifyActivityDrawn((IBinder) msg.obj);
                    } catch (RemoteException e) {
                    }
                    break;
                case ALL_WINDOWS_DRAWN: {
                    Runnable callback;
                    synchronized (mWindowMap) {
                        callback = mWaitingForDrawnCallback;
                        mWaitingForDrawnCallback = null;
                    }
                    if (callback != null) {
                        callback.run();
                    }
                }
                case NEW_ANIMATOR_SCALE: {
                    float scale = getCurrentAnimatorScale();
                    ValueAnimator.setDurationScale(scale);
                    Session session = (Session)msg.obj;
                    if (session != null) {
                        try {
                            session.mCallback.onAnimatorScaleChanged(scale);
                        } catch (RemoteException e) {
                        }
                    } else {
                        ArrayList<IWindowSessionCallback> callbacks
                                = new ArrayList<IWindowSessionCallback>();
                        synchronized (mWindowMap) {
                            for (int i=0; i<mSessions.size(); i++) {
                                callbacks.add(mSessions.valueAt(i).mCallback);
                            }

                        }
                        for (int i=0; i<callbacks.size(); i++) {
                            try {
                                callbacks.get(i).onAnimatorScaleChanged(scale);
                            } catch (RemoteException e) {
                            }
                        }
                    }
                }
                break;
                case CHECK_IF_BOOT_ANIMATION_FINISHED: {
                    final boolean bootAnimationComplete;
                    synchronized (mWindowMap) {
                        if (DEBUG_BOOT) Slog.i(TAG_WM, "CHECK_IF_BOOT_ANIMATION_FINISHED:");
                        bootAnimationComplete = checkBootAnimationCompleteLocked();
                    }
                    if (bootAnimationComplete) {
                        performEnableScreen();
                    }
                }
                break;
                case RESET_ANR_MESSAGE: {
                    synchronized (mWindowMap) {
                        mLastANRState = null;
                    }
                }
                break;
                case WALLPAPER_DRAW_PENDING_TIMEOUT: {
                    synchronized (mWindowMap) {
                        if (mWallpaperControllerLocked.processWallpaperDrawPendingTimeout()) {
                            mWindowPlacerLocked.performSurfacePlacement();
                        }
                    }
                }
                case UPDATE_DOCKED_STACK_DIVIDER: {
                    synchronized (mWindowMap) {
                        final DisplayContent displayContent = getDefaultDisplayContentLocked();
                        displayContent.getDockedDividerController().reevaluateVisibility(false);
                        adjustForImeIfNeeded(displayContent);
                    }
                }
                break;
                case RESIZE_TASK: {
                    try {
                        mActivityManager.resizeTask(msg.arg1, (Rect) msg.obj, msg.arg2);
                    } catch (RemoteException e) {
                        // This will not happen since we are in the same process.
                    }
                }
                break;
                case RESIZE_STACK: {
                    try {
                        mActivityManager.resizeStack(
                                msg.arg1, (Rect) msg.obj, msg.arg2 == 1, false, false, -1);
                    } catch (RemoteException e) {
                        // This will not happen since we are in the same process.
                    }
                }
                break;
                case WINDOW_REPLACEMENT_TIMEOUT: {
                    synchronized (mWindowMap) {
                        for (int i = mReplacingWindowTimeouts.size() - 1; i >= 0; i--) {
                            final AppWindowToken token = mReplacingWindowTimeouts.get(i);
                            token.clearTimedoutReplacesLocked();
                        }
                        mReplacingWindowTimeouts.clear();
                    }
                }
                case NOTIFY_APP_TRANSITION_STARTING: {
                    mAmInternal.notifyAppTransitionStarting(msg.arg1);
                }
                break;
                case NOTIFY_APP_TRANSITION_CANCELLED: {
                    mAmInternal.notifyAppTransitionCancelled();
                }
                break;
                case NOTIFY_APP_TRANSITION_FINISHED: {
                    mAmInternal.notifyAppTransitionFinished();
                }
                break;
                case NOTIFY_STARTING_WINDOW_DRAWN: {
                    mAmInternal.notifyStartingWindowDrawn();
                }
                break;
                case WINDOW_REMOVE_TIMEOUT: {
                    final WindowState window = (WindowState) msg.obj;
                    synchronized(mWindowMap) {
                        // TODO: This is all about fixing b/21693547
                        // where partially initialized Toasts get stuck
                        // around and keep the screen on. We'd like
                        // to just remove the toast...but this can cause clients
                        // who miss the timeout due to normal circumstances (e.g.
                        // running under debugger) to crash (b/29105388). The windows will
                        // eventually be removed when the client process finishes.
                        // The best we can do for now is remove the FLAG_KEEP_SCREEN_ON
                        // and prevent the symptoms of b/21693547.
                        window.mAttrs.flags &= ~FLAG_KEEP_SCREEN_ON;
                        window.setDisplayLayoutNeeded();
                        mWindowPlacerLocked.performSurfacePlacement();
                    }
                }
                break;
                case NOTIFY_DOCKED_STACK_MINIMIZED_CHANGED: {
                    mAmInternal.notifyDockedStackMinimizedChanged(msg.arg1 == 1);
                }
                break;
            }
            if (DEBUG_WINDOW_TRACE) {
                Slog.v(TAG_WM, "handleMessage: exit");
            }
        }
    }

    void destroyPreservedSurfaceLocked() {
        for (int i = mDestroyPreservedSurface.size() - 1; i >= 0 ; i--) {
            final WindowState w = mDestroyPreservedSurface.get(i);
            w.mWinAnimator.destroyPreservedSurfaceLocked();
        }
        mDestroyPreservedSurface.clear();
    }

    void stopUsingSavedSurfaceLocked() {
        for (int i = mFinishedEarlyAnim.size() - 1; i >= 0 ; i--) {
            final AppWindowToken wtoken = mFinishedEarlyAnim.get(i);
            wtoken.stopUsingSavedSurfaceLocked();
        }
        mFinishedEarlyAnim.clear();
    }

    // -------------------------------------------------------------
    // IWindowManager API
    // -------------------------------------------------------------

    @Override
    public IWindowSession openSession(IWindowSessionCallback callback, IInputMethodClient client,
            IInputContext inputContext) {
        if (client == null) throw new IllegalArgumentException("null client");
        if (inputContext == null) throw new IllegalArgumentException("null inputContext");
        Session session = new Session(this, callback, client, inputContext);
        return session;
    }

    @Override
    public boolean inputMethodClientHasFocus(IInputMethodClient client) {
        synchronized (mWindowMap) {
            // The focus for the client is the window immediately below
            // where we would place the input method window.
            int idx = findDesiredInputMethodWindowIndexLocked(false);
            if (idx > 0) {
                // TODO(multidisplay): IMEs are only supported on the default display.
                WindowState imFocus = getDefaultWindowListLocked().get(idx-1);
                if (DEBUG_INPUT_METHOD) {
                    Slog.i(TAG_WM, "Desired input method target: " + imFocus);
                    Slog.i(TAG_WM, "Current focus: " + mCurrentFocus);
                    Slog.i(TAG_WM, "Last focus: " + mLastFocus);
                }
                if (imFocus != null) {
                    // This may be a starting window, in which case we still want
                    // to count it as okay.
                    if (imFocus.mAttrs.type == LayoutParams.TYPE_APPLICATION_STARTING
                            && imFocus.mAppToken != null) {
                        // The client has definitely started, so it really should
                        // have a window in this app token.  Let's look for it.
                        for (int i=0; i<imFocus.mAppToken.windows.size(); i++) {
                            WindowState w = imFocus.mAppToken.windows.get(i);
                            if (w != imFocus) {
                                Log.i(TAG_WM, "Switching to real app window: " + w);
                                imFocus = w;
                                break;
                            }
                        }
                    }
                    if (DEBUG_INPUT_METHOD) {
                        Slog.i(TAG_WM, "IM target client: " + imFocus.mSession.mClient);
                        if (imFocus.mSession.mClient != null) {
                            Slog.i(TAG_WM, "IM target client binder: "
                                    + imFocus.mSession.mClient.asBinder());
                            Slog.i(TAG_WM, "Requesting client binder: " + client.asBinder());
                        }
                    }
                    if (imFocus.mSession.mClient != null &&
                            imFocus.mSession.mClient.asBinder() == client.asBinder()) {
                        return true;
                    }
                }
            }

            // Okay, how about this...  what is the current focus?
            // It seems in some cases we may not have moved the IM
            // target window, such as when it was in a pop-up window,
            // so let's also look at the current focus.  (An example:
            // go to Gmail, start searching so the keyboard goes up,
            // press home.  Sometimes the IME won't go down.)
            // Would be nice to fix this more correctly, but it's
            // way at the end of a release, and this should be good enough.
            if (mCurrentFocus != null && mCurrentFocus.mSession.mClient != null
                    && mCurrentFocus.mSession.mClient.asBinder() == client.asBinder()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void getInitialDisplaySize(int displayId, Point size) {
        synchronized (mWindowMap) {
            final DisplayContent displayContent = getDisplayContentLocked(displayId);
            if (displayContent != null && displayContent.hasAccess(Binder.getCallingUid())) {
                size.x = displayContent.mInitialDisplayWidth;
                size.y = displayContent.mInitialDisplayHeight;
            }
        }
    }

    @Override
    public void getBaseDisplaySize(int displayId, Point size) {
        synchronized (mWindowMap) {
            final DisplayContent displayContent = getDisplayContentLocked(displayId);
            if (displayContent != null && displayContent.hasAccess(Binder.getCallingUid())) {
                size.x = displayContent.mBaseDisplayWidth;
                size.y = displayContent.mBaseDisplayHeight;
            }
        }
    }

    @Override
    public void setForcedDisplaySize(int displayId, int width, int height) {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS) !=
                PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold permission " +
                    android.Manifest.permission.WRITE_SECURE_SETTINGS);
        }
        if (displayId != Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException("Can only set the default display");
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized(mWindowMap) {
                // Set some sort of reasonable bounds on the size of the display that we
                // will try to emulate.
                final int MIN_WIDTH = 200;
                final int MIN_HEIGHT = 200;
                final int MAX_SCALE = 2;
                final DisplayContent displayContent = getDisplayContentLocked(displayId);
                if (displayContent != null) {
                    width = Math.min(Math.max(width, MIN_WIDTH),
                            displayContent.mInitialDisplayWidth * MAX_SCALE);
                    height = Math.min(Math.max(height, MIN_HEIGHT),
                            displayContent.mInitialDisplayHeight * MAX_SCALE);
                    setForcedDisplaySizeLocked(displayContent, width, height);
                    Settings.Global.putString(mContext.getContentResolver(),
                            Settings.Global.DISPLAY_SIZE_FORCED, width + "," + height);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void setForcedDisplayScalingMode(int displayId, int mode) {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS) !=
                PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold permission " +
                    android.Manifest.permission.WRITE_SECURE_SETTINGS);
        }
        if (displayId != Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException("Can only set the default display");
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized(mWindowMap) {
                final DisplayContent displayContent = getDisplayContentLocked(displayId);
                if (displayContent != null) {
                    if (mode < 0 || mode > 1) {
                        mode = 0;
                    }
                    setForcedDisplayScalingModeLocked(displayContent, mode);
                    Settings.Global.putInt(mContext.getContentResolver(),
                            Settings.Global.DISPLAY_SCALING_FORCE, mode);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setForcedDisplayScalingModeLocked(DisplayContent displayContent, int mode) {
        Slog.i(TAG_WM, "Using display scaling mode: " + (mode == 0 ? "auto" : "off"));
        displayContent.mDisplayScalingDisabled = (mode != 0);
        reconfigureDisplayLocked(displayContent);
    }

    private void readForcedDisplayPropertiesLocked(final DisplayContent displayContent) {
        // Display size.
        String sizeStr = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.DISPLAY_SIZE_FORCED);
        if (sizeStr == null || sizeStr.length() == 0) {
            sizeStr = SystemProperties.get(SIZE_OVERRIDE, null);
        }
        if (sizeStr != null && sizeStr.length() > 0) {
            final int pos = sizeStr.indexOf(',');
            if (pos > 0 && sizeStr.lastIndexOf(',') == pos) {
                int width, height;
                try {
                    width = Integer.parseInt(sizeStr.substring(0, pos));
                    height = Integer.parseInt(sizeStr.substring(pos+1));
                    if (displayContent.mBaseDisplayWidth != width
                            || displayContent.mBaseDisplayHeight != height) {
                        Slog.i(TAG_WM, "FORCED DISPLAY SIZE: " + width + "x" + height);
                        displayContent.mBaseDisplayWidth = width;
                        displayContent.mBaseDisplayHeight = height;
                    }
                } catch (NumberFormatException ex) {
                }
            }
        }

        // Display density.
        final int density = getForcedDisplayDensityForUserLocked(mCurrentUserId);
        if (density != 0) {
            displayContent.mBaseDisplayDensity = density;
        }

        // Display scaling mode.
        int mode = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DISPLAY_SCALING_FORCE, 0);
        if (mode != 0) {
            Slog.i(TAG_WM, "FORCED DISPLAY SCALING DISABLED");
            displayContent.mDisplayScalingDisabled = true;
        }
    }

    // displayContent must not be null
    private void setForcedDisplaySizeLocked(DisplayContent displayContent, int width, int height) {
        Slog.i(TAG_WM, "Using new display size: " + width + "x" + height);
        displayContent.mBaseDisplayWidth = width;
        displayContent.mBaseDisplayHeight = height;
        reconfigureDisplayLocked(displayContent);
    }

    @Override
    public void clearForcedDisplaySize(int displayId) {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS) !=
                PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold permission " +
                    android.Manifest.permission.WRITE_SECURE_SETTINGS);
        }
        if (displayId != Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException("Can only set the default display");
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized(mWindowMap) {
                final DisplayContent displayContent = getDisplayContentLocked(displayId);
                if (displayContent != null) {
                    setForcedDisplaySizeLocked(displayContent, displayContent.mInitialDisplayWidth,
                            displayContent.mInitialDisplayHeight);
                    Settings.Global.putString(mContext.getContentResolver(),
                            Settings.Global.DISPLAY_SIZE_FORCED, "");
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public int getInitialDisplayDensity(int displayId) {
        synchronized (mWindowMap) {
            final DisplayContent displayContent = getDisplayContentLocked(displayId);
            if (displayContent != null && displayContent.hasAccess(Binder.getCallingUid())) {
                return displayContent.mInitialDisplayDensity;
            }
        }
        return -1;
    }

    @Override
    public int getBaseDisplayDensity(int displayId) {
        synchronized (mWindowMap) {
            final DisplayContent displayContent = getDisplayContentLocked(displayId);
            if (displayContent != null && displayContent.hasAccess(Binder.getCallingUid())) {
                return displayContent.mBaseDisplayDensity;
            }
        }
        return -1;
    }

    @Override
    public void setForcedDisplayDensity(int displayId, int density) {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS) !=
                PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold permission " +
                    android.Manifest.permission.WRITE_SECURE_SETTINGS);
        }
        if (displayId != Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException("Can only set the default display");
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized(mWindowMap) {
                final DisplayContent displayContent = getDisplayContentLocked(displayId);
                if (displayContent != null) {
                    setForcedDisplayDensityLocked(displayContent, density);
                    Settings.Secure.putStringForUser(mContext.getContentResolver(),
                            Settings.Secure.DISPLAY_DENSITY_FORCED,
                            Integer.toString(density), mCurrentUserId);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void clearForcedDisplayDensity(int displayId) {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS) !=
                PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold permission " +
                    android.Manifest.permission.WRITE_SECURE_SETTINGS);
        }
        if (displayId != Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException("Can only set the default display");
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized(mWindowMap) {
                final DisplayContent displayContent = getDisplayContentLocked(displayId);
                if (displayContent != null) {
                    setForcedDisplayDensityLocked(displayContent,
                            displayContent.mInitialDisplayDensity);
                    Settings.Secure.putStringForUser(mContext.getContentResolver(),
                            Settings.Secure.DISPLAY_DENSITY_FORCED, "", mCurrentUserId);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * @param userId the ID of the user
     * @return the forced display density for the specified user, if set, or
     *         {@code 0} if not set
     */
    private int getForcedDisplayDensityForUserLocked(int userId) {
        String densityStr = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.DISPLAY_DENSITY_FORCED, userId);
        if (densityStr == null || densityStr.length() == 0) {
            densityStr = SystemProperties.get(DENSITY_OVERRIDE, null);
        }
        if (densityStr != null && densityStr.length() > 0) {
            try {
                return Integer.parseInt(densityStr);
            } catch (NumberFormatException ex) {
            }
        }
        return 0;
    }

    /**
     * Forces the given display to the use the specified density.
     *
     * @param displayContent the display to modify
     * @param density the density in DPI to use
     */
    private void setForcedDisplayDensityLocked(@NonNull DisplayContent displayContent,
            int density) {
        displayContent.mBaseDisplayDensity = density;
        reconfigureDisplayLocked(displayContent);
    }

    // displayContent must not be null
    private void reconfigureDisplayLocked(DisplayContent displayContent) {
        // TODO: Multidisplay: for now only use with default display.
        if (!mDisplayReady) {
            return;
        }
        configureDisplayPolicyLocked(displayContent);
        displayContent.layoutNeeded = true;

        boolean configChanged = updateOrientationFromAppTokensLocked(false);
        mTempConfiguration.setToDefaults();
        mTempConfiguration.updateFrom(mCurConfiguration);
        computeScreenConfigurationLocked(mTempConfiguration);
        configChanged |= mCurConfiguration.diff(mTempConfiguration) != 0;

        if (configChanged) {
            mWaitingForConfig = true;
            startFreezingDisplayLocked(false, 0, 0);
            mH.sendEmptyMessage(H.SEND_NEW_CONFIGURATION);
            if (!mReconfigureOnConfigurationChanged.contains(displayContent)) {
                mReconfigureOnConfigurationChanged.add(displayContent);
            }
        }

        mWindowPlacerLocked.performSurfacePlacement();
    }

    private void configureDisplayPolicyLocked(DisplayContent displayContent) {
        mPolicy.setInitialDisplaySize(displayContent.getDisplay(),
                displayContent.mBaseDisplayWidth,
                displayContent.mBaseDisplayHeight,
                displayContent.mBaseDisplayDensity);

        DisplayInfo displayInfo = displayContent.getDisplayInfo();
        mPolicy.setDisplayOverscan(displayContent.getDisplay(),
                displayInfo.overscanLeft, displayInfo.overscanTop,
                displayInfo.overscanRight, displayInfo.overscanBottom);
    }

    @Override
    public void setOverscan(int displayId, int left, int top, int right, int bottom) {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS) !=
                PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold permission " +
                    android.Manifest.permission.WRITE_SECURE_SETTINGS);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized(mWindowMap) {
                DisplayContent displayContent = getDisplayContentLocked(displayId);
                if (displayContent != null) {
                    setOverscanLocked(displayContent, left, top, right, bottom);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setOverscanLocked(DisplayContent displayContent,
            int left, int top, int right, int bottom) {
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        displayInfo.overscanLeft = left;
        displayInfo.overscanTop = top;
        displayInfo.overscanRight = right;
        displayInfo.overscanBottom = bottom;

        mDisplaySettings.setOverscanLocked(displayInfo.uniqueId, displayInfo.name, left, top,
                right, bottom);
        mDisplaySettings.writeSettingsLocked();

        reconfigureDisplayLocked(displayContent);
    }

    // -------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------

    final WindowState windowForClientLocked(Session session, IWindow client,
            boolean throwOnError) {
        return windowForClientLocked(session, client.asBinder(), throwOnError);
    }

    final WindowState windowForClientLocked(Session session, IBinder client,
            boolean throwOnError) {
        WindowState win = mWindowMap.get(client);
        if (localLOGV) Slog.v(
            TAG_WM, "Looking up client " + client + ": " + win);
        if (win == null) {
            RuntimeException ex = new IllegalArgumentException(
                    "Requested window " + client + " does not exist");
            if (throwOnError) {
                throw ex;
            }
            Slog.w(TAG_WM, "Failed looking up window", ex);
            return null;
        }
        if (session != null && win.mSession != session) {
            RuntimeException ex = new IllegalArgumentException(
                    "Requested window " + client + " is in session " +
                    win.mSession + ", not " + session);
            if (throwOnError) {
                throw ex;
            }
            Slog.w(TAG_WM, "Failed looking up window", ex);
            return null;
        }

        return win;
    }

    final void rebuildAppWindowListLocked() {
        rebuildAppWindowListLocked(getDefaultDisplayContentLocked());
    }

    private void rebuildAppWindowListLocked(final DisplayContent displayContent) {
        final WindowList windows = displayContent.getWindowList();
        int NW = windows.size();
        int i;
        int lastBelow = -1;
        int numRemoved = 0;

        if (mRebuildTmp.length < NW) {
            mRebuildTmp = new WindowState[NW+10];
        }

        // First remove all existing app windows.
        i=0;
        while (i < NW) {
            WindowState w = windows.get(i);
            if (w.mAppToken != null) {
                WindowState win = windows.remove(i);
                win.mRebuilding = true;
                mRebuildTmp[numRemoved] = win;
                mWindowsChanged = true;
                if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG_WM, "Rebuild removing window: " + win);
                NW--;
                numRemoved++;
                continue;
            } else if (lastBelow == i-1) {
                if (w.mAttrs.type == TYPE_WALLPAPER) {
                    lastBelow = i;
                }
            }
            i++;
        }

        // Keep whatever windows were below the app windows still below,
        // by skipping them.
        lastBelow++;
        i = lastBelow;

        // First add all of the exiting app tokens...  these are no longer
        // in the main app list, but still have windows shown.  We put them
        // in the back because now that the animation is over we no longer
        // will care about them.
        final ArrayList<TaskStack> stacks = displayContent.getStacks();
        final int numStacks = stacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            AppTokenList exitingAppTokens = stacks.get(stackNdx).mExitingAppTokens;
            int NT = exitingAppTokens.size();
            for (int j = 0; j < NT; j++) {
                i = reAddAppWindowsLocked(displayContent, i, exitingAppTokens.get(j));
            }
        }

        // And add in the still active app tokens in Z order.
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ArrayList<Task> tasks = stacks.get(stackNdx).getTasks();
            final int numTasks = tasks.size();
            for (int taskNdx = 0; taskNdx < numTasks; ++taskNdx) {
                final AppTokenList tokens = tasks.get(taskNdx).mAppTokens;
                final int numTokens = tokens.size();
                for (int tokenNdx = 0; tokenNdx < numTokens; ++tokenNdx) {
                    final AppWindowToken wtoken = tokens.get(tokenNdx);
                    if (wtoken.mIsExiting && !wtoken.waitingForReplacement()) {
                        continue;
                    }
                    i = reAddAppWindowsLocked(displayContent, i, wtoken);
                }
            }
        }

        i -= lastBelow;
        if (i != numRemoved) {
            displayContent.layoutNeeded = true;
            Slog.w(TAG_WM, "On display=" + displayContent.getDisplayId() + " Rebuild removed "
                    + numRemoved + " windows but added " + i + " rebuildAppWindowListLocked() "
                    + " callers=" + Debug.getCallers(10));
            for (i = 0; i < numRemoved; i++) {
                WindowState ws = mRebuildTmp[i];
                if (ws.mRebuilding) {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new FastPrintWriter(sw, false, 1024);
                    ws.dump(pw, "", true);
                    pw.flush();
                    Slog.w(TAG_WM, "This window was lost: " + ws);
                    Slog.w(TAG_WM, sw.toString());
                    ws.mWinAnimator.destroySurfaceLocked();
                }
            }
            Slog.w(TAG_WM, "Current app token list:");
            dumpAppTokensLocked();
            Slog.w(TAG_WM, "Final window list:");
            dumpWindowsLocked();
        }
        Arrays.fill(mRebuildTmp, null);
    }

    void makeWindowFreezingScreenIfNeededLocked(WindowState w) {
        // If the screen is currently frozen or off, then keep
        // it frozen/off until this window draws at its new
        // orientation.
        if (!okToDisplay() && mWindowsFreezingScreen != WINDOWS_FREEZING_SCREENS_TIMEOUT) {
            if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Changing surface while display frozen: " + w);
            w.mOrientationChanging = true;
            w.mLastFreezeDuration = 0;
            mWindowPlacerLocked.mOrientationChangeComplete = false;
            if (mWindowsFreezingScreen == WINDOWS_FREEZING_SCREENS_NONE) {
                mWindowsFreezingScreen = WINDOWS_FREEZING_SCREENS_ACTIVE;
                // XXX should probably keep timeout from
                // when we first froze the display.
                mH.removeMessages(H.WINDOW_FREEZE_TIMEOUT);
                mH.sendEmptyMessageDelayed(H.WINDOW_FREEZE_TIMEOUT,
                        WINDOW_FREEZE_TIMEOUT_DURATION);
            }
        }
    }

    /**
     * @return bitmap indicating if another pass through layout must be made.
     */
    int handleAnimatingStoppedAndTransitionLocked() {
        int changes = 0;

        mAppTransition.setIdle();

        for (int i = mNoAnimationNotifyOnTransitionFinished.size() - 1; i >= 0; i--) {
            final IBinder token = mNoAnimationNotifyOnTransitionFinished.get(i);
            mAppTransition.notifyAppTransitionFinishedLocked(token);
        }
        mNoAnimationNotifyOnTransitionFinished.clear();

        mWallpaperControllerLocked.hideDeferredWallpapersIfNeeded();

        // Restore window app tokens to the ActivityManager views
        ArrayList<TaskStack> stacks = getDefaultDisplayContentLocked().getStacks();
        for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ArrayList<Task> tasks = stacks.get(stackNdx).getTasks();
            for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                final AppTokenList tokens = tasks.get(taskNdx).mAppTokens;
                for (int tokenNdx = tokens.size() - 1; tokenNdx >= 0; --tokenNdx) {
                    tokens.get(tokenNdx).sendingToBottom = false;
                }
            }
        }
        rebuildAppWindowListLocked();

        changes |= PhoneWindowManager.FINISH_LAYOUT_REDO_LAYOUT;
        if (DEBUG_WALLPAPER_LIGHT) Slog.v(TAG_WM,
                "Wallpaper layer changed: assigning layers + relayout");
        moveInputMethodWindowsIfNeededLocked(true);
        mWindowPlacerLocked.mWallpaperMayChange = true;
        // Since the window list has been rebuilt, focus might
        // have to be recomputed since the actual order of windows
        // might have changed again.
        mFocusMayChange = true;

        return changes;
    }

    void updateResizingWindows(final WindowState w) {
        final WindowStateAnimator winAnimator = w.mWinAnimator;
        if (w.mHasSurface && w.mLayoutSeq == mLayoutSeq && !w.isGoneForLayoutLw()) {
            final Task task = w.getTask();
            // In the case of stack bound animations, the window frames
            // will update (unlike other animations which just modifiy
            // various transformation properties). We don't want to
            // notify the client of frame changes in this case. Not only
            // is it a lot of churn, but the frame may not correspond
            // to the surface size or the onscreen area at various
            // phases in the animation, and the client will become
            // sad and confused.
            if (task != null && task.mStack.getBoundsAnimating()) {
                return;
            }
            w.setInsetsChanged();
            boolean configChanged = w.isConfigChanged();
            if (DEBUG_CONFIGURATION && configChanged) {
                Slog.v(TAG_WM, "Win " + w + " config changed: "
                        + mCurConfiguration);
            }
            final boolean dragResizingChanged = w.isDragResizeChanged()
                    && !w.isDragResizingChangeReported();
            if (localLOGV) Slog.v(TAG_WM, "Resizing " + w
                    + ": configChanged=" + configChanged
                    + " dragResizingChanged=" + dragResizingChanged
                    + " last=" + w.mLastFrame + " frame=" + w.mFrame);
            w.mLastFrame.set(w.mFrame);
            if (w.mContentInsetsChanged
                    || w.mVisibleInsetsChanged
                    || winAnimator.mSurfaceResized
                    || w.mOutsetsChanged
                    || configChanged
                    || dragResizingChanged
                    || !w.isResizedWhileNotDragResizingReported()) {
                if (DEBUG_RESIZE || DEBUG_ORIENTATION) {
                    Slog.v(TAG_WM, "Resize reasons for w=" + w + ": "
                            + " contentInsetsChanged=" + w.mContentInsetsChanged
                            + " " + w.mContentInsets.toShortString()
                            + " visibleInsetsChanged=" + w.mVisibleInsetsChanged
                            + " " + w.mVisibleInsets.toShortString()
                            + " stableInsetsChanged=" + w.mStableInsetsChanged
                            + " " + w.mStableInsets.toShortString()
                            + " outsetsChanged=" + w.mOutsetsChanged
                            + " " + w.mOutsets.toShortString()
                            + " surfaceResized=" + winAnimator.mSurfaceResized
                            + " configChanged=" + configChanged
                            + " dragResizingChanged=" + dragResizingChanged
                            + " resizedWhileNotDragResizingReported="
                            + w.isResizedWhileNotDragResizingReported());
                }

                // If it's a dead window left on screen, and the configuration changed,
                // there is nothing we can do about it. Remove the window now.
                if (w.mAppToken != null && w.mAppDied) {
                    w.mAppToken.removeAllDeadWindows();
                    return;
                }

                w.mLastOverscanInsets.set(w.mOverscanInsets);
                w.mLastContentInsets.set(w.mContentInsets);
                w.mLastVisibleInsets.set(w.mVisibleInsets);
                w.mLastStableInsets.set(w.mStableInsets);
                w.mLastOutsets.set(w.mOutsets);
                makeWindowFreezingScreenIfNeededLocked(w);
                // If the orientation is changing, or we're starting or ending
                // a drag resizing action, then we need to hold off on unfreezing
                // the display until this window has been redrawn; to do that,
                // we need to go through the process of getting informed by the
                // application when it has finished drawing.
                if (w.mOrientationChanging || dragResizingChanged
                        || w.isResizedWhileNotDragResizing()) {
                    if (DEBUG_SURFACE_TRACE || DEBUG_ANIM || DEBUG_ORIENTATION || DEBUG_RESIZE) {
                        Slog.v(TAG_WM, "Orientation or resize start waiting for draw"
                                + ", mDrawState=DRAW_PENDING in " + w
                                + ", surfaceController " + winAnimator.mSurfaceController);
                    }
                    winAnimator.mDrawState = DRAW_PENDING;
                    if (w.mAppToken != null) {
                        w.mAppToken.clearAllDrawn();
                    }
                }
                if (!mResizingWindows.contains(w)) {
                    if (DEBUG_RESIZE || DEBUG_ORIENTATION) Slog.v(TAG_WM,
                            "Resizing window " + w);
                    mResizingWindows.add(w);
                }
            } else if (w.mOrientationChanging) {
                if (w.isDrawnLw()) {
                    if (DEBUG_ORIENTATION) Slog.v(TAG_WM,
                            "Orientation not waiting for draw in "
                            + w + ", surfaceController " + winAnimator.mSurfaceController);
                    w.mOrientationChanging = false;
                    w.mLastFreezeDuration = (int)(SystemClock.elapsedRealtime()
                            - mDisplayFreezeTime);
                }
            }
        }
    }

    void checkDrawnWindowsLocked() {
        if (mWaitingForDrawn.isEmpty() || mWaitingForDrawnCallback == null) {
            return;
        }
        for (int j = mWaitingForDrawn.size() - 1; j >= 0; j--) {
            WindowState win = mWaitingForDrawn.get(j);
            if (DEBUG_SCREEN_ON) Slog.i(TAG_WM, "Waiting for drawn " + win +
                    ": removed=" + win.mRemoved + " visible=" + win.isVisibleLw() +
                    " mHasSurface=" + win.mHasSurface +
                    " drawState=" + win.mWinAnimator.mDrawState);
            if (win.mRemoved || !win.mHasSurface || !win.mPolicyVisibility) {
                // Window has been removed or hidden; no draw will now happen, so stop waiting.
                if (DEBUG_SCREEN_ON) Slog.w(TAG_WM, "Aborted waiting for drawn: " + win);
                mWaitingForDrawn.remove(win);
            } else if (win.hasDrawnLw()) {
                // Window is now drawn (and shown).
                if (DEBUG_SCREEN_ON) Slog.d(TAG_WM, "Window drawn win=" + win);
                mWaitingForDrawn.remove(win);
            }
        }
        if (mWaitingForDrawn.isEmpty()) {
            if (DEBUG_SCREEN_ON) Slog.d(TAG_WM, "All windows drawn!");
            mH.removeMessages(H.WAITING_FOR_DRAWN_TIMEOUT);
            mH.sendEmptyMessage(H.ALL_WINDOWS_DRAWN);
        }
    }

    void setHoldScreenLocked(final Session newHoldScreen) {
        final boolean hold = newHoldScreen != null;

        if (hold && mHoldingScreenOn != newHoldScreen) {
            mHoldingScreenWakeLock.setWorkSource(new WorkSource(newHoldScreen.mUid));
        }
        mHoldingScreenOn = newHoldScreen;

        final boolean state = mHoldingScreenWakeLock.isHeld();
        if (hold != state) {
            if (hold) {
                if (DEBUG_KEEP_SCREEN_ON) {
                    Slog.d(TAG_KEEP_SCREEN_ON, "Acquiring screen wakelock due to " +
                            mWindowPlacerLocked.mHoldScreenWindow);
                }
                mLastWakeLockHoldingWindow = mWindowPlacerLocked.mHoldScreenWindow;
                mLastWakeLockObscuringWindow = null;
                mHoldingScreenWakeLock.acquire();
                mPolicy.keepScreenOnStartedLw();
            } else {
                if (DEBUG_KEEP_SCREEN_ON) {
                    Slog.d(TAG_KEEP_SCREEN_ON, "Releasing screen wakelock, obscured by " +
                            mWindowPlacerLocked.mObsuringWindow);
                }
                mLastWakeLockHoldingWindow = null;
                mLastWakeLockObscuringWindow = mWindowPlacerLocked.mObsuringWindow;
                mPolicy.keepScreenOnStoppedLw();
                mHoldingScreenWakeLock.release();
            }
        }
    }

    void requestTraversal() {
        synchronized (mWindowMap) {
            mWindowPlacerLocked.requestTraversal();
        }
    }

    /** Note that Locked in this case is on mLayoutToAnim */
    void scheduleAnimationLocked() {
        if (!mAnimationScheduled) {
            mAnimationScheduled = true;
            mChoreographer.postFrameCallback(mAnimator.mAnimationFrameCallback);
        }
    }

    boolean needsLayout() {
        final int numDisplays = mDisplayContents.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mDisplayContents.valueAt(displayNdx);
            if (displayContent.layoutNeeded) {
                return true;
            }
        }
        return false;
    }

    /** If a window that has an animation specifying a colored background and the current wallpaper
     * is visible, then the color goes *below* the wallpaper so we don't cause the wallpaper to
     * suddenly disappear. */
    int adjustAnimationBackground(WindowStateAnimator winAnimator) {
        WindowList windows = winAnimator.mWin.getWindowList();
        for (int i = windows.size() - 1; i >= 0; --i) {
            WindowState testWin = windows.get(i);
            if (testWin.mIsWallpaper && testWin.isVisibleNow()) {
                return testWin.mWinAnimator.mAnimLayer;
            }
        }
        return winAnimator.mAnimLayer;
    }

    boolean reclaimSomeSurfaceMemoryLocked(WindowStateAnimator winAnimator, String operation,
                                           boolean secure) {
        final WindowSurfaceController surfaceController = winAnimator.mSurfaceController;
        boolean leakedSurface = false;
        boolean killedApps = false;

        EventLog.writeEvent(EventLogTags.WM_NO_SURFACE_MEMORY, winAnimator.mWin.toString(),
                winAnimator.mSession.mPid, operation);

        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            // There was some problem...   first, do a sanity check of the window list to make sure
            // we haven't left any dangling surfaces around.

            Slog.i(TAG_WM, "Out of memory for surface!  Looking for leaks...");
            final int numDisplays = mDisplayContents.size();
            for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                final WindowList windows = mDisplayContents.valueAt(displayNdx).getWindowList();
                final int numWindows = windows.size();
                for (int winNdx = 0; winNdx < numWindows; ++winNdx) {
                    final WindowState ws = windows.get(winNdx);
                    final WindowStateAnimator wsa = ws.mWinAnimator;
                    if (wsa.mSurfaceController == null) {
                        continue;
                    }
                    if (!mSessions.contains(wsa.mSession)) {
                        Slog.w(TAG_WM, "LEAKED SURFACE (session doesn't exist): "
                                + ws + " surface=" + wsa.mSurfaceController
                                + " token=" + ws.mToken
                                + " pid=" + ws.mSession.mPid
                                + " uid=" + ws.mSession.mUid);
                        wsa.destroySurface();
                        mForceRemoves.add(ws);
                        leakedSurface = true;
                    } else if (ws.mAppToken != null && ws.mAppToken.clientHidden) {
                        Slog.w(TAG_WM, "LEAKED SURFACE (app token hidden): "
                                + ws + " surface=" + wsa.mSurfaceController
                                + " token=" + ws.mAppToken
                                + " saved=" + ws.hasSavedSurface());
                        if (SHOW_TRANSACTIONS) logSurface(ws, "LEAK DESTROY", false);
                        wsa.destroySurface();
                        leakedSurface = true;
                    }
                }
            }

            if (!leakedSurface) {
                Slog.w(TAG_WM, "No leaked surfaces; killing applicatons!");
                SparseIntArray pidCandidates = new SparseIntArray();
                for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                    final WindowList windows = mDisplayContents.valueAt(displayNdx).getWindowList();
                    final int numWindows = windows.size();
                    for (int winNdx = 0; winNdx < numWindows; ++winNdx) {
                        final WindowState ws = windows.get(winNdx);
                        if (mForceRemoves.contains(ws)) {
                            continue;
                        }
                        WindowStateAnimator wsa = ws.mWinAnimator;
                        if (wsa.mSurfaceController != null) {
                            pidCandidates.append(wsa.mSession.mPid, wsa.mSession.mPid);
                        }
                    }
                    if (pidCandidates.size() > 0) {
                        int[] pids = new int[pidCandidates.size()];
                        for (int i=0; i<pids.length; i++) {
                            pids[i] = pidCandidates.keyAt(i);
                        }
                        try {
                            if (mActivityManager.killPids(pids, "Free memory", secure)) {
                                killedApps = true;
                            }
                        } catch (RemoteException e) {
                        }
                    }
                }
            }

            if (leakedSurface || killedApps) {
                // We managed to reclaim some memory, so get rid of the trouble
                // surface and ask the app to request another one.
                Slog.w(TAG_WM, "Looks like we have reclaimed some memory, clearing surface for retry.");
                if (surfaceController != null) {
                    if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) logSurface(winAnimator.mWin,
                            "RECOVER DESTROY", false);
                    winAnimator.destroySurface();
                    scheduleRemoveStartingWindowLocked(winAnimator.mWin.mAppToken);
                }

                try {
                    winAnimator.mWin.mClient.dispatchGetNewSurface();
                } catch (RemoteException e) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }

        return leakedSurface || killedApps;
    }

    boolean updateFocusedWindowLocked(int mode, boolean updateInputWindows) {
        WindowState newFocus = computeFocusedWindowLocked();
        if (mCurrentFocus != newFocus) {
            Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "wmUpdateFocus");
            // This check makes sure that we don't already have the focus
            // change message pending.
            mH.removeMessages(H.REPORT_FOCUS_CHANGE);
            mH.sendEmptyMessage(H.REPORT_FOCUS_CHANGE);
            // TODO(multidisplay): Focused windows on default display only.
            final DisplayContent displayContent = getDefaultDisplayContentLocked();
            final boolean imWindowChanged = moveInputMethodWindowsIfNeededLocked(
                    mode != UPDATE_FOCUS_WILL_ASSIGN_LAYERS
                            && mode != UPDATE_FOCUS_WILL_PLACE_SURFACES);
            if (imWindowChanged) {
                displayContent.layoutNeeded = true;
                newFocus = computeFocusedWindowLocked();
            }

            if (DEBUG_FOCUS_LIGHT || localLOGV) Slog.v(TAG_WM, "Changing focus from " +
                    mCurrentFocus + " to " + newFocus + " Callers=" + Debug.getCallers(4));
            final WindowState oldFocus = mCurrentFocus;
            mCurrentFocus = newFocus;
            mLosingFocus.remove(newFocus);

            int focusChanged = mPolicy.focusChangedLw(oldFocus, newFocus);

            if (imWindowChanged && oldFocus != mInputMethodWindow) {
                // Focus of the input method window changed. Perform layout if needed.
                if (mode == UPDATE_FOCUS_PLACING_SURFACES) {
                    mWindowPlacerLocked.performLayoutLockedInner(displayContent, true /*initial*/,
                            updateInputWindows);
                    focusChanged &= ~WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
                } else if (mode == UPDATE_FOCUS_WILL_PLACE_SURFACES) {
                    // Client will do the layout, but we need to assign layers
                    // for handleNewWindowLocked() below.
                    mLayersController.assignLayersLocked(displayContent.getWindowList());
                }
            }

            if ((focusChanged & WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT) != 0) {
                // The change in focus caused us to need to do a layout.  Okay.
                displayContent.layoutNeeded = true;
                if (mode == UPDATE_FOCUS_PLACING_SURFACES) {
                    mWindowPlacerLocked.performLayoutLockedInner(displayContent, true /*initial*/,
                            updateInputWindows);
                }
            }

            if (mode != UPDATE_FOCUS_WILL_ASSIGN_LAYERS) {
                // If we defer assigning layers, then the caller is responsible for
                // doing this part.
                mInputMonitor.setInputFocusLw(mCurrentFocus, updateInputWindows);
            }

            adjustForImeIfNeeded(displayContent);

            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
            return true;
        }
        return false;
    }

    private WindowState computeFocusedWindowLocked() {
        final int displayCount = mDisplayContents.size();
        for (int i = 0; i < displayCount; i++) {
            final DisplayContent displayContent = mDisplayContents.valueAt(i);
            WindowState win = findFocusedWindowLocked(displayContent);
            if (win != null) {
                return win;
            }
        }
        return null;
    }

    WindowState findFocusedWindowLocked(DisplayContent displayContent) {
        final WindowList windows = displayContent.getWindowList();
        for (int i = windows.size() - 1; i >= 0; i--) {
            final WindowState win = windows.get(i);

            if (localLOGV || DEBUG_FOCUS) Slog.v(
                TAG_WM, "Looking for focus: " + i
                + " = " + win
                + ", flags=" + win.mAttrs.flags
                + ", canReceive=" + win.canReceiveKeys());

            if (!win.canReceiveKeys()) {
                continue;
            }

            AppWindowToken wtoken = win.mAppToken;

            // If this window's application has been removed, just skip it.
            if (wtoken != null && (wtoken.removed || wtoken.sendingToBottom)) {
                if (DEBUG_FOCUS) Slog.v(TAG_WM, "Skipping " + wtoken + " because "
                        + (wtoken.removed ? "removed" : "sendingToBottom"));
                continue;
            }

            // Descend through all of the app tokens and find the first that either matches
            // win.mAppToken (return win) or mFocusedApp (return null).
            if (wtoken != null && win.mAttrs.type != TYPE_APPLICATION_STARTING &&
                    mFocusedApp != null) {
                ArrayList<Task> tasks = displayContent.getTasks();
                for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                    AppTokenList tokens = tasks.get(taskNdx).mAppTokens;
                    int tokenNdx = tokens.size() - 1;
                    for ( ; tokenNdx >= 0; --tokenNdx) {
                        final AppWindowToken token = tokens.get(tokenNdx);
                        if (wtoken == token) {
                            break;
                        }
                        if (mFocusedApp == token && token.windowsAreFocusable()) {
                            // Whoops, we are below the focused app whose windows are focusable...
                            // No focus for you!!!
                            if (localLOGV || DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM,
                                    "findFocusedWindow: Reached focused app=" + mFocusedApp);
                            return null;
                        }
                    }
                    if (tokenNdx >= 0) {
                        // Early exit from loop, must have found the matching token.
                        break;
                    }
                }
            }

            if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM, "findFocusedWindow: Found new focus @ " + i +
                        " = " + win);
            return win;
        }

        if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM, "findFocusedWindow: No focusable windows.");
        return null;
    }

    private void startFreezingDisplayLocked(boolean inTransaction, int exitAnim, int enterAnim) {
        if (mDisplayFrozen) {
            return;
        }

        if (!mDisplayReady || !mPolicy.isScreenOn()) {
            // No need to freeze the screen before the system is ready or if
            // the screen is off.
            return;
        }

        if (DEBUG_ORIENTATION) Slog.d(TAG_WM,
                "startFreezingDisplayLocked: inTransaction=" + inTransaction
                + " exitAnim=" + exitAnim + " enterAnim=" + enterAnim
                + " called by " + Debug.getCallers(8));
        mScreenFrozenLock.acquire();

        mDisplayFrozen = true;
        mDisplayFreezeTime = SystemClock.elapsedRealtime();
        mLastFinishedFreezeSource = null;

        mInputMonitor.freezeInputDispatchingLw();

        // Clear the last input window -- that is just used for
        // clean transitions between IMEs, and if we are freezing
        // the screen then the whole world is changing behind the scenes.
        mPolicy.setLastInputMethodWindowLw(null, null);

        if (mAppTransition.isTransitionSet()) {
            mAppTransition.freeze();
        }

        if (PROFILE_ORIENTATION) {
            File file = new File("/data/system/frozen");
            Debug.startMethodTracing(file.toString(), 8 * 1024 * 1024);
        }

        if (CUSTOM_SCREEN_ROTATION) {
            mExitAnimId = exitAnim;
            mEnterAnimId = enterAnim;
            final DisplayContent displayContent = getDefaultDisplayContentLocked();
            final int displayId = displayContent.getDisplayId();
            ScreenRotationAnimation screenRotationAnimation =
                    mAnimator.getScreenRotationAnimationLocked(displayId);
            if (screenRotationAnimation != null) {
                screenRotationAnimation.kill();
            }

            // Check whether the current screen contains any secure content.
            boolean isSecure = false;
            final WindowList windows = getDefaultWindowListLocked();
            final int N = windows.size();
            for (int i = 0; i < N; i++) {
                WindowState ws = windows.get(i);
                if (ws.isOnScreen() && (ws.mAttrs.flags & FLAG_SECURE) != 0) {
                    isSecure = true;
                    break;
                }
            }

            // TODO(multidisplay): rotation on main screen only.
            displayContent.updateDisplayInfo();
            screenRotationAnimation = new ScreenRotationAnimation(mContext, displayContent,
                    mFxSession, inTransaction, mPolicy.isDefaultOrientationForced(), isSecure);
            mAnimator.setScreenRotationAnimationLocked(displayId, screenRotationAnimation);
        }
    }

    void stopFreezingDisplayLocked() {
        if (!mDisplayFrozen) {
            return;
        }

        if (mWaitingForConfig || mAppsFreezingScreen > 0
                || mWindowsFreezingScreen == WINDOWS_FREEZING_SCREENS_ACTIVE
                || mClientFreezingScreen || !mOpeningApps.isEmpty()) {
            if (DEBUG_ORIENTATION) Slog.d(TAG_WM,
                "stopFreezingDisplayLocked: Returning mWaitingForConfig=" + mWaitingForConfig
                + ", mAppsFreezingScreen=" + mAppsFreezingScreen
                + ", mWindowsFreezingScreen=" + mWindowsFreezingScreen
                + ", mClientFreezingScreen=" + mClientFreezingScreen
                + ", mOpeningApps.size()=" + mOpeningApps.size());
            return;
        }

        if (DEBUG_ORIENTATION) Slog.d(TAG_WM,
                "stopFreezingDisplayLocked: Unfreezing now");

        mDisplayFrozen = false;
        mLastDisplayFreezeDuration = (int)(SystemClock.elapsedRealtime() - mDisplayFreezeTime);
        StringBuilder sb = new StringBuilder(128);
        sb.append("Screen frozen for ");
        TimeUtils.formatDuration(mLastDisplayFreezeDuration, sb);
        if (mLastFinishedFreezeSource != null) {
            sb.append(" due to ");
            sb.append(mLastFinishedFreezeSource);
        }
        Slog.i(TAG_WM, sb.toString());
        mH.removeMessages(H.APP_FREEZE_TIMEOUT);
        mH.removeMessages(H.CLIENT_FREEZE_TIMEOUT);
        if (PROFILE_ORIENTATION) {
            Debug.stopMethodTracing();
        }

        boolean updateRotation = false;

        final DisplayContent displayContent = getDefaultDisplayContentLocked();
        final int displayId = displayContent.getDisplayId();
        ScreenRotationAnimation screenRotationAnimation =
                mAnimator.getScreenRotationAnimationLocked(displayId);
        if (CUSTOM_SCREEN_ROTATION && screenRotationAnimation != null
                && screenRotationAnimation.hasScreenshot()) {
            if (DEBUG_ORIENTATION) Slog.i(TAG_WM, "**** Dismissing screen rotation animation");
            // TODO(multidisplay): rotation on main screen only.
            DisplayInfo displayInfo = displayContent.getDisplayInfo();
            // Get rotation animation again, with new top window
            boolean isDimming = displayContent.isDimming();
            if (!mPolicy.validateRotationAnimationLw(mExitAnimId, mEnterAnimId, isDimming)) {
                mExitAnimId = mEnterAnimId = 0;
            }
            if (screenRotationAnimation.dismiss(mFxSession, MAX_ANIMATION_DURATION,
                    getTransitionAnimationScaleLocked(), displayInfo.logicalWidth,
                        displayInfo.logicalHeight, mExitAnimId, mEnterAnimId)) {
                scheduleAnimationLocked();
            } else {
                screenRotationAnimation.kill();
                mAnimator.setScreenRotationAnimationLocked(displayId, null);
                updateRotation = true;
            }
        } else {
            if (screenRotationAnimation != null) {
                screenRotationAnimation.kill();
                mAnimator.setScreenRotationAnimationLocked(displayId, null);
            }
            updateRotation = true;
        }

        mInputMonitor.thawInputDispatchingLw();

        boolean configChanged;

        // While the display is frozen we don't re-compute the orientation
        // to avoid inconsistent states.  However, something interesting
        // could have actually changed during that time so re-evaluate it
        // now to catch that.
        configChanged = updateOrientationFromAppTokensLocked(false);

        // A little kludge: a lot could have happened while the
        // display was frozen, so now that we are coming back we
        // do a gc so that any remote references the system
        // processes holds on others can be released if they are
        // no longer needed.
        mH.removeMessages(H.FORCE_GC);
        mH.sendEmptyMessageDelayed(H.FORCE_GC, 2000);

        mScreenFrozenLock.release();

        if (updateRotation) {
            if (DEBUG_ORIENTATION) Slog.d(TAG_WM, "Performing post-rotate rotation");
            configChanged |= updateRotationUncheckedLocked(false);
        }

        if (configChanged) {
            mH.sendEmptyMessage(H.SEND_NEW_CONFIGURATION);
        }
    }

    static int getPropertyInt(String[] tokens, int index, int defUnits, int defDps,
            DisplayMetrics dm) {
        if (index < tokens.length) {
            String str = tokens[index];
            if (str != null && str.length() > 0) {
                try {
                    int val = Integer.parseInt(str);
                    return val;
                } catch (Exception e) {
                }
            }
        }
        if (defUnits == TypedValue.COMPLEX_UNIT_PX) {
            return defDps;
        }
        int val = (int)TypedValue.applyDimension(defUnits, defDps, dm);
        return val;
    }

    void createWatermarkInTransaction() {
        if (mWatermark != null) {
            return;
        }

        File file = new File("/system/etc/setup.conf");
        FileInputStream in = null;
        DataInputStream ind = null;
        try {
            in = new FileInputStream(file);
            ind = new DataInputStream(in);
            String line = ind.readLine();
            if (line != null) {
                String[] toks = line.split("%");
                if (toks != null && toks.length > 0) {
                    mWatermark = new Watermark(getDefaultDisplayContentLocked().getDisplay(),
                            mRealDisplayMetrics, mFxSession, toks);
                }
            }
        } catch (FileNotFoundException e) {
        } catch (IOException e) {
        } finally {
            if (ind != null) {
                try {
                    ind.close();
                } catch (IOException e) {
                }
            } else if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                }
            }
        }
    }

    @Override
    public void statusBarVisibilityChanged(int visibility) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.STATUS_BAR)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Caller does not hold permission "
                    + android.Manifest.permission.STATUS_BAR);
        }

        synchronized (mWindowMap) {
            mLastStatusBarVisibility = visibility;
            visibility = mPolicy.adjustSystemUiVisibilityLw(visibility);
            updateStatusBarVisibilityLocked(visibility);
        }
    }

    // TOOD(multidisplay): StatusBar on multiple screens?
    boolean updateStatusBarVisibilityLocked(int visibility) {
        if (mLastDispatchedSystemUiVisibility == visibility) {
            return false;
        }
        final int globalDiff = (visibility ^ mLastDispatchedSystemUiVisibility)
                // We are only interested in differences of one of the
                // clearable flags...
                & View.SYSTEM_UI_CLEARABLE_FLAGS
                // ...if it has actually been cleared.
                & ~visibility;

        mLastDispatchedSystemUiVisibility = visibility;
        mInputManager.setSystemUiVisibility(visibility);
        final WindowList windows = getDefaultWindowListLocked();
        final int N = windows.size();
        for (int i = 0; i < N; i++) {
            WindowState ws = windows.get(i);
            try {
                int curValue = ws.mSystemUiVisibility;
                int diff = (curValue ^ visibility) & globalDiff;
                int newValue = (curValue&~diff) | (visibility&diff);
                if (newValue != curValue) {
                    ws.mSeq++;
                    ws.mSystemUiVisibility = newValue;
                }
                if (newValue != curValue || ws.mAttrs.hasSystemUiListeners) {
                    ws.mClient.dispatchSystemUiVisibilityChanged(ws.mSeq,
                            visibility, newValue, diff);
                }
            } catch (RemoteException e) {
                // so sorry
            }
        }
        return true;
    }

    @Override
    public void reevaluateStatusBarVisibility() {
        synchronized (mWindowMap) {
            int visibility = mPolicy.adjustSystemUiVisibilityLw(mLastStatusBarVisibility);
            if (updateStatusBarVisibilityLocked(visibility)) {
                mWindowPlacerLocked.requestTraversal();
            }
        }
    }

    private static final class HideNavInputConsumer extends InputConsumerImpl
            implements WindowManagerPolicy.InputConsumer {
        private final InputEventReceiver mInputEventReceiver;

        HideNavInputConsumer(WindowManagerService service, Looper looper,
                             InputEventReceiver.Factory inputEventReceiverFactory) {
            super(service, "input consumer", null);
            mInputEventReceiver = inputEventReceiverFactory.createInputEventReceiver(
                    mClientChannel, looper);
        }

        @Override
        public void dismiss() {
            if (mService.removeInputConsumer()) {
                synchronized (mService.mWindowMap) {
                    mInputEventReceiver.dispose();
                    disposeChannelsLw();
                }
            }
        }
    }

    @Override
    public WindowManagerPolicy.InputConsumer addInputConsumer(Looper looper,
            InputEventReceiver.Factory inputEventReceiverFactory) {
        synchronized (mWindowMap) {
            HideNavInputConsumer inputConsumerImpl = new HideNavInputConsumer(
                    this, looper, inputEventReceiverFactory);
            mInputConsumer = inputConsumerImpl;
            mInputMonitor.updateInputWindowsLw(true);
            return inputConsumerImpl;
        }
    }

    boolean removeInputConsumer() {
        synchronized (mWindowMap) {
            if (mInputConsumer != null) {
                mInputConsumer = null;
                mInputMonitor.updateInputWindowsLw(true);
                return true;
            }
            return false;
        }
    }

    @Override
    public void createWallpaperInputConsumer(InputChannel inputChannel) {
        synchronized (mWindowMap) {
            mWallpaperInputConsumer = new InputConsumerImpl(this, "wallpaper input", inputChannel);
            mWallpaperInputConsumer.mWindowHandle.hasWallpaper = true;
            mInputMonitor.updateInputWindowsLw(true);
        }
    }

    @Override
    public void removeWallpaperInputConsumer() {
        synchronized (mWindowMap) {
            if (mWallpaperInputConsumer != null) {
                mWallpaperInputConsumer.disposeChannelsLw();
                mWallpaperInputConsumer = null;
                mInputMonitor.updateInputWindowsLw(true);
            }
        }
    }

    @Override
    public boolean hasNavigationBar() {
        return mPolicy.hasNavigationBar();
    }

    @Override
    public void lockNow(Bundle options) {
        mPolicy.lockNow(options);
    }

    public void showRecentApps(boolean fromHome) {
        mPolicy.showRecentApps(fromHome);
    }

    @Override
    public boolean isSafeModeEnabled() {
        return mSafeMode;
    }

    @Override
    public boolean clearWindowContentFrameStats(IBinder token) {
        if (!checkCallingPermission(Manifest.permission.FRAME_STATS,
                "clearWindowContentFrameStats()")) {
            throw new SecurityException("Requires FRAME_STATS permission");
        }
        synchronized (mWindowMap) {
            WindowState windowState = mWindowMap.get(token);
            if (windowState == null) {
                return false;
            }
            WindowSurfaceController surfaceController = windowState.mWinAnimator.mSurfaceController;
            if (surfaceController == null) {
                return false;
            }
            return surfaceController.clearWindowContentFrameStats();
        }
    }

    @Override
    public WindowContentFrameStats getWindowContentFrameStats(IBinder token) {
        if (!checkCallingPermission(Manifest.permission.FRAME_STATS,
                "getWindowContentFrameStats()")) {
            throw new SecurityException("Requires FRAME_STATS permission");
        }
        synchronized (mWindowMap) {
            WindowState windowState = mWindowMap.get(token);
            if (windowState == null) {
                return null;
            }
            WindowSurfaceController surfaceController = windowState.mWinAnimator.mSurfaceController;
            if (surfaceController == null) {
                return null;
            }
            if (mTempWindowRenderStats == null) {
                mTempWindowRenderStats = new WindowContentFrameStats();
            }
            WindowContentFrameStats stats = mTempWindowRenderStats;
            if (!surfaceController.getWindowContentFrameStats(stats)) {
                return null;
            }
            return stats;
        }
    }

    public void notifyAppRelaunching(IBinder token) {
        synchronized (mWindowMap) {
            AppWindowToken appWindow = findAppWindowToken(token);
            if (appWindow != null) {
                appWindow.startRelaunching();
            }
        }
    }

    public void notifyAppRelaunchingFinished(IBinder token) {
        synchronized (mWindowMap) {
            AppWindowToken appWindow = findAppWindowToken(token);
            if (appWindow != null) {
                appWindow.finishRelaunching();
            }
        }
    }

    @Override
    public int getDockedDividerInsetsLw() {
        return getDefaultDisplayContentLocked().getDockedDividerController().getContentInsets();
    }

    void dumpPolicyLocked(PrintWriter pw, String[] args, boolean dumpAll) {
        pw.println("WINDOW MANAGER POLICY STATE (dumpsys window policy)");
        mPolicy.dump("    ", pw, args);
    }

    void dumpAnimatorLocked(PrintWriter pw, String[] args, boolean dumpAll) {
        pw.println("WINDOW MANAGER ANIMATOR STATE (dumpsys window animator)");
        mAnimator.dumpLocked(pw, "    ", dumpAll);
    }

    void dumpTokensLocked(PrintWriter pw, boolean dumpAll) {
        pw.println("WINDOW MANAGER TOKENS (dumpsys window tokens)");
        if (!mTokenMap.isEmpty()) {
            pw.println("  All tokens:");
            Iterator<WindowToken> it = mTokenMap.values().iterator();
            while (it.hasNext()) {
                WindowToken token = it.next();
                pw.print("  "); pw.print(token);
                if (dumpAll) {
                    pw.println(':');
                    token.dump(pw, "    ");
                } else {
                    pw.println();
                }
            }
        }
        mWallpaperControllerLocked.dumpTokens(pw, "  ", dumpAll);
        if (!mFinishedStarting.isEmpty()) {
            pw.println();
            pw.println("  Finishing start of application tokens:");
            for (int i=mFinishedStarting.size()-1; i>=0; i--) {
                WindowToken token = mFinishedStarting.get(i);
                pw.print("  Finished Starting #"); pw.print(i);
                        pw.print(' '); pw.print(token);
                if (dumpAll) {
                    pw.println(':');
                    token.dump(pw, "    ");
                } else {
                    pw.println();
                }
            }
        }
        if (!mOpeningApps.isEmpty() || !mClosingApps.isEmpty()) {
            pw.println();
            if (mOpeningApps.size() > 0) {
                pw.print("  mOpeningApps="); pw.println(mOpeningApps);
            }
            if (mClosingApps.size() > 0) {
                pw.print("  mClosingApps="); pw.println(mClosingApps);
            }
        }
    }

    void dumpSessionsLocked(PrintWriter pw, boolean dumpAll) {
        pw.println("WINDOW MANAGER SESSIONS (dumpsys window sessions)");
        for (int i=0; i<mSessions.size(); i++) {
            Session s = mSessions.valueAt(i);
            pw.print("  Session "); pw.print(s); pw.println(':');
            s.dump(pw, "    ");
        }
    }

    void dumpDisplayContentsLocked(PrintWriter pw, boolean dumpAll) {
        pw.println("WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)");
        if (mDisplayReady) {
            final int numDisplays = mDisplayContents.size();
            for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                final DisplayContent displayContent = mDisplayContents.valueAt(displayNdx);
                displayContent.dump("  ", pw);
            }
        } else {
            pw.println("  NO DISPLAY");
        }
    }

    void dumpWindowsLocked(PrintWriter pw, boolean dumpAll,
            ArrayList<WindowState> windows) {
        pw.println("WINDOW MANAGER WINDOWS (dumpsys window windows)");
        dumpWindowsNoHeaderLocked(pw, dumpAll, windows);
    }

    void dumpWindowsNoHeaderLocked(PrintWriter pw, boolean dumpAll,
            ArrayList<WindowState> windows) {
        final int numDisplays = mDisplayContents.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final WindowList windowList = mDisplayContents.valueAt(displayNdx).getWindowList();
            for (int winNdx = windowList.size() - 1; winNdx >= 0; --winNdx) {
                final WindowState w = windowList.get(winNdx);
                if (windows == null || windows.contains(w)) {
                    pw.print("  Window #"); pw.print(winNdx); pw.print(' ');
                            pw.print(w); pw.println(":");
                    w.dump(pw, "    ", dumpAll || windows != null);
                }
            }
        }
        if (mInputMethodDialogs.size() > 0) {
            pw.println();
            pw.println("  Input method dialogs:");
            for (int i=mInputMethodDialogs.size()-1; i>=0; i--) {
                WindowState w = mInputMethodDialogs.get(i);
                if (windows == null || windows.contains(w)) {
                    pw.print("  IM Dialog #"); pw.print(i); pw.print(": "); pw.println(w);
                }
            }
        }
        if (mPendingRemove.size() > 0) {
            pw.println();
            pw.println("  Remove pending for:");
            for (int i=mPendingRemove.size()-1; i>=0; i--) {
                WindowState w = mPendingRemove.get(i);
                if (windows == null || windows.contains(w)) {
                    pw.print("  Remove #"); pw.print(i); pw.print(' ');
                            pw.print(w);
                    if (dumpAll) {
                        pw.println(":");
                        w.dump(pw, "    ", true);
                    } else {
                        pw.println();
                    }
                }
            }
        }
        if (mForceRemoves != null && mForceRemoves.size() > 0) {
            pw.println();
            pw.println("  Windows force removing:");
            for (int i=mForceRemoves.size()-1; i>=0; i--) {
                WindowState w = mForceRemoves.get(i);
                pw.print("  Removing #"); pw.print(i); pw.print(' ');
                        pw.print(w);
                if (dumpAll) {
                    pw.println(":");
                    w.dump(pw, "    ", true);
                } else {
                    pw.println();
                }
            }
        }
        if (mDestroySurface.size() > 0) {
            pw.println();
            pw.println("  Windows waiting to destroy their surface:");
            for (int i=mDestroySurface.size()-1; i>=0; i--) {
                WindowState w = mDestroySurface.get(i);
                if (windows == null || windows.contains(w)) {
                    pw.print("  Destroy #"); pw.print(i); pw.print(' ');
                            pw.print(w);
                    if (dumpAll) {
                        pw.println(":");
                        w.dump(pw, "    ", true);
                    } else {
                        pw.println();
                    }
                }
            }
        }
        if (mLosingFocus.size() > 0) {
            pw.println();
            pw.println("  Windows losing focus:");
            for (int i=mLosingFocus.size()-1; i>=0; i--) {
                WindowState w = mLosingFocus.get(i);
                if (windows == null || windows.contains(w)) {
                    pw.print("  Losing #"); pw.print(i); pw.print(' ');
                            pw.print(w);
                    if (dumpAll) {
                        pw.println(":");
                        w.dump(pw, "    ", true);
                    } else {
                        pw.println();
                    }
                }
            }
        }
        if (mResizingWindows.size() > 0) {
            pw.println();
            pw.println("  Windows waiting to resize:");
            for (int i=mResizingWindows.size()-1; i>=0; i--) {
                WindowState w = mResizingWindows.get(i);
                if (windows == null || windows.contains(w)) {
                    pw.print("  Resizing #"); pw.print(i); pw.print(' ');
                            pw.print(w);
                    if (dumpAll) {
                        pw.println(":");
                        w.dump(pw, "    ", true);
                    } else {
                        pw.println();
                    }
                }
            }
        }
        if (mWaitingForDrawn.size() > 0) {
            pw.println();
            pw.println("  Clients waiting for these windows to be drawn:");
            for (int i=mWaitingForDrawn.size()-1; i>=0; i--) {
                WindowState win = mWaitingForDrawn.get(i);
                pw.print("  Waiting #"); pw.print(i); pw.print(' '); pw.print(win);
            }
        }
        pw.println();
        pw.print("  mCurConfiguration="); pw.println(this.mCurConfiguration);
        pw.print("  mHasPermanentDpad="); pw.println(mHasPermanentDpad);
        pw.print("  mCurrentFocus="); pw.println(mCurrentFocus);
        if (mLastFocus != mCurrentFocus) {
            pw.print("  mLastFocus="); pw.println(mLastFocus);
        }
        pw.print("  mFocusedApp="); pw.println(mFocusedApp);
        if (mInputMethodTarget != null) {
            pw.print("  mInputMethodTarget="); pw.println(mInputMethodTarget);
        }
        pw.print("  mInTouchMode="); pw.print(mInTouchMode);
                pw.print(" mLayoutSeq="); pw.println(mLayoutSeq);
        pw.print("  mLastDisplayFreezeDuration=");
                TimeUtils.formatDuration(mLastDisplayFreezeDuration, pw);
                if ( mLastFinishedFreezeSource != null) {
                    pw.print(" due to ");
                    pw.print(mLastFinishedFreezeSource);
                }
                pw.println();
        pw.print("  mLastWakeLockHoldingWindow=");pw.print(mLastWakeLockHoldingWindow);
                pw.print(" mLastWakeLockObscuringWindow="); pw.print(mLastWakeLockObscuringWindow);
                pw.println();

        mInputMonitor.dump(pw, "  ");

        if (dumpAll) {
            pw.print("  mSystemDecorLayer="); pw.print(mSystemDecorLayer);
                    pw.print(" mScreenRect="); pw.println(mScreenRect.toShortString());
            if (mLastStatusBarVisibility != 0) {
                pw.print("  mLastStatusBarVisibility=0x");
                        pw.println(Integer.toHexString(mLastStatusBarVisibility));
            }
            if (mInputMethodWindow != null) {
                pw.print("  mInputMethodWindow="); pw.println(mInputMethodWindow);
            }
            mWindowPlacerLocked.dump(pw, "  ");
            mWallpaperControllerLocked.dump(pw, "  ");
            mLayersController.dump(pw, "  ");
            pw.print("  mSystemBooted="); pw.print(mSystemBooted);
                    pw.print(" mDisplayEnabled="); pw.println(mDisplayEnabled);
            if (needsLayout()) {
                pw.print("  layoutNeeded on displays=");
                for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                    final DisplayContent displayContent = mDisplayContents.valueAt(displayNdx);
                    if (displayContent.layoutNeeded) {
                        pw.print(displayContent.getDisplayId());
                    }
                }
                pw.println();
            }
            pw.print("  mTransactionSequence="); pw.println(mTransactionSequence);
            pw.print("  mDisplayFrozen="); pw.print(mDisplayFrozen);
                    pw.print(" windows="); pw.print(mWindowsFreezingScreen);
                    pw.print(" client="); pw.print(mClientFreezingScreen);
                    pw.print(" apps="); pw.print(mAppsFreezingScreen);
                    pw.print(" waitingForConfig="); pw.println(mWaitingForConfig);
            pw.print("  mRotation="); pw.print(mRotation);
                    pw.print(" mAltOrientation="); pw.println(mAltOrientation);
            pw.print("  mLastWindowForcedOrientation="); pw.print(mLastWindowForcedOrientation);
                    pw.print(" mForcedAppOrientation="); pw.println(mForcedAppOrientation);
            pw.print("  mDeferredRotationPauseCount="); pw.println(mDeferredRotationPauseCount);
            pw.print("  Animation settings: disabled="); pw.print(mAnimationsDisabled);
                    pw.print(" window="); pw.print(mWindowAnimationScaleSetting);
                    pw.print(" transition="); pw.print(mTransitionAnimationScaleSetting);
                    pw.print(" animator="); pw.println(mAnimatorDurationScaleSetting);
            pw.print(" mSkipAppTransitionAnimation=");pw.println(mSkipAppTransitionAnimation);
            pw.println("  mLayoutToAnim:");
            mAppTransition.dump(pw, "    ");
        }
    }

    boolean dumpWindows(PrintWriter pw, String name, String[] args,
            int opti, boolean dumpAll) {
        WindowList windows = new WindowList();
        if ("apps".equals(name) || "visible".equals(name) || "visible-apps".equals(name)) {
            final boolean appsOnly = name.contains("apps");
            final boolean visibleOnly = name.contains("visible");
            synchronized(mWindowMap) {
                if (appsOnly) {
                    dumpDisplayContentsLocked(pw, true);
                }

                final int numDisplays = mDisplayContents.size();
                for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                    final WindowList windowList =
                            mDisplayContents.valueAt(displayNdx).getWindowList();
                    for (int winNdx = windowList.size() - 1; winNdx >= 0; --winNdx) {
                        final WindowState w = windowList.get(winNdx);
                        if ((!visibleOnly || w.mWinAnimator.getShown())
                                && (!appsOnly || w.mAppToken != null)) {
                            windows.add(w);
                        }
                    }
                }
            }
        } else {
            int objectId = 0;
            // See if this is an object ID.
            try {
                objectId = Integer.parseInt(name, 16);
                name = null;
            } catch (RuntimeException e) {
            }
            synchronized(mWindowMap) {
                final int numDisplays = mDisplayContents.size();
                for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                    final WindowList windowList =
                            mDisplayContents.valueAt(displayNdx).getWindowList();
                    for (int winNdx = windowList.size() - 1; winNdx >= 0; --winNdx) {
                        final WindowState w = windowList.get(winNdx);
                        if (name != null) {
                            if (w.mAttrs.getTitle().toString().contains(name)) {
                                windows.add(w);
                            }
                        } else if (System.identityHashCode(w) == objectId) {
                            windows.add(w);
                        }
                    }
                }
            }
        }

        if (windows.size() <= 0) {
            return false;
        }

        synchronized(mWindowMap) {
            dumpWindowsLocked(pw, dumpAll, windows);
        }
        return true;
    }

    void dumpLastANRLocked(PrintWriter pw) {
        pw.println("WINDOW MANAGER LAST ANR (dumpsys window lastanr)");
        if (mLastANRState == null) {
            pw.println("  <no ANR has occurred since boot>");
        } else {
            pw.println(mLastANRState);
        }
    }

    /**
     * Saves information about the state of the window manager at
     * the time an ANR occurred before anything else in the system changes
     * in response.
     *
     * @param appWindowToken The application that ANR'd, may be null.
     * @param windowState The window that ANR'd, may be null.
     * @param reason The reason for the ANR, may be null.
     */
    public void saveANRStateLocked(AppWindowToken appWindowToken, WindowState windowState,
            String reason) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new FastPrintWriter(sw, false, 1024);
        pw.println("  ANR time: " + DateFormat.getInstance().format(new Date()));
        if (appWindowToken != null) {
            pw.println("  Application at fault: " + appWindowToken.stringName);
        }
        if (windowState != null) {
            pw.println("  Window at fault: " + windowState.mAttrs.getTitle());
        }
        if (reason != null) {
            pw.println("  Reason: " + reason);
        }
        pw.println();
        dumpWindowsNoHeaderLocked(pw, true, null);
        pw.println();
        pw.println("Last ANR continued");
        dumpDisplayContentsLocked(pw, true);
        pw.close();
        mLastANRState = sw.toString();

        mH.removeMessages(H.RESET_ANR_MESSAGE);
        mH.sendEmptyMessageDelayed(H.RESET_ANR_MESSAGE, LAST_ANR_LIFETIME_DURATION_MSECS);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission("android.permission.DUMP")
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump WindowManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        boolean dumpAll = false;

        int opti = 0;
        while (opti < args.length) {
            String opt = args[opti];
            if (opt == null || opt.length() <= 0 || opt.charAt(0) != '-') {
                break;
            }
            opti++;
            if ("-a".equals(opt)) {
                dumpAll = true;
            } else if ("-h".equals(opt)) {
                pw.println("Window manager dump options:");
                pw.println("  [-a] [-h] [cmd] ...");
                pw.println("  cmd may be one of:");
                pw.println("    l[astanr]: last ANR information");
                pw.println("    p[policy]: policy state");
                pw.println("    a[animator]: animator state");
                pw.println("    s[essions]: active sessions");
                pw.println("    surfaces: active surfaces (debugging enabled only)");
                pw.println("    d[isplays]: active display contents");
                pw.println("    t[okens]: token list");
                pw.println("    w[indows]: window list");
                pw.println("  cmd may also be a NAME to dump windows.  NAME may");
                pw.println("    be a partial substring in a window name, a");
                pw.println("    Window hex object identifier, or");
                pw.println("    \"all\" for all windows, or");
                pw.println("    \"visible\" for the visible windows.");
                pw.println("    \"visible-apps\" for the visible app windows.");
                pw.println("  -a: include all available server state.");
                return;
            } else {
                pw.println("Unknown argument: " + opt + "; use -h for help");
            }
        }

        // Is the caller requesting to dump a particular piece of data?
        if (opti < args.length) {
            String cmd = args[opti];
            opti++;
            if ("lastanr".equals(cmd) || "l".equals(cmd)) {
                synchronized(mWindowMap) {
                    dumpLastANRLocked(pw);
                }
                return;
            } else if ("policy".equals(cmd) || "p".equals(cmd)) {
                synchronized(mWindowMap) {
                    dumpPolicyLocked(pw, args, true);
                }
                return;
            } else if ("animator".equals(cmd) || "a".equals(cmd)) {
                synchronized(mWindowMap) {
                    dumpAnimatorLocked(pw, args, true);
                }
                return;
            } else if ("sessions".equals(cmd) || "s".equals(cmd)) {
                synchronized(mWindowMap) {
                    dumpSessionsLocked(pw, true);
                }
                return;
            } else if ("surfaces".equals(cmd)) {
                synchronized(mWindowMap) {
                    WindowSurfaceController.SurfaceTrace.dumpAllSurfaces(pw, null);
                }
                return;
            } else if ("displays".equals(cmd) || "d".equals(cmd)) {
                synchronized(mWindowMap) {
                    dumpDisplayContentsLocked(pw, true);
                }
                return;
            } else if ("tokens".equals(cmd) || "t".equals(cmd)) {
                synchronized(mWindowMap) {
                    dumpTokensLocked(pw, true);
                }
                return;
            } else if ("windows".equals(cmd) || "w".equals(cmd)) {
                synchronized(mWindowMap) {
                    dumpWindowsLocked(pw, true, null);
                }
                return;
            } else if ("all".equals(cmd) || "a".equals(cmd)) {
                synchronized(mWindowMap) {
                    dumpWindowsLocked(pw, true, null);
                }
                return;
            } else {
                // Dumping a single name?
                if (!dumpWindows(pw, cmd, args, opti, dumpAll)) {
                    pw.println("Bad window command, or no windows match: " + cmd);
                    pw.println("Use -h for help.");
                }
                return;
            }
        }

        synchronized(mWindowMap) {
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpLastANRLocked(pw);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpPolicyLocked(pw, args, dumpAll);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpAnimatorLocked(pw, args, dumpAll);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpSessionsLocked(pw, dumpAll);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            WindowSurfaceController.SurfaceTrace.dumpAllSurfaces(pw, dumpAll ?
                    "-------------------------------------------------------------------------------"
                    : null);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpDisplayContentsLocked(pw, dumpAll);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpTokensLocked(pw, dumpAll);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpWindowsLocked(pw, dumpAll, null);
        }
    }

    // Called by the heartbeat to ensure locks are not held indefnitely (for deadlock detection).
    @Override
    public void monitor() {
        synchronized (mWindowMap) { }
    }

    private DisplayContent newDisplayContentLocked(final Display display) {
        DisplayContent displayContent = new DisplayContent(display, this);
        final int displayId = display.getDisplayId();
        if (DEBUG_DISPLAY) Slog.v(TAG_WM, "Adding display=" + display);
        mDisplayContents.put(displayId, displayContent);

        DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final Rect rect = new Rect();
        mDisplaySettings.getOverscanLocked(displayInfo.name, displayInfo.uniqueId, rect);
        displayInfo.overscanLeft = rect.left;
        displayInfo.overscanTop = rect.top;
        displayInfo.overscanRight = rect.right;
        displayInfo.overscanBottom = rect.bottom;
        mDisplayManagerInternal.setDisplayInfoOverrideFromWindowManager(displayId, displayInfo);
        configureDisplayPolicyLocked(displayContent);

        // TODO: Create an input channel for each display with touch capability.
        if (displayId == Display.DEFAULT_DISPLAY) {
            displayContent.mTapDetector = new TaskTapPointerEventListener(this, displayContent);
            registerPointerEventListener(displayContent.mTapDetector);
            registerPointerEventListener(mMousePositionTracker);
        }

        return displayContent;
    }

    public void createDisplayContentLocked(final Display display) {
        if (display == null) {
            throw new IllegalArgumentException("getDisplayContent: display must not be null");
        }
        getDisplayContentLocked(display.getDisplayId());
    }

    /**
     * Retrieve the DisplayContent for the specified displayId. Will create a new DisplayContent if
     * there is a Display for the displayId.
     * @param displayId The display the caller is interested in.
     * @return The DisplayContent associated with displayId or null if there is no Display for it.
     */
    public DisplayContent getDisplayContentLocked(final int displayId) {
        DisplayContent displayContent = mDisplayContents.get(displayId);
        if (displayContent == null) {
            final Display display = mDisplayManager.getDisplay(displayId);
            if (display != null) {
                displayContent = newDisplayContentLocked(display);
            }
        }
        return displayContent;
    }

    // There is an inherent assumption that this will never return null.
    public DisplayContent getDefaultDisplayContentLocked() {
        return getDisplayContentLocked(Display.DEFAULT_DISPLAY);
    }

    public WindowList getDefaultWindowListLocked() {
        return getDefaultDisplayContentLocked().getWindowList();
    }

    public DisplayInfo getDefaultDisplayInfoLocked() {
        return getDefaultDisplayContentLocked().getDisplayInfo();
    }

    /**
     * Return the list of WindowStates associated on the passed display.
     * @param display The screen to return windows from.
     * @return The list of WindowStates on the screen, or null if the there is no screen.
     */
    public WindowList getWindowListLocked(final Display display) {
        return getWindowListLocked(display.getDisplayId());
    }

    /**
     * Return the list of WindowStates associated on the passed display.
     * @param displayId The screen to return windows from.
     * @return The list of WindowStates on the screen, or null if the there is no screen.
     */
    public WindowList getWindowListLocked(final int displayId) {
        final DisplayContent displayContent = getDisplayContentLocked(displayId);
        return displayContent != null ? displayContent.getWindowList() : null;
    }

    public void onDisplayAdded(int displayId) {
        mH.sendMessage(mH.obtainMessage(H.DO_DISPLAY_ADDED, displayId, 0));
    }

    public void handleDisplayAdded(int displayId) {
        synchronized (mWindowMap) {
            final Display display = mDisplayManager.getDisplay(displayId);
            if (display != null) {
                createDisplayContentLocked(display);
                displayReady(displayId);
            }
            mWindowPlacerLocked.requestTraversal();
        }
    }

    public void onDisplayRemoved(int displayId) {
        mH.sendMessage(mH.obtainMessage(H.DO_DISPLAY_REMOVED, displayId, 0));
    }

    private void handleDisplayRemovedLocked(int displayId) {
        final DisplayContent displayContent = getDisplayContentLocked(displayId);
        if (displayContent != null) {
            if (displayContent.isAnimating()) {
                displayContent.mDeferredRemoval = true;
                return;
            }
            if (DEBUG_DISPLAY) Slog.v(TAG_WM, "Removing display=" + displayContent);
            mDisplayContents.delete(displayId);
            displayContent.close();
            if (displayId == Display.DEFAULT_DISPLAY) {
                unregisterPointerEventListener(displayContent.mTapDetector);
                unregisterPointerEventListener(mMousePositionTracker);
            }
        }
        mAnimator.removeDisplayLocked(displayId);
        mWindowPlacerLocked.requestTraversal();
    }

    public void onDisplayChanged(int displayId) {
        mH.sendMessage(mH.obtainMessage(H.DO_DISPLAY_CHANGED, displayId, 0));
    }

    private void handleDisplayChangedLocked(int displayId) {
        final DisplayContent displayContent = getDisplayContentLocked(displayId);
        if (displayContent != null) {
            displayContent.updateDisplayInfo();
        }
        mWindowPlacerLocked.requestTraversal();
    }

    @Override
    public Object getWindowManagerLock() {
        return mWindowMap;
    }

    /**
     * Hint to a token that its activity will relaunch, which will trigger removal and addition of
     * a window.
     * @param token Application token for which the activity will be relaunched.
     */
    public void setReplacingWindow(IBinder token, boolean animate) {
        AppWindowToken appWindowToken = null;
        synchronized (mWindowMap) {
            appWindowToken = findAppWindowToken(token);
            if (appWindowToken == null || !appWindowToken.isVisible()) {
                Slog.w(TAG_WM, "Attempted to set replacing window on non-existing app token "
                        + token);
                return;
            }
            appWindowToken.setReplacingWindows(animate);
        }
    }

    /**
     * Hint to a token that its windows will be replaced across activity relaunch.
     * The windows would otherwise be removed  shortly following this as the
     * activity is torn down.
     * @param token Application token for which the activity will be relaunched.
     * @param childrenOnly Whether to mark only child windows for replacement
     *                     (for the case where main windows are being preserved/
     *                     reused rather than replaced).
     *
     */
    public void setReplacingWindows(IBinder token, boolean childrenOnly) {
        AppWindowToken appWindowToken = null;
        synchronized (mWindowMap) {
            appWindowToken = findAppWindowToken(token);
            if (appWindowToken == null || !appWindowToken.isVisible()) {
                Slog.w(TAG_WM, "Attempted to set replacing window on non-existing app token "
                        + token);
                return;
            }

            if (childrenOnly) {
                appWindowToken.setReplacingChildren();
            } else {
                appWindowToken.setReplacingWindows(false /* animate */);
            }

            scheduleClearReplacingWindowIfNeeded(token, true /* replacing */);
        }
    }

    /**
     * If we're replacing the window, schedule a timer to clear the replaced window
     * after a timeout, in case the replacing window is not coming.
     *
     * If we're not replacing the window, clear the replace window settings of the app.
     *
     * @param token Application token for the activity whose window might be replaced.
     * @param replacing Whether the window is being replaced or not.
     */
    public void scheduleClearReplacingWindowIfNeeded(IBinder token, boolean replacing) {
        AppWindowToken appWindowToken = null;
        synchronized (mWindowMap) {
            appWindowToken = findAppWindowToken(token);
            if (appWindowToken == null) {
                Slog.w(TAG_WM, "Attempted to reset replacing window on non-existing app token "
                        + token);
                return;
            }
            if (replacing) {
                scheduleReplacingWindowTimeouts(appWindowToken);
            } else {
                appWindowToken.resetReplacingWindows();
            }
        }
    }

    void scheduleReplacingWindowTimeouts(AppWindowToken appWindowToken) {
        if (!mReplacingWindowTimeouts.contains(appWindowToken)) {
            mReplacingWindowTimeouts.add(appWindowToken);
        }
        mH.removeMessages(H.WINDOW_REPLACEMENT_TIMEOUT);
        mH.sendEmptyMessageDelayed(
                H.WINDOW_REPLACEMENT_TIMEOUT, WINDOW_REPLACEMENT_TIMEOUT_DURATION);
    }

    @Override
    public int getDockedStackSide() {
        synchronized (mWindowMap) {
            final TaskStack dockedStack = getDefaultDisplayContentLocked()
                    .getDockedStackVisibleForUserLocked();
            return dockedStack == null ? DOCKED_INVALID : dockedStack.getDockSide();
        }
    }

    @Override
    public void setDockedStackResizing(boolean resizing) {
        synchronized (mWindowMap) {
            getDefaultDisplayContentLocked().getDockedDividerController().setResizing(resizing);
            requestTraversal();
        }
    }

    @Override
    public void setDockedStackDividerTouchRegion(Rect touchRegion) {
        synchronized (mWindowMap) {
            getDefaultDisplayContentLocked().getDockedDividerController()
                    .setTouchRegion(touchRegion);
            setFocusTaskRegionLocked();
        }
    }

    @Override
    public void setResizeDimLayer(boolean visible, int targetStackId, float alpha) {
        synchronized (mWindowMap) {
            getDefaultDisplayContentLocked().getDockedDividerController().setResizeDimLayer(
                    visible, targetStackId, alpha);
        }
    }

    public void animateResizePinnedStack(final Rect bounds, final int animationDuration) {
        synchronized (mWindowMap) {
            final TaskStack stack = mStackIdToStack.get(PINNED_STACK_ID);
            if (stack == null) {
                Slog.w(TAG, "animateResizePinnedStack: stackId " + PINNED_STACK_ID + " not found.");
                return;
            }
            final Rect originalBounds = new Rect();
            stack.getBounds(originalBounds);
            UiThread.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    mBoundsAnimationController.animateBounds(
                            stack, originalBounds, bounds, animationDuration);
                }
            });
        }
    }

    public void setTaskResizeable(int taskId, int resizeMode) {
        synchronized (mWindowMap) {
            final Task task = mTaskIdToTask.get(taskId);
            if (task != null) {
                task.setResizeable(resizeMode);
            }
        }
    }

    public void setForceResizableTasks(boolean forceResizableTasks) {
        synchronized (mWindowMap) {
            mForceResizableTasks = forceResizableTasks;
        }
    }

    static int dipToPixel(int dip, DisplayMetrics displayMetrics) {
        return (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip, displayMetrics);
    }

    @Override
    public void registerDockedStackListener(IDockedStackListener listener) {
        if (!checkCallingPermission(android.Manifest.permission.REGISTER_WINDOW_MANAGER_LISTENERS,
                "registerDockedStackListener()")) {
            return;
        }
        // TODO(multi-display): The listener is registered on the default display only.
        getDefaultDisplayContentLocked().mDividerControllerLocked.registerDockedStackListener(
                listener);
    }

    @Override
    public void requestAppKeyboardShortcuts(IResultReceiver receiver, int deviceId) {
        try {
            WindowState focusedWindow = getFocusedWindow();
            if (focusedWindow != null && focusedWindow.mClient != null) {
                getFocusedWindow().mClient.requestAppKeyboardShortcuts(receiver, deviceId);
            }
        } catch (RemoteException e) {
        }
    }

    @Override
    public void getStableInsets(Rect outInsets) throws RemoteException {
        synchronized (mWindowMap) {
            getStableInsetsLocked(outInsets);
        }
    }

    void getStableInsetsLocked(Rect outInsets) {
        final DisplayInfo di = getDefaultDisplayInfoLocked();
        mPolicy.getStableInsetsLw(di.rotation, di.logicalWidth, di.logicalHeight, outInsets);
    }

    private void getNonDecorInsetsLocked(Rect outInsets) {
        final DisplayInfo di = getDefaultDisplayInfoLocked();
        mPolicy.getNonDecorInsetsLw(di.rotation, di.logicalWidth, di.logicalHeight, outInsets);
    }

    /**
     * Intersects the specified {@code inOutBounds} with the display frame that excludes the stable
     * inset areas.
     *
     * @param inOutBounds The inOutBounds to subtract the stable inset areas from.
     */
    public void subtractStableInsets(Rect inOutBounds) {
        synchronized (mWindowMap) {
            getStableInsetsLocked(mTmpRect2);
            final DisplayInfo di = getDefaultDisplayInfoLocked();
            mTmpRect.set(0, 0, di.logicalWidth, di.logicalHeight);
            subtractInsets(mTmpRect, mTmpRect2, inOutBounds);
        }
    }

    /**
     * Intersects the specified {@code inOutBounds} with the display frame that excludes
     * areas that could never be removed in Honeycomb. See
     * {@link WindowManagerPolicy#getNonDecorInsetsLw}.
     *
     * @param inOutBounds The inOutBounds to subtract the inset areas from.
     */
    public void subtractNonDecorInsets(Rect inOutBounds) {
        synchronized (mWindowMap) {
            getNonDecorInsetsLocked(mTmpRect2);
            final DisplayInfo di = getDefaultDisplayInfoLocked();
            mTmpRect.set(0, 0, di.logicalWidth, di.logicalHeight);
            subtractInsets(mTmpRect, mTmpRect2, inOutBounds);
        }
    }

    void subtractInsets(Rect display, Rect insets, Rect inOutBounds) {
        mTmpRect3.set(display);
        mTmpRect3.inset(insets);
        inOutBounds.intersect(mTmpRect3);
    }

    /**
     * Calculates the smallest width for a task given the {@param bounds}. It does that by iterating
     * across all screen orientations, and returns the minimum of the task width taking into account
     * that the bounds might change because the snap algorithm snaps to a different value.
     *
     * @return the smallest width to be used in the Configuration, in dips
     */
    public int getSmallestWidthForTaskBounds(Rect bounds) {
        synchronized (mWindowMap) {
            return getDefaultDisplayContentLocked().getDockedDividerController()
                    .getSmallestWidthDpForBounds(bounds);
        }
    }

    private MousePositionTracker mMousePositionTracker = new MousePositionTracker();

    private static class MousePositionTracker implements PointerEventListener {
        private boolean mLatestEventWasMouse;
        private float mLatestMouseX;
        private float mLatestMouseY;

        void updatePosition(float x, float y) {
            synchronized (this) {
                mLatestEventWasMouse = true;
                mLatestMouseX = x;
                mLatestMouseY = y;
            }
        }

        @Override
        public void onPointerEvent(MotionEvent motionEvent) {
            if (motionEvent.isFromSource(InputDevice.SOURCE_MOUSE)) {
                updatePosition(motionEvent.getRawX(), motionEvent.getRawY());
            } else {
                synchronized (this) {
                    mLatestEventWasMouse = false;
                }
            }
        }
    };

    void updatePointerIcon(IWindow client) {
        float mouseX, mouseY;

        synchronized(mMousePositionTracker) {
            if (!mMousePositionTracker.mLatestEventWasMouse) {
                return;
            }
            mouseX = mMousePositionTracker.mLatestMouseX;
            mouseY = mMousePositionTracker.mLatestMouseY;
        }

        synchronized (mWindowMap) {
            if (mDragState != null) {
                // Drag cursor overrides the app cursor.
                return;
            }
            WindowState callingWin = windowForClientLocked(null, client, false);
            if (callingWin == null) {
                Slog.w(TAG_WM, "Bad requesting window " + client);
                return;
            }
            final DisplayContent displayContent = callingWin.getDisplayContent();
            if (displayContent == null) {
                return;
            }
            WindowState windowUnderPointer =
                    displayContent.getTouchableWinAtPointLocked(mouseX, mouseY);
            if (windowUnderPointer != callingWin) {
                return;
            }
            try {
                windowUnderPointer.mClient.updatePointerIcon(
                        windowUnderPointer.translateToWindowX(mouseX),
                        windowUnderPointer.translateToWindowY(mouseY));
            } catch (RemoteException e) {
                Slog.w(TAG_WM, "unable to update pointer icon");
            }
        }
    }

    void restorePointerIconLocked(DisplayContent displayContent, float latestX, float latestY) {
        // Mouse position tracker has not been getting updates while dragging, update it now.
        mMousePositionTracker.updatePosition(latestX, latestY);

        WindowState windowUnderPointer =
                displayContent.getTouchableWinAtPointLocked(latestX, latestY);
        if (windowUnderPointer != null) {
            try {
                windowUnderPointer.mClient.updatePointerIcon(
                        windowUnderPointer.translateToWindowX(latestX),
                        windowUnderPointer.translateToWindowY(latestY));
            } catch (RemoteException e) {
                Slog.w(TAG_WM, "unable to restore pointer icon");
            }
        } else {
            InputManager.getInstance().setPointerIconType(PointerIcon.TYPE_DEFAULT);
        }
    }

    @Override
    public void registerShortcutKey(long shortcutCode, IShortcutService shortcutKeyReceiver)
            throws RemoteException {
        if (!checkCallingPermission(Manifest.permission.REGISTER_WINDOW_MANAGER_LISTENERS,
                "registerShortcutKey")) {
            throw new SecurityException(
                    "Requires REGISTER_WINDOW_MANAGER_LISTENERS permission");
        }
        mPolicy.registerShortcutKey(shortcutCode, shortcutKeyReceiver);
    }

    private final class LocalService extends WindowManagerInternal {
        @Override
        public void requestTraversalFromDisplayManager() {
            requestTraversal();
        }

        @Override
        public void setMagnificationSpec(MagnificationSpec spec) {
            synchronized (mWindowMap) {
                if (mAccessibilityController != null) {
                    mAccessibilityController.setMagnificationSpecLocked(spec);
                } else {
                    throw new IllegalStateException("Magnification callbacks not set!");
                }
            }
            if (Binder.getCallingPid() != android.os.Process.myPid()) {
                spec.recycle();
            }
        }

        @Override
        public void getMagnificationRegion(@NonNull Region magnificationRegion) {
            synchronized (mWindowMap) {
                if (mAccessibilityController != null) {
                    mAccessibilityController.getMagnificationRegionLocked(magnificationRegion);
                } else {
                    throw new IllegalStateException("Magnification callbacks not set!");
                }
            }
        }

        @Override
        public MagnificationSpec getCompatibleMagnificationSpecForWindow(IBinder windowToken) {
            synchronized (mWindowMap) {
                WindowState windowState = mWindowMap.get(windowToken);
                if (windowState == null) {
                    return null;
                }
                MagnificationSpec spec = null;
                if (mAccessibilityController != null) {
                    spec = mAccessibilityController.getMagnificationSpecForWindowLocked(windowState);
                }
                if ((spec == null || spec.isNop()) && windowState.mGlobalScale == 1.0f) {
                    return null;
                }
                spec = (spec == null) ? MagnificationSpec.obtain() : MagnificationSpec.obtain(spec);
                spec.scale *= windowState.mGlobalScale;
                return spec;
            }
        }

        @Override
        public void setMagnificationCallbacks(@Nullable MagnificationCallbacks callbacks) {
            synchronized (mWindowMap) {
                if (mAccessibilityController == null) {
                    mAccessibilityController = new AccessibilityController(
                            WindowManagerService.this);
                }
                mAccessibilityController.setMagnificationCallbacksLocked(callbacks);
                if (!mAccessibilityController.hasCallbacksLocked()) {
                    mAccessibilityController = null;
                }
            }
        }

        @Override
        public void setWindowsForAccessibilityCallback(WindowsForAccessibilityCallback callback) {
            synchronized (mWindowMap) {
                if (mAccessibilityController == null) {
                    mAccessibilityController = new AccessibilityController(
                            WindowManagerService.this);
                }
                mAccessibilityController.setWindowsForAccessibilityCallback(callback);
                if (!mAccessibilityController.hasCallbacksLocked()) {
                    mAccessibilityController = null;
                }
            }
        }

        @Override
        public void setInputFilter(IInputFilter filter) {
            mInputManager.setInputFilter(filter);
        }

        @Override
        public IBinder getFocusedWindowToken() {
            synchronized (mWindowMap) {
                WindowState windowState = getFocusedWindowLocked();
                if (windowState != null) {
                    return windowState.mClient.asBinder();
                }
                return null;
            }
        }

        @Override
        public boolean isKeyguardLocked() {
            return WindowManagerService.this.isKeyguardLocked();
        }

        @Override
        public void showGlobalActions() {
            WindowManagerService.this.showGlobalActions();
        }

        @Override
        public void getWindowFrame(IBinder token, Rect outBounds) {
            synchronized (mWindowMap) {
                WindowState windowState = mWindowMap.get(token);
                if (windowState != null) {
                    outBounds.set(windowState.mFrame);
                } else {
                    outBounds.setEmpty();
                }
            }
        }

        @Override
        public void waitForAllWindowsDrawn(Runnable callback, long timeout) {
            boolean allWindowsDrawn = false;
            synchronized (mWindowMap) {
                mWaitingForDrawnCallback = callback;
                final WindowList windows = getDefaultWindowListLocked();
                for (int winNdx = windows.size() - 1; winNdx >= 0; --winNdx) {
                    final WindowState win = windows.get(winNdx);
                    final boolean isForceHiding = mPolicy.isForceHiding(win.mAttrs);
                    if (win.isVisibleLw()
                            && (win.mAppToken != null || isForceHiding)) {
                        win.mWinAnimator.mDrawState = DRAW_PENDING;
                        // Force add to mResizingWindows.
                        win.mLastContentInsets.set(-1, -1, -1, -1);
                        mWaitingForDrawn.add(win);

                        // No need to wait for the windows below Keyguard.
                        if (isForceHiding) {
                            break;
                        }
                    }
                }
                mWindowPlacerLocked.requestTraversal();
                mH.removeMessages(H.WAITING_FOR_DRAWN_TIMEOUT);
                if (mWaitingForDrawn.isEmpty()) {
                    allWindowsDrawn = true;
                } else {
                    mH.sendEmptyMessageDelayed(H.WAITING_FOR_DRAWN_TIMEOUT, timeout);
                    checkDrawnWindowsLocked();
                }
            }
            if (allWindowsDrawn) {
                callback.run();
            }
        }

        @Override
        public void addWindowToken(IBinder token, int type) {
            WindowManagerService.this.addWindowToken(token, type);
        }

        @Override
        public void removeWindowToken(IBinder token, boolean removeWindows) {
            synchronized(mWindowMap) {
                if (removeWindows) {
                    WindowToken wtoken = mTokenMap.remove(token);
                    if (wtoken != null) {
                        wtoken.removeAllWindows();
                    }
                }
                WindowManagerService.this.removeWindowToken(token);
            }
        }

        @Override
        public void registerAppTransitionListener(AppTransitionListener listener) {
            synchronized (mWindowMap) {
                mAppTransition.registerListenerLocked(listener);
            }
        }

        @Override
        public int getInputMethodWindowVisibleHeight() {
            synchronized (mWindowMap) {
                return mPolicy.getInputMethodWindowVisibleHeightLw();
            }
        }

        @Override
        public void saveLastInputMethodWindowForTransition() {
            synchronized (mWindowMap) {
                if (mInputMethodWindow != null) {
                    mPolicy.setLastInputMethodWindowLw(mInputMethodWindow, mInputMethodTarget);
                }
            }
        }

        @Override
        public void clearLastInputMethodWindowForTransition() {
            synchronized (mWindowMap) {
                mPolicy.setLastInputMethodWindowLw(null, null);
            }
        }

        @Override
        public boolean isHardKeyboardAvailable() {
            synchronized (mWindowMap) {
                return mHardKeyboardAvailable;
            }
        }

        @Override
        public void setOnHardKeyboardStatusChangeListener(
                OnHardKeyboardStatusChangeListener listener) {
            synchronized (mWindowMap) {
                mHardKeyboardStatusChangeListener = listener;
            }
        }

        @Override
        public boolean isStackVisible(int stackId) {
            synchronized (mWindowMap) {
                return WindowManagerService.this.isStackVisibleLocked(stackId);
            }
        }

        @Override
        public boolean isDockedDividerResizing() {
            synchronized (mWindowMap) {
                return getDefaultDisplayContentLocked().getDockedDividerController().isResizing();
            }
        }
    }
}
