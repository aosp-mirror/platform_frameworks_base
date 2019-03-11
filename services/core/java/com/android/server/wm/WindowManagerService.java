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

import static android.Manifest.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS;
import static android.Manifest.permission.INTERNAL_SYSTEM_WINDOW;
import static android.Manifest.permission.MANAGE_APP_TOKENS;
import static android.Manifest.permission.READ_FRAME_BUFFER;
import static android.Manifest.permission.REGISTER_WINDOW_MANAGER_LISTENERS;
import static android.Manifest.permission.RESTRICTED_VR_ACCESS;
import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.app.ActivityManagerInternal.ALLOW_FULL_ONLY;
import static android.app.ActivityTaskManager.SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
import static android.app.AppOpsManager.OP_SYSTEM_ALERT_WINDOW;
import static android.app.StatusBarManager.DISABLE_MASK;
import static android.app.admin.DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.SYSTEM_UID;
import static android.os.Process.myPid;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.provider.Settings.Global.DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.DOCKED_INVALID;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_DRAG;
import static android.view.WindowManager.LayoutParams.TYPE_DREAM;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_QS_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManager.REMOVE_CONTENT_MODE_UNDEFINED;
import static android.view.WindowManagerGlobal.RELAYOUT_DEFER_SURFACE_DESTROY;
import static android.view.WindowManagerGlobal.RELAYOUT_RES_SURFACE_CHANGED;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_INVALID;

import static com.android.internal.util.LatencyTracker.ACTION_ROTATE_SCREEN;
import static com.android.server.LockGuard.INDEX_WINDOW;
import static com.android.server.LockGuard.installLock;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_BOOT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_CONFIGURATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DISPLAY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_INPUT_METHOD;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_KEEP_SCREEN_ON;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SCREENSHOT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SCREEN_ON;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_TRACE;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_STACK_CRAWLS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_VERBOSE_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_KEEP_SCREEN_ON;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerServiceDumpProto.DISPLAY_FROZEN;
import static com.android.server.wm.WindowManagerServiceDumpProto.FOCUSED_APP;
import static com.android.server.wm.WindowManagerServiceDumpProto.FOCUSED_WINDOW;
import static com.android.server.wm.WindowManagerServiceDumpProto.INPUT_METHOD_WINDOW;
import static com.android.server.wm.WindowManagerServiceDumpProto.LAST_ORIENTATION;
import static com.android.server.wm.WindowManagerServiceDumpProto.POLICY;
import static com.android.server.wm.WindowManagerServiceDumpProto.ROOT_WINDOW_CONTAINER;
import static com.android.server.wm.WindowManagerServiceDumpProto.ROTATION;

import android.Manifest;
import android.Manifest.permission;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.TaskSnapshot;
import android.app.ActivityManagerInternal;
import android.app.ActivityTaskManager;
import android.app.ActivityThread;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.app.IAssistDataReceiver;
import android.app.WindowConfiguration;
import android.app.admin.DevicePolicyCache;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.hardware.configstore.V1_0.ISurfaceFlingerConfigs;
import android.hardware.configstore.V1_0.OptionalBool;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.input.InputManager;
import android.hardware.input.InputManagerInternal;
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
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.SystemService;
import android.os.Trace;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.TimeUtils;
import android.util.TypedValue;
import android.util.proto.ProtoOutputStream;
import android.view.Choreographer;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.IDisplayFoldListener;
import android.view.IDockedStackListener;
import android.view.IInputFilter;
import android.view.IOnKeyguardExitResult;
import android.view.IPinnedStackListener;
import android.view.IRecentsAnimationRunner;
import android.view.IRotationWatcher;
import android.view.IWallpaperVisibilityListener;
import android.view.IWindow;
import android.view.IWindowId;
import android.view.IWindowManager;
import android.view.IWindowSession;
import android.view.IWindowSessionCallback;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InsetsState;
import android.view.KeyEvent;
import android.view.MagnificationSpec;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.RemoteAnimationAdapter;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.View;
import android.view.WindowContentFrameStats;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManager.RemoveContentMode;
import android.view.WindowManager.TransitionType;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerPolicyConstants.PointerEventListener;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.IResultReceiver;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IShortcutService;
import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.LatencyTracker;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.view.WindowManagerPolicyThread;
import com.android.server.AnimationThread;
import com.android.server.DisplayThread;
import com.android.server.EventLogTags;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.UiThread;
import com.android.server.Watchdog;
import com.android.server.input.InputManagerService;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.policy.WindowManagerPolicy.ScreenOffListener;
import com.android.server.power.ShutdownThread;
import com.android.server.utils.PriorityDump;

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

    /** Amount of time (in milliseconds) to delay before declaring a seamless rotation timeout. */
    static final int SEAMLESS_ROTATION_TIMEOUT_DURATION = 2000;

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
    /** Indicates we are removing the focused window when updating the focus. */
    static final int UPDATE_FOCUS_REMOVING_FOCUS = 4;

    private static final String SYSTEM_SECURE = "ro.secure";
    private static final String SYSTEM_DEBUGGABLE = "ro.debuggable";

    private static final String DENSITY_OVERRIDE = "ro.config.density_override";
    private static final String SIZE_OVERRIDE = "ro.config.size_override";

    private static final int MAX_SCREENSHOT_RETRIES = 3;

    private static final String PROPERTY_EMULATOR_CIRCULAR = "ro.emulator.circular";

    // Used to indicate that if there is already a transition set, it should be preserved when
    // trying to apply a new one.
    private static final boolean ALWAYS_KEEP_CURRENT = true;

    // Enums for animation scale update types.
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({WINDOW_ANIMATION_SCALE, TRANSITION_ANIMATION_SCALE, ANIMATION_DURATION_SCALE})
    private @interface UpdateAnimationScaleMode {};
    private static final int WINDOW_ANIMATION_SCALE = 0;
    private static final int TRANSITION_ANIMATION_SCALE = 1;
    private static final int ANIMATION_DURATION_SCALE = 2;

    private static final int ANIMATION_COMPLETED_TIMEOUT_MS = 5000;

    final WindowTracing mWindowTracing;

    final private KeyguardDisableHandler mKeyguardDisableHandler;
    // TODO: eventually unify all keyguard state in a common place instead of having it spread over
    // AM's KeyguardController and the policy's KeyguardServiceDelegate.
    boolean mKeyguardGoingAway;
    boolean mKeyguardOrAodShowingOnDefaultDisplay;
    // VR Vr2d Display Id.
    int mVr2dDisplayId = INVALID_DISPLAY;
    boolean mVrModeEnabled = false;

    private final IVrStateCallbacks mVrStateCallbacks = new IVrStateCallbacks.Stub() {
        @Override
        public void onVrStateChanged(boolean enabled) {
            synchronized (mGlobalLock) {
                mVrModeEnabled = enabled;
                mRoot.forAllDisplayPolicies(PooledLambda.obtainConsumer(
                        DisplayPolicy::onVrStateChangedLw, PooledLambda.__(), enabled));
            }
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED:
                    mKeyguardDisableHandler.updateKeyguardEnabled(getSendingUserId());
                    break;
            }
        }
    };
    final WindowSurfacePlacer mWindowPlacerLocked;

    private final PriorityDump.PriorityDumper mPriorityDumper = new PriorityDump.PriorityDumper() {
        @Override
        public void dumpCritical(FileDescriptor fd, PrintWriter pw, String[] args,
                boolean asProto) {
            doDump(fd, pw, new String[] {"-a"}, asProto);
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
            doDump(fd, pw, args, asProto);
        }
    };

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

    final boolean mHasPermanentDpad;
    final long mDrawLockTimeoutMillis;
    final boolean mAllowAnimationsInLowPowerMode;

    // TODO(b/122671846) Remove the flag below in favor of isLowRam once feature is stable
    /**
     * Use very low resolution task snapshots. Replaces task snapshot starting windows with
     * splashscreen starting windows. Used on low RAM devices to save memory.
     */
    final boolean mLowRamTaskSnapshotsAndRecents;

    final boolean mAllowBootMessages;

    final boolean mLimitedAlphaCompositing;
    final int mMaxUiWidth;

    @VisibleForTesting
    WindowManagerPolicy mPolicy;

    final IActivityManager mActivityManager;
    // TODO: Probably not needed once activities are fully in WM.
    final IActivityTaskManager mActivityTaskManager;
    final ActivityManagerInternal mAmInternal;
    final ActivityTaskManagerInternal mAtmInternal;

    final AppOpsManager mAppOps;
    final PackageManagerInternal mPmInternal;

    final DisplayWindowSettings mDisplayWindowSettings;

    /** If the system should display notifications for apps displaying an alert window. */
    boolean mShowAlertWindowNotifications = true;

    /**
     * All currently active sessions with clients.
     */
    final ArraySet<Session> mSessions = new ArraySet<>();

    /** Mapping from an IWindow IBinder to the server's Window object. */
    final WindowHashMap mWindowMap = new WindowHashMap();

    /** Global service lock used by the package the owns this service. */
    final WindowManagerGlobalLock mGlobalLock;

    /**
     * List of app window tokens that are waiting for replacing windows. If the
     * replacement doesn't come in time the stale windows needs to be disposed of.
     */
    final ArrayList<AppWindowToken> mWindowReplacementTimeouts = new ArrayList<>();

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

    // TODO: use WindowProcessController once go/wm-unified is done.
    /** Mapping of process pids to configurations */
    final SparseArray<Configuration> mProcessConfigurations = new SparseArray<>();

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

    /** List of window currently causing non-system overlay windows to be hidden. */
    private ArrayList<WindowState> mHidingNonSystemOverlayWindows = new ArrayList<>();

    AccessibilityController mAccessibilityController;
    private RecentsAnimationController mRecentsAnimationController;

    Watermark mWatermark;
    StrictModeFlash mStrictModeFlash;
    CircularDisplayMask mCircularDisplayMask;
    EmulatorDisplayOverlay mEmulatorDisplayOverlay;

    final float[] mTmpFloats = new float[9];
    final Rect mTmpRect = new Rect();
    final Rect mTmpRect2 = new Rect();
    final Rect mTmpRect3 = new Rect();
    final RectF mTmpRectF = new RectF();

    final Matrix mTmpTransform = new Matrix();

    boolean mDisplayReady;
    boolean mSafeMode;
    boolean mDisplayEnabled = false;
    boolean mSystemBooted = false;
    boolean mForceDisplayEnabled = false;
    boolean mShowingBootMessages = false;
    boolean mBootAnimationStopped = false;
    boolean mSystemReady = false;

    // Following variables are for debugging screen wakelock only.
    WindowState mLastWakeLockHoldingWindow = null;
    WindowState mLastWakeLockObscuringWindow = null;

    /** Dump of the windows and app tokens at the time of the last ANR. Cleared after
     * LAST_ANR_LIFETIME_DURATION_MSECS */
    String mLastANRState;

    // The root of the device window hierarchy.
    RootWindowContainer mRoot;

    int mDockedStackCreateMode = SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
    Rect mDockedStackCreateBounds;

    boolean mForceResizableTasks;
    boolean mSupportsPictureInPicture;
    boolean mSupportsFreeformWindowManagement;
    boolean mIsPc;
    /**
     * Flag that indicates that desktop mode is forced for public secondary screens.
     *
     * This includes several settings:
     * - Set freeform windowing mode on external screen if it's supported and enabled.
     * - Enable system decorations and IME on external screen.
     * - TODO: Show mouse pointer on external screen.
     */
    boolean mForceDesktopModeOnExternalDisplays;

    boolean mDisableTransitionAnimation;

    int getDragLayerLocked() {
        return mPolicy.getWindowLayerFromTypeLw(TYPE_DRAG) * TYPE_LAYER_MULTIPLIER + TYPE_LAYER_OFFSET;
    }

    class RotationWatcher {
        final IRotationWatcher mWatcher;
        final IBinder.DeathRecipient mDeathRecipient;
        final int mDisplayId;
        RotationWatcher(IRotationWatcher watcher, IBinder.DeathRecipient deathRecipient,
                int displayId) {
            mWatcher = watcher;
            mDeathRecipient = deathRecipient;
            mDisplayId = displayId;
        }
    }

    ArrayList<RotationWatcher> mRotationWatchers = new ArrayList<>();
    final WallpaperVisibilityListeners mWallpaperVisibilityListeners =
            new WallpaperVisibilityListeners();

    boolean mDisplayFrozen = false;
    long mDisplayFreezeTime = 0;
    int mLastDisplayFreezeDuration = 0;
    Object mLastFinishedFreezeSource = null;
    boolean mSwitchingUser = false;

    final static int WINDOWS_FREEZING_SCREENS_NONE = 0;
    final static int WINDOWS_FREEZING_SCREENS_ACTIVE = 1;
    final static int WINDOWS_FREEZING_SCREENS_TIMEOUT = 2;
    int mWindowsFreezingScreen = WINDOWS_FREEZING_SCREENS_NONE;

    boolean mClientFreezingScreen = false;
    int mAppsFreezingScreen = 0;

    @VisibleForTesting
    boolean mPerDisplayFocusEnabled;

    // State while inside of layoutAndPlaceSurfacesLocked().
    boolean mFocusMayChange;

    // This is held as long as we have the screen frozen, to give us time to
    // perform a rotation animation when turning off shows the lock screen which
    // changes the orientation.
    private final PowerManager.WakeLock mScreenFrozenLock;

    final TaskSnapshotController mTaskSnapshotController;

    boolean mIsTouchDevice;

    final H mH = new H();

    /**
     * Handler for things to run that have direct impact on an animation, i.e. animation tick,
     * layout, starting window creation, whereas {@link H} runs things that are still important, but
     * not as critical.
     */
    final Handler mAnimationHandler = new Handler(AnimationThread.getHandler().getLooper());

    boolean mHardKeyboardAvailable;
    WindowManagerInternal.OnHardKeyboardStatusChangeListener mHardKeyboardStatusChangeListener;
    SettingsObserver mSettingsObserver;

    /**
     * A count of the windows which are 'seamlessly rotated', e.g. a surface
     * at an old orientation is being transformed. We freeze orientation updates
     * while any windows are seamlessly rotated, so we need to track when this
     * hits zero so we can apply deferred orientation updates.
     */
    private int mSeamlessRotationCount = 0;
    /**
     * True in the interval from starting seamless rotation until the last rotated
     * window draws in the new orientation.
     */
    private boolean mRotatingSeamlessly = false;

    private final class SettingsObserver extends ContentObserver {
        private final Uri mDisplayInversionEnabledUri =
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED);
        private final Uri mWindowAnimationScaleUri =
                Settings.Global.getUriFor(Settings.Global.WINDOW_ANIMATION_SCALE);
        private final Uri mTransitionAnimationScaleUri =
                Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE);
        private final Uri mAnimationDurationScaleUri =
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE);
        private final Uri mImmersiveModeConfirmationsUri =
                Settings.Secure.getUriFor(Settings.Secure.IMMERSIVE_MODE_CONFIRMATIONS);
        private final Uri mPolicyControlUri =
                Settings.Global.getUriFor(Settings.Global.POLICY_CONTROL);

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
            resolver.registerContentObserver(mImmersiveModeConfirmationsUri, false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(mPolicyControlUri, false, this,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri == null) {
                return;
            }

            if (mImmersiveModeConfirmationsUri.equals(uri) || mPolicyControlUri.equals(uri)) {
                updateSystemUiSettings();
                return;
            }

            if (mDisplayInversionEnabledUri.equals(uri)) {
                updateCircularDisplayMaskIfNeeded();
                return;
            }

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

        void updateSystemUiSettings() {
            boolean changed;
            synchronized (mGlobalLock) {
                changed = ImmersiveModeConfirmation.loadSetting(mCurrentUserId, mContext)
                        || PolicyControl.reloadFromSetting(mContext);
            }
            if (changed) {
                updateRotation(false /* alwaysSendConfiguration */, false /* forceRelayout */);
            }
        }
    }

    PowerManager mPowerManager;
    PowerManagerInternal mPowerManagerInternal;

    private float mWindowAnimationScaleSetting = 1.0f;
    private float mTransitionAnimationScaleSetting = 1.0f;
    private float mAnimatorDurationScaleSetting = 1.0f;
    private boolean mAnimationsDisabled = false;

    final InputManagerService mInputManager;
    final DisplayManagerInternal mDisplayManagerInternal;
    final DisplayManager mDisplayManager;
    final ActivityTaskManagerService mAtmService;

    /** Corner radius that windows should have in order to match the display. */
    final float mWindowCornerRadius;

    /** Indicates whether this device supports wide color gamut / HDR rendering */
    private boolean mHasWideColorGamutSupport;
    private boolean mHasHdrSupport;

    /** Who is holding the screen on. */
    private Session mHoldingScreenOn;
    private PowerManager.WakeLock mHoldingScreenWakeLock;

    /** Whether or not a layout can cause a wake up when theater mode is enabled. */
    boolean mAllowTheaterModeWakeFromLayout;

    final TaskPositioningController mTaskPositioningController;
    final DragDropController mDragDropController;

    /** For frozen screen animations. */
    private int mExitAnimId, mEnterAnimId;

    /** The display that the rotation animation is applying to. */
    private int mFrozenDisplayId;

    /** Skip repeated AppWindowTokens initialization. Note that AppWindowsToken's version of this
     * is a long initialized to Long.MIN_VALUE so that it doesn't match this value on startup. */
    int mTransactionSequence;

    final WindowAnimator mAnimator;
    final SurfaceAnimationRunner mSurfaceAnimationRunner;

    /**
     * Keeps track of which animations got transferred to which animators. Entries will get cleaned
     * up when the animation finishes.
     */
    final ArrayMap<AnimationAdapter, SurfaceAnimator> mAnimationTransferMap = new ArrayMap<>();

    private WindowContentFrameStats mTempWindowRenderStats;

    private final LatencyTracker mLatencyTracker;

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

    // If true, only the core apps and services are being launched because the device
    // is in a special boot mode, such as being encrypted or waiting for a decryption password.
    // For example, when this flag is true, there will be no wallpaper service.
    final boolean mOnlyCore;

    static WindowManagerThreadPriorityBooster sThreadPriorityBooster =
            new WindowManagerThreadPriorityBooster();

    SurfaceBuilderFactory mSurfaceBuilderFactory = SurfaceControl.Builder::new;
    TransactionFactory mTransactionFactory = SurfaceControl.Transaction::new;
    SurfaceFactory mSurfaceFactory = Surface::new;

    private final SurfaceControl.Transaction mTransaction;

    static void boostPriorityForLockedSection() {
        sThreadPriorityBooster.boost();
    }

    static void resetPriorityAfterLockedSection() {
        sThreadPriorityBooster.reset();
    }

    void openSurfaceTransaction() {
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "openSurfaceTransaction");
            synchronized (mGlobalLock) {
                SurfaceControl.openTransaction();
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    /**
     * Closes a surface transaction.
     * @param where debug string indicating where the transaction originated
     */
    void closeSurfaceTransaction(String where) {
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "closeSurfaceTransaction");
            synchronized (mGlobalLock) {
                SurfaceControl.closeTransaction();
                mWindowTracing.logState(where);
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }
    /** Listener to notify activity manager about app transitions. */
    final WindowManagerInternal.AppTransitionListener mActivityManagerAppTransitionNotifier
            = new WindowManagerInternal.AppTransitionListener() {

        @Override
        public void onAppTransitionCancelledLocked(int transit) {
            mAtmInternal.notifyAppTransitionCancelled();
        }

        @Override
        public void onAppTransitionFinishedLocked(IBinder token) {
            mAtmInternal.notifyAppTransitionFinished();
            final AppWindowToken atoken = mRoot.getAppWindowToken(token);
            if (atoken == null) {
                return;
            }
            if (atoken.mLaunchTaskBehind) {
                try {
                    mActivityTaskManager.notifyLaunchTaskBehindComplete(atoken.token);
                } catch (RemoteException e) {
                }
                atoken.mLaunchTaskBehind = false;
            } else {
                atoken.updateReportedVisibilityLocked();
                if (atoken.mEnteringAnimation) {
                    if (getRecentsAnimationController() != null
                            && getRecentsAnimationController().isTargetApp(atoken)) {
                        // Currently running a recents animation, this will get called early because
                        // we show the recents animation target activity immediately when the
                        // animation starts. In this case, we should defer sending the finished
                        // callback until the animation successfully finishes
                        return;
                    } else {
                        atoken.mEnteringAnimation = false;
                        try {
                            mActivityTaskManager.notifyEnterAnimationComplete(atoken.token);
                        } catch (RemoteException e) {
                        }
                    }
                }
            }
        }
    };

    final ArrayList<AppFreezeListener> mAppFreezeListeners = new ArrayList<>();

    interface AppFreezeListener {
        void onAppFreezeTimeout();
    }

    private static WindowManagerService sInstance;
    static WindowManagerService getInstance() {
        return sInstance;
    }

    public static WindowManagerService main(final Context context, final InputManagerService im,
            final boolean showBootMsgs, final boolean onlyCore, WindowManagerPolicy policy,
            ActivityTaskManagerService atm) {
        return main(context, im, showBootMsgs, onlyCore, policy, atm,
                SurfaceControl.Transaction::new);
    }

    /**
     * Creates and returns an instance of the WindowManagerService. This call allows the caller
     * to override the {@link TransactionFactory} to stub functionality under test.
     */
    @VisibleForTesting
    public static WindowManagerService main(final Context context, final InputManagerService im,
            final boolean showBootMsgs, final boolean onlyCore, WindowManagerPolicy policy,
            ActivityTaskManagerService atm, TransactionFactory transactionFactory) {
        DisplayThread.getHandler().runWithScissors(() ->
                sInstance = new WindowManagerService(context, im, showBootMsgs, onlyCore, policy,
                        atm, transactionFactory), 0);
        return sInstance;
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

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver result) {
        new WindowManagerShellCommand(this).exec(this, in, out, err, args, callback, result);
    }

    private WindowManagerService(Context context, InputManagerService inputManager,
            boolean showBootMsgs, boolean onlyCore, WindowManagerPolicy policy,
            ActivityTaskManagerService atm, TransactionFactory transactionFactory) {
        installLock(this, INDEX_WINDOW);
        mGlobalLock = atm.getGlobalLock();
        mAtmService = atm;
        mContext = context;
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
        mMaxUiWidth = context.getResources().getInteger(
                com.android.internal.R.integer.config_maxUiWidth);
        mDisableTransitionAnimation = context.getResources().getBoolean(
                com.android.internal.R.bool.config_disableTransitionAnimation);
        mPerDisplayFocusEnabled = context.getResources().getBoolean(
                com.android.internal.R.bool.config_perDisplayFocusEnabled);
        mLowRamTaskSnapshotsAndRecents = context.getResources().getBoolean(
                com.android.internal.R.bool.config_lowRamTaskSnapshotsAndRecents);
        mInputManager = inputManager; // Must be before createDisplayContentLocked.
        mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
        mDisplayWindowSettings = new DisplayWindowSettings(this);
        mWindowCornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(context.getResources());

        mTransactionFactory = transactionFactory;
        mTransaction = mTransactionFactory.make();
        mPolicy = policy;
        mAnimator = new WindowAnimator(this);
        mRoot = new RootWindowContainer(this);

        mWindowPlacerLocked = new WindowSurfacePlacer(this);
        mTaskSnapshotController = new TaskSnapshotController(this);

        mWindowTracing = WindowTracing.createDefaultAndStartLooper(this,
                Choreographer.getInstance());

        LocalServices.addService(WindowManagerPolicy.class, mPolicy);

        mDisplayManager = (DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);

        mKeyguardDisableHandler = KeyguardDisableHandler.create(mContext, mPolicy, mH);

        mPowerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);

        if (mPowerManagerInternal != null) {
            mPowerManagerInternal.registerLowPowerModeObserver(
                    new PowerManagerInternal.LowPowerModeListener() {
                @Override
                public int getServiceType() {
                    return ServiceType.ANIMATION;
                }

                @Override
                public void onLowPowerModeChanged(PowerSaveState result) {
                    synchronized (mGlobalLock) {
                        final boolean enabled = result.batterySaverEnabled;
                        if (mAnimationsDisabled != enabled && !mAllowAnimationsInLowPowerMode) {
                            mAnimationsDisabled = enabled;
                            dispatchNewAnimatorScaleLocked(null);
                        }
                    }
                }
            });
            mAnimationsDisabled = mPowerManagerInternal
                    .getLowPowerState(ServiceType.ANIMATION).batterySaverEnabled;
        }
        mScreenFrozenLock = mPowerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "SCREEN_FROZEN");
        mScreenFrozenLock.setReferenceCounted(false);

        mActivityManager = ActivityManager.getService();
        mActivityTaskManager = ActivityTaskManager.getService();
        mAmInternal = LocalServices.getService(ActivityManagerInternal.class);
        mAtmInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
        mAppOps = (AppOpsManager)context.getSystemService(Context.APP_OPS_SERVICE);
        AppOpsManager.OnOpChangedInternalListener opListener =
                new AppOpsManager.OnOpChangedInternalListener() {
                    @Override public void onOpChanged(int op, String packageName) {
                        updateAppOpsState();
                    }
                };
        mAppOps.startWatchingMode(OP_SYSTEM_ALERT_WINDOW, null, opListener);
        mAppOps.startWatchingMode(AppOpsManager.OP_TOAST_WINDOW, null, opListener);

        mPmInternal = LocalServices.getService(PackageManagerInternal.class);
        final IntentFilter suspendPackagesFilter = new IntentFilter();
        suspendPackagesFilter.addAction(Intent.ACTION_PACKAGES_SUSPENDED);
        suspendPackagesFilter.addAction(Intent.ACTION_PACKAGES_UNSUSPENDED);
        context.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String[] affectedPackages =
                        intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                final boolean suspended =
                        Intent.ACTION_PACKAGES_SUSPENDED.equals(intent.getAction());
                updateHiddenWhileSuspendedState(new ArraySet<>(Arrays.asList(affectedPackages)),
                        suspended);
            }
        }, UserHandle.ALL, suspendPackagesFilter, null, null);

        final ContentResolver resolver = context.getContentResolver();
        // Get persisted window scale setting
        mWindowAnimationScaleSetting = Settings.Global.getFloat(resolver,
                Settings.Global.WINDOW_ANIMATION_SCALE, mWindowAnimationScaleSetting);
        mTransitionAnimationScaleSetting = Settings.Global.getFloat(resolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                context.getResources().getFloat(
                        R.dimen.config_appTransitionAnimationDurationScaleDefault));

        setAnimatorDurationScale(Settings.Global.getFloat(resolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, mAnimatorDurationScaleSetting));

        mForceDesktopModeOnExternalDisplays = Settings.Global.getInt(resolver,
                DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS, 0) != 0;

        IntentFilter filter = new IntentFilter();
        // Track changes to DevicePolicyManager state so we can enable/disable keyguard.
        filter.addAction(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        mContext.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, filter, null, null);

        mLatencyTracker = LatencyTracker.getInstance(context);

        mSettingsObserver = new SettingsObserver();

        mHoldingScreenWakeLock = mPowerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, TAG_WM);
        mHoldingScreenWakeLock.setReferenceCounted(false);

        mSurfaceAnimationRunner = new SurfaceAnimationRunner(mPowerManagerInternal);

        mAllowTheaterModeWakeFromLayout = context.getResources().getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromWindowLayout);

        mTaskPositioningController = new TaskPositioningController(
                this, mInputManager, mActivityTaskManager, mH.getLooper());
        mDragDropController = new DragDropController(this, mH.getLooper());

        LocalServices.addService(WindowManagerInternal.class, new LocalService());
    }

    /**
     * Called after all entities (such as the {@link ActivityManagerService}) have been set up and
     * associated with the {@link WindowManagerService}.
     */
    public void onInitReady() {
        initPolicy();

        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);

        openSurfaceTransaction();
        try {
            createWatermarkInTransaction();
        } finally {
            closeSurfaceTransaction("createWatermarkInTransaction");
        }

        showEmulatorDisplayOverlayIfNeeded();
    }

    public InputManagerCallback getInputManagerCallback() {
        return mInputManagerCallback;
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

    static boolean excludeWindowTypeFromTapOutTask(int windowType) {
        switch (windowType) {
            case TYPE_STATUS_BAR:
            case TYPE_NAVIGATION_BAR:
            case TYPE_INPUT_METHOD_DIALOG:
                return true;
        }
        return false;
    }

    public int addWindow(Session session, IWindow client, int seq,
            LayoutParams attrs, int viewVisibility, int displayId, Rect outFrame,
            Rect outContentInsets, Rect outStableInsets, Rect outOutsets,
            DisplayCutout.ParcelableWrapper outDisplayCutout, InputChannel outInputChannel,
            InsetsState outInsetsState) {
        int[] appOp = new int[1];
        int res = mPolicy.checkAddPermission(attrs, appOp);
        if (res != WindowManagerGlobal.ADD_OKAY) {
            return res;
        }

        boolean reportNewConfig = false;
        WindowState parentWindow = null;
        long origId;
        final int callingUid = Binder.getCallingUid();
        final int type = attrs.type;

        synchronized (mGlobalLock) {
            if (!mDisplayReady) {
                throw new IllegalStateException("Display has not been initialialized");
            }

            final DisplayContent displayContent = getDisplayContentOrCreate(displayId, attrs.token);

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
                parentWindow = windowForClientLocked(null, attrs.token, false);
                if (parentWindow == null) {
                    Slog.w(TAG_WM, "Attempted to add window with token that is not a window: "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_SUBWINDOW_TOKEN;
                }
                if (parentWindow.mAttrs.type >= FIRST_SUB_WINDOW
                        && parentWindow.mAttrs.type <= LAST_SUB_WINDOW) {
                    Slog.w(TAG_WM, "Attempted to add window with token that is a sub-window: "
                            + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_SUBWINDOW_TOKEN;
                }
            }

            if (type == TYPE_PRIVATE_PRESENTATION && !displayContent.isPrivate()) {
                Slog.w(TAG_WM, "Attempted to add private presentation window to a non-private display.  Aborting.");
                return WindowManagerGlobal.ADD_PERMISSION_DENIED;
            }

            AppWindowToken atoken = null;
            final boolean hasParent = parentWindow != null;
            // Use existing parent window token for child windows since they go in the same token
            // as there parent window so we can apply the same policy on them.
            WindowToken token = displayContent.getWindowToken(
                    hasParent ? parentWindow.mAttrs.token : attrs.token);
            // If this is a child window, we want to apply the same type checking rules as the
            // parent window type.
            final int rootType = hasParent ? parentWindow.mAttrs.type : type;

            boolean addToastWindowRequiresToken = false;

            if (token == null) {
                if (rootType >= FIRST_APPLICATION_WINDOW && rootType <= LAST_APPLICATION_WINDOW) {
                    Slog.w(TAG_WM, "Attempted to add application window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
                if (rootType == TYPE_INPUT_METHOD) {
                    Slog.w(TAG_WM, "Attempted to add input method window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
                if (rootType == TYPE_VOICE_INTERACTION) {
                    Slog.w(TAG_WM, "Attempted to add voice interaction window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
                if (rootType == TYPE_WALLPAPER) {
                    Slog.w(TAG_WM, "Attempted to add wallpaper window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
                if (rootType == TYPE_DREAM) {
                    Slog.w(TAG_WM, "Attempted to add Dream window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
                if (rootType == TYPE_QS_DIALOG) {
                    Slog.w(TAG_WM, "Attempted to add QS dialog window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
                if (rootType == TYPE_ACCESSIBILITY_OVERLAY) {
                    Slog.w(TAG_WM, "Attempted to add Accessibility overlay window with unknown token "
                            + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
                if (type == TYPE_TOAST) {
                    // Apps targeting SDK above N MR1 cannot arbitrary add toast windows.
                    if (doesAddToastWindowRequireToken(attrs.packageName, callingUid,
                            parentWindow)) {
                        Slog.w(TAG_WM, "Attempted to add a toast window with unknown token "
                                + attrs.token + ".  Aborting.");
                        return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                    }
                }
                final IBinder binder = attrs.token != null ? attrs.token : client.asBinder();
                final boolean isRoundedCornerOverlay =
                        (attrs.privateFlags & PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY) != 0;
                token = new WindowToken(this, binder, type, false, displayContent,
                        session.mCanAddInternalSystemWindow, isRoundedCornerOverlay);
            } else if (rootType >= FIRST_APPLICATION_WINDOW && rootType <= LAST_APPLICATION_WINDOW) {
                atoken = token.asAppWindowToken();
                if (atoken == null) {
                    Slog.w(TAG_WM, "Attempted to add window with non-application token "
                          + token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_NOT_APP_TOKEN;
                } else if (atoken.removed) {
                    Slog.w(TAG_WM, "Attempted to add window with exiting application token "
                          + token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_APP_EXITING;
                } else if (type == TYPE_APPLICATION_STARTING && atoken.startingWindow != null) {
                    Slog.w(TAG_WM, "Attempted to add starting window to token with already existing"
                            + " starting window");
                    return WindowManagerGlobal.ADD_DUPLICATE_ADD;
                }
            } else if (rootType == TYPE_INPUT_METHOD) {
                if (token.windowType != TYPE_INPUT_METHOD) {
                    Slog.w(TAG_WM, "Attempted to add input method window with bad token "
                            + attrs.token + ".  Aborting.");
                      return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (rootType == TYPE_VOICE_INTERACTION) {
                if (token.windowType != TYPE_VOICE_INTERACTION) {
                    Slog.w(TAG_WM, "Attempted to add voice interaction window with bad token "
                            + attrs.token + ".  Aborting.");
                      return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (rootType == TYPE_WALLPAPER) {
                if (token.windowType != TYPE_WALLPAPER) {
                    Slog.w(TAG_WM, "Attempted to add wallpaper window with bad token "
                            + attrs.token + ".  Aborting.");
                      return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (rootType == TYPE_DREAM) {
                if (token.windowType != TYPE_DREAM) {
                    Slog.w(TAG_WM, "Attempted to add Dream window with bad token "
                            + attrs.token + ".  Aborting.");
                      return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (rootType == TYPE_ACCESSIBILITY_OVERLAY) {
                if (token.windowType != TYPE_ACCESSIBILITY_OVERLAY) {
                    Slog.w(TAG_WM, "Attempted to add Accessibility overlay window with bad token "
                            + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (type == TYPE_TOAST) {
                // Apps targeting SDK above N MR1 cannot arbitrary add toast windows.
                addToastWindowRequiresToken = doesAddToastWindowRequireToken(attrs.packageName,
                        callingUid, parentWindow);
                if (addToastWindowRequiresToken && token.windowType != TYPE_TOAST) {
                    Slog.w(TAG_WM, "Attempted to add a toast window with bad token "
                            + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (type == TYPE_QS_DIALOG) {
                if (token.windowType != TYPE_QS_DIALOG) {
                    Slog.w(TAG_WM, "Attempted to add QS dialog window with bad token "
                            + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (token.asAppWindowToken() != null) {
                Slog.w(TAG_WM, "Non-null appWindowToken for system window of rootType=" + rootType);
                // It is not valid to use an app token with other system types; we will
                // instead make a new token for it (as if null had been passed in for the token).
                attrs.token = null;
                token = new WindowToken(this, client.asBinder(), type, false, displayContent,
                        session.mCanAddInternalSystemWindow);
            }

            final WindowState win = new WindowState(this, session, client, token, parentWindow,
                    appOp[0], seq, attrs, viewVisibility, session.mUid,
                    session.mCanAddInternalSystemWindow);
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

            final boolean hasStatusBarServicePermission =
                    mContext.checkCallingOrSelfPermission(permission.STATUS_BAR_SERVICE)
                            == PackageManager.PERMISSION_GRANTED;
            final DisplayPolicy displayPolicy = displayContent.getDisplayPolicy();
            displayPolicy.adjustWindowParamsLw(win, win.mAttrs, hasStatusBarServicePermission);
            win.setShowToOwnerOnlyLocked(mPolicy.checkShowToOwnerOnly(attrs));

            res = displayPolicy.prepareAddWindowLw(win, attrs);
            if (res != WindowManagerGlobal.ADD_OKAY) {
                return res;
            }

            final boolean openInputChannels = (outInputChannel != null
                    && (attrs.inputFeatures & INPUT_FEATURE_NO_INPUT_CHANNEL) == 0);
            if  (openInputChannels) {
                win.openInputChannel(outInputChannel);
            }

            // If adding a toast requires a token for this app we always schedule hiding
            // toast windows to make sure they don't stick around longer then necessary.
            // We hide instead of remove such windows as apps aren't prepared to handle
            // windows being removed under them.
            //
            // If the app is older it can add toasts without a token and hence overlay
            // other apps. To be maximally compatible with these apps we will hide the
            // window after the toast timeout only if the focused window is from another
            // UID, otherwise we allow unlimited duration. When a UID looses focus we
            // schedule hiding all of its toast windows.
            if (type == TYPE_TOAST) {
                if (!displayContent.canAddToastWindowForUid(callingUid)) {
                    Slog.w(TAG_WM, "Adding more than one toast window for UID at a time.");
                    return WindowManagerGlobal.ADD_DUPLICATE_ADD;
                }
                // Make sure this happens before we moved focus as one can make the
                // toast focusable to force it not being hidden after the timeout.
                // Focusable toasts are always timed out to prevent a focused app to
                // show a focusable toasts while it has focus which will be kept on
                // the screen after the activity goes away.
                if (addToastWindowRequiresToken
                        || (attrs.flags & LayoutParams.FLAG_NOT_FOCUSABLE) == 0
                        || displayContent.mCurrentFocus == null
                        || displayContent.mCurrentFocus.mOwnerUid != callingUid) {
                    mH.sendMessageDelayed(
                            mH.obtainMessage(H.WINDOW_HIDE_TIMEOUT, win),
                            win.mAttrs.hideTimeoutMilliseconds);
                }
            }

            // From now on, no exceptions or errors allowed!

            res = WindowManagerGlobal.ADD_OKAY;
            if (displayContent.mCurrentFocus == null) {
                displayContent.mWinAddedSinceNullFocus.add(win);
            }

            if (excludeWindowTypeFromTapOutTask(type)) {
                displayContent.mTapExcludedWindows.add(win);
            }

            origId = Binder.clearCallingIdentity();

            win.attach();
            mWindowMap.put(client.asBinder(), win);

            win.initAppOpsState();

            final boolean suspended = mPmInternal.isPackageSuspended(win.getOwningPackage(),
                    UserHandle.getUserId(win.getOwningUid()));
            win.setHiddenWhileSuspended(suspended);

            final boolean hideSystemAlertWindows = !mHidingNonSystemOverlayWindows.isEmpty();
            win.setForceHideNonSystemOverlayWindowIfNeeded(hideSystemAlertWindows);

            final AppWindowToken aToken = token.asAppWindowToken();
            if (type == TYPE_APPLICATION_STARTING && aToken != null) {
                aToken.startingWindow = win;
                if (DEBUG_STARTING_WINDOW) Slog.v (TAG_WM, "addWindow: " + aToken
                        + " startingWindow=" + win);
            }

            boolean imMayMove = true;

            win.mToken.addWindow(win);
            if (type == TYPE_INPUT_METHOD) {
                displayContent.setInputMethodWindowLocked(win);
                imMayMove = false;
            } else if (type == TYPE_INPUT_METHOD_DIALOG) {
                displayContent.computeImeTarget(true /* updateImeTarget */);
                imMayMove = false;
            } else {
                if (type == TYPE_WALLPAPER) {
                    displayContent.mWallpaperController.clearLastWallpaperTimeoutTime();
                    displayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
                } else if ((attrs.flags&FLAG_SHOW_WALLPAPER) != 0) {
                    displayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
                } else if (displayContent.mWallpaperController.isBelowWallpaperTarget(win)) {
                    // If there is currently a wallpaper being shown, and
                    // the base layer of the new window is below the current
                    // layer of the target window, then adjust the wallpaper.
                    // This is to avoid a new window being placed between the
                    // wallpaper and its target.
                    displayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
                }
            }

            // If the window is being added to a stack that's currently adjusted for IME,
            // make sure to apply the same adjust to this new window.
            win.applyAdjustForImeIfNeeded();

            if (type == TYPE_DOCK_DIVIDER) {
                mRoot.getDisplayContent(displayId).getDockedDividerController().setWindow(win);
            }

            final WindowStateAnimator winAnimator = win.mWinAnimator;
            winAnimator.mEnterAnimationPending = true;
            winAnimator.mEnteringAnimation = true;
            // Check if we need to prepare a transition for replacing window first.
            if (atoken != null && atoken.isVisible()
                    && !prepareWindowReplacementTransition(atoken)) {
                // If not, check if need to set up a dummy transition during display freeze
                // so that the unfreeze wait for the apps to draw. This might be needed if
                // the app is relaunching.
                prepareNoneTransitionForRelaunching(atoken);
            }

            final DisplayFrames displayFrames = displayContent.mDisplayFrames;
            // TODO: Not sure if onDisplayInfoUpdated() call is needed.
            final DisplayInfo displayInfo = displayContent.getDisplayInfo();
            displayFrames.onDisplayInfoUpdated(displayInfo,
                    displayContent.calculateDisplayCutoutForRotation(displayInfo.rotation));
            final Rect taskBounds;
            final boolean floatingStack;
            if (atoken != null && atoken.getTask() != null) {
                taskBounds = mTmpRect;
                atoken.getTask().getBounds(mTmpRect);
                floatingStack = atoken.getTask().isFloating();
            } else {
                taskBounds = null;
                floatingStack = false;
            }
            if (displayPolicy.getLayoutHintLw(win.mAttrs, taskBounds, displayFrames, floatingStack,
                    outFrame, outContentInsets, outStableInsets, outOutsets, outDisplayCutout)) {
                res |= WindowManagerGlobal.ADD_FLAG_ALWAYS_CONSUME_NAV_BAR;
            }
            outInsetsState.set(displayContent.getInsetsStateController().getInsetsForDispatch(win));

            if (mInTouchMode) {
                res |= WindowManagerGlobal.ADD_FLAG_IN_TOUCH_MODE;
            }
            if (win.mAppToken == null || !win.mAppToken.isClientHidden()) {
                res |= WindowManagerGlobal.ADD_FLAG_APP_VISIBLE;
            }

            displayContent.getInputMonitor().setUpdateInputWindowsNeededLw();

            boolean focusChanged = false;
            if (win.canReceiveKeys()) {
                focusChanged = updateFocusedWindowLocked(UPDATE_FOCUS_WILL_ASSIGN_LAYERS,
                        false /*updateInputWindows*/);
                if (focusChanged) {
                    imMayMove = false;
                }
            }

            if (imMayMove) {
                displayContent.computeImeTarget(true /* updateImeTarget */);
            }

            // Don't do layout here, the window must call
            // relayout to be displayed, so we'll do it there.
            win.getParent().assignChildLayers();

            if (focusChanged) {
                displayContent.getInputMonitor().setInputFocusLw(displayContent.mCurrentFocus,
                        false /*updateInputWindows*/);
            }
            displayContent.getInputMonitor().updateInputWindowsLw(false /*force*/);

            if (localLOGV || DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "addWindow: New client "
                    + client.asBinder() + ": window=" + win + " Callers=" + Debug.getCallers(5));

            if (win.isVisibleOrAdding() && displayContent.updateOrientationFromAppTokens()) {
                reportNewConfig = true;
            }
        }

        if (reportNewConfig) {
            sendNewConfiguration(displayId);
        }

        Binder.restoreCallingIdentity(origId);

        return res;
    }

    /**
     * Get existing {@link DisplayContent} or create a new one if the display is registered in
     * DisplayManager.
     *
     * NOTE: This should only be used in cases when there is a chance that a {@link DisplayContent}
     * that corresponds to a display just added to DisplayManager has not yet been created. This
     * usually means that the call of this method was initiated from outside of Activity or Window
     * Manager. In most cases the regular getter should be used.
     * @param displayId The preferred display Id.
     * @param token The window token associated with the window we are trying to get display for.
     *              if not null then the display of the window token will be returned. Set to null
     *              is there isn't an a token associated with the request.
     * @see RootWindowContainer#getDisplayContent(int)
     */
    private DisplayContent getDisplayContentOrCreate(int displayId, IBinder token) {
        if (token != null) {
            final WindowToken wToken = mRoot.getWindowToken(token);
            if (wToken != null) {
                return wToken.getDisplayContent();
            }
        }

        DisplayContent displayContent = mRoot.getDisplayContent(displayId);

        // Create an instance if possible instead of waiting for the ActivityManagerService to drive
        // the creation.
        if (displayContent == null) {
            final Display display = mDisplayManager.getDisplay(displayId);

            if (display != null) {
                displayContent = mRoot.createDisplayContent(display, null /* controller */);
            }
        }

        return displayContent;
    }

    private boolean doesAddToastWindowRequireToken(String packageName, int callingUid,
            WindowState attachedWindow) {
        // Try using the target SDK of the root window
        if (attachedWindow != null) {
            return attachedWindow.mAppToken != null
                    && attachedWindow.mAppToken.mTargetSdk >= Build.VERSION_CODES.O;
        } else {
            // Otherwise, look at the package
            try {
                ApplicationInfo appInfo = mContext.getPackageManager()
                        .getApplicationInfoAsUser(packageName, 0,
                                UserHandle.getUserId(callingUid));
                if (appInfo.uid != callingUid) {
                    throw new SecurityException("Package " + packageName + " not in UID "
                            + callingUid);
                }
                if (appInfo.targetSdkVersion >= Build.VERSION_CODES.O) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException e) {
                /* ignore */
            }
        }
        return false;
    }

    /**
     * Returns true if we're done setting up any transitions.
     */
    private boolean prepareWindowReplacementTransition(AppWindowToken atoken) {
        atoken.clearAllDrawn();
        final WindowState replacedWindow = atoken.getReplacingWindow();
        if (replacedWindow == null) {
            // We expect to already receive a request to remove the old window. If it did not
            // happen, let's just simply add a window.
            return false;
        }
        // We use the visible frame, because we want the animation to morph the window from what
        // was visible to the user to the final destination of the new window.
        Rect frame = replacedWindow.getVisibleFrameLw();
        // We treat this as if this activity was opening, so we can trigger the app transition
        // animation and piggy-back on existing transition animation infrastructure.
        final DisplayContent dc = atoken.getDisplayContent();
        dc.mOpeningApps.add(atoken);
        dc.prepareAppTransition(WindowManager.TRANSIT_ACTIVITY_RELAUNCH, ALWAYS_KEEP_CURRENT,
                0 /* flags */, false /* forceOverride */);
        dc.mAppTransition.overridePendingAppTransitionClipReveal(frame.left, frame.top,
                frame.width(), frame.height());
        dc.executeAppTransition();
        return true;
    }

    private void prepareNoneTransitionForRelaunching(AppWindowToken atoken) {
        // Set up a none-transition and add the app to opening apps, so that the display
        // unfreeze wait for the apps to be drawn.
        // Note that if the display unfroze already because app unfreeze timed out,
        // we don't set up the transition anymore and just let it go.
        final DisplayContent dc = atoken.getDisplayContent();
        if (mDisplayFrozen && !dc.mOpeningApps.contains(atoken) && atoken.isRelaunching()) {
            dc.mOpeningApps.add(atoken);
            dc.prepareAppTransition(WindowManager.TRANSIT_NONE, !ALWAYS_KEEP_CURRENT, 0 /* flags */,
                    false /* forceOverride */);
            dc.executeAppTransition();
        }
    }

    boolean isSecureLocked(WindowState w) {
        if ((w.mAttrs.flags&WindowManager.LayoutParams.FLAG_SECURE) != 0) {
            return true;
        }
        if (DevicePolicyCache.getInstance().getScreenCaptureDisabled(
                UserHandle.getUserId(w.mOwnerUid))) {
            return true;
        }
        return false;
    }

    /**
     * Set whether screen capture is disabled for all windows of a specific user from
     * the device policy cache.
     */
    @Override
    public void refreshScreenCaptureDisabled(int userId) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != SYSTEM_UID) {
            throw new SecurityException("Only system can call refreshScreenCaptureDisabled.");
        }

        synchronized (mGlobalLock) {
            // Update secure surface for all windows belonging to this user.
            mRoot.setSecureSurfaceState(userId,
                    DevicePolicyCache.getInstance().getScreenCaptureDisabled(userId));
        }
    }

    void removeWindow(Session session, IWindow client) {
        synchronized (mGlobalLock) {
            WindowState win = windowForClientLocked(session, client, false);
            if (win == null) {
                return;
            }
            win.removeIfPossible();
        }
    }

    /**
     * Performs some centralized bookkeeping clean-up on the window that is being removed.
     * NOTE: Should only be called from {@link WindowState#removeImmediately()}
     * TODO: Maybe better handled with a method {@link WindowContainer#removeChild} if we can
     * figure-out a good way to have all parents of a WindowState doing the same thing without
     * forgetting to add the wiring when a new parent of WindowState is added.
     */
    void postWindowRemoveCleanupLocked(WindowState win) {
        if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "postWindowRemoveCleanupLocked: " + win);
        mWindowMap.remove(win.mClient.asBinder());

        markForSeamlessRotation(win, false);

        win.resetAppOpsState();

        final DisplayContent dc = win.getDisplayContent();
        if (dc.mCurrentFocus == null) {
            dc.mWinRemovedSinceNullFocus.add(win);
        }
        mPendingRemove.remove(win);
        mResizingWindows.remove(win);
        updateNonSystemOverlayWindowsVisibilityIfNeeded(win, false /* surfaceShown */);
        mWindowsChanged = true;
        if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG_WM, "Final remove of window: " + win);

        final DisplayContent displayContent = win.getDisplayContent();
        if (displayContent.mInputMethodWindow == win) {
            displayContent.setInputMethodWindowLocked(null);
        }

        final WindowToken token = win.mToken;
        final AppWindowToken atoken = win.mAppToken;
        if (DEBUG_ADD_REMOVE) Slog.v(TAG_WM, "Removing " + win + " from " + token);
        // Window will already be removed from token before this post clean-up method is called.
        if (token.isEmpty()) {
            if (!token.mPersistOnEmpty) {
                token.removeImmediately();
            } else if (atoken != null) {
                // TODO: Should this be moved into AppWindowToken.removeWindow? Might go away after
                // re-factor.
                atoken.firstWindowDrawn = false;
                atoken.clearAllDrawn();
                final TaskStack stack = atoken.getStack();
                if (stack != null) {
                    stack.mExitingAppTokens.remove(atoken);
                }
            }
        }

        if (atoken != null) {
            atoken.postWindowRemoveStartingWindowCleanup(win);
        }

        if (win.mAttrs.type == TYPE_WALLPAPER) {
            dc.mWallpaperController.clearLastWallpaperTimeoutTime();
            dc.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
        } else if ((win.mAttrs.flags & FLAG_SHOW_WALLPAPER) != 0) {
            dc.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
        }

        if (dc != null && !mWindowPlacerLocked.isInLayout()) {
            dc.assignWindowLayers(true /* setLayoutNeeded */);
            mWindowPlacerLocked.performSurfacePlacement();
            if (win.mAppToken != null) {
                win.mAppToken.updateReportedVisibilityLocked();
            }
        }

        dc.getInputMonitor().updateInputWindowsLw(true /*force*/);
    }

    private void updateHiddenWhileSuspendedState(ArraySet<String> packages, boolean suspended) {
        synchronized (mGlobalLock) {
            mRoot.updateHiddenWhileSuspendedState(packages, suspended);
        }
    }

    private void updateAppOpsState() {
        synchronized (mGlobalLock) {
            mRoot.updateAppOpsState();
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
            synchronized (mGlobalLock) {
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
            synchronized (mGlobalLock) {
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

                    // We need to report touchable region changes to accessibility.
                    if (mAccessibilityController != null
                            && w.getDisplayContent().getDisplayId() == DEFAULT_DISPLAY) {
                        mAccessibilityController.onSomeWindowResizedOrMovedLocked();
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void getWindowDisplayFrame(Session session, IWindow client,
            Rect outDisplayFrame) {
        synchronized (mGlobalLock) {
            WindowState win = windowForClientLocked(session, client, false);
            if (win == null) {
                outDisplayFrame.setEmpty();
                return;
            }
            outDisplayFrame.set(win.getDisplayFrameLw());
            if (win.inSizeCompatMode()) {
                outDisplayFrame.scale(win.mInvGlobalScale);
            }
        }
    }

    public void onRectangleOnScreenRequested(IBinder token, Rect rectangle) {
        synchronized (mGlobalLock) {
            if (mAccessibilityController != null) {
                WindowState window = mWindowMap.get(token);
                if (window != null) {
                    mAccessibilityController.onRectangleOnScreenRequestedLocked(
                            window.getDisplayId(), rectangle);
                }
            }
        }
    }

    public IWindowId getWindowId(IBinder token) {
        synchronized (mGlobalLock) {
            WindowState window = mWindowMap.get(token);
            return window != null ? window.mWindowId : null;
        }
    }

    public void pokeDrawLock(Session session, IBinder token) {
        synchronized (mGlobalLock) {
            WindowState window = windowForClientLocked(session, token, false);
            if (window != null) {
                window.pokeDrawLockLw(mDrawLockTimeoutMillis);
            }
        }
    }

    public int relayoutWindow(Session session, IWindow client, int seq, LayoutParams attrs,
            int requestedWidth, int requestedHeight, int viewVisibility, int flags,
            long frameNumber, Rect outFrame, Rect outOverscanInsets, Rect outContentInsets,
            Rect outVisibleInsets, Rect outStableInsets, Rect outOutsets, Rect outBackdropFrame,
            DisplayCutout.ParcelableWrapper outCutout, MergedConfiguration mergedConfiguration,
            SurfaceControl outSurfaceControl, InsetsState outInsetsState) {
        int result = 0;
        boolean configChanged;
        final boolean hasStatusBarPermission =
                mContext.checkCallingOrSelfPermission(permission.STATUS_BAR)
                        == PackageManager.PERMISSION_GRANTED;
        final boolean hasStatusBarServicePermission =
                mContext.checkCallingOrSelfPermission(permission.STATUS_BAR_SERVICE)
                        == PackageManager.PERMISSION_GRANTED;

        long origId = Binder.clearCallingIdentity();
        final int displayId;
        synchronized (mGlobalLock) {
            final WindowState win = windowForClientLocked(session, client, false);
            if (win == null) {
                return 0;
            }
            displayId = win.getDisplayId();
            final DisplayContent displayContent = win.getDisplayContent();
            final DisplayPolicy displayPolicy = displayContent.getDisplayPolicy();

            WindowStateAnimator winAnimator = win.mWinAnimator;
            if (viewVisibility != View.GONE) {
                win.setRequestedSize(requestedWidth, requestedHeight);
            }

            win.setFrameNumber(frameNumber);

            final DisplayContent dc = win.getDisplayContent();
            if (!dc.mWaitingForConfig) {
                win.finishSeamlessRotation(false /* timeout */);
            }

            int attrChanges = 0;
            int flagChanges = 0;
            if (attrs != null) {
                displayPolicy.adjustWindowParamsLw(win, attrs, hasStatusBarServicePermission);
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
                if (win.mAppToken != null && ((flagChanges & FLAG_SHOW_WHEN_LOCKED) != 0
                        || (flagChanges & FLAG_DISMISS_KEYGUARD) != 0)) {
                    win.mAppToken.checkKeyguardFlagsChanged();
                }
                if (((attrChanges & LayoutParams.ACCESSIBILITY_TITLE_CHANGED) != 0)
                        && (mAccessibilityController != null)
                        && (win.getDisplayId() == DEFAULT_DISPLAY)) {
                    // No move or resize, but the controller checks for title changes as well
                    mAccessibilityController.onSomeWindowResizedOrMovedLocked();
                }

                if ((flagChanges & SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS) != 0) {
                    updateNonSystemOverlayWindowsVisibilityIfNeeded(
                            win, win.mWinAnimator.getShown());
                }
            }

            if (DEBUG_LAYOUT) Slog.v(TAG_WM, "Relayout " + win + ": viewVisibility=" + viewVisibility
                    + " req=" + requestedWidth + "x" + requestedHeight + " " + win.mAttrs);
            winAnimator.mSurfaceDestroyDeferred = (flags & RELAYOUT_DEFER_SURFACE_DESTROY) != 0;
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

            final int oldVisibility = win.mViewVisibility;

            // If the window is becoming visible, visibleOrAdding may change which may in turn
            // change the IME target.
            final boolean becameVisible =
                    (oldVisibility == View.INVISIBLE || oldVisibility == View.GONE)
                            && viewVisibility == View.VISIBLE;
            boolean imMayMove = (flagChanges & (FLAG_ALT_FOCUSABLE_IM | FLAG_NOT_FOCUSABLE)) != 0
                    || becameVisible;
            final boolean isDefaultDisplay = win.isDefaultDisplay();
            boolean focusMayChange = win.mViewVisibility != viewVisibility
                    || ((flagChanges & FLAG_NOT_FOCUSABLE) != 0)
                    || (!win.mRelayoutCalled);

            boolean wallpaperMayMove = win.mViewVisibility != viewVisibility
                    && (win.mAttrs.flags & FLAG_SHOW_WALLPAPER) != 0;
            wallpaperMayMove |= (flagChanges & FLAG_SHOW_WALLPAPER) != 0;
            if ((flagChanges & FLAG_SECURE) != 0 && winAnimator.mSurfaceController != null) {
                winAnimator.mSurfaceController.setSecure(isSecureLocked(win));
            }

            win.mRelayoutCalled = true;
            win.mInRelayout = true;

            win.mViewVisibility = viewVisibility;
            if (DEBUG_SCREEN_ON) {
                RuntimeException stack = new RuntimeException();
                stack.fillInStackTrace();
                Slog.i(TAG_WM, "Relayout " + win + ": oldVis=" + oldVisibility
                        + " newVis=" + viewVisibility, stack);
            }

            win.setDisplayLayoutNeeded();
            win.mGivenInsetsPending = (flags & WindowManagerGlobal.RELAYOUT_INSETS_PENDING) != 0;

            // We should only relayout if the view is visible, it is a starting window, or the
            // associated appToken is not hidden.
            final boolean shouldRelayout = viewVisibility == View.VISIBLE &&
                    (win.mAppToken == null || win.mAttrs.type == TYPE_APPLICATION_STARTING
                            || !win.mAppToken.isClientHidden());

            // If we are not currently running the exit animation, we need to see about starting
            // one.
            // We don't want to animate visibility of windows which are pending replacement.
            // In the case of activity relaunch child windows could request visibility changes as
            // they are detached from the main application window during the tear down process.
            // If we satisfied these visibility changes though, we would cause a visual glitch
            // hiding the window before it's replacement was available. So we just do nothing on
            // our side.
            // This must be called before the call to performSurfacePlacement.
            if (!shouldRelayout && winAnimator.hasSurface() && !win.mAnimatingExit) {
                if (DEBUG_VISIBILITY) {
                    Slog.i(TAG_WM,
                            "Relayout invis " + win + ": mAnimatingExit=" + win.mAnimatingExit);
                }
                result |= RELAYOUT_RES_SURFACE_CHANGED;
                if (!win.mWillReplaceWindow) {
                    focusMayChange = tryStartExitingAnimation(win, winAnimator, focusMayChange);
                }
            }

            // We may be deferring layout passes at the moment, but since the client is interested
            // in the new out values right now we need to force a layout.
            mWindowPlacerLocked.performSurfacePlacement(true /* force */);

            if (shouldRelayout) {
                Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "relayoutWindow: viewVisibility_1");

                result = win.relayoutVisibleWindow(result, attrChanges, oldVisibility);

                try {
                    result = createSurfaceControl(outSurfaceControl, result, win, winAnimator);
                } catch (Exception e) {
                    displayContent.getInputMonitor().updateInputWindowsLw(true /*force*/);

                    Slog.w(TAG_WM, "Exception thrown when creating surface for client "
                             + client + " (" + win.mAttrs.getTitle() + ")",
                             e);
                    Binder.restoreCallingIdentity(origId);
                    return 0;
                }
                if ((result & WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME) != 0) {
                    focusMayChange = true;
                }
                if (win.mAttrs.type == TYPE_INPUT_METHOD
                        && displayContent.mInputMethodWindow == null) {
                    displayContent.setInputMethodWindowLocked(win);
                    imMayMove = true;
                }
                win.adjustStartingWindowFlags();
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            } else {
                Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "relayoutWindow: viewVisibility_2");

                winAnimator.mEnterAnimationPending = false;
                winAnimator.mEnteringAnimation = false;

                if (viewVisibility == View.VISIBLE && winAnimator.hasSurface()) {
                    // We already told the client to go invisible, but the message may not be
                    // handled yet, or it might want to draw a last frame. If we already have a
                    // surface, let the client use that, but don't create new surface at this point.
                    Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "relayoutWindow: getSurface");
                    winAnimator.mSurfaceController.getSurfaceControl(outSurfaceControl);
                    Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                } else {
                    if (DEBUG_VISIBILITY) Slog.i(TAG_WM, "Releasing surface in: " + win);

                    try {
                        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "wmReleaseOutSurface_"
                                + win.mAttrs.getTitle());
                        outSurfaceControl.release();
                    } finally {
                        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                    }
                }

                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            }

            if (focusMayChange) {
                if (updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, true /*updateInputWindows*/)) {
                    imMayMove = false;
                }
            }

            // updateFocusedWindowLocked() already assigned layers so we only need to
            // reassign them at this point if the IM window state gets shuffled
            boolean toBeDisplayed = (result & WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME) != 0;
            if (imMayMove) {
                displayContent.computeImeTarget(true /* updateImeTarget */);
                if (toBeDisplayed) {
                    // Little hack here -- we -should- be able to rely on the function to return
                    // true if the IME has moved and needs its layer recomputed. However, if the IME
                    // was hidden and isn't actually moved in the list, its layer may be out of data
                    // so we make sure to recompute it.
                    displayContent.assignWindowLayers(false /* setLayoutNeeded */);
                }
            }

            if (wallpaperMayMove) {
                displayContent.pendingLayoutChanges |=
                        WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
            }

            if (win.mAppToken != null) {
                displayContent.mUnknownAppVisibilityController.notifyRelayouted(win.mAppToken);
            }

            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER,
                    "relayoutWindow: updateOrientationFromAppTokens");
            configChanged = displayContent.updateOrientationFromAppTokens();
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);

            if (toBeDisplayed && win.mIsWallpaper) {
                DisplayInfo displayInfo = displayContent.getDisplayInfo();
                displayContent.mWallpaperController.updateWallpaperOffset(
                        win, displayInfo.logicalWidth, displayInfo.logicalHeight, false);
            }
            if (win.mAppToken != null) {
                win.mAppToken.updateReportedVisibilityLocked();
            }
            if (winAnimator.mReportSurfaceResized) {
                winAnimator.mReportSurfaceResized = false;
                result |= WindowManagerGlobal.RELAYOUT_RES_SURFACE_RESIZED;
            }
            if (displayPolicy.isNavBarForcedShownLw(win)) {
                result |= WindowManagerGlobal.RELAYOUT_RES_CONSUME_ALWAYS_NAV_BAR;
            }
            if (!win.isGoneForLayoutLw()) {
                win.mResizedWhileGone = false;
            }

            // We must always send the latest {@link MergedConfiguration}, regardless of whether we
            // have already reported it. The client might not have processed the previous value yet
            // and needs process it before handling the corresponding window frame. the variable
            // {@code mergedConfiguration} is an out parameter that will be passed back to the
            // client over IPC and checked there.
            // Note: in the cases where the window is tied to an activity, we should not send a
            // configuration update when the window has requested to be hidden. Doing so can lead
            // to the client erroneously accepting a configuration that would have otherwise caused
            // an activity restart. We instead hand back the last reported
            // {@link MergedConfiguration}.
            if (shouldRelayout) {
                win.getMergedConfiguration(mergedConfiguration);
            } else {
                win.getLastReportedMergedConfiguration(mergedConfiguration);
            }

            win.setLastReportedMergedConfiguration(mergedConfiguration);

            // Update the last inset values here because the values are sent back to the client.
            // The last inset values represent the last client state.
            win.updateLastInsetValues();

            win.getCompatFrame(outFrame);
            win.getInsetsForRelayout(outOverscanInsets, outContentInsets, outVisibleInsets,
                    outStableInsets, outOutsets);
            outCutout.set(win.getWmDisplayCutout().getDisplayCutout());
            outBackdropFrame.set(win.getBackdropFrame(win.getFrameLw()));
            outInsetsState.set(displayContent.getInsetsStateController().getInsetsForDispatch(win));
            if (localLOGV) Slog.v(
                TAG_WM, "Relayout given client " + client.asBinder()
                + ", requestedWidth=" + requestedWidth
                + ", requestedHeight=" + requestedHeight
                + ", viewVisibility=" + viewVisibility
                + "\nRelayout returning frame=" + outFrame
                + ", surface=" + outSurfaceControl);

            if (localLOGV || DEBUG_FOCUS) Slog.v(
                TAG_WM, "Relayout of " + win + ": focusMayChange=" + focusMayChange);

            result |= mInTouchMode ? WindowManagerGlobal.RELAYOUT_RES_IN_TOUCH_MODE : 0;

            if (DEBUG_LAYOUT) {
                Slog.v(TAG_WM, "Relayout complete " + win + ": outFrame=" + outFrame.toShortString());
            }
            win.mInRelayout = false;
        }

        if (configChanged) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "relayoutWindow: sendNewConfiguration");
            sendNewConfiguration(displayId);
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
        Binder.restoreCallingIdentity(origId);
        return result;
    }

    private boolean tryStartExitingAnimation(WindowState win, WindowStateAnimator winAnimator,
            boolean focusMayChange) {
        // Try starting an animation; if there isn't one, we
        // can destroy the surface right away.
        int transit = WindowManagerPolicy.TRANSIT_EXIT;
        if (win.mAttrs.type == TYPE_APPLICATION_STARTING) {
            transit = WindowManagerPolicy.TRANSIT_PREVIEW_DONE;
        }
        if (win.isWinVisibleLw() && winAnimator.applyAnimationLocked(transit, false)) {
            focusMayChange = true;
            win.mAnimatingExit = true;
        } else if (win.isAnimating()) {
            // Currently in a hide animation... turn this into
            // an exit.
            win.mAnimatingExit = true;
        } else if (win.getDisplayContent().mWallpaperController.isWallpaperTarget(win)) {
            // If the wallpaper is currently behind this
            // window, we need to change both of them inside
            // of a transaction to avoid artifacts.
            win.mAnimatingExit = true;
        } else {
            final DisplayContent displayContent = win.getDisplayContent();
            if (displayContent.mInputMethodWindow == win) {
                displayContent.setInputMethodWindowLocked(null);
            }
            boolean stopped = win.mAppToken != null ? win.mAppToken.mAppStopped : true;
            // We set mDestroying=true so AppWindowToken#notifyAppStopped in-to destroy surfaces
            // will later actually destroy the surface if we do not do so here. Normally we leave
            // this to the exit animation.
            win.mDestroying = true;
            win.destroySurface(false, stopped);
        }
        if (mAccessibilityController != null) {
            mAccessibilityController.onWindowTransitionLocked(win, transit);
        }

        // When we start the exit animation we take the Surface from the client
        // so it will stop perturbing it. We need to likewise takeaway the SurfaceFlinger
        // side child surfaces, so they will remain preserved in their current state
        // (rather than be cleaned up immediately by the app code).
        SurfaceControl.openTransaction();
        winAnimator.detachChildren();
        SurfaceControl.closeTransaction();

        return focusMayChange;
    }

    private int createSurfaceControl(SurfaceControl outSurfaceControl, int result, WindowState win,
            WindowStateAnimator winAnimator) {
        if (!win.mHasSurface) {
            result |= RELAYOUT_RES_SURFACE_CHANGED;
        }

        WindowSurfaceController surfaceController;
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "createSurfaceControl");
            surfaceController = winAnimator.createSurfaceLocked(win.mAttrs.type, win.mOwnerUid);
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
        if (surfaceController != null) {
            surfaceController.getSurfaceControl(outSurfaceControl);
            if (SHOW_TRANSACTIONS) Slog.i(TAG_WM, "  OUT SURFACE " + outSurfaceControl + ": copied");
        } else {
            // For some reason there isn't a surface.  Clear the
            // caller's object so they see the same state.
            Slog.w(TAG_WM, "Failed to create surface control for " + win);
            outSurfaceControl.release();
        }

        return result;
    }

    public boolean outOfMemoryWindow(Session session, IWindow client) {
        final long origId = Binder.clearCallingIdentity();

        try {
            synchronized (mGlobalLock) {
                WindowState win = windowForClientLocked(session, client, false);
                if (win == null) {
                    return false;
                }
                return mRoot.reclaimSomeSurfaceMemory(win.mWinAnimator, "from-client", false);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void finishDrawingWindow(Session session, IWindow client) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                WindowState win = windowForClientLocked(session, client, false);
                if (DEBUG_ADD_REMOVE) Slog.d(TAG_WM, "finishDrawingWindow: " + win + " mDrawState="
                        + (win != null ? win.mWinAnimator.drawStateToString() : "null"));
                if (win != null && win.mWinAnimator.finishDrawingLocked()) {
                    if ((win.mAttrs.flags & FLAG_SHOW_WALLPAPER) != 0) {
                        win.getDisplayContent().pendingLayoutChanges |=
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

    boolean checkCallingPermission(String permission, String func) {
        // Quick check: if the calling permission is me, it's all okay.
        if (Binder.getCallingPid() == myPid()) {
            return true;
        }

        if (mContext.checkCallingPermission(permission)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        final String msg = "Permission Denial: " + func + " from pid=" + Binder.getCallingPid()
                + ", uid=" + Binder.getCallingUid() + " requires " + permission;
        Slog.w(TAG_WM, msg);
        return false;
    }

    @Override
    public void addWindowToken(IBinder binder, int type, int displayId) {
        if (!checkCallingPermission(MANAGE_APP_TOKENS, "addWindowToken()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized (mGlobalLock) {
            final DisplayContent dc = getDisplayContentOrCreate(displayId, null /* token */);
            if (dc == null) {
                Slog.w(TAG_WM, "addWindowToken: Attempted to add token: " + binder
                        + " for non-exiting displayId=" + displayId);
                return;
            }

            WindowToken token = dc.getWindowToken(binder);
            if (token != null) {
                Slog.w(TAG_WM, "addWindowToken: Attempted to add binder token: " + binder
                        + " for already created window token: " + token
                        + " displayId=" + displayId);
                return;
            }
            if (type == TYPE_WALLPAPER) {
                new WallpaperWindowToken(this, binder, true, dc,
                        true /* ownerCanManageAppTokens */);
            } else {
                new WindowToken(this, binder, type, true, dc, true /* ownerCanManageAppTokens */);
            }
        }
    }

    @Override
    public void removeWindowToken(IBinder binder, int displayId) {
        if (!checkCallingPermission(MANAGE_APP_TOKENS, "removeWindowToken()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent dc = mRoot.getDisplayContent(displayId);
                if (dc == null) {
                    Slog.w(TAG_WM, "removeWindowToken: Attempted to remove token: " + binder
                            + " for non-exiting displayId=" + displayId);
                    return;
                }

                final WindowToken token = dc.removeWindowToken(binder);
                if (token == null) {
                    Slog.w(TAG_WM,
                            "removeWindowToken: Attempted to remove non-existing token: " + binder);
                    return;
                }

                dc.getInputMonitor().updateInputWindowsLw(true /*force*/);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void setNewDisplayOverrideConfiguration(Configuration overrideConfig,
            @NonNull DisplayContent dc) {
        if (dc.mWaitingForConfig) {
            dc.mWaitingForConfig = false;
            mLastFinishedFreezeSource = "new-config";
        }

        mRoot.setDisplayOverrideConfigurationIfNeeded(overrideConfig, dc);
    }

    // TODO(multi-display): remove when no default display use case.
    // (i.e. KeyguardController / RecentsAnimation)
    @Override
    public void prepareAppTransition(@TransitionType int transit, boolean alwaysKeepCurrent) {
        if (!checkCallingPermission(MANAGE_APP_TOKENS, "prepareAppTransition()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }
        getDefaultDisplayContentLocked().prepareAppTransition(transit,
                alwaysKeepCurrent, 0 /* flags */, false /* forceOverride */);
    }

    @Override
    public void overridePendingAppTransitionMultiThumbFuture(
            IAppTransitionAnimationSpecsFuture specsFuture, IRemoteCallback callback,
            boolean scaleUp, int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent == null) {
                Slog.w(TAG, "Attempted to call overridePendingAppTransitionMultiThumbFuture"
                        + " for the display " + displayId + " that does not exist.");
                return;
            }
            displayContent.mAppTransition.overridePendingAppTransitionMultiThumbFuture(specsFuture,
                    callback, scaleUp);
        }
    }

    @Override
    public void overridePendingAppTransitionRemote(RemoteAnimationAdapter remoteAnimationAdapter,
            int displayId) {
        if (!checkCallingPermission(CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS,
                "overridePendingAppTransitionRemote()")) {
            throw new SecurityException(
                    "Requires CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS permission");
        }
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent == null) {
                Slog.w(TAG, "Attempted to call overridePendingAppTransitionRemote"
                        + " for the display " + displayId + " that does not exist.");
                return;
            }
            displayContent.mAppTransition.overridePendingAppTransitionRemote(
                    remoteAnimationAdapter);
        }
    }

    @Override
    public void endProlongedAnimations() {
        // TODO: Remove once clients are updated.
    }

    // TODO(multi-display): remove when no default display use case.
    // (i.e. KeyguardController / RecentsAnimation)
    @Override
    public void executeAppTransition() {
        if (!checkCallingPermission(MANAGE_APP_TOKENS, "executeAppTransition()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }
        getDefaultDisplayContentLocked().executeAppTransition();
    }

    public void initializeRecentsAnimation(int targetActivityType,
            IRecentsAnimationRunner recentsAnimationRunner,
            RecentsAnimationController.RecentsAnimationCallbacks callbacks, int displayId,
            SparseBooleanArray recentTaskIds) {
        synchronized (mGlobalLock) {
            mRecentsAnimationController = new RecentsAnimationController(this,
                    recentsAnimationRunner, callbacks, displayId);
            mRoot.getDisplayContent(displayId).mAppTransition.updateBooster();
            mRecentsAnimationController.initialize(targetActivityType, recentTaskIds);
        }
    }

    @VisibleForTesting
    void setRecentsAnimationController(RecentsAnimationController controller) {
        mRecentsAnimationController = controller;
    }

    public RecentsAnimationController getRecentsAnimationController() {
        return mRecentsAnimationController;
    }

    /**
     * @return Whether the next recents animation can continue to start. Called from
     *         {@link RecentsAnimation#startRecentsActivity}.
     */
    public boolean canStartRecentsAnimation() {
        synchronized (mGlobalLock) {
            // TODO(multi-display): currently only default display support recent activity
            if (getDefaultDisplayContentLocked().mAppTransition.isTransitionSet()) {
                return false;
            }
            return true;
        }
    }

    /**
     * Cancels any running recents animation. The caller should NOT hold the WM lock while calling
     * this method, as it will call back into AM and may cause a deadlock. Any locking will be done
     * in the animation controller itself.
     */
    public void cancelRecentsAnimationSynchronously(
            @RecentsAnimationController.ReorderMode int reorderMode, String reason) {
        if (mRecentsAnimationController != null) {
            // This call will call through to cleanupAnimation() below after the animation is
            // canceled
            mRecentsAnimationController.cancelAnimationSynchronously(reorderMode, reason);
        }
    }

    public void cleanupRecentsAnimation(@RecentsAnimationController.ReorderMode int reorderMode) {
        synchronized (mGlobalLock) {
            if (mRecentsAnimationController != null) {
                final RecentsAnimationController controller = mRecentsAnimationController;
                mRecentsAnimationController = null;
                controller.cleanupAnimation(reorderMode);
                // TODO(mult-display): currently only default display support recents animation.
                getDefaultDisplayContentLocked().mAppTransition.updateBooster();
            }
        }
    }

    public void setAppFullscreen(IBinder token, boolean toOpaque) {
        synchronized (mGlobalLock) {
            final AppWindowToken atoken = mRoot.getAppWindowToken(token);
            if (atoken != null) {
                atoken.setFillsParent(toOpaque);
                setWindowOpaqueLocked(token, toOpaque);
                mWindowPlacerLocked.requestTraversal();
            }
        }
    }

    public void setWindowOpaque(IBinder token, boolean isOpaque) {
        synchronized (mGlobalLock) {
            setWindowOpaqueLocked(token, isOpaque);
        }
    }

    private void setWindowOpaqueLocked(IBinder token, boolean isOpaque) {
        final AppWindowToken wtoken = mRoot.getAppWindowToken(token);
        if (wtoken != null) {
            final WindowState win = wtoken.findMainWindow();
            if (win != null) {
                win.mWinAnimator.setOpaqueLocked(isOpaque);
            }
        }
    }

    public void setDockedStackCreateState(int mode, Rect bounds) {
        synchronized (mGlobalLock) {
            setDockedStackCreateStateLocked(mode, bounds);
        }
    }

    void setDockedStackCreateStateLocked(int mode, Rect bounds) {
        mDockedStackCreateMode = mode;
        mDockedStackCreateBounds = bounds;
    }

    public void checkSplitScreenMinimizedChanged(boolean animate) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = getDefaultDisplayContentLocked();
            displayContent.getDockedDividerController().checkMinimizeChanged(animate);
        }
    }

    public boolean isValidPictureInPictureAspectRatio(int displayId, float aspectRatio) {
        final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
        return displayContent.getPinnedStackController().isValidPictureInPictureAspectRatio(
                aspectRatio);
    }

    @Override
    public void getStackBounds(int windowingMode, int activityType, Rect bounds) {
        synchronized (mGlobalLock) {
            final TaskStack stack = mRoot.getStack(windowingMode, activityType);
            if (stack != null) {
                stack.getBounds(bounds);
                return;
            }
            bounds.setEmpty();
        }
    }

    /**
     * Notifies window manager that {@link DisplayPolicy#isShowingDreamLw} has changed.
     */
    public void notifyShowingDreamChanged() {
        // TODO(multi-display): support show dream in multi-display.
        notifyKeyguardFlagsChanged(null /* callback */, DEFAULT_DISPLAY);
    }

    @Override
    public WindowManagerPolicy.WindowState getInputMethodWindowLw() {
        return mRoot.getCurrentInputMethodWindow();
    }

    @Override
    public void notifyKeyguardTrustedChanged() {
        mAtmInternal.notifyKeyguardTrustedChanged();
    }

    @Override
    public void screenTurningOff(ScreenOffListener listener) {
        mTaskSnapshotController.screenTurningOff(listener);
    }

    @Override
    public void triggerAnimationFailsafe() {
        mH.sendEmptyMessage(H.ANIMATION_FAILSAFE);
    }

    @Override
    public void onKeyguardShowingAndNotOccludedChanged() {
        mH.sendEmptyMessage(H.RECOMPUTE_FOCUS);
    }

    @Override
    public void onPowerKeyDown(boolean isScreenOn) {
        mRoot.forAllDisplayPolicies(PooledLambda.obtainConsumer(
                DisplayPolicy::onPowerKeyDown, PooledLambda.__(), isScreenOn));
    }

    @Override
    public void onUserSwitched() {
        mSettingsObserver.updateSystemUiSettings();
        synchronized (mGlobalLock) {
            // force a re-application of focused window sysui visibility on each display.
            mRoot.forAllDisplayPolicies(DisplayPolicy::resetSystemUiVisibilityLw);
        }
    }

    @Override
    public void moveDisplayToTop(int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent != null && mRoot.getTopChild() != displayContent) {
                mRoot.positionChildAt(WindowContainer.POSITION_TOP, displayContent,
                        true /* includingParents */);
            }
        }
    }

    /**
     * Starts deferring layout passes. Useful when doing multiple changes but to optimize
     * performance, only one layout pass should be done. This can be called multiple times, and
     * layouting will be resumed once the last caller has called
     * {@link #continueSurfaceLayout}.
     */
    void deferSurfaceLayout() {
        mWindowPlacerLocked.deferLayout();
    }

    /** Resumes layout passes after deferring them. See {@link #deferSurfaceLayout()} */
    void continueSurfaceLayout() {
        mWindowPlacerLocked.continueLayout();
    }

    /**
     * Notifies activity manager that some Keyguard flags have changed and that it needs to
     * reevaluate the visibilities of the activities.
     * @param callback Runnable to be called when activity manager is done reevaluating visibilities
     */
    void notifyKeyguardFlagsChanged(@Nullable Runnable callback, int displayId) {
        mAtmInternal.notifyKeyguardFlagsChanged(callback, displayId);
    }

    public boolean isKeyguardTrusted() {
        synchronized (mGlobalLock) {
            return mPolicy.isKeyguardTrustedLw();
        }
    }

    public void setKeyguardGoingAway(boolean keyguardGoingAway) {
        synchronized (mGlobalLock) {
            mKeyguardGoingAway = keyguardGoingAway;
        }
    }

    public void setKeyguardOrAodShowingOnDefaultDisplay(boolean showing) {
        synchronized (mGlobalLock) {
            mKeyguardOrAodShowingOnDefaultDisplay = showing;
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

        synchronized (mGlobalLock) {
            if (!mClientFreezingScreen) {
                mClientFreezingScreen = true;
                final long origId = Binder.clearCallingIdentity();
                try {
                    startFreezingDisplayLocked(exitAnim, enterAnim);
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

        synchronized (mGlobalLock) {
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
    public void disableKeyguard(IBinder token, String tag, int userId) {
        userId = mAmInternal.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, false /* allowAll */, ALLOW_FULL_ONLY, "disableKeyguard", null);
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DISABLE_KEYGUARD)
            != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }
        final int callingUid = Binder.getCallingUid();
        final long origIdentity = Binder.clearCallingIdentity();
        try {
            mKeyguardDisableHandler.disableKeyguard(token, tag, callingUid, userId);
        } finally {
            Binder.restoreCallingIdentity(origIdentity);
        }
    }

    @Override
    public void reenableKeyguard(IBinder token, int userId) {
        userId = mAmInternal.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, false /* allowAll */, ALLOW_FULL_ONLY, "reenableKeyguard", null);
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DISABLE_KEYGUARD)
            != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }
        Preconditions.checkNotNull(token, "token is null");
        final int callingUid = Binder.getCallingUid();
        final long origIdentity = Binder.clearCallingIdentity();
        try {
            mKeyguardDisableHandler.reenableKeyguard(token, callingUid, userId);
        } finally {
            Binder.restoreCallingIdentity(origIdentity);
        }
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
    public boolean isKeyguardLocked() {
        return mPolicy.isKeyguardLocked();
    }

    public boolean isKeyguardShowingAndNotOccluded() {
        return mPolicy.isKeyguardShowingAndNotOccluded();
    }

    @Override
    public boolean isKeyguardSecure(int userId) {
        if (userId != UserHandle.getCallingUserId()
                && !checkCallingPermission(Manifest.permission.INTERACT_ACROSS_USERS,
                "isKeyguardSecure")) {
            throw new SecurityException("Requires INTERACT_ACROSS_USERS permission");
        }

        long origId = Binder.clearCallingIdentity();
        try {
            return mPolicy.isKeyguardSecure(userId);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public boolean isShowingDream() {
        synchronized (mGlobalLock) {
            // TODO(b/123372519): Fix this when dream can be shown on non-default display.
            return getDefaultDisplayContentLocked().getDisplayPolicy().isShowingDreamLw();
        }
    }

    @Override
    public void dismissKeyguard(IKeyguardDismissCallback callback, CharSequence message) {
        if (!checkCallingPermission(permission.CONTROL_KEYGUARD, "dismissKeyguard")) {
            throw new SecurityException("Requires CONTROL_KEYGUARD permission");
        }
        synchronized (mGlobalLock) {
            mPolicy.dismissKeyguardLw(callback, message);
        }
    }

    public void onKeyguardOccludedChanged(boolean occluded) {
        synchronized (mGlobalLock) {
            mPolicy.onKeyguardOccludedChangedLw(occluded);
        }
    }

    @Override
    public void setSwitchingUser(boolean switching) {
        if (!checkCallingPermission(Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                "setSwitchingUser()")) {
            throw new SecurityException("Requires INTERACT_ACROSS_USERS_FULL permission");
        }
        mPolicy.setSwitchingUser(switching);
        synchronized (mGlobalLock) {
            mSwitchingUser = switching;
        }
    }

    void showGlobalActions() {
        mPolicy.showGlobalActions();
    }

    @Override
    public void closeSystemDialogs(String reason) {
        synchronized (mGlobalLock) {
            mRoot.closeSystemDialogs(reason);
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
        synchronized (mGlobalLock) {
            return mAnimationsDisabled ? 0 : mAnimatorDurationScaleSetting;
        }
    }

    void dispatchNewAnimatorScaleLocked(Session session) {
        mH.obtainMessage(H.NEW_ANIMATOR_SCALE, session).sendToTarget();
    }

    @Override
    public void registerPointerEventListener(PointerEventListener listener, int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent != null) {
                displayContent.registerPointerEventListener(listener);
            }
        }
    }

    @Override
    public void unregisterPointerEventListener(PointerEventListener listener, int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent != null) {
                displayContent.unregisterPointerEventListener(listener);
            }
        }
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
    public void switchKeyboardLayout(int deviceId, int direction) {
        mInputManager.switchKeyboardLayout(deviceId, direction);
    }

    // Called by window manager policy.  Not exposed externally.
    @Override
    public void shutdown(boolean confirm) {
        // Pass in the UI context, since ShutdownThread requires it (to show UI).
        ShutdownThread.shutdown(ActivityThread.currentActivityThread().getSystemUiContext(),
                PowerManager.SHUTDOWN_USER_REQUESTED, confirm);
    }

    // Called by window manager policy.  Not exposed externally.
    @Override
    public void reboot(boolean confirm) {
        // Pass in the UI context, since ShutdownThread requires it (to show UI).
        ShutdownThread.reboot(ActivityThread.currentActivityThread().getSystemUiContext(),
                PowerManager.SHUTDOWN_USER_REQUESTED, confirm);
    }

    // Called by window manager policy.  Not exposed externally.
    @Override
    public void rebootSafeMode(boolean confirm) {
        // Pass in the UI context, since ShutdownThread requires it (to show UI).
        ShutdownThread.rebootSafeMode(ActivityThread.currentActivityThread().getSystemUiContext(),
                confirm);
    }

    public void setCurrentProfileIds(final int[] currentProfileIds) {
        synchronized (mGlobalLock) {
            mCurrentProfileIds = currentProfileIds;
        }
    }

    public void setCurrentUser(final int newUserId, final int[] currentProfileIds) {
        synchronized (mGlobalLock) {
            mCurrentUserId = newUserId;
            mCurrentProfileIds = currentProfileIds;
            mPolicy.setCurrentUserLw(newUserId);
            mKeyguardDisableHandler.setCurrentUser(newUserId);

            // Hide windows that should not be seen by the new user.
            mRoot.switchUser();
            mWindowPlacerLocked.performSurfacePlacement();

            // Notify whether the docked stack exists for the current user
            final DisplayContent displayContent = getDefaultDisplayContentLocked();
            final TaskStack stack =
                    displayContent.getSplitScreenPrimaryStackIgnoringVisibility();
            displayContent.mDividerControllerLocked.notifyDockedStackExistsChanged(
                    stack != null && stack.hasTaskForUser(newUserId));

            mRoot.forAllDisplays(dc -> dc.mAppTransition.setCurrentUser(newUserId));

            // If the display is already prepared, update the density.
            // Otherwise, we'll update it when it's prepared.
            if (mDisplayReady) {
                final int forcedDensity = getForcedDisplayDensityForUserLocked(newUserId);
                final int targetDensity = forcedDensity != 0 ? forcedDensity
                        : displayContent.mInitialDisplayDensity;
                displayContent.setForcedDensity(targetDensity, UserHandle.USER_CURRENT);
            }
        }
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
        synchronized (mGlobalLock) {
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
            mH.sendEmptyMessageDelayed(H.BOOT_TIMEOUT, 30 * 1000);
        }

        mPolicy.systemBooted();

        performEnableScreen();
    }

    @Override
    public void enableScreenIfNeeded() {
        synchronized (mGlobalLock) {
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
        synchronized (mGlobalLock) {
            if (mDisplayEnabled) {
                return;
            }
            Slog.w(TAG_WM, "***** BOOT TIMEOUT: forcing display enabled");
            mForceDisplayEnabled = true;
        }
        performEnableScreen();
    }

    /**
     * Called when System UI has been started.
     */
    public void onSystemUiStarted() {
        mPolicy.onSystemUiStarted();
    }

    private void performEnableScreen() {
        synchronized (mGlobalLock) {
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

            if (!mShowingBootMessages && !mPolicy.canDismissBootAnimation()) {
                return;
            }

            // Don't enable the screen until all existing windows have been drawn.
            if (!mForceDisplayEnabled
                    // TODO(multidisplay): Expand to all displays?
                    && getDefaultDisplayContentLocked().checkWaitingForWindows()) {
                return;
            }

            if (!mBootAnimationStopped) {
                Trace.asyncTraceBegin(TRACE_TAG_WINDOW_MANAGER, "Stop bootanim", 0);
                // stop boot animation
                // formerly we would just kill the process, but we now ask it to exit so it
                // can choose where to stop the animation.
                SystemProperties.set("service.bootanim.exit", "1");
                mBootAnimationStopped = true;
            }

            if (!mForceDisplayEnabled && !checkBootAnimationCompleteLocked()) {
                if (DEBUG_BOOT) Slog.i(TAG_WM, "performEnableScreen: Waiting for anim complete");
                return;
            }

            try {
                IBinder surfaceFlinger = ServiceManager.getService("SurfaceFlinger");
                if (surfaceFlinger != null) {
                    Slog.i(TAG_WM, "******* TELLING SURFACE FLINGER WE ARE BOOTED!");
                    Parcel data = Parcel.obtain();
                    data.writeInterfaceToken("android.ui.ISurfaceComposer");
                    surfaceFlinger.transact(IBinder.FIRST_CALL_TRANSACTION, // BOOT_FINISHED
                            data, null, 0);
                    data.recycle();
                }
            } catch (RemoteException ex) {
                Slog.e(TAG_WM, "Boot completed: SurfaceFlinger is dead!");
            }

            EventLog.writeEvent(EventLogTags.WM_BOOT_ANIMATION_DONE, SystemClock.uptimeMillis());
            Trace.asyncTraceEnd(TRACE_TAG_WINDOW_MANAGER, "Stop bootanim", 0);
            mDisplayEnabled = true;
            if (DEBUG_SCREEN_ON || DEBUG_BOOT) Slog.i(TAG_WM, "******************** ENABLING SCREEN!");

            // Enable input dispatch.
            mInputManagerCallback.setEventDispatchingLw(mEventDispatchingEnabled);
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
        synchronized (mGlobalLock) {
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
        synchronized (mGlobalLock) {
            mInTouchMode = mode;
        }
    }

    private void updateCircularDisplayMaskIfNeeded() {
        if (mContext.getResources().getConfiguration().isScreenRound()
                && mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_windowShowCircularMask)) {
            final int currentUserId;
            synchronized (mGlobalLock) {
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
        synchronized (mGlobalLock) {

            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG_WM,
                    ">>> OPEN TRANSACTION showCircularMask(visible=" + visible + ")");
            openSurfaceTransaction();
            try {
                if (visible) {
                    // TODO(multi-display): support multiple displays
                    if (mCircularDisplayMask == null) {
                        int screenOffset = mContext.getResources().getInteger(
                                com.android.internal.R.integer.config_windowOutsetBottom);
                        int maskThickness = mContext.getResources().getDimensionPixelSize(
                                com.android.internal.R.dimen.circular_display_mask_thickness);

                        mCircularDisplayMask = new CircularDisplayMask(
                                getDefaultDisplayContentLocked(),
                                mPolicy.getWindowLayerFromTypeLw(
                                        WindowManager.LayoutParams.TYPE_POINTER)
                                        * TYPE_LAYER_MULTIPLIER + 10, screenOffset, maskThickness);
                    }
                    mCircularDisplayMask.setVisibility(true);
                } else if (mCircularDisplayMask != null) {
                    mCircularDisplayMask.setVisibility(false);
                    mCircularDisplayMask = null;
                }
            } finally {
                closeSurfaceTransaction("showCircularMask");
                if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG_WM,
                        "<<< CLOSE TRANSACTION showCircularMask(visible=" + visible + ")");
            }
        }
    }

    public void showEmulatorDisplayOverlay() {
        synchronized (mGlobalLock) {

            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG_WM,
                    ">>> OPEN TRANSACTION showEmulatorDisplayOverlay");
            openSurfaceTransaction();
            try {
                if (mEmulatorDisplayOverlay == null) {
                    mEmulatorDisplayOverlay = new EmulatorDisplayOverlay(
                            mContext,
                            getDefaultDisplayContentLocked(),
                            mPolicy.getWindowLayerFromTypeLw(
                                    WindowManager.LayoutParams.TYPE_POINTER)
                                    * TYPE_LAYER_MULTIPLIER + 10);
                }
                mEmulatorDisplayOverlay.setVisibility(true);
            } finally {
                closeSurfaceTransaction("showEmulatorDisplayOverlay");
                if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG_WM,
                        "<<< CLOSE TRANSACTION showEmulatorDisplayOverlay");
            }
        }
    }

    // TODO: more accounting of which pid(s) turned it on, keep count,
    // only allow disables from pids which have count on, etc.
    @Override
    public void showStrictModeViolation(boolean on) {
        final int pid = Binder.getCallingPid();
        if (on) {
            // Show the visualization, and enqueue a second message to tear it
            // down if we don't hear back from the app.
            mH.sendMessage(mH.obtainMessage(H.SHOW_STRICT_MODE_VIOLATION, 1, pid));
            mH.sendMessageDelayed(mH.obtainMessage(H.SHOW_STRICT_MODE_VIOLATION, 0, pid),
                    DateUtils.SECOND_IN_MILLIS);
        } else {
            mH.sendMessage(mH.obtainMessage(H.SHOW_STRICT_MODE_VIOLATION, 0, pid));
        }
    }

    private void showStrictModeViolation(int arg, int pid) {
        final boolean on = arg != 0;
        synchronized (mGlobalLock) {
            // Ignoring requests to enable the red border from clients which aren't on screen.
            // (e.g. Broadcast Receivers in the background..)
            if (on && !mRoot.canShowStrictModeViolation(pid)) {
                return;
            }

            if (SHOW_VERBOSE_TRANSACTIONS) Slog.i(TAG_WM,
                    ">>> OPEN TRANSACTION showStrictModeViolation");
            // TODO: Modify this to use the surface trace once it is not going crazy.
            // b/31532461
            SurfaceControl.openTransaction();
            try {
                // TODO(multi-display): support multiple displays
                if (mStrictModeFlash == null) {
                    mStrictModeFlash = new StrictModeFlash(
                            getDefaultDisplayContentLocked());
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

    @Override
    public Bitmap screenshotWallpaper() {
        if (!checkCallingPermission(READ_FRAME_BUFFER, "screenshotWallpaper()")) {
            throw new SecurityException("Requires READ_FRAME_BUFFER permission");
        }
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "screenshotWallpaper");
            synchronized (mGlobalLock) {
                // TODO(b/115486823) Screenshot at secondary displays if needed.
                final DisplayContent dc = mRoot.getDisplayContent(DEFAULT_DISPLAY);
                return dc.mWallpaperController.screenshotWallpaperLocked();
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    /**
     * Takes a snapshot of the screen.  In landscape mode this grabs the whole screen.
     * In portrait mode, it grabs the upper region of the screen based on the vertical dimension
     * of the target image.
     */
    @Override
    public boolean requestAssistScreenshot(final IAssistDataReceiver receiver) {
        if (!checkCallingPermission(READ_FRAME_BUFFER, "requestAssistScreenshot()")) {
            throw new SecurityException("Requires READ_FRAME_BUFFER permission");
        }

        final Bitmap bm;
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(DEFAULT_DISPLAY);
            if (displayContent == null) {
                if (DEBUG_SCREENSHOT) {
                    Slog.i(TAG_WM, "Screenshot returning null. No Display for displayId="
                            + DEFAULT_DISPLAY);
                }
                bm = null;
            } else {
                bm = displayContent.screenshotDisplayLocked(Bitmap.Config.ARGB_8888);
            }
        }

        FgThread.getHandler().post(() -> {
            try {
                receiver.onHandleAssistScreenshot(bm);
            } catch (RemoteException e) {
            }
        });

        return true;
    }

    public TaskSnapshot getTaskSnapshot(int taskId, int userId, boolean reducedResolution) {
        return mTaskSnapshotController.getSnapshot(taskId, userId, true /* restoreFromDisk */,
                reducedResolution);
    }

    /**
     * In case a task write/delete operation was lost because the system crashed, this makes sure to
     * clean up the directory to remove obsolete files.
     *
     * @param persistentTaskIds A set of task ids that exist in our in-memory model.
     * @param runningUserIds The ids of the list of users that have tasks loaded in our in-memory
     *                       model.
     */
    public void removeObsoleteTaskFiles(ArraySet<Integer> persistentTaskIds, int[] runningUserIds) {
        synchronized (mGlobalLock) {
            mTaskSnapshotController.removeObsoleteTaskFiles(persistentTaskIds, runningUserIds);
        }
    }

    void setRotateForApp(int displayId,
            @DisplayRotation.FixedToUserRotation int fixedToUserRotation) {
        synchronized (mGlobalLock) {
            final DisplayContent display = mRoot.getDisplayContent(displayId);
            if (display == null) {
                Slog.w(TAG, "Trying to set rotate for app for a missing display.");
                return;
            }
            display.getDisplayRotation().setFixedToUserRotation(fixedToUserRotation);
        }
    }

    @Override
    public void freezeRotation(int rotation) {
        freezeDisplayRotation(Display.DEFAULT_DISPLAY, rotation);
    }

    /**
     * Freeze rotation changes.  (Enable "rotation lock".)
     * Persists across reboots.
     * @param displayId The ID of the display to freeze.
     * @param rotation The desired rotation to freeze to, or -1 to use the current rotation.
     */
    @Override
    public void freezeDisplayRotation(int displayId, int rotation) {
        // TODO(multi-display): Track which display is rotated.
        if (!checkCallingPermission(android.Manifest.permission.SET_ORIENTATION,
                "freezeRotation()")) {
            throw new SecurityException("Requires SET_ORIENTATION permission");
        }
        if (rotation < -1 || rotation > Surface.ROTATION_270) {
            throw new IllegalArgumentException("Rotation argument must be -1 or a valid "
                    + "rotation constant.");
        }

        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent display = mRoot.getDisplayContent(displayId);
                if (display == null) {
                    Slog.w(TAG, "Trying to freeze rotation for a missing display.");
                    return;
                }
                display.getDisplayRotation().freezeRotation(rotation);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        updateRotationUnchecked(false, false);
    }

    @Override
    public void thawRotation() {
        thawDisplayRotation(Display.DEFAULT_DISPLAY);
    }

    /**
     * Thaw rotation changes.  (Disable "rotation lock".)
     * Persists across reboots.
     */
    @Override
    public void thawDisplayRotation(int displayId) {
        if (!checkCallingPermission(android.Manifest.permission.SET_ORIENTATION,
                "thawRotation()")) {
            throw new SecurityException("Requires SET_ORIENTATION permission");
        }

        if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "thawRotation: mRotation="
                + getDefaultDisplayRotation());

        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent display = mRoot.getDisplayContent(displayId);
                if (display == null) {
                    Slog.w(TAG, "Trying to thaw rotation for a missing display.");
                    return;
                }
                display.getDisplayRotation().thawRotation();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        updateRotationUnchecked(false, false);
    }

    @Override
    public boolean isRotationFrozen() {
        return isDisplayRotationFrozen(Display.DEFAULT_DISPLAY);
    }

    @Override
    public boolean isDisplayRotationFrozen(int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent display = mRoot.getDisplayContent(displayId);
            if (display == null) {
                Slog.w(TAG, "Trying to thaw rotation for a missing display.");
                return false;
            }
            return display.getDisplayRotation().isRotationFrozen();
        }
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

    private void updateRotationUnchecked(boolean alwaysSendConfiguration, boolean forceRelayout) {
        if(DEBUG_ORIENTATION) Slog.v(TAG_WM, "updateRotationUnchecked:"
                + " alwaysSendConfiguration=" + alwaysSendConfiguration
                + " forceRelayout=" + forceRelayout);

        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "updateRotation");

        long origId = Binder.clearCallingIdentity();

        try {
            synchronized (mGlobalLock) {
                boolean layoutNeeded = false;
                final int displayCount = mRoot.mChildren.size();
                for (int i = 0; i < displayCount; ++i) {
                    final DisplayContent displayContent = mRoot.mChildren.get(i);
                    Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "updateRotation: display");
                    final boolean rotationChanged = displayContent.updateRotationUnchecked();
                    Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);

                    if (!rotationChanged || forceRelayout) {
                        displayContent.setLayoutNeeded();
                        layoutNeeded = true;
                    }
                    if (rotationChanged || alwaysSendConfiguration) {
                        displayContent.sendNewConfiguration();
                    }
                }

                if (layoutNeeded) {
                    Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER,
                            "updateRotation: performSurfacePlacement");
                    mWindowPlacerLocked.performSurfacePlacement();
                    Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    @Override
    public int getDefaultDisplayRotation() {
        synchronized (mGlobalLock) {
            return getDefaultDisplayContentLocked().getRotation();
        }
    }

    @Override
    public int watchRotation(IRotationWatcher watcher, int displayId) {
        final DisplayContent displayContent;
        synchronized (mGlobalLock) {
            displayContent = mRoot.getDisplayContent(displayId);
        }
        if (displayContent == null) {
            throw new IllegalArgumentException("Trying to register rotation event "
                    + "for invalid display: " + displayId);
        }

        final IBinder watcherBinder = watcher.asBinder();
        IBinder.DeathRecipient dr = new IBinder.DeathRecipient() {
            @Override
            public void binderDied() {
                synchronized (mGlobalLock) {
                    for (int i=0; i<mRotationWatchers.size(); i++) {
                        if (watcherBinder == mRotationWatchers.get(i).mWatcher.asBinder()) {
                            RotationWatcher removed = mRotationWatchers.remove(i);
                            IBinder binder = removed.mWatcher.asBinder();
                            if (binder != null) {
                                binder.unlinkToDeath(this, 0);
                            }
                            i--;
                        }
                    }
                }
            }
        };

        synchronized (mGlobalLock) {
            try {
                watcher.asBinder().linkToDeath(dr, 0);
                mRotationWatchers.add(new RotationWatcher(watcher, dr, displayId));
            } catch (RemoteException e) {
                // Client died, no cleanup needed.
            }

            return displayContent.getRotation();
        }
    }

    @Override
    public void removeRotationWatcher(IRotationWatcher watcher) {
        final IBinder watcherBinder = watcher.asBinder();
        synchronized (mGlobalLock) {
            for (int i=0; i<mRotationWatchers.size(); i++) {
                RotationWatcher rotationWatcher = mRotationWatchers.get(i);
                if (watcherBinder == rotationWatcher.mWatcher.asBinder()) {
                    RotationWatcher removed = mRotationWatchers.remove(i);
                    IBinder binder = removed.mWatcher.asBinder();
                    if (binder != null) {
                        binder.unlinkToDeath(removed.mDeathRecipient, 0);
                    }
                    i--;
                }
            }
        }
    }

    @Override
    public boolean registerWallpaperVisibilityListener(IWallpaperVisibilityListener listener,
            int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent == null) {
                throw new IllegalArgumentException("Trying to register visibility event "
                        + "for invalid display: " + displayId);
            }
            mWallpaperVisibilityListeners.registerWallpaperVisibilityListener(listener, displayId);
            return displayContent.mWallpaperController.isWallpaperVisible();
        }
    }

    @Override
    public void unregisterWallpaperVisibilityListener(IWallpaperVisibilityListener listener,
            int displayId) {
        synchronized (mGlobalLock) {
            mWallpaperVisibilityListeners
                    .unregisterWallpaperVisibilityListener(listener, displayId);
        }
    }

    @Override
    public void registerDisplayFoldListener(IDisplayFoldListener listener) {
        mPolicy.registerDisplayFoldListener(listener);
    }

    @Override
    public void unregisterDisplayFoldListener(IDisplayFoldListener listener) {
        mPolicy.unregisterDisplayFoldListener(listener);
    }

    /**
     * Overrides the folded area.
     *
     * @param area the overriding folded area or an empty {@code Rect} to clear the override.
     */
    void setOverrideFoldedArea(@NonNull Rect area) {
        if (mContext.checkCallingOrSelfPermission(WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold permission " + WRITE_SECURE_SETTINGS);
        }

        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                mPolicy.setOverrideFoldedArea(area);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Get the display folded area.
     */
    @NonNull Rect getFoldedArea() {
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                return mPolicy.getFoldedArea();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public int getPreferredOptionsPanelGravity(int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent == null) {
                return Gravity.CENTER | Gravity.BOTTOM;
            }
            return displayContent.getPreferredOptionsPanelGravity();
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
     * Lists all available windows in the system. The listing is written in the specified Socket's
     * output stream with the following syntax: windowHashCodeInHexadecimal windowName
     * Each line of the output represents a different window.
     *
     * @param client The remote client to send the listing to.
     * @return false if an error occurred, true otherwise.
     */
    boolean viewServerListWindows(Socket client) {
        if (isSystemSecure()) {
            return false;
        }

        boolean result = true;

        final ArrayList<WindowState> windows = new ArrayList();
        synchronized (mGlobalLock) {
            mRoot.forAllWindows(w -> {
                windows.add(w);
            }, false /* traverseTopToBottom */);
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
        synchronized (mGlobalLock) {
            mWindowChangeListeners.add(listener);
        }
    }

    public void removeWindowChangeListener(WindowChangeListener listener) {
        synchronized (mGlobalLock) {
            mWindowChangeListeners.remove(listener);
        }
    }

    private void notifyWindowsChanged() {
        WindowChangeListener[] windowChangeListeners;
        synchronized (mGlobalLock) {
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
        synchronized (mGlobalLock) {
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

        synchronized (mGlobalLock) {
            return mRoot.getWindow((w) -> System.identityHashCode(w) == hashCode);
        }
    }

    /**
     * Instruct the Activity Manager to fetch and update the current display's configuration and
     * broadcast them to config-changed listeners if appropriate.
     * NOTE: Can't be called with the window manager lock held since it call into activity manager.
     */
    void sendNewConfiguration(int displayId) {
        try {
            final boolean configUpdated = mActivityTaskManager.updateDisplayOverrideConfiguration(
                    null /* values */, displayId);
            if (!configUpdated) {
                // Something changed (E.g. device rotation), but no configuration update is needed.
                // E.g. changing device rotation by 180 degrees. Go ahead and perform surface
                // placement to unfreeze the display since we froze it when the rotation was updated
                // in DisplayContent#updateRotationUnchecked.
                synchronized (mGlobalLock) {
                    final DisplayContent dc = mRoot.getDisplayContent(displayId);
                    if (dc != null && dc.mWaitingForConfig) {
                        dc.mWaitingForConfig = false;
                        mLastFinishedFreezeSource = "config-unchanged";
                        dc.setLayoutNeeded();
                        mWindowPlacerLocked.performSurfacePlacement();
                    }
                }
            }
        } catch (RemoteException e) {
        }
    }

    public Configuration computeNewConfiguration(int displayId) {
        synchronized (mGlobalLock) {
            return computeNewConfigurationLocked(displayId);
        }
    }

    private Configuration computeNewConfigurationLocked(int displayId) {
        if (!mDisplayReady) {
            return null;
        }
        final Configuration config = new Configuration();
        final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
        displayContent.computeScreenConfiguration(config);
        return config;
    }

    void notifyHardKeyboardStatusChange() {
        final boolean available;
        final WindowManagerInternal.OnHardKeyboardStatusChangeListener listener;
        synchronized (mGlobalLock) {
            listener = mHardKeyboardStatusChangeListener;
            available = mHardKeyboardAvailable;
        }
        if (listener != null) {
            listener.onHardKeyboardStatusChange(available);
        }
    }

    // -------------------------------------------------------------
    // Input Events and Focus Management
    // -------------------------------------------------------------

    final InputManagerCallback mInputManagerCallback = new InputManagerCallback(this);
    private boolean mEventDispatchingEnabled;

    @Override
    public void setEventDispatching(boolean enabled) {
        if (!checkCallingPermission(MANAGE_APP_TOKENS, "setEventDispatching()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized (mGlobalLock) {
            mEventDispatchingEnabled = enabled;
            if (mDisplayEnabled) {
                mInputManagerCallback.setEventDispatchingLw(enabled);
            }
        }
    }

    private WindowState getFocusedWindow() {
        synchronized (mGlobalLock) {
            return getFocusedWindowLocked();
        }
    }

    private WindowState getFocusedWindowLocked() {
        // Return the focused window in the focused display.
        return mRoot.getTopFocusedDisplayContent().mCurrentFocus;
    }

    TaskStack getImeFocusStackLocked() {
        // Don't use mCurrentFocus.getStack() because it returns home stack for system windows.
        // Also don't use mInputMethodTarget's stack, because some window with FLAG_NOT_FOCUSABLE
        // and FLAG_ALT_FOCUSABLE_IM flags both set might be set to IME target so they're moved
        // to make room for IME, but the window is not the focused window that's taking input.
        // TODO (b/111080190): Consider the case of multiple IMEs on multi-display.
        final DisplayContent topFocusedDisplay = mRoot.getTopFocusedDisplayContent();
        final AppWindowToken focusedApp = topFocusedDisplay.mFocusedApp;
        return (focusedApp != null && focusedApp.getTask() != null)
                ? focusedApp.getTask().mStack : null;
    }

    public boolean detectSafeMode() {
        if (!mInputManagerCallback.waitForInputDevicesReady(
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
                mSafeMode = true;
                SystemProperties.set(ShutdownThread.REBOOT_SAFEMODE_PROPERTY, "");
            }
        } catch (IllegalArgumentException e) {
        }
        if (mSafeMode) {
            Log.i(TAG_WM, "SAFE MODE ENABLED (menu=" + menuState + " s=" + sState
                    + " dpad=" + dpadState + " trackball=" + trackballState + ")");
            // May already be set if (for instance) this process has crashed
            if (SystemProperties.getInt(ShutdownThread.RO_SAFEMODE_PROPERTY, 0) == 0) {
                SystemProperties.set(ShutdownThread.RO_SAFEMODE_PROPERTY, "1");
            }
        } else {
            Log.i(TAG_WM, "SAFE MODE not enabled");
        }
        mPolicy.setSafeMode(mSafeMode);
        return mSafeMode;
    }

    public void displayReady() {
        synchronized (mGlobalLock) {
            if (mMaxUiWidth > 0) {
                mRoot.forAllDisplays(displayContent -> displayContent.setMaxUiWidth(mMaxUiWidth));
            }
            final boolean changed = applyForcedPropertiesForDefaultDisplay();
            mAnimator.ready();
            mDisplayReady = true;
            if (changed) {
                reconfigureDisplayLocked(getDefaultDisplayContentLocked());
            }
            mIsTouchDevice = mContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_TOUCHSCREEN);
        }

        try {
            mActivityTaskManager.updateConfiguration(null);
        } catch (RemoteException e) {
        }

        updateCircularDisplayMaskIfNeeded();
    }

    public void systemReady() {
        mSystemReady = true;
        mPolicy.systemReady();
        mRoot.forAllDisplayPolicies(DisplayPolicy::systemReady);
        mTaskSnapshotController.systemReady();
        mHasWideColorGamutSupport = queryWideColorGamutSupport();
        mHasHdrSupport = queryHdrSupport();
        UiThread.getHandler().post(mSettingsObserver::updateSystemUiSettings);
        IVrManager vrManager = IVrManager.Stub.asInterface(
                ServiceManager.getService(Context.VR_SERVICE));
        if (vrManager != null) {
            try {
                final boolean vrModeEnabled = vrManager.getVrModeState();
                synchronized (mGlobalLock) {
                    vrManager.registerListener(mVrStateCallbacks);
                    if (vrModeEnabled) {
                        mVrModeEnabled = vrModeEnabled;
                        mVrStateCallbacks.onVrStateChanged(vrModeEnabled);
                    }
                }
            } catch (RemoteException e) {
                // Ignore, we cannot do anything if we failed to register VR mode listener
            }
        }
    }

    private static boolean queryWideColorGamutSupport() {
        try {
            ISurfaceFlingerConfigs surfaceFlinger = ISurfaceFlingerConfigs.getService();
            OptionalBool hasWideColor = surfaceFlinger.hasWideColorDisplay();
            if (hasWideColor != null) {
                return hasWideColor.value;
            }
        } catch (RemoteException e) {
            // Ignore, we're in big trouble if we can't talk to SurfaceFlinger's config store
        }
        return false;
    }

    private static boolean queryHdrSupport() {
        try {
            ISurfaceFlingerConfigs surfaceFlinger = ISurfaceFlingerConfigs.getService();
            OptionalBool hasHdr = surfaceFlinger.hasHDRDisplay();
            if (hasHdr != null) {
                return hasHdr.value;
            }
        } catch (RemoteException e) {
            // Ignore, we're in big trouble if we can't talk to SurfaceFlinger's config store
        }
        return false;
    }

    // -------------------------------------------------------------
    // Async Handler
    // -------------------------------------------------------------

    final class H extends android.os.Handler {
        public static final int REPORT_FOCUS_CHANGE = 2;
        public static final int REPORT_LOSING_FOCUS = 3;
        public static final int WINDOW_FREEZE_TIMEOUT = 11;

        public static final int PERSIST_ANIMATION_SCALE = 14;
        public static final int FORCE_GC = 15;
        public static final int ENABLE_SCREEN = 16;
        public static final int APP_FREEZE_TIMEOUT = 17;
        public static final int SEND_NEW_CONFIGURATION = 18;
        public static final int REPORT_WINDOWS_CHANGE = 19;

        public static final int REPORT_HARD_KEYBOARD_STATUS_CHANGE = 22;
        public static final int BOOT_TIMEOUT = 23;
        public static final int WAITING_FOR_DRAWN_TIMEOUT = 24;
        public static final int SHOW_STRICT_MODE_VIOLATION = 25;

        public static final int CLIENT_FREEZE_TIMEOUT = 30;
        public static final int NOTIFY_ACTIVITY_DRAWN = 32;

        public static final int ALL_WINDOWS_DRAWN = 33;

        public static final int NEW_ANIMATOR_SCALE = 34;

        public static final int SHOW_CIRCULAR_DISPLAY_MASK = 35;
        public static final int SHOW_EMULATOR_DISPLAY_OVERLAY = 36;

        public static final int CHECK_IF_BOOT_ANIMATION_FINISHED = 37;
        public static final int RESET_ANR_MESSAGE = 38;
        public static final int WALLPAPER_DRAW_PENDING_TIMEOUT = 39;

        public static final int UPDATE_DOCKED_STACK_DIVIDER = 41;

        public static final int WINDOW_REPLACEMENT_TIMEOUT = 46;

        public static final int UPDATE_ANIMATION_SCALE = 51;
        public static final int WINDOW_HIDE_TIMEOUT = 52;
        public static final int SEAMLESS_ROTATION_TIMEOUT = 54;
        public static final int RESTORE_POINTER_ICON = 55;
        public static final int SET_HAS_OVERLAY_UI = 58;
        public static final int SET_RUNNING_REMOTE_ANIMATION = 59;
        public static final int ANIMATION_FAILSAFE = 60;
        public static final int RECOMPUTE_FOCUS = 61;

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
                    final DisplayContent displayContent = (DisplayContent) msg.obj;
                    WindowState lastFocus;
                    WindowState newFocus;

                    AccessibilityController accessibilityController = null;

                    synchronized (mGlobalLock) {
                        // TODO(multidisplay): Accessibility supported only of default desiplay.
                        if (mAccessibilityController != null && displayContent.isDefaultDisplay) {
                            accessibilityController = mAccessibilityController;
                        }

                        lastFocus = displayContent.mLastFocus;
                        newFocus = displayContent.mCurrentFocus;
                    }
                    if (lastFocus == newFocus) {
                        // Focus is not changing, so nothing to do.
                        return;
                    }
                    synchronized (mGlobalLock) {
                        displayContent.mLastFocus = newFocus;
                        if (DEBUG_FOCUS_LIGHT) Slog.i(TAG_WM, "Focus moving from " + lastFocus +
                                " to " + newFocus + " displayId=" + displayContent.getDisplayId());
                        if (newFocus != null && lastFocus != null && !newFocus.isDisplayedLw()) {
                            if (DEBUG_FOCUS_LIGHT) Slog.i(TAG_WM, "Delaying loss of focus...");
                            displayContent.mLosingFocus.add(lastFocus);
                            lastFocus = null;
                        }
                    }

                    // First notify the accessibility manager for the change so it has
                    // the windows before the newly focused one starts firing eventgs.
                    if (accessibilityController != null) {
                        accessibilityController.onWindowFocusChangedNotLocked();
                    }

                    if (newFocus != null) {
                        if (DEBUG_FOCUS_LIGHT) Slog.i(TAG_WM, "Gaining focus: " + newFocus);
                        newFocus.reportFocusChangedSerialized(true, mInTouchMode);
                        notifyFocusChanged();
                    }

                    if (lastFocus != null) {
                        if (DEBUG_FOCUS_LIGHT) Slog.i(TAG_WM, "Losing focus: " + lastFocus);
                        lastFocus.reportFocusChangedSerialized(false, mInTouchMode);
                    }
                    break;
                }

                case REPORT_LOSING_FOCUS: {
                    final DisplayContent displayContent = (DisplayContent) msg.obj;
                    ArrayList<WindowState> losers;

                    synchronized (mGlobalLock) {
                        losers = displayContent.mLosingFocus;
                        displayContent.mLosingFocus = new ArrayList<>();
                    }

                    final int N = losers.size();
                    for (int i = 0; i < N; i++) {
                        if (DEBUG_FOCUS_LIGHT) Slog.i(TAG_WM, "Losing delayed focus: " +
                                losers.get(i));
                        losers.get(i).reportFocusChangedSerialized(false, mInTouchMode);
                    }
                    break;
                }

                case WINDOW_FREEZE_TIMEOUT: {
                    final DisplayContent displayContent = (DisplayContent) msg.obj;
                    synchronized (mGlobalLock) {
                        displayContent.onWindowFreezeTimeout();
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
                    synchronized (mGlobalLock) {
                        // Since we're holding both mWindowMap and mAnimator we don't need to
                        // hold mAnimator.mLayoutToAnim.
                        if (mAnimator.isAnimating() || mAnimator.isAnimationScheduled()) {
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
                    synchronized (mGlobalLock) {
                        Slog.w(TAG_WM, "App freeze timeout expired.");
                        mWindowsFreezingScreen = WINDOWS_FREEZING_SCREENS_TIMEOUT;
                        for (int i = mAppFreezeListeners.size() - 1; i >=0 ; --i) {
                            mAppFreezeListeners.get(i).onAppFreezeTimeout();
                        }
                    }
                    break;
                }

                case CLIENT_FREEZE_TIMEOUT: {
                    synchronized (mGlobalLock) {
                        if (mClientFreezingScreen) {
                            mClientFreezingScreen = false;
                            mLastFinishedFreezeSource = "client-timeout";
                            stopFreezingDisplayLocked();
                        }
                    }
                    break;
                }

                case SEND_NEW_CONFIGURATION: {
                    final DisplayContent displayContent = (DisplayContent) msg.obj;
                    removeMessages(SEND_NEW_CONFIGURATION, displayContent);
                    if (displayContent.isReady()) {
                        sendNewConfiguration(displayContent.getDisplayId());
                    } else {
                        // Message could come after display has already been removed.
                        if (DEBUG_CONFIGURATION) {
                            final String reason = displayContent.getParent() == null
                                    ? "detached" : "unready";
                            Slog.w(TAG, "Trying to send configuration to " + reason + " display="
                                    + displayContent);
                        }
                    }
                    break;
                }

                case REPORT_WINDOWS_CHANGE: {
                    if (mWindowsChanged) {
                        synchronized (mGlobalLock) {
                            mWindowsChanged = false;
                        }
                        notifyWindowsChanged();
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
                    synchronized (mGlobalLock) {
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

                case NOTIFY_ACTIVITY_DRAWN: {
                    try {
                        mActivityTaskManager.notifyActivityDrawn((IBinder) msg.obj);
                    } catch (RemoteException e) {
                    }
                    break;
                }
                case ALL_WINDOWS_DRAWN: {
                    Runnable callback;
                    synchronized (mGlobalLock) {
                        callback = mWaitingForDrawnCallback;
                        mWaitingForDrawnCallback = null;
                    }
                    if (callback != null) {
                        callback.run();
                    }
                    break;
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
                        synchronized (mGlobalLock) {
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
                    break;
                }
                case CHECK_IF_BOOT_ANIMATION_FINISHED: {
                    final boolean bootAnimationComplete;
                    synchronized (mGlobalLock) {
                        if (DEBUG_BOOT) Slog.i(TAG_WM, "CHECK_IF_BOOT_ANIMATION_FINISHED:");
                        bootAnimationComplete = checkBootAnimationCompleteLocked();
                    }
                    if (bootAnimationComplete) {
                        performEnableScreen();
                    }
                    break;
                }
                case RESET_ANR_MESSAGE: {
                    synchronized (mGlobalLock) {
                        mLastANRState = null;
                    }
                    mAtmInternal.clearSavedANRState();
                    break;
                }
                case WALLPAPER_DRAW_PENDING_TIMEOUT: {
                    synchronized (mGlobalLock) {
                        final WallpaperController wallpaperController =
                                (WallpaperController) msg.obj;
                        if (wallpaperController != null
                                && wallpaperController.processWallpaperDrawPendingTimeout()) {
                            mWindowPlacerLocked.performSurfacePlacement();
                        }
                    }
                    break;
                }
                case UPDATE_DOCKED_STACK_DIVIDER: {
                    synchronized (mGlobalLock) {
                        final DisplayContent displayContent = getDefaultDisplayContentLocked();
                        displayContent.getDockedDividerController().reevaluateVisibility(false);
                        displayContent.adjustForImeIfNeeded();
                    }
                    break;
                }
                case WINDOW_REPLACEMENT_TIMEOUT: {
                    synchronized (mGlobalLock) {
                        for (int i = mWindowReplacementTimeouts.size() - 1; i >= 0; i--) {
                            final AppWindowToken token = mWindowReplacementTimeouts.get(i);
                            token.onWindowReplacementTimeout();
                        }
                        mWindowReplacementTimeouts.clear();
                    }
                    break;
                }
                case WINDOW_HIDE_TIMEOUT: {
                    final WindowState window = (WindowState) msg.obj;
                    synchronized (mGlobalLock) {
                        // TODO: This is all about fixing b/21693547
                        // where partially initialized Toasts get stuck
                        // around and keep the screen on. We'd like
                        // to just remove the toast...but this can cause clients
                        // who miss the timeout due to normal circumstances (e.g.
                        // running under debugger) to crash (b/29105388). The windows will
                        // eventually be removed when the client process finishes.
                        // The best we can do for now is remove the FLAG_KEEP_SCREEN_ON
                        // and prevent the symptoms of b/21693547. Since apps don't
                        // support windows being removed under them we hide the window
                        // and it will be removed when the app dies.
                        window.mAttrs.flags &= ~FLAG_KEEP_SCREEN_ON;
                        window.hidePermanentlyLw();
                        window.setDisplayLayoutNeeded();
                        mWindowPlacerLocked.performSurfacePlacement();
                    }
                    break;
                }
                case RESTORE_POINTER_ICON: {
                    synchronized (mGlobalLock) {
                        restorePointerIconLocked((DisplayContent)msg.obj, msg.arg1, msg.arg2);
                    }
                    break;
                }
                case SEAMLESS_ROTATION_TIMEOUT: {
                    final DisplayContent displayContent = (DisplayContent) msg.obj;
                    synchronized (mGlobalLock) {
                        displayContent.onSeamlessRotationTimeout();
                    }
                    break;
                }
                case SET_HAS_OVERLAY_UI: {
                    mAmInternal.setHasOverlayUi(msg.arg1, msg.arg2 == 1);
                    break;
                }
                case SET_RUNNING_REMOTE_ANIMATION: {
                    mAmInternal.setRunningRemoteAnimation(msg.arg1, msg.arg2 == 1);
                    break;
                }
                case ANIMATION_FAILSAFE: {
                    synchronized (mGlobalLock) {
                        if (mRecentsAnimationController != null) {
                            mRecentsAnimationController.scheduleFailsafe();
                        }
                    }
                    break;
                }
                case RECOMPUTE_FOCUS: {
                    synchronized (mGlobalLock) {
                        updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL,
                                true /* updateInputWindows */);
                    }
                    break;
                }
            }
            if (DEBUG_WINDOW_TRACE) {
                Slog.v(TAG_WM, "handleMessage: exit");
            }
        }

        /** Remove the previous messages with the same 'what' and 'obj' then send the new one. */
        void sendNewMessageDelayed(int what, Object obj, long delayMillis) {
            removeMessages(what, obj);
            sendMessageDelayed(obtainMessage(what, obj), delayMillis);
        }
    }

    void destroyPreservedSurfaceLocked() {
        for (int i = mDestroyPreservedSurface.size() - 1; i >= 0 ; i--) {
            final WindowState w = mDestroyPreservedSurface.get(i);
            w.mWinAnimator.destroyPreservedSurfaceLocked();
        }
        mDestroyPreservedSurface.clear();
    }

    // -------------------------------------------------------------
    // IWindowManager API
    // -------------------------------------------------------------

    @Override
    public IWindowSession openSession(IWindowSessionCallback callback) {
        return new Session(this, callback);
    }

    @Override
    public void getInitialDisplaySize(int displayId, Point size) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent != null && displayContent.hasAccess(Binder.getCallingUid())) {
                size.x = displayContent.mInitialDisplayWidth;
                size.y = displayContent.mInitialDisplayHeight;
            }
        }
    }

    @Override
    public void getBaseDisplaySize(int displayId, Point size) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent != null && displayContent.hasAccess(Binder.getCallingUid())) {
                size.x = displayContent.mBaseDisplayWidth;
                size.y = displayContent.mBaseDisplayHeight;
            }
        }
    }

    @Override
    public void setForcedDisplaySize(int displayId, int width, int height) {
        if (mContext.checkCallingOrSelfPermission(WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold permission " + WRITE_SECURE_SETTINGS);
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    displayContent.setForcedSize(width, height);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void setForcedDisplayScalingMode(int displayId, int mode) {
        if (mContext.checkCallingOrSelfPermission(WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold permission " + WRITE_SECURE_SETTINGS);
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    displayContent.setForcedScalingMode(mode);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /** The global settings only apply to default display. */
    private boolean applyForcedPropertiesForDefaultDisplay() {
        boolean changed = false;
        final DisplayContent displayContent = getDefaultDisplayContentLocked();
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
                        displayContent.updateBaseDisplayMetrics(width, height,
                                displayContent.mBaseDisplayDensity);
                        changed = true;
                    }
                } catch (NumberFormatException ex) {
                }
            }
        }

        // Display density.
        final int density = getForcedDisplayDensityForUserLocked(mCurrentUserId);
        if (density != 0 && density != displayContent.mBaseDisplayDensity) {
            displayContent.mBaseDisplayDensity = density;
            changed = true;
        }

        // Display scaling mode.
        int mode = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DISPLAY_SCALING_FORCE, 0);
        if (displayContent.mDisplayScalingDisabled != (mode != 0)) {
            Slog.i(TAG_WM, "FORCED DISPLAY SCALING DISABLED");
            displayContent.mDisplayScalingDisabled = true;
            changed = true;
        }
        return changed;
    }

    @Override
    public void clearForcedDisplaySize(int displayId) {
        if (mContext.checkCallingOrSelfPermission(WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold permission " + WRITE_SECURE_SETTINGS);
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    displayContent.setForcedSize(displayContent.mInitialDisplayWidth,
                            displayContent.mInitialDisplayHeight);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public int getInitialDisplayDensity(int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent != null && displayContent.hasAccess(Binder.getCallingUid())) {
                return displayContent.mInitialDisplayDensity;
            }
        }
        return -1;
    }

    @Override
    public int getBaseDisplayDensity(int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent != null && displayContent.hasAccess(Binder.getCallingUid())) {
                return displayContent.mBaseDisplayDensity;
            }
        }
        return -1;
    }

    @Override
    public void setForcedDisplayDensityForUser(int displayId, int density, int userId) {
        if (mContext.checkCallingOrSelfPermission(WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold permission " + WRITE_SECURE_SETTINGS);
        }

        final int targetUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "setForcedDisplayDensityForUser",
                null);
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    displayContent.setForcedDensity(density, targetUserId);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void clearForcedDisplayDensityForUser(int displayId, int userId) {
        if (mContext.checkCallingOrSelfPermission(WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold permission " + WRITE_SECURE_SETTINGS);
        }

        final int callingUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "clearForcedDisplayDensityForUser",
                null);
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    displayContent.setForcedDensity(displayContent.mInitialDisplayDensity,
                            callingUserId);
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

    void reconfigureDisplayLocked(@NonNull DisplayContent displayContent) {
        if (!displayContent.isReady()) {
            return;
        }
        displayContent.configureDisplayPolicy();
        displayContent.setLayoutNeeded();

        boolean configChanged = displayContent.updateOrientationFromAppTokens();
        final Configuration currentDisplayConfig = displayContent.getConfiguration();
        mTempConfiguration.setTo(currentDisplayConfig);
        displayContent.computeScreenConfiguration(mTempConfiguration);
        configChanged |= currentDisplayConfig.diff(mTempConfiguration) != 0;

        if (configChanged) {
            displayContent.mWaitingForConfig = true;
            startFreezingDisplayLocked(0 /* exitAnim */,
                    0 /* enterAnim */, displayContent);
            displayContent.sendNewConfiguration();
        }

        mWindowPlacerLocked.performSurfacePlacement();
    }

    @Override
    public void setOverscan(int displayId, int left, int top, int right, int bottom) {
        if (mContext.checkCallingOrSelfPermission(WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold permission " + WRITE_SECURE_SETTINGS);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                DisplayContent displayContent = mRoot.getDisplayContent(displayId);
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

        mDisplayWindowSettings.setOverscanLocked(displayInfo, left, top, right, bottom);

        reconfigureDisplayLocked(displayContent);
    }

    @Override
    public void startWindowTrace(){
        mWindowTracing.startTrace(null /* printwriter */);
    }

    @Override
    public void stopWindowTrace(){
        mWindowTracing.stopTrace(null /* printwriter */);
    }

    @Override
    public boolean isWindowTraceEnabled() {
        return mWindowTracing.isEnabled();
    }

    // -------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------

    final WindowState windowForClientLocked(Session session, IWindow client, boolean throwOnError) {
        return windowForClientLocked(session, client.asBinder(), throwOnError);
    }

    final WindowState windowForClientLocked(Session session, IBinder client, boolean throwOnError) {
        WindowState win = mWindowMap.get(client);
        if (localLOGV) Slog.v(TAG_WM, "Looking up client " + client + ": " + win);
        if (win == null) {
            if (throwOnError) {
                throw new IllegalArgumentException(
                        "Requested window " + client + " does not exist");
            }
            Slog.w(TAG_WM, "Failed looking up window callers=" + Debug.getCallers(3));
            return null;
        }
        if (session != null && win.mSession != session) {
            if (throwOnError) {
                throw new IllegalArgumentException("Requested window " + client + " is in session "
                        + win.mSession + ", not " + session);
            }
            Slog.w(TAG_WM, "Failed looking up window callers=" + Debug.getCallers(3));
            return null;
        }

        return win;
    }

    void makeWindowFreezingScreenIfNeededLocked(WindowState w) {
        // If the screen is currently frozen or off, then keep
        // it frozen/off until this window draws at its new
        // orientation.
        if (!w.mToken.okToDisplay() && mWindowsFreezingScreen != WINDOWS_FREEZING_SCREENS_TIMEOUT) {
            if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Changing surface while display frozen: " + w);
            w.setOrientationChanging(true);
            w.mLastFreezeDuration = 0;
            mRoot.mOrientationChangeComplete = false;
            if (mWindowsFreezingScreen == WINDOWS_FREEZING_SCREENS_NONE) {
                mWindowsFreezingScreen = WINDOWS_FREEZING_SCREENS_ACTIVE;
                // XXX should probably keep timeout from
                // when we first froze the display.
                mH.sendNewMessageDelayed(H.WINDOW_FREEZE_TIMEOUT, w.getDisplayContent(),
                        WINDOW_FREEZE_TIMEOUT_DURATION);
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
                    Slog.d(TAG_KEEP_SCREEN_ON, "Acquiring screen wakelock due to "
                            + mRoot.mHoldScreenWindow);
                }
                mLastWakeLockHoldingWindow = mRoot.mHoldScreenWindow;
                mLastWakeLockObscuringWindow = null;
                mHoldingScreenWakeLock.acquire();
                mPolicy.keepScreenOnStartedLw();
            } else {
                if (DEBUG_KEEP_SCREEN_ON) {
                    Slog.d(TAG_KEEP_SCREEN_ON, "Releasing screen wakelock, obscured by "
                            + mRoot.mObscuringWindow);
                }
                mLastWakeLockHoldingWindow = null;
                mLastWakeLockObscuringWindow = mRoot.mObscuringWindow;
                mPolicy.keepScreenOnStoppedLw();
                mHoldingScreenWakeLock.release();
            }
        }
    }

    void requestTraversal() {
        synchronized (mGlobalLock) {
            mWindowPlacerLocked.requestTraversal();
        }
    }

    /** Note that Locked in this case is on mLayoutToAnim */
    void scheduleAnimationLocked() {
        if (mAnimator != null) {
            mAnimator.scheduleAnimation();
        }
    }

    boolean updateFocusedWindowLocked(int mode, boolean updateInputWindows) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "wmUpdateFocus");
        boolean changed = mRoot.updateFocusedWindowLocked(mode, updateInputWindows);
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        return changed;
    }

    void startFreezingDisplayLocked(int exitAnim, int enterAnim) {
        startFreezingDisplayLocked(exitAnim, enterAnim,
                getDefaultDisplayContentLocked());
    }

    void startFreezingDisplayLocked(int exitAnim, int enterAnim,
            DisplayContent displayContent) {
        if (mDisplayFrozen || mRotatingSeamlessly) {
            return;
        }

        if (!displayContent.isReady() || !mPolicy.isScreenOn() || !displayContent.okToAnimate()) {
            // No need to freeze the screen before the display is ready,  if the screen is off,
            // or we can't currently animate.
            return;
        }

        if (DEBUG_ORIENTATION) Slog.d(TAG_WM,
                "startFreezingDisplayLocked: exitAnim="
                + exitAnim + " enterAnim=" + enterAnim
                + " called by " + Debug.getCallers(8));
        mScreenFrozenLock.acquire();

        mDisplayFrozen = true;
        mDisplayFreezeTime = SystemClock.elapsedRealtime();
        mLastFinishedFreezeSource = null;

        // {@link mDisplayFrozen} prevents us from freezing on multiple displays at the same time.
        // As a result, we only track the display that has initially froze the screen.
        mFrozenDisplayId = displayContent.getDisplayId();

        mInputManagerCallback.freezeInputDispatchingLw();

        if (displayContent.mAppTransition.isTransitionSet()) {
            displayContent.mAppTransition.freeze();
        }

        if (PROFILE_ORIENTATION) {
            File file = new File("/data/system/frozen");
            Debug.startMethodTracing(file.toString(), 8 * 1024 * 1024);
        }

        mLatencyTracker.onActionStart(ACTION_ROTATE_SCREEN);
        if (CUSTOM_SCREEN_ROTATION) {
            mExitAnimId = exitAnim;
            mEnterAnimId = enterAnim;
            ScreenRotationAnimation screenRotationAnimation =
                    mAnimator.getScreenRotationAnimationLocked(mFrozenDisplayId);
            if (screenRotationAnimation != null) {
                screenRotationAnimation.kill();
            }

            // Check whether the current screen contains any secure content.
            boolean isSecure = displayContent.hasSecureWindowOnScreen();

            displayContent.updateDisplayInfo();
            screenRotationAnimation = new ScreenRotationAnimation(mContext, displayContent,
                    displayContent.getDisplayRotation().isFixedToUserRotation(), isSecure,
                    this);
            mAnimator.setScreenRotationAnimationLocked(mFrozenDisplayId,
                    screenRotationAnimation);
        }
    }

    void stopFreezingDisplayLocked() {
        if (!mDisplayFrozen) {
            return;
        }

        final DisplayContent displayContent = mRoot.getDisplayContent(mFrozenDisplayId);
        final boolean waitingForConfig = displayContent != null && displayContent.mWaitingForConfig;
        final int numOpeningApps = displayContent != null ? displayContent.mOpeningApps.size() : 0;
        if (waitingForConfig || mAppsFreezingScreen > 0
                || mWindowsFreezingScreen == WINDOWS_FREEZING_SCREENS_ACTIVE
                || mClientFreezingScreen || numOpeningApps > 0) {
            if (DEBUG_ORIENTATION) Slog.d(TAG_WM,
                "stopFreezingDisplayLocked: Returning mWaitingForConfig=" + waitingForConfig
                + ", mAppsFreezingScreen=" + mAppsFreezingScreen
                + ", mWindowsFreezingScreen=" + mWindowsFreezingScreen
                + ", mClientFreezingScreen=" + mClientFreezingScreen
                + ", mOpeningApps.size()=" + numOpeningApps);
            return;
        }

        if (DEBUG_ORIENTATION) Slog.d(TAG_WM,
                "stopFreezingDisplayLocked: Unfreezing now");


        // We must make a local copy of the displayId as it can be potentially overwritten later on
        // in this method. For example, {@link startFreezingDisplayLocked} may be called as a result
        // of update rotation, but we reference the frozen display after that call in this method.
        final int displayId = mFrozenDisplayId;
        mFrozenDisplayId = INVALID_DISPLAY;
        mDisplayFrozen = false;
        mInputManagerCallback.thawInputDispatchingLw();
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

        ScreenRotationAnimation screenRotationAnimation =
                mAnimator.getScreenRotationAnimationLocked(displayId);
        if (CUSTOM_SCREEN_ROTATION && screenRotationAnimation != null
                && screenRotationAnimation.hasScreenshot()) {
            if (DEBUG_ORIENTATION) Slog.i(TAG_WM, "**** Dismissing screen rotation animation");
            DisplayInfo displayInfo = displayContent.getDisplayInfo();
            // Get rotation animation again, with new top window
            if (!displayContent.getDisplayPolicy()
                    .validateRotationAnimationLw(mExitAnimId, mEnterAnimId, false)) {
                mExitAnimId = mEnterAnimId = 0;
            }
            if (screenRotationAnimation.dismiss(mTransaction, MAX_ANIMATION_DURATION,
                    getTransitionAnimationScaleLocked(), displayInfo.logicalWidth,
                        displayInfo.logicalHeight, mExitAnimId, mEnterAnimId)) {
                mTransaction.apply();
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

        boolean configChanged;

        // While the display is frozen we don't re-compute the orientation
        // to avoid inconsistent states.  However, something interesting
        // could have actually changed during that time so re-evaluate it
        // now to catch that.
        configChanged = displayContent != null && displayContent.updateOrientationFromAppTokens();

        // A little kludge: a lot could have happened while the
        // display was frozen, so now that we are coming back we
        // do a gc so that any remote references the system
        // processes holds on others can be released if they are
        // no longer needed.
        mH.removeMessages(H.FORCE_GC);
        mH.sendEmptyMessageDelayed(H.FORCE_GC, 2000);

        mScreenFrozenLock.release();

        if (updateRotation && displayContent != null && updateRotation) {
            if (DEBUG_ORIENTATION) Slog.d(TAG_WM, "Performing post-rotate rotation");
            configChanged |= displayContent.updateRotationUnchecked();
        }

        if (configChanged) {
            displayContent.sendNewConfiguration();
        }
        mLatencyTracker.onActionEnd(ACTION_ROTATE_SCREEN);
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
                    // TODO(multi-display): Show watermarks on secondary displays.
                    final DisplayContent displayContent = getDefaultDisplayContentLocked();
                    mWatermark = new Watermark(displayContent, displayContent.mRealDisplayMetrics,
                            toks);
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
    public void setRecentsVisibility(boolean visible) {
        mAtmInternal.enforceCallerIsRecentsOrHasPermission(android.Manifest.permission.STATUS_BAR,
                "setRecentsVisibility()");
        synchronized (mGlobalLock) {
            mPolicy.setRecentsVisibilityLw(visible);
        }
    }

    @Override
    public void setPipVisibility(boolean visible) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.STATUS_BAR)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Caller does not hold permission "
                    + android.Manifest.permission.STATUS_BAR);
        }

        synchronized (mGlobalLock) {
            mPolicy.setPipVisibilityLw(visible);
        }
    }

    @Override
    public void setShelfHeight(boolean visible, int shelfHeight) {
        mAtmInternal.enforceCallerIsRecentsOrHasPermission(android.Manifest.permission.STATUS_BAR,
                "setShelfHeight()");
        synchronized (mGlobalLock) {
            getDefaultDisplayContentLocked().getPinnedStackController().setAdjustedForShelf(visible,
                    shelfHeight);
        }
    }

    @Override
    public void statusBarVisibilityChanged(int displayId, int visibility) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.STATUS_BAR)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Caller does not hold permission "
                    + android.Manifest.permission.STATUS_BAR);
        }

        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent != null) {
                displayContent.statusBarVisibilityChanged(visibility);
            } else {
                Slog.w(TAG, "statusBarVisibilityChanged with invalid displayId=" + displayId);
            }
        }
    }

    public void setNavBarVirtualKeyHapticFeedbackEnabled(boolean enabled) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.STATUS_BAR)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Caller does not hold permission "
                    + android.Manifest.permission.STATUS_BAR);
        }

        synchronized (mGlobalLock) {
            mPolicy.setNavBarVirtualKeyHapticFeedbackEnabledLw(enabled);
        }
    }

    /**
     * Used by ActivityManager to determine where to position an app with aspect ratio shorter then
     * the screen is.
     * @see DisplayPolicy#getNavBarPosition()
     */
    @Override
    @WindowManagerPolicy.NavigationBarPosition
    public int getNavBarPosition(int displayId) {
        synchronized (mGlobalLock) {
            // Perform layout if it was scheduled before to make sure that we get correct nav bar
            // position when doing rotations.
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent == null) {
                Slog.w(TAG, "getNavBarPosition with invalid displayId=" + displayId
                        + " callers=" + Debug.getCallers(3));
                return NAV_BAR_INVALID;
            }
            displayContent.performLayout(false /* initial */,
                    false /* updateInputWindows */);
            return displayContent.getDisplayPolicy().getNavBarPosition();
        }
    }

    @Override
    public WindowManagerPolicy.InputConsumer createInputConsumer(Looper looper, String name,
            InputEventReceiver.Factory inputEventReceiverFactory, int displayId) {
        synchronized (mGlobalLock) {
            DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent != null) {
                return displayContent.getInputMonitor().createInputConsumer(looper, name,
                        inputEventReceiverFactory);
            } else {
                return null;
            }
        }
    }

    @Override
    public void createInputConsumer(IBinder token, String name, int displayId,
            InputChannel inputChannel) {
        synchronized (mGlobalLock) {
            DisplayContent display = mRoot.getDisplayContent(displayId);
            if (display != null) {
                display.getInputMonitor().createInputConsumer(token, name, inputChannel,
                        Binder.getCallingPid(), Binder.getCallingUserHandle());
            }
        }
    }

    @Override
    public boolean destroyInputConsumer(String name, int displayId) {
        synchronized (mGlobalLock) {
            DisplayContent display = mRoot.getDisplayContent(displayId);
            if (display != null) {
                return display.getInputMonitor().destroyInputConsumer(name);
            }
            return false;
        }
    }

    @Override
    public Region getCurrentImeTouchRegion() {
        if (mContext.checkCallingOrSelfPermission(RESTRICTED_VR_ACCESS) != PERMISSION_GRANTED) {
            throw new SecurityException("getCurrentImeTouchRegion is restricted to VR services");
        }
        synchronized (mGlobalLock) {
            final Region r = new Region();
            // TODO(b/111080190): this method is only return the recent focused IME touch region,
            // For Multi-Session IME, will need to add API for given display Id to
            // get the right IME touch region.
            for (int i = mRoot.mChildren.size() - 1; i >= 0; --i) {
                final DisplayContent displayContent = mRoot.mChildren.get(i);
                if (displayContent.mInputMethodWindow != null) {
                    displayContent.mInputMethodWindow.getTouchableRegion(r);
                    return r;
                }
            }
            return r;
        }
    }

    @Override
    public boolean hasNavigationBar(int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent dc = mRoot.getDisplayContent(displayId);
            if (dc == null) {
                return false;
            }
            return dc.getDisplayPolicy().hasNavigationBar();
        }
    }

    @Override
    public void lockNow(Bundle options) {
        mPolicy.lockNow(options);
    }

    public void showRecentApps() {
        mPolicy.showRecentApps();
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
        synchronized (mGlobalLock) {
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
        synchronized (mGlobalLock) {
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
        synchronized (mGlobalLock) {
            final AppWindowToken appWindow = mRoot.getAppWindowToken(token);
            if (appWindow != null) {
                appWindow.startRelaunching();
            }
        }
    }

    public void notifyAppRelaunchingFinished(IBinder token) {
        synchronized (mGlobalLock) {
            final AppWindowToken appWindow = mRoot.getAppWindowToken(token);
            if (appWindow != null) {
                appWindow.finishRelaunching();
            }
        }
    }

    public void notifyAppRelaunchesCleared(IBinder token) {
        synchronized (mGlobalLock) {
            final AppWindowToken appWindow = mRoot.getAppWindowToken(token);
            if (appWindow != null) {
                appWindow.clearRelaunching();
            }
        }
    }

    public void notifyAppResumedFinished(IBinder token) {
        synchronized (mGlobalLock) {
            final AppWindowToken appWindow = mRoot.getAppWindowToken(token);
            if (appWindow != null) {
                appWindow.getDisplayContent().mUnknownAppVisibilityController
                        .notifyAppResumedFinished(appWindow);
            }
        }
    }

    /**
     * Called when a task has been removed from the recent tasks list.
     * <p>
     * Note: This doesn't go through {@link TaskWindowContainerController} yet as the window
     * container may not exist when this happens.
     */
    public void notifyTaskRemovedFromRecents(int taskId, int userId) {
        synchronized (mGlobalLock) {
            mTaskSnapshotController.notifyTaskRemovedFromRecents(taskId, userId);
        }
    }

    private void dumpPolicyLocked(PrintWriter pw, String[] args, boolean dumpAll) {
        pw.println("WINDOW MANAGER POLICY STATE (dumpsys window policy)");
        mPolicy.dump("    ", pw, args);
    }

    private void dumpAnimatorLocked(PrintWriter pw, String[] args, boolean dumpAll) {
        pw.println("WINDOW MANAGER ANIMATOR STATE (dumpsys window animator)");
        mAnimator.dumpLocked(pw, "    ", dumpAll);
    }

    private void dumpTokensLocked(PrintWriter pw, boolean dumpAll) {
        pw.println("WINDOW MANAGER TOKENS (dumpsys window tokens)");
        mRoot.dumpTokens(pw, dumpAll);
    }

    private void dumpTraceStatus(PrintWriter pw) {
        pw.println("WINDOW MANAGER TRACE (dumpsys window trace)");
        pw.print(mWindowTracing.getStatus() + "\n");
    }

    private void dumpSessionsLocked(PrintWriter pw, boolean dumpAll) {
        pw.println("WINDOW MANAGER SESSIONS (dumpsys window sessions)");
        for (int i=0; i<mSessions.size(); i++) {
            Session s = mSessions.valueAt(i);
            pw.print("  Session "); pw.print(s); pw.println(':');
            s.dump(pw, "    ");
        }
    }

    /**
     * Write to a protocol buffer output stream. Protocol buffer message definition is at
     * {@link com.android.server.wm.WindowManagerServiceDumpProto}.
     *
     * @param proto     Stream to write the WindowContainer object to.
     * @param logLevel  Determines the amount of data to be written to the Protobuf.
     */
    void writeToProtoLocked(ProtoOutputStream proto, @WindowTraceLogLevel int logLevel) {
        mPolicy.writeToProto(proto, POLICY);
        mRoot.writeToProto(proto, ROOT_WINDOW_CONTAINER, logLevel);
        final DisplayContent topFocusedDisplayContent = mRoot.getTopFocusedDisplayContent();
        if (topFocusedDisplayContent.mCurrentFocus != null) {
            topFocusedDisplayContent.mCurrentFocus.writeIdentifierToProto(proto, FOCUSED_WINDOW);
        }
        if (topFocusedDisplayContent.mFocusedApp != null) {
            topFocusedDisplayContent.mFocusedApp.writeNameToProto(proto, FOCUSED_APP);
        }
        final WindowState imeWindow = mRoot.getCurrentInputMethodWindow();
        if (imeWindow != null) {
            imeWindow.writeIdentifierToProto(proto, INPUT_METHOD_WINDOW);
        }
        proto.write(DISPLAY_FROZEN, mDisplayFrozen);
        final DisplayContent defaultDisplayContent = getDefaultDisplayContentLocked();
        proto.write(ROTATION, defaultDisplayContent.getRotation());
        proto.write(LAST_ORIENTATION, defaultDisplayContent.getLastOrientation());
    }

    private void dumpWindowsLocked(PrintWriter pw, boolean dumpAll,
            ArrayList<WindowState> windows) {
        pw.println("WINDOW MANAGER WINDOWS (dumpsys window windows)");
        dumpWindowsNoHeaderLocked(pw, dumpAll, windows);
    }

    private void dumpWindowsNoHeaderLocked(PrintWriter pw, boolean dumpAll,
            ArrayList<WindowState> windows) {
        mRoot.dumpWindowsNoHeader(pw, dumpAll, windows);

        if (!mHidingNonSystemOverlayWindows.isEmpty()) {
            pw.println();
            pw.println("  Hiding System Alert Windows:");
            for (int i = mHidingNonSystemOverlayWindows.size() - 1; i >= 0; i--) {
                final WindowState w = mHidingNonSystemOverlayWindows.get(i);
                pw.print("  #"); pw.print(i); pw.print(' ');
                pw.print(w);
                if (dumpAll) {
                    pw.println(":");
                    w.dump(pw, "    ", true);
                } else {
                    pw.println();
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
        pw.print("  mGlobalConfiguration="); pw.println(mRoot.getConfiguration());
        pw.print("  mHasPermanentDpad="); pw.println(mHasPermanentDpad);
        mRoot.dumpTopFocusedDisplayId(pw);
        mRoot.forAllDisplays(dc -> {
            final WindowState inputMethodTarget = dc.mInputMethodTarget;
            if (inputMethodTarget != null) {
                pw.print("  mInputMethodTarget in display# "); pw.print(dc.getDisplayId());
                pw.print(' '); pw.println(inputMethodTarget);
            }
        });
        pw.print("  mInTouchMode="); pw.println(mInTouchMode);
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

        mInputManagerCallback.dump(pw, "  ");
        mTaskSnapshotController.dump(pw, "  ");

        if (dumpAll) {
            final WindowState imeWindow = mRoot.getCurrentInputMethodWindow();
            if (imeWindow != null) {
                pw.print("  mInputMethodWindow="); pw.println(imeWindow);
            }
            mWindowPlacerLocked.dump(pw, "  ");
            pw.print("  mSystemBooted="); pw.print(mSystemBooted);
                    pw.print(" mDisplayEnabled="); pw.println(mDisplayEnabled);

            mRoot.dumpLayoutNeededDisplayIds(pw);

            pw.print("  mTransactionSequence="); pw.println(mTransactionSequence);
            pw.print("  mDisplayFrozen="); pw.print(mDisplayFrozen);
                    pw.print(" windows="); pw.print(mWindowsFreezingScreen);
                    pw.print(" client="); pw.print(mClientFreezingScreen);
                    pw.print(" apps="); pw.print(mAppsFreezingScreen);
            final DisplayContent defaultDisplayContent = getDefaultDisplayContentLocked();
            pw.print("  mRotation="); pw.print(defaultDisplayContent.getRotation());
            pw.print("  mLastWindowForcedOrientation=");
                    pw.print(defaultDisplayContent.getLastWindowForcedOrientation());
                    pw.print(" mLastOrientation=");
                            pw.println(defaultDisplayContent.getLastOrientation());
                    pw.print(" waitingForConfig=");
                            pw.println(defaultDisplayContent.mWaitingForConfig);

            pw.print("  Animation settings: disabled="); pw.print(mAnimationsDisabled);
                    pw.print(" window="); pw.print(mWindowAnimationScaleSetting);
                    pw.print(" transition="); pw.print(mTransitionAnimationScaleSetting);
                    pw.print(" animator="); pw.println(mAnimatorDurationScaleSetting);
            if (mRecentsAnimationController != null) {
                pw.print("  mRecentsAnimationController="); pw.println(mRecentsAnimationController);
                mRecentsAnimationController.dump(pw, "    ");
            }
            PolicyControl.dump("  ", pw);
        }
    }

    private boolean dumpWindows(PrintWriter pw, String name, String[] args, int opti,
            boolean dumpAll) {
        final ArrayList<WindowState> windows = new ArrayList();
        if ("apps".equals(name) || "visible".equals(name) || "visible-apps".equals(name)) {
            final boolean appsOnly = name.contains("apps");
            final boolean visibleOnly = name.contains("visible");
            synchronized (mGlobalLock) {
                if (appsOnly) {
                    mRoot.dumpDisplayContents(pw);
                }

                mRoot.forAllWindows((w) -> {
                    if ((!visibleOnly || w.mWinAnimator.getShown())
                            && (!appsOnly || w.mAppToken != null)) {
                        windows.add(w);
                    }
                }, true /* traverseTopToBottom */);
            }
        } else {
            synchronized (mGlobalLock) {
                mRoot.getWindowsByName(windows, name);
            }
        }

        if (windows.size() <= 0) {
            return false;
        }

        synchronized (mGlobalLock) {
            dumpWindowsLocked(pw, dumpAll, windows);
        }
        return true;
    }

    private void dumpLastANRLocked(PrintWriter pw) {
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
    void saveANRStateLocked(AppWindowToken appWindowToken, WindowState windowState, String reason) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new FastPrintWriter(sw, false, 1024);
        pw.println("  ANR time: " + DateFormat.getDateTimeInstance().format(new Date()));
        if (appWindowToken != null) {
            pw.println("  Application at fault: " + appWindowToken.stringName);
        }
        if (windowState != null) {
            pw.println("  Window at fault: " + windowState.mAttrs.getTitle());
        }
        if (reason != null) {
            pw.println("  Reason: " + reason);
        }
        for (int i = mRoot.getChildCount() - 1; i >= 0; i--) {
            final DisplayContent dc = mRoot.getChildAt(i);
            final int displayId = dc.getDisplayId();
            if (!dc.mWinAddedSinceNullFocus.isEmpty()) {
                pw.println("  Windows added in display #" + displayId + " since null focus: "
                        + dc.mWinAddedSinceNullFocus);
            }
            if (!dc.mWinRemovedSinceNullFocus.isEmpty()) {
                pw.println("  Windows removed in display #" + displayId + " since null focus: "
                        + dc.mWinRemovedSinceNullFocus);
            }
        }
        pw.println();
        dumpWindowsNoHeaderLocked(pw, true, null);
        pw.println();
        pw.println("Last ANR continued");
        mRoot.dumpDisplayContents(pw);
        pw.close();
        mLastANRState = sw.toString();

        mH.removeMessages(H.RESET_ANR_MESSAGE);
        mH.sendEmptyMessageDelayed(H.RESET_ANR_MESSAGE, LAST_ANR_LIFETIME_DURATION_MSECS);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        PriorityDump.dump(mPriorityDumper, fd, pw, args);
    }

    private void doDump(FileDescriptor fd, PrintWriter pw, String[] args, boolean useProto) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
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
                pw.println("    trace: print trace status and write Winscope trace to file");
                pw.println("  cmd may also be a NAME to dump windows.  NAME may");
                pw.println("    be a partial substring in a window name, a");
                pw.println("    Window hex object identifier, or");
                pw.println("    \"all\" for all windows, or");
                pw.println("    \"visible\" for the visible windows.");
                pw.println("    \"visible-apps\" for the visible app windows.");
                pw.println("  -a: include all available server state.");
                pw.println("  --proto: output dump in protocol buffer format.");
                return;
            } else {
                pw.println("Unknown argument: " + opt + "; use -h for help");
            }
        }

        if (useProto) {
            final ProtoOutputStream proto = new ProtoOutputStream(fd);
            synchronized (mGlobalLock) {
                writeToProtoLocked(proto, WindowTraceLogLevel.ALL);
            }
            proto.flush();
            return;
        }
        // Is the caller requesting to dump a particular piece of data?
        if (opti < args.length) {
            String cmd = args[opti];
            opti++;
            if ("lastanr".equals(cmd) || "l".equals(cmd)) {
                synchronized (mGlobalLock) {
                    dumpLastANRLocked(pw);
                }
                return;
            } else if ("policy".equals(cmd) || "p".equals(cmd)) {
                synchronized (mGlobalLock) {
                    dumpPolicyLocked(pw, args, true);
                }
                return;
            } else if ("animator".equals(cmd) || "a".equals(cmd)) {
                synchronized (mGlobalLock) {
                    dumpAnimatorLocked(pw, args, true);
                }
                return;
            } else if ("sessions".equals(cmd) || "s".equals(cmd)) {
                synchronized (mGlobalLock) {
                    dumpSessionsLocked(pw, true);
                }
                return;
            } else if ("displays".equals(cmd) || "d".equals(cmd)) {
                synchronized (mGlobalLock) {
                    mRoot.dumpDisplayContents(pw);
                }
                return;
            } else if ("tokens".equals(cmd) || "t".equals(cmd)) {
                synchronized (mGlobalLock) {
                    dumpTokensLocked(pw, true);
                }
                return;
            } else if ("windows".equals(cmd) || "w".equals(cmd)) {
                synchronized (mGlobalLock) {
                    dumpWindowsLocked(pw, true, null);
                }
                return;
            } else if ("all".equals(cmd)) {
                synchronized (mGlobalLock) {
                    dumpWindowsLocked(pw, true, null);
                }
                return;
            } else if ("containers".equals(cmd)) {
                synchronized (mGlobalLock) {
                    mRoot.dumpChildrenNames(pw, " ");
                    pw.println(" ");
                    mRoot.forAllWindows(w -> {pw.println(w);}, true /* traverseTopToBottom */);
                }
                return;
            } else if ("trace".equals(cmd)) {
                dumpTraceStatus(pw);
                synchronized (mGlobalLock) {
                    mWindowTracing.writeTraceToFile();
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

        synchronized (mGlobalLock) {
            pw.println();
            final String separator = "---------------------------------------------------------"
                    + "----------------------";
            if (dumpAll) {
                pw.println(separator);
            }
            dumpLastANRLocked(pw);
            pw.println();
            if (dumpAll) {
                pw.println(separator);
            }
            dumpPolicyLocked(pw, args, dumpAll);
            pw.println();
            if (dumpAll) {
                pw.println(separator);
            }
            dumpAnimatorLocked(pw, args, dumpAll);
            pw.println();
            if (dumpAll) {
                pw.println(separator);
            }
            dumpSessionsLocked(pw, dumpAll);
            pw.println();
            if (dumpAll) {
                pw.println(separator);
            }
            if (dumpAll) {
                pw.println(separator);
            }
            mRoot.dumpDisplayContents(pw);
            pw.println();
            if (dumpAll) {
                pw.println(separator);
            }
            dumpTokensLocked(pw, dumpAll);
            pw.println();
            if (dumpAll) {
                pw.println(separator);
            }
            dumpWindowsLocked(pw, dumpAll, null);
            if (dumpAll) {
                pw.println(separator);
            }
            dumpTraceStatus(pw);
        }
    }

    // Called by the heartbeat to ensure locks are not held indefnitely (for deadlock detection).
    @Override
    public void monitor() {
        synchronized (mGlobalLock) { }
    }

    // There is an inherent assumption that this will never return null.
    // TODO(multi-display): Inspect all the call-points of this method to see if they make sense to
    // support non-default display.
    DisplayContent getDefaultDisplayContentLocked() {
        return mRoot.getDisplayContent(DEFAULT_DISPLAY);
    }

    public void onOverlayChanged() {
        synchronized (mGlobalLock) {
            mRoot.forAllDisplays(displayContent -> {
                displayContent.getDisplayPolicy().onOverlayChangedLw();
                displayContent.updateDisplayInfo();
            });
            requestTraversal();
        }
    }

    public void onDisplayChanged(int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent != null) {
                displayContent.updateDisplayInfo();
            }
            mWindowPlacerLocked.requestTraversal();
        }
    }

    @Override
    public Object getWindowManagerLock() {
        return mGlobalLock;
    }

    /**
     * Hint to a token that its activity will relaunch, which will trigger removal and addition of
     * a window.
     * @param token Application token for which the activity will be relaunched.
     */
    public void setWillReplaceWindow(IBinder token, boolean animate) {
        synchronized (mGlobalLock) {
            final AppWindowToken appWindowToken = mRoot.getAppWindowToken(token);
            if (appWindowToken == null) {
                Slog.w(TAG_WM, "Attempted to set replacing window on non-existing app token "
                        + token);
                return;
            }
            if (!appWindowToken.hasContentToDisplay()) {
                Slog.w(TAG_WM, "Attempted to set replacing window on app token with no content"
                        + token);
                return;
            }
            appWindowToken.setWillReplaceWindows(animate);
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
    // TODO: The s at the end of the method name is the only difference with the name of the method
    // above. We should combine them or find better names.
    void setWillReplaceWindows(IBinder token, boolean childrenOnly) {
        synchronized (mGlobalLock) {
            final AppWindowToken appWindowToken = mRoot.getAppWindowToken(token);
            if (appWindowToken == null) {
                Slog.w(TAG_WM, "Attempted to set replacing window on non-existing app token "
                        + token);
                return;
            }
            if (!appWindowToken.hasContentToDisplay()) {
                Slog.w(TAG_WM, "Attempted to set replacing window on app token with no content"
                        + token);
                return;
            }

            if (childrenOnly) {
                appWindowToken.setWillReplaceChildWindows();
            } else {
                appWindowToken.setWillReplaceWindows(false /* animate */);
            }

            scheduleClearWillReplaceWindows(token, true /* replacing */);
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
    public void scheduleClearWillReplaceWindows(IBinder token, boolean replacing) {
        synchronized (mGlobalLock) {
            final AppWindowToken appWindowToken = mRoot.getAppWindowToken(token);
            if (appWindowToken == null) {
                Slog.w(TAG_WM, "Attempted to reset replacing window on non-existing app token "
                        + token);
                return;
            }
            if (replacing) {
                scheduleWindowReplacementTimeouts(appWindowToken);
            } else {
                appWindowToken.clearWillReplaceWindows();
            }
        }
    }

    void scheduleWindowReplacementTimeouts(AppWindowToken appWindowToken) {
        if (!mWindowReplacementTimeouts.contains(appWindowToken)) {
            mWindowReplacementTimeouts.add(appWindowToken);
        }
        mH.removeMessages(H.WINDOW_REPLACEMENT_TIMEOUT);
        mH.sendEmptyMessageDelayed(
                H.WINDOW_REPLACEMENT_TIMEOUT, WINDOW_REPLACEMENT_TIMEOUT_DURATION);
    }

    @Override
    public int getDockedStackSide() {
        synchronized (mGlobalLock) {
            final TaskStack dockedStack = getDefaultDisplayContentLocked()
                    .getSplitScreenPrimaryStackIgnoringVisibility();
            return dockedStack == null ? DOCKED_INVALID : dockedStack.getDockSide();
        }
    }

    public void setDockedStackResizing(boolean resizing) {
        synchronized (mGlobalLock) {
            getDefaultDisplayContentLocked().getDockedDividerController().setResizing(resizing);
            requestTraversal();
        }
    }

    @Override
    public void setDockedStackDividerTouchRegion(Rect touchRegion) {
        synchronized (mGlobalLock) {
            final DisplayContent dc = getDefaultDisplayContentLocked();
            dc.getDockedDividerController().setTouchRegion(touchRegion);
            dc.updateTouchExcludeRegion();
        }
    }

    @Override
    public void setResizeDimLayer(boolean visible, int targetWindowingMode, float alpha) {
        synchronized (mGlobalLock) {
            getDefaultDisplayContentLocked().getDockedDividerController().setResizeDimLayer(
                    visible, targetWindowingMode, alpha);
        }
    }

    public void setForceResizableTasks(boolean forceResizableTasks) {
        synchronized (mGlobalLock) {
            mForceResizableTasks = forceResizableTasks;
        }
    }

    public void setSupportsPictureInPicture(boolean supportsPictureInPicture) {
        synchronized (mGlobalLock) {
            mSupportsPictureInPicture = supportsPictureInPicture;
        }
    }

    public void setSupportsFreeformWindowManagement(boolean supportsFreeformWindowManagement) {
        synchronized (mGlobalLock) {
            mSupportsFreeformWindowManagement = supportsFreeformWindowManagement;
        }
    }

    void setForceDesktopModeOnExternalDisplays(boolean forceDesktopModeOnExternalDisplays) {
        synchronized (mGlobalLock) {
            mForceDesktopModeOnExternalDisplays = forceDesktopModeOnExternalDisplays;
        }
    }

    public void setIsPc(boolean isPc) {
        synchronized (mGlobalLock) {
            mIsPc = isPc;
        }
    }

    static int dipToPixel(int dip, DisplayMetrics displayMetrics) {
        return (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip, displayMetrics);
    }

    @Override
    public void registerDockedStackListener(IDockedStackListener listener) {
        mAtmInternal.enforceCallerIsRecentsOrHasPermission(REGISTER_WINDOW_MANAGER_LISTENERS,
                "registerDockedStackListener()");
        synchronized (mGlobalLock) {
            // TODO(multi-display): The listener is registered on the default display only.
            getDefaultDisplayContentLocked().mDividerControllerLocked.registerDockedStackListener(
                    listener);
        }
    }

    @Override
    public void registerPinnedStackListener(int displayId, IPinnedStackListener listener) {
        if (!checkCallingPermission(REGISTER_WINDOW_MANAGER_LISTENERS,
                "registerPinnedStackListener()")) {
            return;
        }
        if (!mSupportsPictureInPicture) {
            return;
        }
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            displayContent.getPinnedStackController().registerPinnedStackListener(listener);
        }
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
    public void getStableInsets(int displayId, Rect outInsets) throws RemoteException {
        synchronized (mGlobalLock) {
            getStableInsetsLocked(displayId, outInsets);
        }
    }

    void getStableInsetsLocked(int displayId, Rect outInsets) {
        outInsets.setEmpty();
        final DisplayContent dc = mRoot.getDisplayContent(displayId);
        if (dc != null) {
            final DisplayInfo di = dc.getDisplayInfo();
            dc.getDisplayPolicy().getStableInsetsLw(di.rotation, di.logicalWidth, di.logicalHeight,
                    di.displayCutout, outInsets);
        }
    }

    @Override
    public void setForwardedInsets(int displayId, Insets insets) throws RemoteException {
        synchronized (mGlobalLock) {
            final DisplayContent dc = mRoot.getDisplayContent(displayId);
            if (dc == null) {
                return;
            }
            final int callingUid = Binder.getCallingUid();
            final int displayOwnerUid = dc.getDisplay().getOwnerUid();
            if (callingUid != displayOwnerUid) {
                throw new SecurityException(
                        "Only owner of the display can set ForwardedInsets to it.");
            }
            dc.setForwardedInsets(insets);
        }
    }

    void intersectDisplayInsetBounds(Rect display, Rect insets, Rect inOutBounds) {
        mTmpRect3.set(display);
        mTmpRect3.inset(insets);
        inOutBounds.intersect(mTmpRect3);
    }

    MousePositionTracker mMousePositionTracker = new MousePositionTracker();

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

        synchronized (mGlobalLock) {
            if (mDragDropController.dragDropActiveLocked()) {
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

    /**
     * Update a tap exclude region with a rectangular area in the window identified by the provided
     * id. Touches down on this region will not:
     * <ol>
     * <li>Switch focus to this window.</li>
     * <li>Move the display of this window to top.</li>
     * <li>Send the touch events to this window.</li>
     * </ol>
     * Passing an empty rect will remove the area from the exclude region of this window.
     */
    void updateTapExcludeRegion(IWindow client, int regionId, int left, int top, int width,
            int height) {
        synchronized (mGlobalLock) {
            final WindowState callingWin = windowForClientLocked(null, client, false);
            if (callingWin == null) {
                Slog.w(TAG_WM, "Bad requesting window " + client);
                return;
            }
            callingWin.updateTapExcludeRegion(regionId, left, top, width, height);
        }
    }

    @Override
    public void dontOverrideDisplayInfo(int displayId) {
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent dc = getDisplayContentOrCreate(displayId, null /* token */);
                if (dc == null) {
                    throw new IllegalArgumentException(
                            "Trying to configure a non existent display.");
                }
                // We usually set the override info in DisplayManager so that we get consistent
                // values when displays are changing. However, we don't do this for displays that
                // serve as containers for ActivityViews because we don't want letter-/pillar-boxing
                // during resize.
                dc.mShouldOverrideDisplayConfiguration = false;
                mDisplayManagerInternal.setDisplayInfoOverrideFromWindowManager(displayId,
                        null /* info */);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public int getWindowingMode(int displayId) {
        if (!checkCallingPermission(INTERNAL_SYSTEM_WINDOW, "getWindowingMode()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }

        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent == null) {
                Slog.w(TAG_WM, "Attempted to get windowing mode of a display that does not exist: "
                        + displayId);
                return WindowConfiguration.WINDOWING_MODE_UNDEFINED;
            }
            return mDisplayWindowSettings.getWindowingModeLocked(displayContent);
        }
    }

    @Override
    public void setWindowingMode(int displayId, int mode) {
        if (!checkCallingPermission(INTERNAL_SYSTEM_WINDOW, "setWindowingMode()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }

        synchronized (mGlobalLock) {
            final DisplayContent displayContent = getDisplayContentOrCreate(displayId, null);
            if (displayContent == null) {
                Slog.w(TAG_WM, "Attempted to set windowing mode to a display that does not exist: "
                        + displayId);
                return;
            }

            mDisplayWindowSettings.setWindowingModeLocked(displayContent, mode);

            reconfigureDisplayLocked(displayContent);
        }
    }

    @Override
    public @RemoveContentMode int getRemoveContentMode(int displayId) {
        if (!checkCallingPermission(INTERNAL_SYSTEM_WINDOW, "getRemoveContentMode()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }

        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent == null) {
                Slog.w(TAG_WM, "Attempted to get remove mode of a display that does not exist: "
                        + displayId);
                return REMOVE_CONTENT_MODE_UNDEFINED;
            }
            return mDisplayWindowSettings.getRemoveContentModeLocked(displayContent);
        }
    }

    @Override
    public void setRemoveContentMode(int displayId, @RemoveContentMode int mode) {
        if (!checkCallingPermission(INTERNAL_SYSTEM_WINDOW, "setRemoveContentMode()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }

        synchronized (mGlobalLock) {
            final DisplayContent displayContent = getDisplayContentOrCreate(displayId, null);
            if (displayContent == null) {
                Slog.w(TAG_WM, "Attempted to set remove mode to a display that does not exist: "
                        + displayId);
                return;
            }

            mDisplayWindowSettings.setRemoveContentModeLocked(displayContent, mode);

            reconfigureDisplayLocked(displayContent);
        }
    }

    @Override
    public boolean shouldShowWithInsecureKeyguard(int displayId) {
        if (!checkCallingPermission(INTERNAL_SYSTEM_WINDOW, "shouldShowWithInsecureKeyguard()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }

        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent == null) {
                Slog.w(TAG_WM, "Attempted to get flag of a display that does not exist: "
                        + displayId);
                return false;
            }
            return mDisplayWindowSettings.shouldShowWithInsecureKeyguardLocked(displayContent);
        }
    }

    @Override
    public void setShouldShowWithInsecureKeyguard(int displayId, boolean shouldShow) {
        if (!checkCallingPermission(INTERNAL_SYSTEM_WINDOW,
                "setShouldShowWithInsecureKeyguard()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }

        synchronized (mGlobalLock) {
            final DisplayContent displayContent = getDisplayContentOrCreate(displayId, null);
            if (displayContent == null) {
                Slog.w(TAG_WM, "Attempted to set flag to a display that does not exist: "
                        + displayId);
                return;
            }

            mDisplayWindowSettings.setShouldShowWithInsecureKeyguardLocked(displayContent,
                    shouldShow);

            reconfigureDisplayLocked(displayContent);
        }
    }

    @Override
    public boolean shouldShowSystemDecors(int displayId) {
        if (!checkCallingPermission(INTERNAL_SYSTEM_WINDOW, "shouldShowSystemDecors()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }

        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent == null) {
                Slog.w(TAG_WM, "Attempted to get system decors flag of a display that does "
                        + "not exist: " + displayId);
                return false;
            }
            return displayContent.supportsSystemDecorations();
        }
    }

    @Override
    public void setShouldShowSystemDecors(int displayId, boolean shouldShow) {
        if (!checkCallingPermission(INTERNAL_SYSTEM_WINDOW, "setShouldShowSystemDecors()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }

        synchronized (mGlobalLock) {
            final DisplayContent displayContent = getDisplayContentOrCreate(displayId, null);
            if (displayContent == null) {
                Slog.w(TAG_WM, "Attempted to set system decors flag to a display that does "
                        + "not exist: " + displayId);
                return;
            }

            mDisplayWindowSettings.setShouldShowSystemDecorsLocked(displayContent, shouldShow);

            reconfigureDisplayLocked(displayContent);
        }
    }

    @Override
    public boolean shouldShowIme(int displayId) {
        if (!checkCallingPermission(INTERNAL_SYSTEM_WINDOW, "shouldShowIme()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }

        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent == null) {
                Slog.w(TAG_WM, "Attempted to get IME flag of a display that does not exist: "
                        + displayId);
                return false;
            }
            return mDisplayWindowSettings.shouldShowImeLocked(displayContent);
        }
    }

    @Override
    public void setShouldShowIme(int displayId, boolean shouldShow) {
        if (!checkCallingPermission(INTERNAL_SYSTEM_WINDOW, "setShouldShowIme()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }

        synchronized (mGlobalLock) {
            final DisplayContent displayContent = getDisplayContentOrCreate(displayId, null);
            if (displayContent == null) {
                Slog.w(TAG_WM, "Attempted to set IME flag to a display that does not exist: "
                        + displayId);
                return;
            }

            mDisplayWindowSettings.setShouldShowImeLocked(displayContent, shouldShow);

            reconfigureDisplayLocked(displayContent);
        }
    }

    @Override
    public void registerShortcutKey(long shortcutCode, IShortcutService shortcutKeyReceiver)
            throws RemoteException {
        if (!checkCallingPermission(REGISTER_WINDOW_MANAGER_LISTENERS, "registerShortcutKey")) {
            throw new SecurityException(
                    "Requires REGISTER_WINDOW_MANAGER_LISTENERS permission");
        }
        mPolicy.registerShortcutKey(shortcutCode, shortcutKeyReceiver);
    }

    @Override
    public void requestUserActivityNotification() {
        if (!checkCallingPermission(android.Manifest.permission.USER_ACTIVITY,
                "requestUserActivityNotification()")) {
            throw new SecurityException("Requires USER_ACTIVITY permission");
        }
        mPolicy.requestUserActivityNotification();
    }

    void markForSeamlessRotation(WindowState w, boolean seamlesslyRotated) {
        if (seamlesslyRotated == w.mSeamlesslyRotated || w.mForceSeamlesslyRotate) {
            return;
        }
        w.mSeamlesslyRotated = seamlesslyRotated;
        if (seamlesslyRotated) {
            mSeamlessRotationCount++;
        } else {
            mSeamlessRotationCount--;
        }
        if (mSeamlessRotationCount == 0) {
            if (DEBUG_ORIENTATION) {
                Slog.i(TAG, "Performing post-rotate rotation after seamless rotation");
            }
            finishSeamlessRotation();

            w.getDisplayContent().updateRotationAndSendNewConfigIfNeeded();
        }
    }

    private final class LocalService extends WindowManagerInternal {
        @Override
        public void requestTraversalFromDisplayManager() {
            requestTraversal();
        }

        @Override
        public void setMagnificationSpec(int displayId, MagnificationSpec spec) {
            synchronized (mGlobalLock) {
                if (mAccessibilityController != null) {
                    mAccessibilityController.setMagnificationSpecLocked(displayId, spec);
                } else {
                    throw new IllegalStateException("Magnification callbacks not set!");
                }
            }
            if (Binder.getCallingPid() != myPid()) {
                spec.recycle();
            }
        }

        @Override
        public void setForceShowMagnifiableBounds(int displayId, boolean show) {
            synchronized (mGlobalLock) {
                if (mAccessibilityController != null) {
                    mAccessibilityController.setForceShowMagnifiableBoundsLocked(displayId, show);
                } else {
                    throw new IllegalStateException("Magnification callbacks not set!");
                }
            }
        }

        @Override
        public void getMagnificationRegion(int displayId, @NonNull Region magnificationRegion) {
            synchronized (mGlobalLock) {
                if (mAccessibilityController != null) {
                    mAccessibilityController.getMagnificationRegionLocked(displayId,
                            magnificationRegion);
                } else {
                    throw new IllegalStateException("Magnification callbacks not set!");
                }
            }
        }

        @Override
        public MagnificationSpec getCompatibleMagnificationSpecForWindow(IBinder windowToken) {
            synchronized (mGlobalLock) {
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
        public boolean setMagnificationCallbacks(int displayId,
                @Nullable MagnificationCallbacks callbacks) {
            synchronized (mGlobalLock) {
                if (mAccessibilityController == null) {
                    mAccessibilityController = new AccessibilityController(
                            WindowManagerService.this);
                }
                boolean result = mAccessibilityController.setMagnificationCallbacksLocked(
                        displayId, callbacks);
                if (!mAccessibilityController.hasCallbacksLocked()) {
                    mAccessibilityController = null;
                }
                return result;
            }
        }

        @Override
        public void setWindowsForAccessibilityCallback(WindowsForAccessibilityCallback callback) {
            synchronized (mGlobalLock) {
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
            synchronized (mGlobalLock) {
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
        public boolean isKeyguardShowingAndNotOccluded() {
            return WindowManagerService.this.isKeyguardShowingAndNotOccluded();
        }

        @Override
        public void showGlobalActions() {
            WindowManagerService.this.showGlobalActions();
        }

        @Override
        public void getWindowFrame(IBinder token, Rect outBounds) {
            synchronized (mGlobalLock) {
                WindowState windowState = mWindowMap.get(token);
                if (windowState != null) {
                    outBounds.set(windowState.getFrameLw());
                } else {
                    outBounds.setEmpty();
                }
            }
        }

        @Override
        public void waitForAllWindowsDrawn(Runnable callback, long timeout) {
            boolean allWindowsDrawn = false;
            synchronized (mGlobalLock) {
                mWaitingForDrawnCallback = callback;
                getDefaultDisplayContentLocked().waitForAllWindowsDrawn();
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
        public void setForcedDisplaySize(int displayId, int width, int height) {
            WindowManagerService.this.setForcedDisplaySize(displayId, width, height);
        }

        @Override
        public void clearForcedDisplaySize(int displayId) {
            WindowManagerService.this.clearForcedDisplaySize(displayId);
        }

        @Override
        public void addWindowToken(IBinder token, int type, int displayId) {
            WindowManagerService.this.addWindowToken(token, type, displayId);
        }

        @Override
        public void removeWindowToken(IBinder binder, boolean removeWindows, int displayId) {
            synchronized (mGlobalLock) {
                if (removeWindows) {
                    final DisplayContent dc = mRoot.getDisplayContent(displayId);
                    if (dc == null) {
                        Slog.w(TAG_WM, "removeWindowToken: Attempted to remove token: " + binder
                                + " for non-exiting displayId=" + displayId);
                        return;
                    }

                    final WindowToken token = dc.removeWindowToken(binder);
                    if (token == null) {
                        Slog.w(TAG_WM, "removeWindowToken: Attempted to remove non-existing token: "
                                + binder);
                        return;
                    }

                    token.removeAllWindowsIfPossible();
                }
                WindowManagerService.this.removeWindowToken(binder, displayId);
            }
        }

        // TODO(multi-display): currently only used by PWM to notify keyguard transitions as well
        // forwarding it to SystemUI for synchronizing status and navigation bar animations.
        @Override
        public void registerAppTransitionListener(AppTransitionListener listener) {
            synchronized (mGlobalLock) {
                getDefaultDisplayContentLocked().mAppTransition.registerListenerLocked(listener);
            }
        }

        @Override
        public void reportPasswordChanged(int userId) {
            mKeyguardDisableHandler.updateKeyguardEnabled(userId);
        }

        @Override
        public int getInputMethodWindowVisibleHeight(int displayId) {
            synchronized (mGlobalLock) {
                final DisplayContent dc = mRoot.getDisplayContent(displayId);
                return dc.mDisplayFrames.getInputMethodWindowVisibleHeight();
            }
        }

        @Override
        public void updateInputMethodWindowStatus(@NonNull IBinder imeToken,
                boolean imeWindowVisible, boolean dismissImeOnBackKeyPressed) {
            mPolicy.setDismissImeOnBackKeyPressed(dismissImeOnBackKeyPressed);
        }

        @Override
        public void updateInputMethodTargetWindow(@NonNull IBinder imeToken,
                @NonNull IBinder imeTargetWindowToken) {
            // TODO (b/34628091): Use this method to address the window animation issue.
            if (DEBUG_INPUT_METHOD) {
                Slog.w(TAG_WM, "updateInputMethodTargetWindow: imeToken=" + imeToken
                        + " imeTargetWindowToken=" + imeTargetWindowToken);
            }
        }

        @Override
        public boolean isHardKeyboardAvailable() {
            synchronized (mGlobalLock) {
                return mHardKeyboardAvailable;
            }
        }

        @Override
        public void setOnHardKeyboardStatusChangeListener(
                OnHardKeyboardStatusChangeListener listener) {
            synchronized (mGlobalLock) {
                mHardKeyboardStatusChangeListener = listener;
            }
        }

        @Override
        public boolean isStackVisible(int windowingMode) {
            synchronized (mGlobalLock) {
                final DisplayContent dc = getDefaultDisplayContentLocked();
                return dc.isStackVisible(windowingMode);
            }
        }

        @Override
        public void computeWindowsForAccessibility() {
            final AccessibilityController accessibilityController;
            synchronized (mGlobalLock) {
                accessibilityController = mAccessibilityController;
            }
            if (accessibilityController != null) {
                accessibilityController.performComputeChangedWindowsNotLocked(true);
            }
        }

        @Override
        public void setVr2dDisplayId(int vr2dDisplayId) {
            if (DEBUG_DISPLAY) {
                Slog.d(TAG, "setVr2dDisplayId called for: " + vr2dDisplayId);
            }
            synchronized (mGlobalLock) {
                mVr2dDisplayId = vr2dDisplayId;
            }
        }

        @Override
        public void registerDragDropControllerCallback(IDragDropCallback callback) {
            mDragDropController.registerCallback(callback);
        }

        @Override
        public void lockNow() {
            WindowManagerService.this.lockNow(null);
        }

        @Override
        public int getWindowOwnerUserId(IBinder token) {
            synchronized (mGlobalLock) {
                WindowState window = mWindowMap.get(token);
                if (window != null) {
                    return UserHandle.getUserId(window.mOwnerUid);
                }
                return UserHandle.USER_NULL;
            }
        }

        @Override
        public boolean isUidFocused(int uid) {
            synchronized (mGlobalLock) {
                for (int i = mRoot.getChildCount() - 1; i >= 0; i--) {
                    final DisplayContent displayContent = mRoot.getChildAt(i);
                    if (displayContent.mCurrentFocus != null
                            && uid == displayContent.mCurrentFocus.getOwningUid()) {
                        return true;
                    }
                }
                return false;
            }
        }

        @Override
        public boolean isInputMethodClientFocus(int uid, int pid, int displayId) {
            if (displayId == Display.INVALID_DISPLAY) {
                return false;
            }
            synchronized (mGlobalLock) {
                final DisplayContent displayContent = mRoot.getTopFocusedDisplayContent();
                if (displayContent == null
                        || displayContent.getDisplayId() != displayId
                        || !displayContent.hasAccess(uid)) {
                    return false;
                }
                if (displayContent.isInputMethodClientFocus(uid, pid)) {
                    return true;
                }
                // Okay, how about this...  what is the current focus?
                // It seems in some cases we may not have moved the IM
                // target window, such as when it was in a pop-up window,
                // so let's also look at the current focus.  (An example:
                // go to Gmail, start searching so the keyboard goes up,
                // press home.  Sometimes the IME won't go down.)
                // Would be nice to fix this more correctly, but it's
                // way at the end of a release, and this should be good enough.
                final WindowState currentFocus = displayContent.mCurrentFocus;
                if (currentFocus != null && currentFocus.mSession.mUid == uid
                        && currentFocus.mSession.mPid == pid) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isUidAllowedOnDisplay(int displayId, int uid) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                return true;
            }
            if (displayId == Display.INVALID_DISPLAY) {
                return false;
            }
            synchronized (mGlobalLock) {
                final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
                return displayContent != null && displayContent.hasAccess(uid);
            }
        }

        @Override
        public int getDisplayIdForWindow(IBinder windowToken) {
            synchronized (mGlobalLock) {
                final WindowState window = mWindowMap.get(windowToken);
                if (window != null) {
                    return window.getDisplayContent().getDisplayId();
                }
                return Display.INVALID_DISPLAY;
            }
        }

        @Override
        public boolean shouldShowSystemDecorOnDisplay(int displayId) {
            synchronized (mGlobalLock) {
                return WindowManagerService.this.shouldShowSystemDecors(displayId);
            }
        }
    }

    void registerAppFreezeListener(AppFreezeListener listener) {
        if (!mAppFreezeListeners.contains(listener)) {
            mAppFreezeListeners.add(listener);
        }
    }

    void unregisterAppFreezeListener(AppFreezeListener listener) {
        mAppFreezeListeners.remove(listener);
    }

    /**
     * WARNING: This interrupts surface updates, be careful! Don't
     * execute within the transaction for longer than you would
     * execute on an animation thread.
     * WARNING: This method contains locks known to the State of California
     * to cause Deadlocks and other conditions.
     *
     * Begins a surface transaction with which the AM can batch operations.
     * All Surface updates performed by the WindowManager following this
     * will not appear on screen until after the call to
     * closeSurfaceTransaction.
     *
     * ActivityManager can use this to ensure multiple 'commands' will all
     * be reflected in a single frame. For example when reparenting a window
     * which was previously hidden due to it's parent properties, we may
     * need to ensure it is hidden in the same frame that the properties
     * from the new parent are inherited, otherwise it could be revealed
     * mistakenly.
     *
     * TODO(b/36393204): We can investigate totally replacing #deferSurfaceLayout
     * with something like this but it seems that some existing cases of
     * deferSurfaceLayout may be a little too broad, in particular the total
     * enclosure of startActivityUnchecked which could run for quite some time.
     */
    void inSurfaceTransaction(Runnable exec) {
        SurfaceControl.openTransaction();
        try {
            exec.run();
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    /** Called to inform window manager if non-Vr UI shoul be disabled or not. */
    public void disableNonVrUi(boolean disable) {
        synchronized (mGlobalLock) {
            // Allow alert window notifications to be shown if non-vr UI is enabled.
            final boolean showAlertWindowNotifications = !disable;
            if (showAlertWindowNotifications == mShowAlertWindowNotifications) {
                return;
            }
            mShowAlertWindowNotifications = showAlertWindowNotifications;

            for (int i = mSessions.size() - 1; i >= 0; --i) {
                final Session s = mSessions.valueAt(i);
                s.setShowingAlertWindowNotificationAllowed(mShowAlertWindowNotifications);
            }
        }
    }

    boolean hasWideColorGamutSupport() {
        return mHasWideColorGamutSupport &&
                SystemProperties.getInt("persist.sys.sf.native_mode", 0) != 1;
    }

    boolean hasHdrSupport() {
        return mHasHdrSupport && hasWideColorGamutSupport();
    }

    void updateNonSystemOverlayWindowsVisibilityIfNeeded(WindowState win, boolean surfaceShown) {
        if (!win.hideNonSystemOverlayWindowsWhenVisible()
                && !mHidingNonSystemOverlayWindows.contains(win)) {
            return;
        }
        final boolean systemAlertWindowsHidden = !mHidingNonSystemOverlayWindows.isEmpty();
        if (surfaceShown) {
            if (!mHidingNonSystemOverlayWindows.contains(win)) {
                mHidingNonSystemOverlayWindows.add(win);
            }
        } else {
            mHidingNonSystemOverlayWindows.remove(win);
        }

        final boolean hideSystemAlertWindows = !mHidingNonSystemOverlayWindows.isEmpty();

        if (systemAlertWindowsHidden == hideSystemAlertWindows) {
            return;
        }

        mRoot.forAllWindows((w) -> {
            w.setForceHideNonSystemOverlayWindowIfNeeded(hideSystemAlertWindows);
        }, false /* traverseTopToBottom */);
    }

    /** Called from Accessibility Controller to apply magnification spec */
    public void applyMagnificationSpecLocked(int displayId, MagnificationSpec spec) {
        final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
        if (displayContent != null) {
            displayContent.applyMagnificationSpec(spec);
        }
    }

    SurfaceControl.Builder makeSurfaceBuilder(SurfaceSession s) {
        return mSurfaceBuilderFactory.make(s);
    }

    void sendSetRunningRemoteAnimation(int pid, boolean runningRemoteAnimation) {
        mH.obtainMessage(H.SET_RUNNING_REMOTE_ANIMATION, pid, runningRemoteAnimation ? 1 : 0)
                .sendToTarget();
    }

    void startSeamlessRotation() {
        // We are careful to reset this in case a window was removed before it finished
        // seamless rotation.
        mSeamlessRotationCount = 0;

        mRotatingSeamlessly = true;
    }

    boolean isRotatingSeamlessly() {
        return mRotatingSeamlessly;
    }

    void finishSeamlessRotation() {
        mRotatingSeamlessly = false;
    }

    /**
     * Called when the state of lock task mode changes. This should be used to disable immersive
     * mode confirmation.
     *
     * @param lockTaskState the new lock task mode state. One of
     *                      {@link ActivityManager#LOCK_TASK_MODE_NONE},
     *                      {@link ActivityManager#LOCK_TASK_MODE_LOCKED},
     *                      {@link ActivityManager#LOCK_TASK_MODE_PINNED}.
     */
    void onLockTaskStateChanged(int lockTaskState) {
        // TODO: pass in displayId to determine which display the lock task state changed
        synchronized (mGlobalLock) {
            mRoot.forAllDisplayPolicies(PooledLambda.obtainConsumer(
                    DisplayPolicy::onLockTaskStateChangedLw, PooledLambda.__(), lockTaskState));
        }
    }

    /**
     * Updates {@link WindowManagerPolicy} with new value about whether AOD  is showing. If AOD
     * has changed, this will trigger a {@link WindowSurfacePlacer#performSurfacePlacement} to
     * ensure the new value takes effect.
     */
    public void setAodShowing(boolean aodShowing) {
        synchronized (mGlobalLock) {
            if (mPolicy.setAodShowing(aodShowing)) {
                mWindowPlacerLocked.performSurfacePlacement();
            }
        }
    }

    @Override
    public void reparentDisplayContent(int displayId, SurfaceControl sc) {
        final Display display = mDisplayManager.getDisplay(displayId);
        if (display == null) {
            throw new IllegalArgumentException(
                    "Can't reparent display for non-existent displayId: " + displayId);
        }

        final int callingUid = Binder.getCallingUid();
        final int displayOwnerUid = display.getOwnerUid();
        if (callingUid != displayOwnerUid) {
            throw new SecurityException("Only owner of the display can reparent surfaces to it.");
        }

        synchronized (mGlobalLock) {
            long token = Binder.clearCallingIdentity();
            try {
                DisplayContent displayContent = getDisplayContentOrCreate(displayId, null);
                displayContent.reparentDisplayContent(sc);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    @Override
    public boolean injectInputAfterTransactionsApplied(InputEvent ev, int mode) {
        waitForAnimationsToComplete();

        synchronized (mGlobalLock) {
            mWindowPlacerLocked.performSurfacePlacementIfScheduled();
        }

        new SurfaceControl.Transaction().syncInputWindows().apply(true);

        return LocalServices.getService(InputManagerInternal.class).injectInputEvent(ev, mode);
    }

    private void waitForAnimationsToComplete() {
        synchronized (mGlobalLock) {
            long timeoutRemaining = ANIMATION_COMPLETED_TIMEOUT_MS;
            while (mRoot.isSelfOrChildAnimating() && timeoutRemaining > 0) {
                long startTime = System.currentTimeMillis();
                try {
                    mGlobalLock.wait(timeoutRemaining);
                } catch (InterruptedException e) {
                }
                timeoutRemaining -= (System.currentTimeMillis() - startTime);
            }

            if (mRoot.isSelfOrChildAnimating()) {
                Log.w(TAG, "Timed out waiting for animations to complete.");
            }
        }
    }

    void onAnimationFinished() {
        synchronized (mGlobalLock) {
            mGlobalLock.notifyAll();
        }
    }
}
