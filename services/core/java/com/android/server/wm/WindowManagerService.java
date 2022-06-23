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
import static android.Manifest.permission.INPUT_CONSUMER;
import static android.Manifest.permission.INTERNAL_SYSTEM_WINDOW;
import static android.Manifest.permission.MANAGE_ACTIVITY_TASKS;
import static android.Manifest.permission.MANAGE_APP_TOKENS;
import static android.Manifest.permission.MODIFY_TOUCH_MODE_STATE;
import static android.Manifest.permission.READ_FRAME_BUFFER;
import static android.Manifest.permission.REGISTER_WINDOW_MANAGER_LISTENERS;
import static android.Manifest.permission.RESTRICTED_VR_ACCESS;
import static android.Manifest.permission.START_TASKS_FROM_RECENTS;
import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.app.ActivityManagerInternal.ALLOW_FULL_ONLY;
import static android.app.ActivityManagerInternal.ALLOW_NON_FULL;
import static android.app.AppOpsManager.OP_SYSTEM_ALERT_WINDOW;
import static android.app.StatusBarManager.DISABLE_MASK;
import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.app.admin.DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED;
import static android.content.pm.PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT;
import static android.content.pm.PackageManager.FEATURE_PC;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.InputConstants.DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
import static android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE;
import static android.os.Process.SYSTEM_UID;
import static android.os.Process.myPid;
import static android.os.Process.myUid;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.provider.Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT;
import static android.provider.Settings.Global.DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW;
import static android.provider.Settings.Global.DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS;
import static android.provider.Settings.Global.DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES;
import static android.provider.Settings.Global.DEVELOPMENT_RENDER_SHADOWS_IN_COMPOSITOR;
import static android.provider.Settings.Global.DEVELOPMENT_WM_DISPLAY_SETTINGS_PATH;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
import static android.view.WindowManager.DISPLAY_IME_POLICY_LOCAL;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.FLAG_SLIPPERY;
import static android.view.WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;
import static android.view.WindowManager.LayoutParams.INPUT_FEATURE_SPY;
import static android.view.WindowManager.LayoutParams.INVALID_WINDOW_TYPE;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.LayoutParams.TYPE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_QS_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;
import static android.view.WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManager.REMOVE_CONTENT_MODE_UNDEFINED;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_RELAUNCH;
import static android.view.WindowManagerGlobal.ADD_OKAY;
import static android.view.WindowManagerGlobal.RELAYOUT_RES_CANCEL_AND_REDRAW;
import static android.view.WindowManagerGlobal.RELAYOUT_RES_SURFACE_CHANGED;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_INVALID;
import static android.view.WindowManagerPolicyConstants.TYPE_LAYER_MULTIPLIER;
import static android.view.displayhash.DisplayHashResultCallback.DISPLAY_HASH_ERROR_MISSING_WINDOW;
import static android.view.displayhash.DisplayHashResultCallback.DISPLAY_HASH_ERROR_NOT_VISIBLE_ON_SCREEN;
import static android.window.WindowProviderService.isWindowProviderService;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ADD_REMOVE;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ANIM;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_BOOT;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_FOCUS;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_FOCUS_LIGHT;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_IME;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_KEEP_SCREEN_ON;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_SCREEN_ON;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_STARTING_WINDOW;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WINDOW_MOVEMENT;
import static com.android.internal.protolog.ProtoLogGroup.WM_ERROR;
import static com.android.internal.protolog.ProtoLogGroup.WM_SHOW_TRANSACTIONS;
import static com.android.internal.util.LatencyTracker.ACTION_ROTATE_SCREEN;
import static com.android.server.LockGuard.INDEX_WINDOW;
import static com.android.server.LockGuard.installLock;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.ActivityTaskManagerService.POWER_MODE_REASON_CHANGE_DISPLAY;
import static com.android.server.wm.DisplayContent.IME_TARGET_CONTROL;
import static com.android.server.wm.DisplayContent.IME_TARGET_LAYERING;
import static com.android.server.wm.RootWindowContainer.MATCH_ATTACHED_TASK_OR_RECENT_TASKS;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_ALL;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_APP_TRANSITION;
import static com.android.server.wm.WindowContainer.AnimationFlags.CHILDREN;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowContainer.SYNC_STATE_NONE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DISPLAY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_INPUT_METHOD;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SCREENSHOT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_TRACE;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_STACK_CRAWLS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_VERBOSE_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerServiceDumpProto.DISPLAY_FROZEN;
import static com.android.server.wm.WindowManagerServiceDumpProto.FOCUSED_APP;
import static com.android.server.wm.WindowManagerServiceDumpProto.FOCUSED_DISPLAY_ID;
import static com.android.server.wm.WindowManagerServiceDumpProto.FOCUSED_WINDOW;
import static com.android.server.wm.WindowManagerServiceDumpProto.HARD_KEYBOARD_AVAILABLE;
import static com.android.server.wm.WindowManagerServiceDumpProto.INPUT_METHOD_WINDOW;
import static com.android.server.wm.WindowManagerServiceDumpProto.POLICY;
import static com.android.server.wm.WindowManagerServiceDumpProto.ROOT_WINDOW_CONTAINER;
import static com.android.server.wm.WindowManagerServiceDumpProto.WINDOW_FRAMES_VALID;

import android.Manifest;
import android.Manifest.permission;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityThread;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.IAssistDataReceiver;
import android.app.WindowConfiguration;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.TestUtilityService;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.configstore.V1_0.OptionalBool;
import android.hardware.configstore.V1_1.ISurfaceFlingerConfigs;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.InputConfig;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.RemoteCallback;
import android.os.RemoteCallbackList;
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
import android.provider.DeviceConfigInterface;
import android.provider.Settings;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.sysprop.SurfaceFlingerProperties;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.MergedConfiguration;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.TimeUtils;
import android.util.TypedValue;
import android.util.proto.ProtoOutputStream;
import android.view.Choreographer;
import android.view.ContentRecordingSession;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.ICrossWindowBlurEnabledListener;
import android.view.IDisplayChangeWindowController;
import android.view.IDisplayFoldListener;
import android.view.IDisplayWindowInsetsController;
import android.view.IDisplayWindowListener;
import android.view.IInputFilter;
import android.view.IOnKeyguardExitResult;
import android.view.IPinnedTaskListener;
import android.view.IRecentsAnimationRunner;
import android.view.IRotationWatcher;
import android.view.IScrollCaptureResponseListener;
import android.view.ISystemGestureExclusionListener;
import android.view.IWallpaperVisibilityListener;
import android.view.IWindow;
import android.view.IWindowId;
import android.view.IWindowManager;
import android.view.IWindowSession;
import android.view.IWindowSessionCallback;
import android.view.InputApplicationHandle;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputWindowHandle;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.InsetsVisibilities;
import android.view.KeyEvent;
import android.view.MagnificationSpec;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.RemoteAnimationAdapter;
import android.view.ScrollCaptureResponse;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceSession;
import android.view.TaskTransitionSpec;
import android.view.View;
import android.view.WindowContentFrameStats;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManager.DisplayImePolicy;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManager.RemoveContentMode;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerPolicyConstants.PointerEventListener;
import android.view.displayhash.DisplayHash;
import android.view.displayhash.VerifiedDisplayHash;
import android.window.ClientWindowFrames;
import android.window.ITaskFpsCallback;
import android.window.TaskSnapshot;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.IResultReceiver;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IKeyguardLockedStateListener;
import com.android.internal.policy.IShortcutService;
import com.android.internal.policy.KeyInterceptionInfo;
import com.android.internal.protolog.ProtoLogImpl;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.LatencyTracker;
import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.view.WindowManagerPolicyThread;
import com.android.server.AnimationThread;
import com.android.server.DisplayThread;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.UiThread;
import com.android.server.Watchdog;
import com.android.server.input.InputManagerService;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.policy.WindowManagerPolicy.ScreenOffListener;
import com.android.server.power.ShutdownThread;
import com.android.server.utils.PriorityDump;

import dalvik.annotation.optimization.NeverCompile;

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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** {@hide} */
public class WindowManagerService extends IWindowManager.Stub
        implements Watchdog.Monitor, WindowManagerPolicy.WindowManagerFuncs {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "WindowManagerService" : TAG_WM;

    static final int LAYOUT_REPEAT_THRESHOLD = 4;

    static final boolean PROFILE_ORIENTATION = false;

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

    // Maximum number of milliseconds to wait for input devices to be enumerated before
    // proceding with safe mode detection.
    private static final int INPUT_DEVICES_READY_FOR_SAFE_MODE_DETECTION_TIMEOUT_MILLIS = 1000;

    // Poll interval in milliseconds for watching boot animation finished.
    // TODO(b/159045990) Migrate to SystemService.waitForState with dedicated thread.
    private static final int BOOT_ANIMATION_POLL_INTERVAL = 50;

    // The name of the boot animation service in init.rc.
    private static final String BOOT_ANIMATION_SERVICE = "bootanim";

    static final int UPDATE_FOCUS_NORMAL = 0;
    /** Caller will assign layers */
    static final int UPDATE_FOCUS_WILL_ASSIGN_LAYERS = 1;
    /** Caller is performing surface placement */
    static final int UPDATE_FOCUS_PLACING_SURFACES = 2;
    /** Caller will performSurfacePlacement */
    static final int UPDATE_FOCUS_WILL_PLACE_SURFACES = 3;
    /** Indicates we are removing the focused window when updating the focus. */
    static final int UPDATE_FOCUS_REMOVING_FOCUS = 4;

    private static final String SYSTEM_SECURE = "ro.secure";
    private static final String SYSTEM_DEBUGGABLE = "ro.debuggable";

    private static final String DENSITY_OVERRIDE = "ro.config.density_override";
    private static final String SIZE_OVERRIDE = "ro.config.size_override";

    private static final String PROPERTY_EMULATOR_CIRCULAR = "ro.emulator.circular";

    static final int MY_PID = myPid();
    static final int MY_UID = myUid();

    static final int LOGTAG_INPUT_FOCUS = 62001;

    /**
     * Use WMShell for app transition.
     */
    public static final String ENABLE_SHELL_TRANSITIONS = "persist.wm.debug.shell_transit";

    /**
     * @see #ENABLE_SHELL_TRANSITIONS
     */
    public static final boolean sEnableShellTransitions =
            SystemProperties.getBoolean(ENABLE_SHELL_TRANSITIONS, false);

    /**
     * Run Keyguard animation as remote animation in System UI instead of local animation in
     * the server process.
     *
     * 0: Runs all keyguard animation as local animation
     * 1: Only runs keyguard going away animation as remote animation
     * 2: Runs all keyguard animation as remote animation
     */
    private static final String ENABLE_REMOTE_KEYGUARD_ANIMATION_PROPERTY =
            "persist.wm.enable_remote_keyguard_animation";

    private static final int sEnableRemoteKeyguardAnimation =
            SystemProperties.getInt(ENABLE_REMOTE_KEYGUARD_ANIMATION_PROPERTY, 2);

    /**
     * @see #ENABLE_REMOTE_KEYGUARD_ANIMATION_PROPERTY
     */
    public static final boolean sEnableRemoteKeyguardGoingAwayAnimation =
            sEnableRemoteKeyguardAnimation >= 1;

    /**
     * @see #ENABLE_REMOTE_KEYGUARD_ANIMATION_PROPERTY
     */
    public static final boolean sEnableRemoteKeyguardOccludeAnimation =
            sEnableRemoteKeyguardAnimation >= 2;

    /**
     * Allows a fullscreen windowing mode activity to launch in its desired orientation directly
     * when the display has different orientation.
     */
    static final boolean ENABLE_FIXED_ROTATION_TRANSFORM =
            SystemProperties.getBoolean("persist.wm.fixed_rotation_transform", true);

    // Enums for animation scale update types.
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({WINDOW_ANIMATION_SCALE, TRANSITION_ANIMATION_SCALE, ANIMATION_DURATION_SCALE})
    private @interface UpdateAnimationScaleMode {};
    private static final int WINDOW_ANIMATION_SCALE = 0;
    private static final int TRANSITION_ANIMATION_SCALE = 1;
    private static final int ANIMATION_DURATION_SCALE = 2;

    private static final int ANIMATION_COMPLETED_TIMEOUT_MS = 5000;

    final WindowManagerConstants mConstants;

    final WindowTracing mWindowTracing;
    final TransitionTracer mTransitionTracer;

    private final DisplayAreaPolicy.Provider mDisplayAreaPolicyProvider;

    final private KeyguardDisableHandler mKeyguardDisableHandler;

    private final RemoteCallbackList<IKeyguardLockedStateListener> mKeyguardLockedStateListeners =
            new RemoteCallbackList<>();
    private boolean mDispatchedKeyguardLockedState = false;

    // VR Vr2d Display Id.
    int mVr2dDisplayId = INVALID_DISPLAY;
    boolean mVrModeEnabled = false;

    /**
     * Tracks a map of input tokens to info that is used to decide whether to intercept
     * a key event.
     */
    final Map<IBinder, KeyInterceptionInfo> mKeyInterceptionInfoForToken =
            Collections.synchronizedMap(new ArrayMap<>());

    final StartingSurfaceController mStartingSurfaceController;

    private final IVrStateCallbacks mVrStateCallbacks = new IVrStateCallbacks.Stub() {
        @Override
        public void onVrStateChanged(boolean enabled) {
            synchronized (mGlobalLock) {
                mVrModeEnabled = enabled;
                final PooledConsumer c = PooledLambda.obtainConsumer(
                        DisplayPolicy::onVrStateChangedLw, PooledLambda.__(), enabled);
                mRoot.forAllDisplayPolicies(c);
                c.recycle();
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

    final boolean mAllowBootMessages;

    // Indicates whether the Assistant should show on top of the Dream (respectively, above
    // everything else on screen). Otherwise, it will be put under always-on-top stacks.
    final boolean mAssistantOnTopOfDream;

    final boolean mLimitedAlphaCompositing;
    final int mMaxUiWidth;

    @VisibleForTesting
    WindowManagerPolicy mPolicy;

    final IActivityManager mActivityManager;
    final ActivityManagerInternal mAmInternal;

    final AppOpsManager mAppOps;
    final PackageManagerInternal mPmInternal;
    private final TestUtilityService mTestUtilityService;

    final DisplayWindowSettingsProvider mDisplayWindowSettingsProvider;
    final DisplayWindowSettings mDisplayWindowSettings;

    /** If the system should display notifications for apps displaying an alert window. */
    boolean mShowAlertWindowNotifications = true;

    /**
     * All currently active sessions with clients.
     */
    final ArraySet<Session> mSessions = new ArraySet<>();

    /** Mapping from an IWindow IBinder to the server's Window object. */
    final HashMap<IBinder, WindowState> mWindowMap = new HashMap<>();

    /** Mapping from an InputWindowHandle token to the server's Window object. */
    final HashMap<IBinder, WindowState> mInputToWindowMap = new HashMap<>();

    /** Global service lock used by the package the owns this service. */
    final WindowManagerGlobalLock mGlobalLock;

    /**
     * List of app window tokens that are waiting for replacing windows. If the
     * replacement doesn't come in time the stale windows needs to be disposed of.
     */
    final ArrayList<ActivityRecord> mWindowReplacementTimeouts = new ArrayList<>();

    /**
     * Windows that are being resized.  Used so we can tell the client about
     * the resize after closing the transaction in which we resized the
     * underlying surface.
     */
    final ArrayList<WindowState> mResizingWindows = new ArrayList<>();

    /**
     * Mapping of displayId to {@link DisplayImePolicy}.
     * Note that this can be accessed without holding the lock.
     */
    volatile Map<Integer, Integer> mDisplayImePolicyCache = Collections.unmodifiableMap(
            new ArrayMap<>());

    /**
     * Windows whose surface should be destroyed.
     */
    final ArrayList<WindowState> mDestroySurface = new ArrayList<>();

    /**
     * This is set when we have run out of memory, and will either be an empty
     * list or contain windows that need to be force removed.
     */
    final ArrayList<WindowState> mForceRemoves = new ArrayList<>();

    /**
     * The callbacks to make when the windows all have been drawn for a given
     * {@link WindowContainer}.
     */
    final HashMap<WindowContainer, Runnable> mWaitingForDrawnCallbacks = new HashMap<>();

    /** List of window currently causing non-system overlay windows to be hidden. */
    private ArrayList<WindowState> mHidingNonSystemOverlayWindows = new ArrayList<>();

    final AccessibilityController mAccessibilityController;
    private RecentsAnimationController mRecentsAnimationController;

    Watermark mWatermark;
    StrictModeFlash mStrictModeFlash;
    EmulatorDisplayOverlay mEmulatorDisplayOverlay;

    final Rect mTmpRect = new Rect();

    boolean mDisplayReady;
    boolean mSafeMode;
    boolean mDisplayEnabled = false;
    boolean mSystemBooted = false;
    boolean mForceDisplayEnabled = false;
    boolean mShowingBootMessages = false;
    boolean mSystemReady = false;
    boolean mBootAnimationStopped = false;
    long mBootWaitForWindowsStartTime = -1;

    // Following variables are for debugging screen wakelock only.
    WindowState mLastWakeLockHoldingWindow = null;
    WindowState mLastWakeLockObscuringWindow = null;

    /** Dump of the windows and app tokens at the time of the last ANR. Cleared after
     * LAST_ANR_LIFETIME_DURATION_MSECS */
    String mLastANRState;

    // The root of the device window hierarchy.
    RootWindowContainer mRoot;

    // Whether the system should use BLAST for ViewRootImpl
    final boolean mUseBLAST;
    // Whether to enable BLASTSyncEngine Transaction passing.
    final boolean mUseBLASTSync = true;

    final BLASTSyncEngine mSyncEngine;

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

    IDisplayChangeWindowController mDisplayChangeController = null;
    private final DeathRecipient mDisplayChangeControllerDeath =
            () -> mDisplayChangeController = null;

    final DisplayWindowListenerController mDisplayNotificationController;
    final TaskSystemBarsListenerController mTaskSystemBarsListenerController;

    boolean mDisplayFrozen = false;
    long mDisplayFreezeTime = 0;
    int mLastDisplayFreezeDuration = 0;
    Object mLastFinishedFreezeSource = null;
    boolean mSwitchingUser = false;

    final static int WINDOWS_FREEZING_SCREENS_NONE = 0;
    final static int WINDOWS_FREEZING_SCREENS_ACTIVE = 1;
    final static int WINDOWS_FREEZING_SCREENS_TIMEOUT = 2;
    int mWindowsFreezingScreen = WINDOWS_FREEZING_SCREENS_NONE;

    /** Indicates that the system server is actively demanding the screen be frozen. */
    boolean mClientFreezingScreen = false;
    int mAppsFreezingScreen = 0;

    @VisibleForTesting
    boolean mPerDisplayFocusEnabled;

    // State while inside of layoutAndPlaceSurfacesLocked().
    boolean mFocusMayChange;

    // Number of windows whose insets state have been changed.
    int mWindowsInsetsChanged = 0;

    // This is held as long as we have the screen frozen, to give us time to
    // perform a rotation animation when turning off shows the lock screen which
    // changes the orientation.
    private final PowerManager.WakeLock mScreenFrozenLock;

    final TaskSnapshotController mTaskSnapshotController;

    final BlurController mBlurController;
    final TaskFpsCallbackController mTaskFpsCallbackController;

    boolean mIsTouchDevice;
    boolean mIsFakeTouchDevice;

    final H mH = new H();

    /**
     * Handler for things to run that have direct impact on an animation, i.e. animation tick,
     * layout, starting window creation, whereas {@link H} runs things that are still important, but
     * not as critical.
     */
    final Handler mAnimationHandler = new Handler(AnimationThread.getHandler().getLooper());

    /**
     * Used during task transitions to allow SysUI and launcher to customize task transitions.
     */
    TaskTransitionSpec mTaskTransitionSpec;

    boolean mHardKeyboardAvailable;
    WindowManagerInternal.OnHardKeyboardStatusChangeListener mHardKeyboardStatusChangeListener;
    SettingsObserver mSettingsObserver;
    final EmbeddedWindowController mEmbeddedWindowController;
    final AnrController mAnrController;

    private final DisplayHashController mDisplayHashController;

    volatile float mMaximumObscuringOpacityForTouch =
            InputManager.DEFAULT_MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH;

    @VisibleForTesting
    final WindowContextListenerController mWindowContextListenerController =
            new WindowContextListenerController();

    private InputTarget mFocusedInputTarget;

    @VisibleForTesting
    final ContentRecordingController mContentRecordingController = new ContentRecordingController();

    @VisibleForTesting
    final class SettingsObserver extends ContentObserver {
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
        private final Uri mPointerLocationUri =
                Settings.System.getUriFor(Settings.System.POINTER_LOCATION);
        private final Uri mForceDesktopModeOnExternalDisplaysUri = Settings.Global.getUriFor(
                        Settings.Global.DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS);
        private final Uri mFreeformWindowUri = Settings.Global.getUriFor(
                Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT);
        private final Uri mForceResizableUri = Settings.Global.getUriFor(
                DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES);
        private final Uri mDevEnableNonResizableMultiWindowUri = Settings.Global.getUriFor(
                DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW);
        private final Uri mRenderShadowsInCompositorUri = Settings.Global.getUriFor(
                DEVELOPMENT_RENDER_SHADOWS_IN_COMPOSITOR);
        private final Uri mDisplaySettingsPathUri = Settings.Global.getUriFor(
                DEVELOPMENT_WM_DISPLAY_SETTINGS_PATH);
        private final Uri mMaximumObscuringOpacityForTouchUri = Settings.Global.getUriFor(
                Settings.Global.MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH);

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
            resolver.registerContentObserver(mPolicyControlUri, false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(mPointerLocationUri, false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(mForceDesktopModeOnExternalDisplaysUri, false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(mFreeformWindowUri, false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(mForceResizableUri, false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(mDevEnableNonResizableMultiWindowUri, false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(mDisplaySettingsPathUri, false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(mMaximumObscuringOpacityForTouchUri, false, this,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri == null) {
                return;
            }

            if (mImmersiveModeConfirmationsUri.equals(uri) || mPolicyControlUri.equals(uri)) {
                updateSystemUiSettings(true /* handleChange */);
                return;
            }

            if (mPointerLocationUri.equals(uri)) {
                updatePointerLocation();
                return;
            }

            if (mForceDesktopModeOnExternalDisplaysUri.equals(uri)) {
                updateForceDesktopModeOnExternalDisplays();
                return;
            }

            if (mFreeformWindowUri.equals(uri)) {
                updateFreeformWindowManagement();
                return;
            }

            if (mForceResizableUri.equals(uri)) {
                updateForceResizableTasks();
                return;
            }

            if (mDevEnableNonResizableMultiWindowUri.equals(uri)) {
                updateDevEnableNonResizableMultiWindow();
                return;
            }

            if (mDisplaySettingsPathUri.equals(uri)) {
                updateDisplaySettingsLocation();
                return;
            }

            if (mMaximumObscuringOpacityForTouchUri.equals(uri)) {
                updateMaximumObscuringOpacityForTouch();
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

        void loadSettings() {
            updateSystemUiSettings(false /* handleChange */);
            updatePointerLocation();
            updateMaximumObscuringOpacityForTouch();
        }

        void updateMaximumObscuringOpacityForTouch() {
            ContentResolver resolver = mContext.getContentResolver();
            mMaximumObscuringOpacityForTouch = Settings.Global.getFloat(resolver,
                    Settings.Global.MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH,
                    InputManager.DEFAULT_MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH);
        }

        void updateSystemUiSettings(boolean handleChange) {
            synchronized (mGlobalLock) {
                boolean changed = false;
                if (handleChange) {
                    changed = getDefaultDisplayContentLocked().getDisplayPolicy()
                            .onSystemUiSettingsChanged();
                } else {
                    ImmersiveModeConfirmation.loadSetting(mCurrentUserId, mContext);
                }
                if (changed) {
                    mWindowPlacerLocked.requestTraversal();
                }
            }
        }

        void updatePointerLocation() {
            ContentResolver resolver = mContext.getContentResolver();
            final boolean enablePointerLocation = Settings.System.getIntForUser(resolver,
                    Settings.System.POINTER_LOCATION, 0, UserHandle.USER_CURRENT) != 0;

            if (mPointerLocationEnabled == enablePointerLocation) {
                return;
            }
            mPointerLocationEnabled = enablePointerLocation;
            synchronized (mGlobalLock) {
                final PooledConsumer c = PooledLambda.obtainConsumer(
                        DisplayPolicy::setPointerLocationEnabled, PooledLambda.__(),
                        mPointerLocationEnabled);
                mRoot.forAllDisplayPolicies(c);
                c.recycle();
            }
        }

        void updateForceDesktopModeOnExternalDisplays() {
            ContentResolver resolver = mContext.getContentResolver();
            final boolean enableForceDesktopMode = Settings.Global.getInt(resolver,
                    DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS, 0) != 0;
            if (mForceDesktopModeOnExternalDisplays == enableForceDesktopMode) {
                return;
            }
            setForceDesktopModeOnExternalDisplays(enableForceDesktopMode);
        }

        void updateFreeformWindowManagement() {
            ContentResolver resolver = mContext.getContentResolver();
            final boolean freeformWindowManagement = mContext.getPackageManager().hasSystemFeature(
                    FEATURE_FREEFORM_WINDOW_MANAGEMENT) || Settings.Global.getInt(
                    resolver, DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT, 0) != 0;

            if (mAtmService.mSupportsFreeformWindowManagement != freeformWindowManagement) {
                mAtmService.mSupportsFreeformWindowManagement = freeformWindowManagement;
                synchronized (mGlobalLock) {
                    // Notify the root window container that the display settings value may change.
                    mRoot.onSettingsRetrieved();
                }
            }
        }

        void updateForceResizableTasks() {
            ContentResolver resolver = mContext.getContentResolver();
            final boolean forceResizable = Settings.Global.getInt(resolver,
                    DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES, 0) != 0;

            mAtmService.mForceResizableActivities = forceResizable;
        }

        void updateDevEnableNonResizableMultiWindow() {
            ContentResolver resolver = mContext.getContentResolver();
            final boolean devEnableNonResizableMultiWindow = Settings.Global.getInt(resolver,
                    DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW, 0) != 0;

            mAtmService.mDevEnableNonResizableMultiWindow = devEnableNonResizableMultiWindow;
        }

        void updateDisplaySettingsLocation() {
            final ContentResolver resolver = mContext.getContentResolver();
            final String filePath = Settings.Global.getString(resolver,
                    DEVELOPMENT_WM_DISPLAY_SETTINGS_PATH);
            synchronized (mGlobalLock) {
                mDisplayWindowSettingsProvider.setBaseSettingsFilePath(filePath);
                mRoot.forAllDisplays(display -> {
                    mDisplayWindowSettings.applySettingsToDisplayLocked(display);
                    display.reconfigureDisplayLocked();
                });
            }
        }
    }

    PowerManager mPowerManager;
    PowerManagerInternal mPowerManagerInternal;

    private float mWindowAnimationScaleSetting = 1.0f;
    private float mTransitionAnimationScaleSetting = 1.0f;
    private float mAnimatorDurationScaleSetting = 1.0f;
    private boolean mAnimationsDisabled = false;
    boolean mPointerLocationEnabled = false;

    final LetterboxConfiguration mLetterboxConfiguration;

    private boolean mIsIgnoreOrientationRequestDisabled;

    final InputManagerService mInputManager;
    final DisplayManagerInternal mDisplayManagerInternal;
    final DisplayManager mDisplayManager;
    final ActivityTaskManagerService mAtmService;

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

    /** Skip repeated ActivityRecords initialization. Note that AppWindowsToken's version of this
     * is a long initialized to Long.MIN_VALUE so that it doesn't match this value on startup. */
    int mTransactionSequence;

    final WindowAnimator mAnimator;
    SurfaceAnimationRunner mSurfaceAnimationRunner;

    /**
     * Keeps track of which animations got transferred to which animators. Entries will get cleaned
     * up when the animation finishes.
     */
    final ArrayMap<AnimationAdapter, SurfaceAnimator> mAnimationTransferMap = new ArrayMap<>();

    private WindowContentFrameStats mTempWindowRenderStats;

    final LatencyTracker mLatencyTracker;

    /**
     * Whether the UI is currently running in touch mode (not showing
     * navigational focus because the user is directly pressing the screen).
     */
    private boolean mInTouchMode;

    private ViewServer mViewServer;
    final ArrayList<WindowChangeListener> mWindowChangeListeners = new ArrayList<>();
    boolean mWindowsChanged = false;

    public interface WindowChangeListener {
        public void windowsChanged();
        public void focusChanged();
    }

    final HighRefreshRateDenylist mHighRefreshRateDenylist;

    // Maintainer of a collection of all possible DisplayInfo for all configurations of the
    // logical displays.
    final PossibleDisplayInfoMapper mPossibleDisplayInfoMapper;

    static WindowManagerThreadPriorityBooster sThreadPriorityBooster =
            new WindowManagerThreadPriorityBooster();

    Function<SurfaceSession, SurfaceControl.Builder> mSurfaceControlFactory;
    Supplier<SurfaceControl.Transaction> mTransactionFactory;

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
            SurfaceControl.openTransaction();
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
            SurfaceControl.closeTransaction();
            mWindowTracing.logState(where);
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    /** Listener to notify activity manager about app transitions. */
    final WindowManagerInternal.AppTransitionListener mActivityManagerAppTransitionNotifier
            = new WindowManagerInternal.AppTransitionListener() {

        @Override
        public void onAppTransitionCancelledLocked(boolean keyguardGoingAway) {
        }

        @Override
        public void onAppTransitionFinishedLocked(IBinder token) {
            final ActivityRecord atoken = mRoot.getActivityRecord(token);
            if (atoken == null) {
                return;
            }

            // While running a recents animation, this will get called early because we show the
            // recents animation target activity immediately when the animation starts. Defer the
            // mLaunchTaskBehind updates until recents animation finishes.
            if (atoken.mLaunchTaskBehind && !isRecentsAnimationTarget(atoken)) {
                mAtmService.mTaskSupervisor.scheduleLaunchTaskBehindComplete(atoken.token);
                atoken.mLaunchTaskBehind = false;
            } else {
                atoken.updateReportedVisibilityLocked();
                // We should also defer sending the finished callback until the recents animation
                // successfully finishes.
                if (atoken.mEnteringAnimation && !isRecentsAnimationTarget(atoken)) {
                    atoken.mEnteringAnimation = false;
                    if (atoken.attachedToProcess()) {
                        try {
                            atoken.app.getThread().scheduleEnterAnimationComplete(atoken.token);
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

    public static WindowManagerService main(final Context context, final InputManagerService im,
            final boolean showBootMsgs, WindowManagerPolicy policy,
            ActivityTaskManagerService atm) {
        return main(context, im, showBootMsgs, policy, atm, new DisplayWindowSettingsProvider(),
                SurfaceControl.Transaction::new, SurfaceControl.Builder::new);
    }

    /**
     * Creates and returns an instance of the WindowManagerService. This call allows the caller
     * to override factories that can be used to stub native calls during test.
     */
    @VisibleForTesting
    public static WindowManagerService main(final Context context, final InputManagerService im,
            final boolean showBootMsgs, WindowManagerPolicy policy, ActivityTaskManagerService atm,
            DisplayWindowSettingsProvider displayWindowSettingsProvider,
            Supplier<SurfaceControl.Transaction> transactionFactory,
            Function<SurfaceSession, SurfaceControl.Builder> surfaceControlFactory) {
        final WindowManagerService[] wms = new WindowManagerService[1];
        DisplayThread.getHandler().runWithScissors(() ->
                wms[0] = new WindowManagerService(context, im, showBootMsgs, policy, atm,
                        displayWindowSettingsProvider, transactionFactory,
                        surfaceControlFactory), 0);
        return wms[0];
    }

    private void initPolicy() {
        UiThread.getHandler().runWithScissors(new Runnable() {
            @Override
            public void run() {
                WindowManagerPolicyThread.set(Thread.currentThread(), Looper.myLooper());
                mPolicy.init(mContext, WindowManagerService.this);
            }
        }, 0);
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver result) {
        new WindowManagerShellCommand(this).exec(this, in, out, err, args, callback, result);
    }

    private WindowManagerService(Context context, InputManagerService inputManager,
            boolean showBootMsgs, WindowManagerPolicy policy, ActivityTaskManagerService atm,
            DisplayWindowSettingsProvider displayWindowSettingsProvider,
            Supplier<SurfaceControl.Transaction> transactionFactory,
            Function<SurfaceSession, SurfaceControl.Builder> surfaceControlFactory) {
        installLock(this, INDEX_WINDOW);
        mGlobalLock = atm.getGlobalLock();
        mAtmService = atm;
        mContext = context;
        mIsPc = mContext.getPackageManager().hasSystemFeature(FEATURE_PC);
        mAllowBootMessages = showBootMsgs;
        mLimitedAlphaCompositing = context.getResources().getBoolean(
                com.android.internal.R.bool.config_sf_limitedAlpha);
        mHasPermanentDpad = context.getResources().getBoolean(
                com.android.internal.R.bool.config_hasPermanentDpad);
        mInTouchMode = context.getResources().getBoolean(
                com.android.internal.R.bool.config_defaultInTouchMode);
        inputManager.setInTouchMode(mInTouchMode, MY_PID, MY_UID, true /* hasPermission */);
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
        mAssistantOnTopOfDream = context.getResources().getBoolean(
                com.android.internal.R.bool.config_assistantOnTopOfDream);

        mLetterboxConfiguration = new LetterboxConfiguration(
                // Using SysUI context to have access to Material colors extracted from Wallpaper.
                ActivityThread.currentActivityThread().getSystemUiContext());

        mInputManager = inputManager; // Must be before createDisplayContentLocked.
        mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
        mPossibleDisplayInfoMapper = new PossibleDisplayInfoMapper(mDisplayManagerInternal);

        mSurfaceControlFactory = surfaceControlFactory;
        mTransactionFactory = transactionFactory;
        mTransaction = mTransactionFactory.get();

        mPolicy = policy;
        mAnimator = new WindowAnimator(this);
        mRoot = new RootWindowContainer(this);

        final ContentResolver resolver = context.getContentResolver();
        mUseBLAST = Settings.Global.getInt(resolver,
            Settings.Global.DEVELOPMENT_USE_BLAST_ADAPTER_VR, 1) == 1;

        mSyncEngine = new BLASTSyncEngine(this);

        mWindowPlacerLocked = new WindowSurfacePlacer(this);
        mTaskSnapshotController = new TaskSnapshotController(this);

        mWindowTracing = WindowTracing.createDefaultAndStartLooper(this,
                Choreographer.getInstance());
        mTransitionTracer = new TransitionTracer();

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

        mDisplayNotificationController = new DisplayWindowListenerController(this);
        mTaskSystemBarsListenerController = new TaskSystemBarsListenerController();

        mActivityManager = ActivityManager.getService();
        mAmInternal = LocalServices.getService(ActivityManagerInternal.class);
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
        mTestUtilityService = LocalServices.getService(TestUtilityService.class);
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

        final String displaySettingsPath = Settings.Global.getString(resolver,
                DEVELOPMENT_WM_DISPLAY_SETTINGS_PATH);
        mDisplayWindowSettingsProvider = displayWindowSettingsProvider;
        if (displaySettingsPath != null) {
            mDisplayWindowSettingsProvider.setBaseSettingsFilePath(displaySettingsPath);
        }
        mDisplayWindowSettings = new DisplayWindowSettings(this, mDisplayWindowSettingsProvider);

        IntentFilter filter = new IntentFilter();
        // Track changes to DevicePolicyManager state so we can enable/disable keyguard.
        filter.addAction(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        mContext.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, filter, null, null);

        mLatencyTracker = LatencyTracker.getInstance(context);

        mSettingsObserver = new SettingsObserver();

        mHoldingScreenWakeLock = mPowerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, TAG_WM);
        mHoldingScreenWakeLock.setReferenceCounted(false);

        mSurfaceAnimationRunner = new SurfaceAnimationRunner(mTransactionFactory,
                mPowerManagerInternal);

        mAllowTheaterModeWakeFromLayout = context.getResources().getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromWindowLayout);

        mTaskPositioningController = new TaskPositioningController(this);
        mDragDropController = new DragDropController(this, mH.getLooper());

        mHighRefreshRateDenylist = HighRefreshRateDenylist.create(context.getResources());

        mConstants = new WindowManagerConstants(this, DeviceConfigInterface.REAL);
        mConstants.start(new HandlerExecutor(mH));

        LocalServices.addService(WindowManagerInternal.class, new LocalService());
        mEmbeddedWindowController = new EmbeddedWindowController(mAtmService);

        mDisplayAreaPolicyProvider = DisplayAreaPolicy.Provider.fromResources(
                mContext.getResources());

        mDisplayHashController = new DisplayHashController(mContext);
        setGlobalShadowSettings();
        mAnrController = new AnrController(this);
        mStartingSurfaceController = new StartingSurfaceController(this);

        mBlurController = new BlurController(mContext, mPowerManager);
        mTaskFpsCallbackController = new TaskFpsCallbackController(mContext);
        mAccessibilityController = new AccessibilityController(this);
    }

    DisplayAreaPolicy.Provider getDisplayAreaPolicyProvider() {
        return mDisplayAreaPolicyProvider;
    }

    private void setGlobalShadowSettings() {
        final TypedArray a = mContext.obtainStyledAttributes(null, R.styleable.Lighting, 0, 0);
        float lightY = a.getDimension(R.styleable.Lighting_lightY, 0);
        float lightZ = a.getDimension(R.styleable.Lighting_lightZ, 0);
        float lightRadius = a.getDimension(R.styleable.Lighting_lightRadius, 0);
        float ambientShadowAlpha = a.getFloat(R.styleable.Lighting_ambientShadowAlpha, 0);
        float spotShadowAlpha = a.getFloat(R.styleable.Lighting_spotShadowAlpha, 0);
        a.recycle();
        float[] ambientColor = {0.f, 0.f, 0.f, ambientShadowAlpha};
        float[] spotColor = {0.f, 0.f, 0.f, spotShadowAlpha};
        SurfaceControl.setGlobalShadowSettings(ambientColor, spotColor, lightY, lightZ,
                lightRadius);
    }

    /**
     * Called after all entities (such as the {@link ActivityManagerService}) have been set up and
     * associated with the {@link WindowManagerService}.
     */
    public void onInitReady() {
        initPolicy();

        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);
        createWatermark();
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
                ProtoLog.wtf(WM_ERROR, "Window Manager Crash %s", e);
            }
            throw e;
        }
    }

    static boolean excludeWindowTypeFromTapOutTask(int windowType) {
        switch (windowType) {
            case TYPE_STATUS_BAR:
            case TYPE_NOTIFICATION_SHADE:
            case TYPE_NAVIGATION_BAR:
            case TYPE_INPUT_METHOD_DIALOG:
            case TYPE_VOLUME_OVERLAY:
                return true;
        }
        return false;
    }

    public int addWindow(Session session, IWindow client, LayoutParams attrs, int viewVisibility,
            int displayId, int requestUserId, InsetsVisibilities requestedVisibilities,
            InputChannel outInputChannel, InsetsState outInsetsState,
            InsetsSourceControl[] outActiveControls, Rect outAttachedFrame) {
        Arrays.fill(outActiveControls, null);
        int[] appOp = new int[1];
        final boolean isRoundedCornerOverlay = (attrs.privateFlags
                & PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY) != 0;
        int res = mPolicy.checkAddPermission(attrs.type, isRoundedCornerOverlay, attrs.packageName,
                appOp);
        if (res != ADD_OKAY) {
            return res;
        }

        WindowState parentWindow = null;
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final long origId = Binder.clearCallingIdentity();
        final int type = attrs.type;

        synchronized (mGlobalLock) {
            if (!mDisplayReady) {
                throw new IllegalStateException("Display has not been initialialized");
            }

            final DisplayContent displayContent = getDisplayContentOrCreate(displayId, attrs.token);

            if (displayContent == null) {
                ProtoLog.w(WM_ERROR, "Attempted to add window to a display that does "
                        + "not exist: %d. Aborting.", displayId);
                return WindowManagerGlobal.ADD_INVALID_DISPLAY;
            }
            if (!displayContent.hasAccess(session.mUid)) {
                ProtoLog.w(WM_ERROR,
                        "Attempted to add window to a display for which the application "
                                + "does not have access: %d.  Aborting.",
                        displayContent.getDisplayId());
                return WindowManagerGlobal.ADD_INVALID_DISPLAY;
            }

            if (mWindowMap.containsKey(client.asBinder())) {
                ProtoLog.w(WM_ERROR, "Window %s is already added", client);
                return WindowManagerGlobal.ADD_DUPLICATE_ADD;
            }

            if (type >= FIRST_SUB_WINDOW && type <= LAST_SUB_WINDOW) {
                parentWindow = windowForClientLocked(null, attrs.token, false);
                if (parentWindow == null) {
                    ProtoLog.w(WM_ERROR, "Attempted to add window with token that is not a window: "
                            + "%s.  Aborting.", attrs.token);
                    return WindowManagerGlobal.ADD_BAD_SUBWINDOW_TOKEN;
                }
                if (parentWindow.mAttrs.type >= FIRST_SUB_WINDOW
                        && parentWindow.mAttrs.type <= LAST_SUB_WINDOW) {
                    ProtoLog.w(WM_ERROR, "Attempted to add window with token that is a sub-window: "
                            + "%s.  Aborting.", attrs.token);
                    return WindowManagerGlobal.ADD_BAD_SUBWINDOW_TOKEN;
                }
            }

            if (type == TYPE_PRIVATE_PRESENTATION && !displayContent.isPrivate()) {
                ProtoLog.w(WM_ERROR,
                        "Attempted to add private presentation window to a non-private display.  "
                                + "Aborting.");
                return WindowManagerGlobal.ADD_PERMISSION_DENIED;
            }

            if (type == TYPE_PRESENTATION && !displayContent.getDisplay().isPublicPresentation()) {
                ProtoLog.w(WM_ERROR,
                        "Attempted to add presentation window to a non-suitable display.  "
                                + "Aborting.");
                return WindowManagerGlobal.ADD_INVALID_DISPLAY;
            }

            int userId = UserHandle.getUserId(session.mUid);
            if (requestUserId != userId) {
                try {
                    mAmInternal.handleIncomingUser(callingPid, callingUid, requestUserId,
                            false /*allowAll*/, ALLOW_NON_FULL, null, null);
                } catch (Exception exp) {
                    ProtoLog.w(WM_ERROR, "Trying to add window with invalid user=%d",
                            requestUserId);
                    return WindowManagerGlobal.ADD_INVALID_USER;
                }
                // It's fine to use this userId
                userId = requestUserId;
            }

            ActivityRecord activity = null;
            final boolean hasParent = parentWindow != null;
            // Use existing parent window token for child windows since they go in the same token
            // as there parent window so we can apply the same policy on them.
            WindowToken token = displayContent.getWindowToken(
                    hasParent ? parentWindow.mAttrs.token : attrs.token);
            // If this is a child window, we want to apply the same type checking rules as the
            // parent window type.
            final int rootType = hasParent ? parentWindow.mAttrs.type : type;

            boolean addToastWindowRequiresToken = false;

            final IBinder windowContextToken = attrs.mWindowContextToken;

            if (token == null) {
                if (!unprivilegedAppCanCreateTokenWith(parentWindow, callingUid, type,
                        rootType, attrs.token, attrs.packageName)) {
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
                if (hasParent) {
                    // Use existing parent window token for child windows.
                    token = parentWindow.mToken;
                } else if (mWindowContextListenerController.hasListener(windowContextToken)) {
                    // Respect the window context token if the user provided it.
                    final IBinder binder = attrs.token != null ? attrs.token : windowContextToken;
                    final Bundle options = mWindowContextListenerController
                            .getOptions(windowContextToken);
                    token = new WindowToken.Builder(this, binder, type)
                            .setDisplayContent(displayContent)
                            .setOwnerCanManageAppTokens(session.mCanAddInternalSystemWindow)
                            .setRoundedCornerOverlay(isRoundedCornerOverlay)
                            .setFromClientToken(true)
                            .setOptions(options)
                            .build();
                } else {
                    final IBinder binder = attrs.token != null ? attrs.token : client.asBinder();
                    token = new WindowToken.Builder(this, binder, type)
                            .setDisplayContent(displayContent)
                            .setOwnerCanManageAppTokens(session.mCanAddInternalSystemWindow)
                            .setRoundedCornerOverlay(isRoundedCornerOverlay)
                            .build();
                }
            } else if (rootType >= FIRST_APPLICATION_WINDOW
                    && rootType <= LAST_APPLICATION_WINDOW) {
                activity = token.asActivityRecord();
                if (activity == null) {
                    ProtoLog.w(WM_ERROR, "Attempted to add window with non-application token "
                            + ".%s Aborting.", token);
                    return WindowManagerGlobal.ADD_NOT_APP_TOKEN;
                } else if (activity.getParent() == null) {
                    ProtoLog.w(WM_ERROR, "Attempted to add window with exiting application token "
                            + ".%s Aborting.", token);
                    return WindowManagerGlobal.ADD_APP_EXITING;
                } else if (type == TYPE_APPLICATION_STARTING) {
                    if (activity.mStartingWindow != null) {
                        ProtoLog.w(WM_ERROR, "Attempted to add starting window to "
                                + "token with already existing starting window");
                        return WindowManagerGlobal.ADD_DUPLICATE_ADD;
                    }
                    if (activity.mStartingData == null) {
                        ProtoLog.w(WM_ERROR, "Attempted to add starting window to "
                                + "token but already cleaned");
                        return WindowManagerGlobal.ADD_DUPLICATE_ADD;
                    }
                }
            } else if (rootType == TYPE_INPUT_METHOD) {
                if (token.windowType != TYPE_INPUT_METHOD) {
                    ProtoLog.w(WM_ERROR, "Attempted to add input method window with bad token "
                            + "%s.  Aborting.", attrs.token);
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (rootType == TYPE_VOICE_INTERACTION) {
                if (token.windowType != TYPE_VOICE_INTERACTION) {
                    ProtoLog.w(WM_ERROR, "Attempted to add voice interaction window with bad token "
                            + "%s.  Aborting.", attrs.token);
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (rootType == TYPE_WALLPAPER) {
                if (token.windowType != TYPE_WALLPAPER) {
                    ProtoLog.w(WM_ERROR, "Attempted to add wallpaper window with bad token "
                            + "%s.  Aborting.", attrs.token);
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (rootType == TYPE_ACCESSIBILITY_OVERLAY) {
                if (token.windowType != TYPE_ACCESSIBILITY_OVERLAY) {
                    ProtoLog.w(WM_ERROR,
                            "Attempted to add Accessibility overlay window with bad token "
                                    + "%s.  Aborting.", attrs.token);
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (type == TYPE_TOAST) {
                // Apps targeting SDK above N MR1 cannot arbitrary add toast windows.
                addToastWindowRequiresToken = doesAddToastWindowRequireToken(attrs.packageName,
                        callingUid, parentWindow);
                if (addToastWindowRequiresToken && token.windowType != TYPE_TOAST) {
                    ProtoLog.w(WM_ERROR, "Attempted to add a toast window with bad token "
                            + "%s.  Aborting.", attrs.token);
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (type == TYPE_QS_DIALOG) {
                if (token.windowType != TYPE_QS_DIALOG) {
                    ProtoLog.w(WM_ERROR, "Attempted to add QS dialog window with bad token "
                            + "%s.  Aborting.", attrs.token);
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (token.asActivityRecord() != null) {
                ProtoLog.w(WM_ERROR, "Non-null activity for system window of rootType=%d",
                        rootType);
                // It is not valid to use an app token with other system types; we will
                // instead make a new token for it (as if null had been passed in for the token).
                attrs.token = null;
                token = new WindowToken.Builder(this, client.asBinder(), type)
                        .setDisplayContent(displayContent)
                        .setOwnerCanManageAppTokens(session.mCanAddInternalSystemWindow)
                        .build();
            }

            final WindowState win = new WindowState(this, session, client, token, parentWindow,
                    appOp[0], attrs, viewVisibility, session.mUid, userId,
                    session.mCanAddInternalSystemWindow);
            if (win.mDeathRecipient == null) {
                // Client has apparently died, so there is no reason to
                // continue.
                ProtoLog.w(WM_ERROR, "Adding window client %s"
                        + " that is dead, aborting.", client.asBinder());
                return WindowManagerGlobal.ADD_APP_EXITING;
            }

            if (win.getDisplayContent() == null) {
                ProtoLog.w(WM_ERROR, "Adding window to Display that has been removed.");
                return WindowManagerGlobal.ADD_INVALID_DISPLAY;
            }

            final DisplayPolicy displayPolicy = displayContent.getDisplayPolicy();
            displayPolicy.adjustWindowParamsLw(win, win.mAttrs);
            attrs.flags = sanitizeFlagSlippery(attrs.flags, win.getName(), callingUid, callingPid);
            attrs.inputFeatures = sanitizeSpyWindow(attrs.inputFeatures, win.getName(), callingUid,
                    callingPid);
            win.setRequestedVisibilities(requestedVisibilities);

            res = displayPolicy.validateAddingWindowLw(attrs, callingPid, callingUid);
            if (res != ADD_OKAY) {
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
                    ProtoLog.w(WM_ERROR, "Adding more than one toast window for UID at a time.");
                    return WindowManagerGlobal.ADD_DUPLICATE_ADD;
                }
                // Make sure this happens before we moved focus as one can make the
                // toast focusable to force it not being hidden after the timeout.
                // Focusable toasts are always timed out to prevent a focused app to
                // show a focusable toasts while it has focus which will be kept on
                // the screen after the activity goes away.
                if (addToastWindowRequiresToken
                        || (attrs.flags & FLAG_NOT_FOCUSABLE) == 0
                        || displayContent.mCurrentFocus == null
                        || displayContent.mCurrentFocus.mOwnerUid != callingUid) {
                    mH.sendMessageDelayed(
                            mH.obtainMessage(H.WINDOW_HIDE_TIMEOUT, win),
                            win.mAttrs.hideTimeoutMilliseconds);
                }
            }

            // Switch to listen to the {@link WindowToken token}'s configuration changes when
            // adding a window to the window context. Filter sub window type here because the sub
            // window must be attached to the parent window, which is attached to the window context
            // created window token.
            if (!win.isChildWindow()
                    && mWindowContextListenerController.hasListener(windowContextToken)) {
                final int windowContextType = mWindowContextListenerController
                        .getWindowType(windowContextToken);
                final Bundle options = mWindowContextListenerController
                        .getOptions(windowContextToken);
                if (type != windowContextType) {
                    ProtoLog.w(WM_ERROR, "Window types in WindowContext and"
                            + " LayoutParams.type should match! Type from LayoutParams is %d,"
                            + " but type from WindowContext is %d", type, windowContextType);
                    // We allow WindowProviderService to add window other than windowContextType,
                    // but the WindowProviderService won't be associated with the window's
                    // WindowToken.
                    if (!isWindowProviderService(options)) {
                        return WindowManagerGlobal.ADD_INVALID_TYPE;
                    }
                } else {
                    mWindowContextListenerController.registerWindowContainerListener(
                            windowContextToken, token, callingUid, type, options);
                }
            }

            // From now on, no exceptions or errors allowed!

            res = ADD_OKAY;

            if (mUseBLAST) {
                res |= WindowManagerGlobal.ADD_FLAG_USE_BLAST;
            }

            if (displayContent.mCurrentFocus == null) {
                displayContent.mWinAddedSinceNullFocus.add(win);
            }

            if (excludeWindowTypeFromTapOutTask(type)) {
                displayContent.mTapExcludedWindows.add(win);
            }

            win.attach();
            mWindowMap.put(client.asBinder(), win);
            win.initAppOpsState();

            final boolean suspended = mPmInternal.isPackageSuspended(win.getOwningPackage(),
                    UserHandle.getUserId(win.getOwningUid()));
            win.setHiddenWhileSuspended(suspended);

            final boolean hideSystemAlertWindows = !mHidingNonSystemOverlayWindows.isEmpty();
            win.setForceHideNonSystemOverlayWindowIfNeeded(hideSystemAlertWindows);

            boolean imMayMove = true;

            win.mToken.addWindow(win);
            displayPolicy.addWindowLw(win, attrs);
            displayPolicy.setDropInputModePolicy(win, win.mAttrs);
            if (type == TYPE_APPLICATION_STARTING && activity != null) {
                activity.attachStartingWindow(win);
                ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "addWindow: %s startingWindow=%s",
                        activity, win);
            } else if (type == TYPE_INPUT_METHOD
                    // IME window is always touchable.
                    // Ignore non-touchable windows e.g. Stylus InkWindow.java.
                    && (win.getAttrs().flags & FLAG_NOT_TOUCHABLE) == 0) {
                displayContent.setInputMethodWindowLocked(win);
                imMayMove = false;
            } else if (type == TYPE_INPUT_METHOD_DIALOG) {
                displayContent.computeImeTarget(true /* updateImeTarget */);
                imMayMove = false;
            } else {
                if (type == TYPE_WALLPAPER) {
                    displayContent.mWallpaperController.clearLastWallpaperTimeoutTime();
                    displayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
                } else if (win.hasWallpaper()) {
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

            final WindowStateAnimator winAnimator = win.mWinAnimator;
            winAnimator.mEnterAnimationPending = true;
            winAnimator.mEnteringAnimation = true;
            // Check if we need to prepare a transition for replacing window first.
            if (!win.mTransitionController.isShellTransitionsEnabled()
                    && activity != null && activity.isVisible()
                    && !prepareWindowReplacementTransition(activity)) {
                // If not, check if need to set up a dummy transition during display freeze
                // so that the unfreeze wait for the apps to draw. This might be needed if
                // the app is relaunching.
                prepareNoneTransitionForRelaunching(activity);
            }

            if (displayPolicy.areSystemBarsForcedShownLw()) {
                res |= WindowManagerGlobal.ADD_FLAG_ALWAYS_CONSUME_SYSTEM_BARS;
            }

            if (mInTouchMode) {
                res |= WindowManagerGlobal.ADD_FLAG_IN_TOUCH_MODE;
            }
            if (win.mActivityRecord == null || win.mActivityRecord.isClientVisible()) {
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

            ProtoLog.v(WM_DEBUG_ADD_REMOVE, "addWindow: New client %s"
                    + ": window=%s Callers=%s", client.asBinder(), win, Debug.getCallers(5));

            if (win.isVisibleRequestedOrAdding() && displayContent.updateOrientation()) {
                displayContent.sendNewConfiguration();
            }

            // This window doesn't have a frame yet. Don't let this window cause the insets change.
            displayContent.getInsetsStateController().updateAboveInsetsState(
                    false /* notifyInsetsChanged */);

            outInsetsState.set(win.getCompatInsetsState(), win.isClientLocal());
            getInsetsSourceControls(win, outActiveControls);

            if (win.mLayoutAttached) {
                outAttachedFrame.set(win.getParentWindow().getCompatFrame());
            } else {
                // Make this invalid which indicates a null attached frame.
                outAttachedFrame.set(0, 0, -1, -1);
            }
        }

        Binder.restoreCallingIdentity(origId);

        return res;
    }

    private boolean unprivilegedAppCanCreateTokenWith(WindowState parentWindow,
            int callingUid, int type, int rootType, IBinder tokenForLog, String packageName) {
        if (rootType >= FIRST_APPLICATION_WINDOW && rootType <= LAST_APPLICATION_WINDOW) {
            ProtoLog.w(WM_ERROR, "Attempted to add application window with unknown token "
                    + "%s.  Aborting.", tokenForLog);
            return false;
        }
        if (rootType == TYPE_INPUT_METHOD) {
            ProtoLog.w(WM_ERROR, "Attempted to add input method window with unknown token "
                    + "%s.  Aborting.", tokenForLog);
            return false;
        }
        if (rootType == TYPE_VOICE_INTERACTION) {
            ProtoLog.w(WM_ERROR,
                    "Attempted to add voice interaction window with unknown token "
                            + "%s.  Aborting.", tokenForLog);
            return false;
        }
        if (rootType == TYPE_WALLPAPER) {
            ProtoLog.w(WM_ERROR, "Attempted to add wallpaper window with unknown token "
                    + "%s.  Aborting.", tokenForLog);
            return false;
        }
        if (rootType == TYPE_QS_DIALOG) {
            ProtoLog.w(WM_ERROR, "Attempted to add QS dialog window with unknown token "
                    + "%s.  Aborting.", tokenForLog);
            return false;
        }
        if (rootType == TYPE_ACCESSIBILITY_OVERLAY) {
            ProtoLog.w(WM_ERROR,
                    "Attempted to add Accessibility overlay window with unknown token "
                            + "%s.  Aborting.", tokenForLog);
            return false;
        }
        if (type == TYPE_TOAST) {
            // Apps targeting SDK above N MR1 cannot arbitrary add toast windows.
            if (doesAddToastWindowRequireToken(packageName, callingUid, parentWindow)) {
                ProtoLog.w(WM_ERROR, "Attempted to add a toast window with unknown token "
                        + "%s.  Aborting.", tokenForLog);
                return false;
            }
        }
        return true;
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

        return mRoot.getDisplayContentOrCreate(displayId);
    }

    private boolean doesAddToastWindowRequireToken(String packageName, int callingUid,
            WindowState attachedWindow) {
        // Try using the target SDK of the root window
        if (attachedWindow != null) {
            return attachedWindow.mActivityRecord != null
                    && attachedWindow.mActivityRecord.mTargetSdk >= Build.VERSION_CODES.O;
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
    private boolean prepareWindowReplacementTransition(ActivityRecord activity) {
        activity.clearAllDrawn();
        final WindowState replacedWindow = activity.getReplacingWindow();
        if (replacedWindow == null) {
            // We expect to already receive a request to remove the old window. If it did not
            // happen, let's just simply add a window.
            return false;
        }
        // We use the visible frame, because we want the animation to morph the window from what
        // was visible to the user to the final destination of the new window.
        final Rect frame = new Rect(replacedWindow.getFrame());
        final WindowManager.LayoutParams attrs = replacedWindow.mAttrs;
        frame.inset(replacedWindow.getInsetsStateWithVisibilityOverride().calculateVisibleInsets(
                frame, attrs.type, replacedWindow.getWindowingMode(), attrs.softInputMode,
                attrs.flags));
        // We treat this as if this activity was opening, so we can trigger the app transition
        // animation and piggy-back on existing transition animation infrastructure.
        final DisplayContent dc = activity.getDisplayContent();
        dc.mOpeningApps.add(activity);
        dc.prepareAppTransition(TRANSIT_RELAUNCH);
        dc.mAppTransition.overridePendingAppTransitionClipReveal(frame.left, frame.top,
                frame.width(), frame.height());
        dc.executeAppTransition();
        return true;
    }

    private void prepareNoneTransitionForRelaunching(ActivityRecord activity) {
        // Set up a none-transition and add the app to opening apps, so that the display
        // unfreeze wait for the apps to be drawn.
        // Note that if the display unfroze already because app unfreeze timed out,
        // we don't set up the transition anymore and just let it go.
        final DisplayContent dc = activity.getDisplayContent();
        if (mDisplayFrozen && !dc.mOpeningApps.contains(activity) && activity.isRelaunching()) {
            dc.mOpeningApps.add(activity);
            dc.prepareAppTransition(TRANSIT_NONE);
            dc.executeAppTransition();
        }
    }

    /**
     * Set whether screen capture is disabled for all windows of a specific user from
     * the device policy cache.
     */
    @Override
    public void refreshScreenCaptureDisabled() {
        int callingUid = Binder.getCallingUid();
        if (callingUid != SYSTEM_UID) {
            throw new SecurityException("Only system can call refreshScreenCaptureDisabled.");
        }

        synchronized (mGlobalLock) {
            // Refresh secure surface for all windows.
            mRoot.refreshSecureSurfaceState();
        }
    }

    void removeWindow(Session session, IWindow client) {
        synchronized (mGlobalLock) {
            WindowState win = windowForClientLocked(session, client, false);
            if (win != null) {
                win.removeIfPossible();
                return;
            }

            // Remove embedded window map if the token belongs to an embedded window
            mEmbeddedWindowController.remove(client);
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
        ProtoLog.v(WM_DEBUG_ADD_REMOVE, "postWindowRemoveCleanupLocked: %s", win);
        mWindowMap.remove(win.mClient.asBinder());

        final DisplayContent dc = win.getDisplayContent();
        dc.getDisplayRotation().markForSeamlessRotation(win, false /* seamlesslyRotated */);

        win.resetAppOpsState();

        if (dc.mCurrentFocus == null) {
            dc.mWinRemovedSinceNullFocus.add(win);
        }
        mEmbeddedWindowController.onWindowRemoved(win);
        mResizingWindows.remove(win);
        updateNonSystemOverlayWindowsVisibilityIfNeeded(win, false /* surfaceShown */);
        mWindowsChanged = true;
        ProtoLog.v(WM_DEBUG_WINDOW_MOVEMENT, "Final remove of window: %s", win);

        final DisplayContent displayContent = win.getDisplayContent();
        if (displayContent.mInputMethodWindow == win) {
            displayContent.setInputMethodWindowLocked(null);
        }

        final WindowToken token = win.mToken;
        ProtoLog.v(WM_DEBUG_ADD_REMOVE, "Removing %s from %s", win, token);
        // Window will already be removed from token before this post clean-up method is called.
        if (token.isEmpty() && !token.mPersistOnEmpty) {
            token.removeImmediately();
        }

        if (win.mActivityRecord != null) {
            win.mActivityRecord.postWindowRemoveStartingWindowCleanup(win);
        }

        if (win.mAttrs.type == TYPE_WALLPAPER) {
            dc.mWallpaperController.clearLastWallpaperTimeoutTime();
            dc.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
        } else if (win.hasWallpaper()) {
            dc.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
        }

        if (dc != null && !mWindowPlacerLocked.isInLayout()) {
            dc.assignWindowLayers(true /* setLayoutNeeded */);
            mWindowPlacerLocked.performSurfacePlacement();
            if (win.mActivityRecord != null) {
                win.mActivityRecord.updateReportedVisibilityLocked();
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

    static void logWithStack(String tag, String s) {
        RuntimeException e = null;
        if (SHOW_STACK_CRAWLS) {
            e = new RuntimeException();
            e.fillInStackTrace();
        }
        Slog.i(tag, s, e);
    }

    void clearTouchableRegion(Session session, IWindow client) {
        int uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                WindowState w = windowForClientLocked(session, client, false);
                w.clearClientTouchableRegion();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void setInsetsWindow(Session session, IWindow client, int touchableInsets, Rect contentInsets,
            Rect visibleInsets, Region touchableRegion) {
        int uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
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
                    w.updateSourceFrame(w.getFrame());
                    mWindowPlacerLocked.performSurfacePlacement();
                    w.getDisplayContent().getInputMonitor().updateInputWindowsLw(true);

                    // We need to report touchable region changes to accessibility.
                    if (mAccessibilityController.hasCallbacks()) {
                        mAccessibilityController.onSomeWindowResizedOrMovedWithCallingUid(
                                uid, w.getDisplayContent().getDisplayId());
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void onRectangleOnScreenRequested(IBinder token, Rect rectangle) {
        final AccessibilityController.AccessibilityControllerInternalImpl a11yControllerInternal =
                AccessibilityController.getAccessibilityControllerInternal(this);
        synchronized (mGlobalLock) {
            if (a11yControllerInternal.hasWindowManagerEventDispatcher()) {
                WindowState window = mWindowMap.get(token);
                if (window != null) {
                    a11yControllerInternal.onRectangleOnScreenRequested(
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

    private boolean hasStatusBarPermission(int pid, int uid) {
        return mContext.checkPermission(permission.STATUS_BAR, pid, uid)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns whether this window can proceed with drawing or needs to retry later.
     */
    public boolean cancelDraw(Session session, IWindow client) {
        synchronized (mGlobalLock) {
            final WindowState win = windowForClientLocked(session, client, false);
            if (win == null) {
                return false;
            }

            return win.cancelAndRedraw();
        }
    }

    public int relayoutWindow(Session session, IWindow client, LayoutParams attrs,
            int requestedWidth, int requestedHeight, int viewVisibility, int flags,
            ClientWindowFrames outFrames, MergedConfiguration mergedConfiguration,
            SurfaceControl outSurfaceControl, InsetsState outInsetsState,
            InsetsSourceControl[] outActiveControls, Bundle outSyncIdBundle) {
        Arrays.fill(outActiveControls, null);
        int result = 0;
        boolean configChanged;
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        synchronized (mGlobalLock) {
            final WindowState win = windowForClientLocked(session, client, false);
            if (win == null) {
                return 0;
            }

            if (win.cancelAndRedraw()) {
                result |= RELAYOUT_RES_CANCEL_AND_REDRAW;
            }

            final DisplayContent displayContent = win.getDisplayContent();
            final DisplayPolicy displayPolicy = displayContent.getDisplayPolicy();

            WindowStateAnimator winAnimator = win.mWinAnimator;
            if (viewVisibility != View.GONE) {
                win.setRequestedSize(requestedWidth, requestedHeight);
            }

            int attrChanges = 0;
            int flagChanges = 0;
            int privateFlagChanges = 0;
            if (attrs != null) {
                displayPolicy.adjustWindowParamsLw(win, attrs);
                attrs.flags = sanitizeFlagSlippery(attrs.flags, win.getName(), uid, pid);
                attrs.inputFeatures = sanitizeSpyWindow(attrs.inputFeatures, win.getName(), uid,
                        pid);
                int disableFlags =
                        (attrs.systemUiVisibility | attrs.subtreeSystemUiVisibility) & DISABLE_MASK;
                if (disableFlags != 0 && !hasStatusBarPermission(pid, uid)) {
                    disableFlags = 0;
                }
                win.mDisableFlags = disableFlags;
                if (win.mAttrs.type != attrs.type) {
                    throw new IllegalArgumentException(
                            "Window type can not be changed after the window is added.");
                }
                if (!(win.mAttrs.providedInsets == null && attrs.providedInsets == null)) {
                    if (win.mAttrs.providedInsets == null || attrs.providedInsets == null
                            || (win.mAttrs.providedInsets.length != attrs.providedInsets.length)) {
                        throw new IllegalArgumentException(
                                "Insets types can not be changed after the window is added.");
                    } else {
                        final int insetsTypes = attrs.providedInsets.length;
                        for (int i = 0; i < insetsTypes; i++) {
                            if (win.mAttrs.providedInsets[i].type != attrs.providedInsets[i].type) {
                                throw new IllegalArgumentException(
                                        "Insets types can not be changed after the window is "
                                                + "added.");
                            }
                        }
                    }
                }

                flagChanges = win.mAttrs.flags ^ attrs.flags;
                privateFlagChanges = win.mAttrs.privateFlags ^ attrs.privateFlags;
                attrChanges = win.mAttrs.copyFrom(attrs);
                if ((attrChanges & (WindowManager.LayoutParams.LAYOUT_CHANGED
                        | WindowManager.LayoutParams.SYSTEM_UI_VISIBILITY_CHANGED)) != 0) {
                    win.mLayoutNeeded = true;
                }
                if (win.mActivityRecord != null && ((flagChanges & FLAG_SHOW_WHEN_LOCKED) != 0
                        || (flagChanges & FLAG_DISMISS_KEYGUARD) != 0)) {
                    win.mActivityRecord.checkKeyguardFlagsChanged();
                }
                if (((attrChanges & LayoutParams.ACCESSIBILITY_TITLE_CHANGED) != 0)
                        && (mAccessibilityController.hasCallbacks())) {
                    // No move or resize, but the controller checks for title changes as well
                    mAccessibilityController.onSomeWindowResizedOrMovedWithCallingUid(
                            uid, win.getDisplayContent().getDisplayId());
                }

                if ((privateFlagChanges & SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS) != 0) {
                    updateNonSystemOverlayWindowsVisibilityIfNeeded(
                            win, win.mWinAnimator.getShown());
                }
                if ((attrChanges & (WindowManager.LayoutParams.PRIVATE_FLAGS_CHANGED)) != 0) {
                    winAnimator.setColorSpaceAgnosticLocked((win.mAttrs.privateFlags
                            & WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC) != 0);
                }
                if (win.mActivityRecord != null
                        && !displayContent.mDwpcHelper.keepActivityOnWindowFlagsChanged(
                                win.mActivityRecord.info, flagChanges, privateFlagChanges)) {
                    mH.sendMessage(mH.obtainMessage(H.REPARENT_TASK_TO_DEFAULT_DISPLAY,
                            win.mActivityRecord.getTask()));
                    Slog.w(TAG_WM, "Activity " + win.mActivityRecord + " window flag changed,"
                            + " can't remain on display " + displayContent.getDisplayId());
                    return 0;
                }
            }

            if (DEBUG_LAYOUT) Slog.v(TAG_WM, "Relayout " + win + ": viewVisibility=" + viewVisibility
                    + " req=" + requestedWidth + "x" + requestedHeight + " " + win.mAttrs);
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
            boolean focusMayChange = win.mViewVisibility != viewVisibility
                    || ((flagChanges & FLAG_NOT_FOCUSABLE) != 0)
                    || (!win.mRelayoutCalled);

            boolean wallpaperMayMove = win.mViewVisibility != viewVisibility
                    && win.hasWallpaper();
            wallpaperMayMove |= (flagChanges & FLAG_SHOW_WALLPAPER) != 0;
            if ((flagChanges & FLAG_SECURE) != 0 && winAnimator.mSurfaceController != null) {
                winAnimator.mSurfaceController.setSecure(win.isSecureLocked());
            }

            win.mRelayoutCalled = true;
            win.mInRelayout = true;

            win.setViewVisibility(viewVisibility);
            ProtoLog.i(WM_DEBUG_SCREEN_ON,
                    "Relayout %s: oldVis=%d newVis=%d. %s", win, oldVisibility,
                            viewVisibility, new RuntimeException().fillInStackTrace());


            win.setDisplayLayoutNeeded();
            win.mGivenInsetsPending = (flags & WindowManagerGlobal.RELAYOUT_INSETS_PENDING) != 0;

            // We should only relayout if the view is visible, it is a starting window, or the
            // associated appToken is not hidden.
            final boolean shouldRelayout = viewVisibility == View.VISIBLE &&
                    (win.mActivityRecord == null || win.mAttrs.type == TYPE_APPLICATION_STARTING
                            || win.mActivityRecord.isClientVisible());

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
                    // When FLAG_SHOW_WALLPAPER flag is removed from a window, we usually set a flag
                    // in DC#pendingLayoutChanges and update the wallpaper target later.
                    // However it's possible that FLAG_SHOW_WALLPAPER flag is removed from a window
                    // when the window is about to exit, so we update the wallpaper target
                    // immediately here. Otherwise this window will be stuck in exiting and its
                    // surface remains on the screen.
                    // TODO(b/189856716): Allow destroying surface even if it belongs to the
                    //  keyguard target.
                    if (wallpaperMayMove) {
                        displayContent.mWallpaperController.adjustWallpaperWindows();
                    }
                    focusMayChange = tryStartExitingAnimation(win, winAnimator, focusMayChange);
                }
            }

            // Create surfaceControl before surface placement otherwise layout will be skipped
            // (because WS.isGoneForLayout() is true when there is no surface.
            if (shouldRelayout) {
                try {
                    result = createSurfaceControl(outSurfaceControl, result, win, winAnimator);
                } catch (Exception e) {
                    displayContent.getInputMonitor().updateInputWindowsLw(true /*force*/);

                    ProtoLog.w(WM_ERROR,
                            "Exception thrown when creating surface for client %s (%s). %s",
                            client, win.mAttrs.getTitle(), e);
                    Binder.restoreCallingIdentity(origId);
                    return 0;
                }
            }

            // We may be deferring layout passes at the moment, but since the client is interested
            // in the new out values right now we need to force a layout.
            mWindowPlacerLocked.performSurfacePlacement(true /* force */);

            if (shouldRelayout) {
                Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "relayoutWindow: viewVisibility_1");

                result = win.relayoutVisibleWindow(result);

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

            if (win.mActivityRecord != null) {
                displayContent.mUnknownAppVisibilityController.notifyRelayouted(win.mActivityRecord);
            }

            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "relayoutWindow: updateOrientation");
            configChanged = displayContent.updateOrientation();
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);

            if (toBeDisplayed && win.mIsWallpaper) {
                displayContent.mWallpaperController.updateWallpaperOffset(win, false /* sync */);
            }
            if (win.mActivityRecord != null) {
                win.mActivityRecord.updateReportedVisibilityLocked();
            }
            if (displayPolicy.areSystemBarsForcedShownLw()) {
                result |= WindowManagerGlobal.RELAYOUT_RES_CONSUME_ALWAYS_SYSTEM_BARS;
            }
            if (!win.isGoneForLayout()) {
                win.mResizedWhileGone = false;
            }

            win.fillClientWindowFramesAndConfiguration(outFrames, mergedConfiguration,
                    false /* useLatestConfig */, shouldRelayout);

            // Set resize-handled here because the values are sent back to the client.
            win.onResizeHandled();

            outInsetsState.set(win.getCompatInsetsState(), win.isClientLocal());
            if (DEBUG) {
                Slog.v(TAG_WM, "Relayout given client " + client.asBinder()
                        + ", requestedWidth=" + requestedWidth
                        + ", requestedHeight=" + requestedHeight
                        + ", viewVisibility=" + viewVisibility
                        + "\nRelayout returning frame=" + outFrames.frame
                        + ", surface=" + outSurfaceControl);
            }

            ProtoLog.v(WM_DEBUG_FOCUS, "Relayout of %s: focusMayChange=%b",
                    win, focusMayChange);

            if (DEBUG_LAYOUT) {
                Slog.v(TAG_WM, "Relayout complete " + win + ": outFrames=" + outFrames);
            }
            win.mInRelayout = false;

            if (mUseBLASTSync && win.useBLASTSync() && viewVisibility != View.GONE
                    && (win.mSyncSeqId > win.mLastSeqIdSentToRelayout)) {
                win.markRedrawForSyncReported();

                win.mLastSeqIdSentToRelayout = win.mSyncSeqId;
                outSyncIdBundle.putInt("seqid", win.mSyncSeqId);
                // Only mark mAlreadyRequestedSync if there's an explicit sync request, and not if
                // we're syncing due to mDrawHandlers
                if (win.mSyncState != SYNC_STATE_NONE) {
                    win.mAlreadyRequestedSync = true;
                }
            } else {
                outSyncIdBundle.putInt("seqid", -1);
            }

            if (configChanged) {
                Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER,
                        "relayoutWindow: postNewConfigurationToHandler");
                displayContent.sendNewConfiguration();
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            }
            getInsetsSourceControls(win, outActiveControls);
        }

        Binder.restoreCallingIdentity(origId);
        return result;
    }

    private void getInsetsSourceControls(WindowState win, InsetsSourceControl[] outControls) {
        final InsetsSourceControl[] controls =
                win.getDisplayContent().getInsetsStateController().getControlsForDispatch(win);
        if (controls != null) {
            final int length = Math.min(controls.length, outControls.length);
            for (int i = 0; i < length; i++) {
                // We will leave the critical section before returning the leash to the client,
                // so we need to copy the leash to prevent others release the one that we are
                // about to return.
                if (controls[i] != null) {
                    // This source control is an extra copy if the client is not local. By setting
                    // PARCELABLE_WRITE_RETURN_VALUE, the leash will be released at the end of
                    // SurfaceControl.writeToParcel.
                    outControls[i] = new InsetsSourceControl(controls[i]);
                    outControls[i].setParcelableFlags(PARCELABLE_WRITE_RETURN_VALUE);
                }
            }
        }
    }

    private boolean tryStartExitingAnimation(WindowState win, WindowStateAnimator winAnimator,
            boolean focusMayChange) {
        // Try starting an animation; if there isn't one, we
        // can destroy the surface right away.
        int transit = WindowManagerPolicy.TRANSIT_EXIT;
        if (win.mAttrs.type == TYPE_APPLICATION_STARTING) {
            transit = WindowManagerPolicy.TRANSIT_PREVIEW_DONE;
        }

        String reason = null;
        if (win.isWinVisibleLw() && winAnimator.applyAnimationLocked(transit, false)) {
            reason = "applyAnimation";
            focusMayChange = true;
            win.mAnimatingExit = true;
        } else if (win.mDisplayContent.okToAnimate() && win.isExitAnimationRunningSelfOrParent()) {
            // Currently in a hide animation... turn this into
            // an exit.
            win.mAnimatingExit = true;
        } else if (win.mDisplayContent.okToAnimate()
                && win.mDisplayContent.mWallpaperController.isWallpaperTarget(win)
                && win.mAttrs.type != TYPE_NOTIFICATION_SHADE) {
            reason = "isWallpaperTarget";
            // If the wallpaper is currently behind this app window, we need to change both of them
            // inside of a transaction to avoid artifacts.
            // For NotificationShade, sysui is in charge of running window animation and it updates
            // the client view visibility only after both NotificationShade and the wallpaper are
            // hidden. So we don't need to care about exit animation, but can destroy its surface
            // immediately.
            win.mAnimatingExit = true;
        } else {
            boolean stopped = win.mActivityRecord == null || win.mActivityRecord.mAppStopped;
            // We set mDestroying=true so ActivityRecord#notifyAppStopped in-to destroy surfaces
            // will later actually destroy the surface if we do not do so here. Normally we leave
            // this to the exit animation.
            win.mDestroying = true;
            win.destroySurface(false, stopped);
        }
        if (reason != null) {
            ProtoLog.d(WM_DEBUG_ANIM, "Set animatingExit: reason=startExitingAnimation/%s win=%s",
                    reason, win);
        }
        if (mAccessibilityController.hasCallbacks()) {
            mAccessibilityController.onWindowTransition(win, transit);
        }

        return focusMayChange;
    }

    private int createSurfaceControl(SurfaceControl outSurfaceControl, int result,
            WindowState win, WindowStateAnimator winAnimator) {
        if (!win.mHasSurface) {
            result |= RELAYOUT_RES_SURFACE_CHANGED;
        }

        WindowSurfaceController surfaceController;
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "createSurfaceControl");
            surfaceController = winAnimator.createSurfaceLocked();
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
        if (surfaceController != null) {
            surfaceController.getSurfaceControl(outSurfaceControl);
            ProtoLog.i(WM_SHOW_TRANSACTIONS, "OUT SURFACE %s: copied", outSurfaceControl);

        } else {
            // For some reason there isn't a surface.  Clear the
            // caller's object so they see the same state.
            ProtoLog.w(WM_ERROR, "Failed to create surface control for %s", win);
            outSurfaceControl.release();
        }

        return result;
    }

    int updateViewVisibility(Session session, IWindow client, LayoutParams attrs,
            int viewVisibility, MergedConfiguration outMergedConfiguration,
            SurfaceControl outSurfaceControl, InsetsState outInsetsState,
            InsetsSourceControl[] outActiveControls) {
        // TODO(b/161810301): Finish the implementation.
        return 0;
    }

    void updateWindowLayout(Session session, IWindow client, LayoutParams attrs, int flags,
            ClientWindowFrames clientWindowFrames, int requestedWidth, int requestedHeight) {
        final long origId = Binder.clearCallingIdentity();
        synchronized (mGlobalLock) {
            final WindowState win = windowForClientLocked(session, client, false);
            if (win == null) {
                return;
            }
            win.setFrames(clientWindowFrames, requestedWidth, requestedHeight);

            // TODO(b/161810301): Finish the implementation.
        }
        Binder.restoreCallingIdentity(origId);
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

    void finishDrawingWindow(Session session, IWindow client,
            @Nullable SurfaceControl.Transaction postDrawTransaction, int seqId) {
        if (postDrawTransaction != null) {
            postDrawTransaction.sanitize();
        }

        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                WindowState win = windowForClientLocked(session, client, false);
                ProtoLog.d(WM_DEBUG_ADD_REMOVE, "finishDrawingWindow: %s mDrawState=%s",
                        win, (win != null ? win.mWinAnimator.drawStateToString() : "null"));
                if (win != null && win.finishDrawing(postDrawTransaction, seqId)) {
                    if (win.hasWallpaper()) {
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
        return checkCallingPermission(permission, func, true /* printLog */);
    }

    boolean checkCallingPermission(String permission, String func, boolean printLog) {
        if (Binder.getCallingPid() == MY_PID) {
            return true;
        }

        if (mContext.checkCallingPermission(permission)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        if (printLog) {
            ProtoLog.w(WM_ERROR, "Permission Denial: %s from pid=%d, uid=%d requires %s",
                    func, Binder.getCallingPid(), Binder.getCallingUid(), permission);
        }
        return false;
    }

    @Override
    public void addWindowToken(@NonNull IBinder binder, int type, int displayId,
            @Nullable Bundle options) {
        if (!checkCallingPermission(MANAGE_APP_TOKENS, "addWindowToken()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized (mGlobalLock) {
            final DisplayContent dc = getDisplayContentOrCreate(displayId, null /* token */);
            if (dc == null) {
                ProtoLog.w(WM_ERROR, "addWindowToken: Attempted to add token: %s"
                        + " for non-exiting displayId=%d", binder, displayId);
                return;
            }

            WindowToken token = dc.getWindowToken(binder);
            if (token != null) {
                ProtoLog.w(WM_ERROR, "addWindowToken: Attempted to add binder token: %s"
                        + " for already created window token: %s"
                        + " displayId=%d", binder, token, displayId);
                return;
            }
            if (type == TYPE_WALLPAPER) {
                new WallpaperWindowToken(this, binder, true, dc,
                        true /* ownerCanManageAppTokens */, options);
            } else {
                new WindowToken.Builder(this, binder, type)
                        .setDisplayContent(dc)
                        .setPersistOnEmpty(true)
                        .setOwnerCanManageAppTokens(true)
                        .setOptions(options)
                        .build();
            }
        }
    }

    @Override
    public Configuration attachWindowContextToDisplayArea(IBinder clientToken, int
            type, int displayId, Bundle options) {
        if (clientToken == null) {
            throw new IllegalArgumentException("clientToken must not be null!");
        }
        final boolean callerCanManageAppTokens = checkCallingPermission(MANAGE_APP_TOKENS,
                "attachWindowContextToDisplayArea", false /* printLog */);
        final int callingUid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent dc = mRoot.getDisplayContentOrCreate(displayId);
                if (dc == null) {
                    ProtoLog.w(WM_ERROR, "attachWindowContextToDisplayArea: trying to attach"
                            + " to a non-existing display:%d", displayId);
                    return null;
                }
                // TODO(b/155340867): Investigate if we still need roundedCornerOverlay after
                // the feature b/155340867 is completed.
                final DisplayArea<?> da = dc.findAreaForWindowType(type, options,
                        callerCanManageAppTokens, false /* roundedCornerOverlay */);
                mWindowContextListenerController.registerWindowContainerListener(clientToken, da,
                        callingUid, type, options, false /* shouDispatchConfigWhenRegistering */);
                return da.getConfiguration();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void attachWindowContextToWindowToken(IBinder clientToken, IBinder token) {
        final boolean callerCanManageAppTokens = checkCallingPermission(MANAGE_APP_TOKENS,
                "attachWindowContextToWindowToken", false /* printLog */);
        final int callingUid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final WindowToken windowToken = mRoot.getWindowToken(token);
                if (windowToken == null) {
                    ProtoLog.w(WM_ERROR, "Then token:%s is invalid. It might be "
                            + "removed", token);
                    return;
                }
                final int type = mWindowContextListenerController.getWindowType(clientToken);
                if (type == INVALID_WINDOW_TYPE) {
                    throw new IllegalArgumentException("The clientToken:" + clientToken
                            + " should have been attached.");
                }
                if (type != windowToken.windowType) {
                    throw new IllegalArgumentException("The WindowToken's type should match"
                            + " the created WindowContext's type. WindowToken's type is "
                            + windowToken.windowType + ", while WindowContext's is " + type);
                }
                if (!mWindowContextListenerController.assertCallerCanModifyListener(clientToken,
                        callerCanManageAppTokens, callingUid)) {
                    return;
                }
                mWindowContextListenerController.registerWindowContainerListener(clientToken,
                        windowToken, callingUid, windowToken.windowType, windowToken.mOptions);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void detachWindowContextFromWindowContainer(IBinder clientToken) {
        final boolean callerCanManageAppTokens = checkCallingPermission(MANAGE_APP_TOKENS,
                "detachWindowContextFromWindowContainer", false /* printLog */);
        final int callingUid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                if (!mWindowContextListenerController.assertCallerCanModifyListener(clientToken,
                        callerCanManageAppTokens, callingUid)) {
                    return;
                }
                final WindowContainer wc = mWindowContextListenerController
                        .getContainer(clientToken);

                mWindowContextListenerController.unregisterWindowContainerListener(clientToken);

                final WindowToken token = wc.asWindowToken();
                if (token != null && token.isFromClient()) {
                    removeWindowToken(token.token, token.getDisplayContent().getDisplayId());
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public Configuration attachToDisplayContent(IBinder clientToken, int displayId) {
        if (clientToken == null) {
            throw new IllegalArgumentException("clientToken must not be null!");
        }
        final int callingUid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                // We use "getDisplayContent" instead of "getDisplayContentOrCreate" because
                // this method may be called in DisplayPolicy's constructor and may cause
                // infinite loop. In this scenario, we early return here and switch to do the
                // registration in DisplayContent#onParentChanged at DisplayContent initialization.
                final DisplayContent dc = mRoot.getDisplayContent(displayId);
                if (dc == null) {
                    if (Binder.getCallingPid() != MY_PID) {
                        throw new WindowManager.InvalidDisplayException("attachToDisplayContent: "
                                + "trying to attach to a non-existing display:" + displayId);
                    }
                    // Early return if this method is invoked from system process.
                    // See above comments for more detail.
                    return null;
                }

                mWindowContextListenerController.registerWindowContainerListener(clientToken, dc,
                        callingUid, INVALID_WINDOW_TYPE, null /* options */,
                        false /* shouDispatchConfigWhenRegistering */);
                return dc.getConfiguration();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /** Returns {@code true} if this binder is a registered window token. */
    @Override
    public boolean isWindowToken(IBinder binder) {
        synchronized (mGlobalLock) {
            return mRoot.getWindowToken(binder) != null;
        }

    }

    void removeWindowToken(IBinder binder, boolean removeWindows, boolean animateExit,
            int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent dc = mRoot.getDisplayContent(displayId);

            if (dc == null) {
                ProtoLog.w(WM_ERROR, "removeWindowToken: Attempted to remove token: %s"
                        + " for non-exiting displayId=%d", binder, displayId);
                return;
            }
            final WindowToken token = dc.removeWindowToken(binder, animateExit);
            if (token == null) {
                ProtoLog.w(WM_ERROR,
                        "removeWindowToken: Attempted to remove non-existing token: %s",
                        binder);
                return;
            }

            if (removeWindows) {
                token.removeAllWindowsIfPossible();
            }
            dc.getInputMonitor().updateInputWindowsLw(true /* force */);
        }
    }

    @Override
    public void removeWindowToken(IBinder binder, int displayId) {
        if (!checkCallingPermission(MANAGE_APP_TOKENS, "removeWindowToken()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }
        final long origId = Binder.clearCallingIdentity();
        try {
            removeWindowToken(binder, false /* removeWindows */, true /* animateExit */, displayId);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /** @see WindowManagerInternal#moveWindowTokenToDisplay(IBinder, int)  */
    public void moveWindowTokenToDisplay(IBinder binder, int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent dc = mRoot.getDisplayContentOrCreate(displayId);
            if (dc == null) {
                ProtoLog.w(WM_ERROR, "moveWindowTokenToDisplay: Attempted to move token: %s"
                        + " to non-exiting displayId=%d", binder, displayId);
                return;
            }
            final WindowToken token = mRoot.getWindowToken(binder);
            if (token == null) {
                ProtoLog.w(WM_ERROR,
                        "moveWindowTokenToDisplay: Attempted to move non-existing token: %s",
                        binder);
                return;
            }
            if (token.getDisplayContent() == dc) {
                ProtoLog.w(WM_ERROR,
                        "moveWindowTokenToDisplay: Cannot move to the original display "
                                + "for token: %s", binder);
                return;
            }
            dc.reParentWindowToken(token);
        }
    }

    // TODO(multi-display): remove when no default display use case.
    void prepareAppTransitionNone() {
        if (!checkCallingPermission(MANAGE_APP_TOKENS, "prepareAppTransition()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }
        getDefaultDisplayContentLocked().prepareAppTransition(TRANSIT_NONE);
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
            remoteAnimationAdapter.setCallingPidUid(Binder.getCallingPid(), Binder.getCallingUid());
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
    public void executeAppTransition() {
        if (!checkCallingPermission(MANAGE_APP_TOKENS, "executeAppTransition()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }
        getDefaultDisplayContentLocked().executeAppTransition();
    }

    void initializeRecentsAnimation(int targetActivityType,
            IRecentsAnimationRunner recentsAnimationRunner,
            RecentsAnimationController.RecentsAnimationCallbacks callbacks, int displayId,
            SparseBooleanArray recentTaskIds, ActivityRecord targetActivity) {
        mRecentsAnimationController = new RecentsAnimationController(this, recentsAnimationRunner,
                callbacks, displayId);
        mRoot.getDisplayContent(displayId).mAppTransition.updateBooster();
        mRecentsAnimationController.initialize(targetActivityType, recentTaskIds, targetActivity);
    }

    @VisibleForTesting
    void setRecentsAnimationController(RecentsAnimationController controller) {
        mRecentsAnimationController = controller;
    }

    RecentsAnimationController getRecentsAnimationController() {
        return mRecentsAnimationController;
    }

    void cancelRecentsAnimation(
            @RecentsAnimationController.ReorderMode int reorderMode, String reason) {
        if (mRecentsAnimationController != null) {
            // This call will call through to cleanupAnimation() below after the animation is
            // canceled
            mRecentsAnimationController.cancelAnimation(reorderMode, reason);
        }
    }


    void cleanupRecentsAnimation(@RecentsAnimationController.ReorderMode int reorderMode) {
        if (mRecentsAnimationController != null) {
            final RecentsAnimationController controller = mRecentsAnimationController;
            mRecentsAnimationController = null;
            controller.cleanupAnimation(reorderMode);
            // TODO(multi-display): currently only default display support recents animation.
            final DisplayContent dc = getDefaultDisplayContentLocked();
            if (dc.mAppTransition.isTransitionSet()) {
                dc.mSkipAppTransitionAnimation = true;
            }
            dc.forAllWindowContainers((wc) -> {
                if (wc.isAnimating(TRANSITION, ANIMATION_TYPE_APP_TRANSITION)) {
                    wc.cancelAnimation();
                }
            });
        }
    }

    boolean isRecentsAnimationTarget(ActivityRecord r) {
        return mRecentsAnimationController != null && mRecentsAnimationController.isTargetApp(r);
    }

    void setWindowOpaqueLocked(IBinder token, boolean isOpaque) {
        final ActivityRecord wtoken = mRoot.getActivityRecord(token);
        if (wtoken != null) {
            wtoken.setMainWindowOpaque(isOpaque);
        }
    }

    boolean isValidPictureInPictureAspectRatio(DisplayContent displayContent, float aspectRatio) {
        return displayContent.getPinnedTaskController().isValidPictureInPictureAspectRatio(
                aspectRatio);
    }

    boolean isValidExpandedPictureInPictureAspectRatio(DisplayContent displayContent,
            float aspectRatio) {
        return displayContent.getPinnedTaskController().isValidExpandedPictureInPictureAspectRatio(
                aspectRatio);
    }

    @Override
    public void notifyKeyguardTrustedChanged() {
        synchronized (mGlobalLock) {
            if (mAtmService.mKeyguardController.isKeyguardShowing(DEFAULT_DISPLAY)) {
                mRoot.ensureActivitiesVisible(null, 0, false /* preserveWindows */);
            }
        }
    }

    @Override
    public void screenTurningOff(int displayId, ScreenOffListener listener) {
        mTaskSnapshotController.screenTurningOff(displayId, listener);
    }

    @Override
    public void triggerAnimationFailsafe() {
        mH.sendEmptyMessage(H.ANIMATION_FAILSAFE);
    }

    @Override
    public void onKeyguardShowingAndNotOccludedChanged() {
        mH.sendEmptyMessage(H.RECOMPUTE_FOCUS);
        dispatchKeyguardLockedState();
    }

    @Override
    public void onPowerKeyDown(boolean isScreenOn) {
        final PooledConsumer c = PooledLambda.obtainConsumer(
                DisplayPolicy::onPowerKeyDown, PooledLambda.__(), isScreenOn);
        mRoot.forAllDisplayPolicies(c);
        c.recycle();
    }

    @Override
    public void onUserSwitched() {
        mSettingsObserver.updateSystemUiSettings(true /* handleChange */);
        synchronized (mGlobalLock) {
            // force a re-application of focused window sysui visibility on each display.
            mRoot.forAllDisplayPolicies(DisplayPolicy::resetSystemBarAttributes);
        }
    }

    @Override
    public void moveDisplayToTop(int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent != null && mRoot.getTopChild() != displayContent) {
                displayContent.getParent().positionChildAt(WindowContainer.POSITION_TOP,
                        displayContent, true /* includingParents */);
            }
        }
        syncInputTransactions(true /* waitForAnimations */);
    }

    @Override
    public boolean isAppTransitionStateIdle() {
        return getDefaultDisplayContentLocked().mAppTransition.isIdle();
    }


    // -------------------------------------------------------------
    // Misc IWindowSession methods
    // -------------------------------------------------------------

    /** Freeze the screen during a user-switch event. Called by UserController. */
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
                    startFreezingDisplay(exitAnim, enterAnim);
                    mH.removeMessages(H.CLIENT_FREEZE_TIMEOUT);
                    mH.sendEmptyMessageDelayed(H.CLIENT_FREEZE_TIMEOUT, 5000);
                } finally {
                    Binder.restoreCallingIdentity(origId);
                }
            }
        }
    }

    /**
     * No longer actively demand that the screen remain frozen.
     * Called by UserController after a user-switch.
     * This doesn't necessarily immediately unlock the screen; it just allows it if we're ready.
     */
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
        Objects.requireNonNull(token, "token is null");
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

        final long origId = Binder.clearCallingIdentity();
        try {
            return mPolicy.isKeyguardSecure(userId);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void dismissKeyguard(IKeyguardDismissCallback callback, CharSequence message) {
        if (!checkCallingPermission(permission.CONTROL_KEYGUARD, "dismissKeyguard")) {
            throw new SecurityException("Requires CONTROL_KEYGUARD permission");
        }
        if (mAtmService.mKeyguardController.isShowingDream()) {
            mAtmService.mTaskSupervisor.wakeUp("leaveDream");
        }
        synchronized (mGlobalLock) {
            mPolicy.dismissKeyguardLw(callback, message);
        }
    }

    @RequiresPermission(Manifest.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE)
    @Override
    public void addKeyguardLockedStateListener(IKeyguardLockedStateListener listener) {
        enforceSubscribeToKeyguardLockedStatePermission();
        boolean registered = mKeyguardLockedStateListeners.register(listener);
        if (!registered) {
            Slog.w(TAG, "Failed to register listener: " + listener);
        }
    }

    @RequiresPermission(Manifest.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE)
    @Override
    public void removeKeyguardLockedStateListener(IKeyguardLockedStateListener listener) {
        enforceSubscribeToKeyguardLockedStatePermission();
        mKeyguardLockedStateListeners.unregister(listener);
    }

    private void enforceSubscribeToKeyguardLockedStatePermission() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE,
                Manifest.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE
                        + " permission required to subscribe to keyguard locked state changes");
    }

    private void dispatchKeyguardLockedState() {
        mH.post(() -> {
            final boolean isKeyguardLocked = mPolicy.isKeyguardShowing();
            if (mDispatchedKeyguardLockedState == isKeyguardLocked) {
                return;
            }
            final int n = mKeyguardLockedStateListeners.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mKeyguardLockedStateListeners.getBroadcastItem(i).onKeyguardLockedStateChanged(
                            isKeyguardLocked);
                } catch (RemoteException e) {
                    // Handled by the RemoteCallbackList.
                }
            }
            mKeyguardLockedStateListeners.finishBroadcast();
            mDispatchedKeyguardLockedState = isKeyguardLocked;
        });
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

    @RequiresPermission(Manifest.permission.INTERNAL_SYSTEM_WINDOW)
    @Override
    public void showGlobalActions() {
        if (!checkCallingPermission(Manifest.permission.INTERNAL_SYSTEM_WINDOW,
                "showGlobalActions()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }
        mPolicy.showGlobalActions();
    }

    @Override
    public void closeSystemDialogs(String reason) {
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        if (!mAtmService.checkCanCloseSystemDialogs(callingPid, callingUid, null)) {
            return;
        }
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
            mRoot.switchUser(newUserId);
            mWindowPlacerLocked.performSurfacePlacement();

            // Notify whether the root docked task exists for the current user
            final DisplayContent displayContent = getDefaultDisplayContentLocked();

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
    boolean isCurrentProfile(int userId) {
        if (userId == mCurrentUserId) return true;
        for (int i = 0; i < mCurrentProfileIds.length; i++) {
            if (mCurrentProfileIds[i] == userId) return true;
        }
        return false;
    }

    public void enableScreenAfterBoot() {
        synchronized (mGlobalLock) {
            ProtoLog.i(WM_DEBUG_BOOT, "enableScreenAfterBoot: mDisplayEnabled=%b "
                            + "mForceDisplayEnabled=%b mShowingBootMessages=%b mSystemBooted=%b. "
                            + "%s",
                    mDisplayEnabled, mForceDisplayEnabled, mShowingBootMessages, mSystemBooted,
                    new RuntimeException("here").fillInStackTrace());
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
        ProtoLog.i(WM_DEBUG_BOOT, "enableScreenIfNeededLocked: mDisplayEnabled=%b "
                        + "mForceDisplayEnabled=%b mShowingBootMessages=%b mSystemBooted=%b. "
                        + "%s",
                mDisplayEnabled, mForceDisplayEnabled, mShowingBootMessages, mSystemBooted,
                new RuntimeException("here").fillInStackTrace());
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
            ProtoLog.w(WM_ERROR, "***** BOOT TIMEOUT: forcing display enabled");
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
            ProtoLog.i(WM_DEBUG_BOOT, "performEnableScreen: mDisplayEnabled=%b"
                            + " mForceDisplayEnabled=%b" + " mShowingBootMessages=%b"
                            + " mSystemBooted=%b. %s", mDisplayEnabled,
                    mForceDisplayEnabled, mShowingBootMessages, mSystemBooted,
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
            if (!mForceDisplayEnabled) {
                if (mBootWaitForWindowsStartTime < 0) {
                    // First time we will start waiting for all windows to be drawn.
                    mBootWaitForWindowsStartTime = SystemClock.elapsedRealtime();
                }
                for (int i = mRoot.getChildCount() - 1; i >= 0; i--) {
                    if (mRoot.getChildAt(i).shouldWaitForSystemDecorWindowsOnBoot()) {
                        return;
                    }
                }
                long waitTime = SystemClock.elapsedRealtime() - mBootWaitForWindowsStartTime;
                mBootWaitForWindowsStartTime = -1;
                if (waitTime > 10) {
                    ProtoLog.i(WM_DEBUG_BOOT,
                            "performEnableScreen: Waited %dms for all windows to be drawn",
                            waitTime);
                }
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
                ProtoLog.i(WM_DEBUG_BOOT, "performEnableScreen: Waiting for anim complete");
                return;
            }

            try {
                // TODO(b/221898546): remove the following and convert to jni
                IBinder surfaceFlinger = ServiceManager.getService("SurfaceFlingerAIDL");
                if (surfaceFlinger != null) {
                    ProtoLog.i(WM_ERROR, "******* TELLING SURFACE FLINGER WE ARE BOOTED!");
                    Parcel data = Parcel.obtain();
                    data.writeInterfaceToken("android.gui.ISurfaceComposer");
                    surfaceFlinger.transact(IBinder.FIRST_CALL_TRANSACTION, // BOOT_FINISHED
                            data, null, 0);
                    data.recycle();
                }
            } catch (RemoteException ex) {
                ProtoLog.e(WM_ERROR, "Boot completed: SurfaceFlinger is dead!");
            }

            EventLogTags.writeWmBootAnimationDone(SystemClock.uptimeMillis());
            Trace.asyncTraceEnd(TRACE_TAG_WINDOW_MANAGER, "Stop bootanim", 0);
            mDisplayEnabled = true;
            ProtoLog.i(WM_DEBUG_SCREEN_ON, "******************** ENABLING SCREEN!");

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
            ProtoLog.i(WM_DEBUG_BOOT, "checkBootAnimationComplete: Waiting for anim complete");
            return false;
        }
        ProtoLog.i(WM_DEBUG_BOOT, "checkBootAnimationComplete: Animation complete!");
        return true;
    }

    public void showBootMessage(final CharSequence msg, final boolean always) {
        boolean first = false;
        synchronized (mGlobalLock) {
            ProtoLog.i(WM_DEBUG_BOOT, "showBootMessage: msg=%s always=%b"
                            + " mAllowBootMessages=%b mShowingBootMessages=%b"
                            + " mSystemBooted=%b. %s", msg, always, mAllowBootMessages,
                    mShowingBootMessages, mSystemBooted,
                    new RuntimeException("here").fillInStackTrace());
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
        ProtoLog.i(WM_DEBUG_BOOT, "hideBootMessagesLocked: mDisplayEnabled=%b"
                        + " mForceDisplayEnabled=%b mShowingBootMessages=%b"
                        + " mSystemBooted=%b. %s", mDisplayEnabled, mForceDisplayEnabled,
                mShowingBootMessages, mSystemBooted,
                new RuntimeException("here").fillInStackTrace());
        if (mShowingBootMessages) {
            mShowingBootMessages = false;
            mPolicy.hideBootMessages();
        }
    }

    /**
     * Sets the touch mode state.
     *
     * To be able to change touch mode state, the caller must either own the focused window, or must
     * have the {@link android.Manifest.permission#MODIFY_TOUCH_MODE_STATE} permission. Instrumented
     * process, sourced with {@link android.Manifest.permission#MODIFY_TOUCH_MODE_STATE}, may switch
     * touch mode at any time.
     *
     * @param mode the touch mode to set
     */
    @Override // Binder call
    public void setInTouchMode(boolean mode) {
        synchronized (mGlobalLock) {
            if (mInTouchMode == mode) {
                return;
            }
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final boolean hasPermission =
                    mAtmService.instrumentationSourceHasPermission(pid, MODIFY_TOUCH_MODE_STATE)
                            || checkCallingPermission(MODIFY_TOUCH_MODE_STATE, "setInTouchMode()",
                                                      /* printlog= */ false);
            final long token = Binder.clearCallingIdentity();
            try {
                if (mInputManager.setInTouchMode(mode, pid, uid, hasPermission)) {
                    mInTouchMode = mode;
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    boolean getInTouchMode() {
        synchronized (mGlobalLock) {
            return mInTouchMode;
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

    public void showEmulatorDisplayOverlay() {
        synchronized (mGlobalLock) {

            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG_WM, ">>> showEmulatorDisplayOverlay");
            if (mEmulatorDisplayOverlay == null) {
                mEmulatorDisplayOverlay = new EmulatorDisplayOverlay(mContext,
                        getDefaultDisplayContentLocked(),
                        mPolicy.getWindowLayerFromTypeLw(WindowManager.LayoutParams.TYPE_POINTER)
                                * TYPE_LAYER_MULTIPLIER + 10, mTransaction);
            }
            mEmulatorDisplayOverlay.setVisibility(true, mTransaction);
            mTransaction.apply();
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

            if (SHOW_VERBOSE_TRANSACTIONS) Slog.i(TAG_WM, ">>> showStrictModeViolation");
            // TODO: Modify this to use the surface trace once it is not going baffling.
            // b/31532461
            // TODO(multi-display): support multiple displays
            if (mStrictModeFlash == null) {
                mStrictModeFlash = new StrictModeFlash(getDefaultDisplayContentLocked(),
                        mTransaction);
            }
            mStrictModeFlash.setVisibility(on, mTransaction);
            mTransaction.apply();
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

    @Override
    public SurfaceControl mirrorWallpaperSurface(int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent dc = mRoot.getDisplayContent(displayId);
            return dc.mWallpaperController.mirrorWallpaperSurface();
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
                bm = displayContent.screenshotDisplayLocked();
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

    /**
     * Retrieves a snapshot. If restoreFromDisk equals equals {@code true}, DO NOT HOLD THE WINDOW
     * MANAGER LOCK WHEN CALLING THIS METHOD!
     */
    public TaskSnapshot getTaskSnapshot(int taskId, int userId, boolean isLowResolution,
            boolean restoreFromDisk) {
        return mTaskSnapshotController.getSnapshot(taskId, userId, restoreFromDisk,
                isLowResolution);
    }

    /**
     * Generates and returns an up-to-date {@link Bitmap} for the specified taskId.
     *
     * @param taskId                  The task ID of the task for which a Bitmap is requested.
     * @param layerCaptureArgsBuilder A {@link SurfaceControl.LayerCaptureArgs.Builder} with
     *                                arguments for how to capture the Bitmap. The caller can
     *                                specify any arguments, but this method will ensure that the
     *                                specified task's SurfaceControl is used and the crop is set to
     *                                the bounds of that task.
     * @return The Bitmap, or null if no task with the specified ID can be found or the bitmap could
     * not be generated.
     */
    @Nullable
    public Bitmap captureTaskBitmap(int taskId,
            @NonNull SurfaceControl.LayerCaptureArgs.Builder layerCaptureArgsBuilder) {
        if (mTaskSnapshotController.shouldDisableSnapshots()) {
            return null;
        }

        synchronized (mGlobalLock) {
            final Task task = mRoot.anyTaskForId(taskId);
            if (task == null) {
                return null;
            }

            // The bounds returned by the task represent the task's position on the screen. However,
            // we need to specify a crop relative to the task's surface control. Therefore, shift
            // the task's bounds to 0,0 so that we have the correct size and position within the
            // task's surface control.
            task.getBounds(mTmpRect);
            mTmpRect.offsetTo(0, 0);

            final SurfaceControl sc = task.getSurfaceControl();
            final SurfaceControl.ScreenshotHardwareBuffer buffer = SurfaceControl.captureLayers(
                    layerCaptureArgsBuilder.setLayer(sc).setSourceCrop(mTmpRect).build());
            if (buffer == null) {
                Slog.w(TAG, "Could not get screenshot buffer for taskId: " + taskId);
                return null;
            }

            return buffer.asBitmap();
        }
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

    @Override
    public void setFixedToUserRotation(int displayId, int fixedToUserRotation) {
        if (!checkCallingPermission(android.Manifest.permission.SET_ORIENTATION,
                "setFixedToUserRotation()")) {
            throw new SecurityException("Requires SET_ORIENTATION permission");
        }
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent display = mRoot.getDisplayContent(displayId);
                if (display == null) {
                    Slog.w(TAG, "Trying to set fixed to user rotation for a missing display.");
                    return;
                }
                display.getDisplayRotation().setFixedToUserRotation(fixedToUserRotation);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    int getFixedToUserRotation(int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent display = mRoot.getDisplayContent(displayId);
            if (display == null) {
                Slog.w(TAG, "Trying to get fixed to user rotation for a missing display.");
                return -1;
            }
            return display.getDisplayRotation().getFixedToUserRotationMode();
        }
    }

    @Override
    public void setIgnoreOrientationRequest(int displayId, boolean ignoreOrientationRequest) {
        if (!checkCallingPermission(
                android.Manifest.permission.SET_ORIENTATION, "setIgnoreOrientationRequest()")) {
            throw new SecurityException("Requires SET_ORIENTATION permission");
        }

        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent display = mRoot.getDisplayContent(displayId);
                if (display == null) {
                    Slog.w(TAG, "Trying to setIgnoreOrientationRequest() for a missing display.");
                    return;
                }
                display.setIgnoreOrientationRequest(ignoreOrientationRequest);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    boolean getIgnoreOrientationRequest(int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent display = mRoot.getDisplayContent(displayId);
            if (display == null) {
                Slog.w(TAG, "Trying to getIgnoreOrientationRequest() for a missing display.");
                return false;
            }
            return display.getIgnoreOrientationRequest();
        }
    }

    /**
     * Controls whether ignore orientation request logic in {@link DisplayArea} is disabled
     * at runtime.
     *
     * <p>Note: this assumes that {@link #mGlobalLock} is held by the caller.
     *
     * @param isDisabled when {@code true}, the system always ignores the value of {@link
     *                   DisplayArea#getIgnoreOrientationRequest} and app requested orientation is
     *                   respected.
     */
    void setIsIgnoreOrientationRequestDisabled(boolean isDisabled) {
        if (isDisabled == mIsIgnoreOrientationRequestDisabled) {
            return;
        }
        mIsIgnoreOrientationRequestDisabled = isDisabled;
        for (int i = mRoot.getChildCount() - 1; i >= 0; i--) {
            mRoot.getChildAt(i).onIsIgnoreOrientationRequestDisabledChanged();
        }
    }

    /**
     * Whether the system ignores the value of {@link DisplayArea#getIgnoreOrientationRequest} and
     * app requested orientation is respected.
     *
     * <p>Note: this assumes that {@link #mGlobalLock} is held by the caller.
     */
    boolean isIgnoreOrientationRequestDisabled() {
        return mIsIgnoreOrientationRequestDisabled;
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

        final long origId = Binder.clearCallingIdentity();
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

        ProtoLog.v(WM_DEBUG_ORIENTATION, "thawRotation: mRotation=%d", getDefaultDisplayRotation());

        final long origId = Binder.clearCallingIdentity();
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
                Slog.w(TAG, "Trying to check if rotation is frozen on a missing display.");
                return false;
            }
            return display.getDisplayRotation().isRotationFrozen();
        }
    }

    int getDisplayUserRotation(int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent display = mRoot.getDisplayContent(displayId);
            if (display == null) {
                Slog.w(TAG, "Trying to get user rotation of a missing display.");
                return -1;
            }
            return display.getDisplayRotation().getUserRotation();
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
        ProtoLog.v(WM_DEBUG_ORIENTATION, "updateRotationUnchecked:"
                        + " alwaysSendConfiguration=%b forceRelayout=%b",
                alwaysSendConfiguration, forceRelayout);

        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "updateRotation");

        final long origId = Binder.clearCallingIdentity();

        try {
            synchronized (mGlobalLock) {
                boolean layoutNeeded = false;
                final int displayCount = mRoot.mChildren.size();
                for (int i = 0; i < displayCount; ++i) {
                    final DisplayContent displayContent = mRoot.mChildren.get(i);
                    Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "updateRotation: display");
                    final boolean rotationChanged = displayContent.updateRotationUnchecked();
                    Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);

                    if (rotationChanged) {
                        mAtmService.getTaskChangeNotificationController()
                                .notifyOnActivityRotation(displayContent.mDisplayId);
                    }

                    final boolean pendingRemoteDisplayChange = rotationChanged
                            && (displayContent.mRemoteDisplayChangeController
                                    .isWaitingForRemoteDisplayChange()
                            || displayContent.mTransitionController.isCollecting());
                    // Even if alwaysSend, we are waiting for a transition or remote to provide
                    // updated configuration, so we can't update configuration yet.
                    if (!pendingRemoteDisplayChange) {
                        if (!rotationChanged || forceRelayout) {
                            displayContent.setLayoutNeeded();
                            layoutNeeded = true;
                        }
                        if (rotationChanged || alwaysSendConfiguration) {
                            displayContent.sendNewConfiguration();
                        }
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
    public void setDisplayChangeWindowController(IDisplayChangeWindowController controller) {
        mAtmService.enforceTaskPermission("setDisplayWindowRotationController");
        try {
            synchronized (mGlobalLock) {
                if (mDisplayChangeController != null) {
                    mDisplayChangeController.asBinder().unlinkToDeath(
                            mDisplayChangeControllerDeath, 0);
                    mDisplayChangeController = null;
                }
                controller.asBinder().linkToDeath(mDisplayChangeControllerDeath, 0);
                mDisplayChangeController = controller;
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Unable to set rotation controller");
        }
    }

    @Override
    public SurfaceControl addShellRoot(int displayId, IWindow client,
            @WindowManager.ShellRootLayer int shellRootLayer) {
        if (mContext.checkCallingOrSelfPermission(MANAGE_APP_TOKENS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold permission " + MANAGE_APP_TOKENS);
        }
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent dc = mRoot.getDisplayContent(displayId);
                if (dc == null) {
                    return null;
                }
                return dc.addShellRoot(client, shellRootLayer);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void setShellRootAccessibilityWindow(int displayId,
            @WindowManager.ShellRootLayer int shellRootLayer, IWindow target) {
        if (mContext.checkCallingOrSelfPermission(MANAGE_APP_TOKENS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold permission " + MANAGE_APP_TOKENS);
        }
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent dc = mRoot.getDisplayContent(displayId);
                if (dc == null) {
                    return;
                }
                ShellRoot root = dc.mShellRoots.get(shellRootLayer);
                if (root == null) {
                    return;
                }
                root.setAccessibilityWindow(target);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void setDisplayWindowInsetsController(
            int displayId, IDisplayWindowInsetsController insetsController) {
        if (mContext.checkCallingOrSelfPermission(MANAGE_APP_TOKENS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold permission " + MANAGE_APP_TOKENS);
        }
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent dc = mRoot.getDisplayContent(displayId);
                if (dc == null) {
                    return;
                }
                dc.setRemoteInsetsController(insetsController);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void updateDisplayWindowRequestedVisibilities(int displayId, InsetsVisibilities vis) {
        if (mContext.checkCallingOrSelfPermission(MANAGE_APP_TOKENS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold permission " + MANAGE_APP_TOKENS);
        }
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent dc = mRoot.getDisplayContent(displayId);
                if (dc == null || dc.mRemoteInsetsControlTarget == null) {
                    return;
                }
                dc.mRemoteInsetsControlTarget.setRequestedVisibilities(vis);
                dc.getInsetsStateController().onInsetsModified(dc.mRemoteInsetsControlTarget);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
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
    public void registerSystemGestureExclusionListener(ISystemGestureExclusionListener listener,
            int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent == null) {
                throw new IllegalArgumentException("Trying to register visibility event "
                        + "for invalid display: " + displayId);
            }
            displayContent.registerSystemGestureExclusionListener(listener);
        }
    }

    @Override
    public void unregisterSystemGestureExclusionListener(ISystemGestureExclusionListener listener,
            int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent == null) {
                throw new IllegalArgumentException("Trying to register visibility event "
                        + "for invalid display: " + displayId);
            }
            displayContent.unregisterSystemGestureExclusionListener(listener);
        }
    }

    void reportSystemGestureExclusionChanged(Session session, IWindow window,
            List<Rect> exclusionRects) {
        synchronized (mGlobalLock) {
            final WindowState win = windowForClientLocked(session, window, true);
            if (win.setSystemGestureExclusion(exclusionRects)) {
                win.getDisplayContent().updateSystemGestureExclusion();
            }
        }
    }

    void reportKeepClearAreasChanged(Session session, IWindow window,
            List<Rect> restricted, List<Rect> unrestricted) {
        synchronized (mGlobalLock) {
            final WindowState win = windowForClientLocked(session, window, true);
            if (win.setKeepClearAreas(restricted, unrestricted)) {
                win.getDisplayContent().updateKeepClearAreas();
            }
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

        final long origId = Binder.clearCallingIdentity();
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
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                return mPolicy.getFoldedArea();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Registers a hierarchy listener that gets callbacks when the hierarchy changes. The listener's
     * onDisplayAdded() will not be called for the displays returned.
     *
     * @return the displayIds for the existing displays
     */
    @Override
    public int[] registerDisplayWindowListener(IDisplayWindowListener listener) {
        mAtmService.enforceTaskPermission("registerDisplayWindowListener");
        final long ident = Binder.clearCallingIdentity();
        try {
            return mDisplayNotificationController.registerListener(listener);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /** Unregister a hierarchy listener so that it stops receiving callbacks. */
    @Override
    public void unregisterDisplayWindowListener(IDisplayWindowListener listener) {
        mAtmService.enforceTaskPermission("unregisterDisplayWindowListener");
        mDisplayNotificationController.unregisterListener(listener);
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
                    ProtoLog.w(WM_ERROR, "View server did not start");
                }
            }
            return false;
        }

        try {
            mViewServer = new ViewServer(this, port);
            return mViewServer.start();
        } catch (IOException e) {
            ProtoLog.w(WM_ERROR, "View server did not start");
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
            ProtoLog.w(WM_ERROR, "Could not send command %s with parameters %s. %s", command,
                    parameters, e);
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

    WindowState getFocusedWindowLocked() {
        // Return the focused window in the focused display.
        return mRoot.getTopFocusedDisplayContent().mCurrentFocus;
    }

    Task getImeFocusRootTaskLocked() {
        // Don't use mCurrentFocus.getStack() because it returns home stack for system windows.
        // Also don't use mInputMethodTarget's stack, because some window with FLAG_NOT_FOCUSABLE
        // and FLAG_ALT_FOCUSABLE_IM flags both set might be set to IME target so they're moved
        // to make room for IME, but the window is not the focused window that's taking input.
        // TODO (b/111080190): Consider the case of multiple IMEs on multi-display.
        final DisplayContent topFocusedDisplay = mRoot.getTopFocusedDisplayContent();
        final ActivityRecord focusedApp = topFocusedDisplay.mFocusedApp;
        return (focusedApp != null && focusedApp.getTask() != null)
                ? focusedApp.getTask().getRootTask() : null;
    }

    public boolean detectSafeMode() {
        if (!mInputManagerCallback.waitForInputDevicesReady(
                INPUT_DEVICES_READY_FOR_SAFE_MODE_DETECTION_TIMEOUT_MILLIS)) {
            ProtoLog.w(WM_ERROR, "Devices still not ready after waiting %d"
                            + " milliseconds before attempting to detect safe mode.",
                    INPUT_DEVICES_READY_FOR_SAFE_MODE_DETECTION_TIMEOUT_MILLIS);
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
            ProtoLog.i(WM_ERROR, "SAFE MODE ENABLED (menu=%d s=%d dpad=%d"
                    + " trackball=%d)", menuState, sState, dpadState, trackballState);
            // May already be set if (for instance) this process has crashed
            if (SystemProperties.getInt(ShutdownThread.RO_SAFEMODE_PROPERTY, 0) == 0) {
                SystemProperties.set(ShutdownThread.RO_SAFEMODE_PROPERTY, "1");
            }
        } else {
            ProtoLog.i(WM_ERROR, "SAFE MODE not enabled");
        }
        mPolicy.setSafeMode(mSafeMode);
        return mSafeMode;
    }

    public void displayReady() {
        synchronized (mGlobalLock) {
            if (mMaxUiWidth > 0) {
                mRoot.forAllDisplays(displayContent -> displayContent.setMaxUiWidth(mMaxUiWidth));
            }
            applyForcedPropertiesForDefaultDisplay();
            mAnimator.ready();
            mDisplayReady = true;
            // Reconfigure all displays to make sure that forced properties and
            // DisplayWindowSettings are applied.
            mRoot.forAllDisplays(DisplayContent::reconfigureDisplayLocked);
            mIsTouchDevice = mContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_TOUCHSCREEN);
            mIsFakeTouchDevice = mContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_FAKETOUCH);
        }

        mAtmService.updateConfiguration(null /* request to compute config */);
    }

    public void systemReady() {
        mSystemReady = true;
        mPolicy.systemReady();
        mRoot.forAllDisplayPolicies(DisplayPolicy::systemReady);
        mTaskSnapshotController.systemReady();
        mHasWideColorGamutSupport = queryWideColorGamutSupport();
        mHasHdrSupport = queryHdrSupport();
        UiThread.getHandler().post(mSettingsObserver::loadSettings);
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


    // Keep logic in sync with SurfaceFlingerProperties.cpp
    // Consider exposing properties via ISurfaceComposer instead.
    private static boolean queryWideColorGamutSupport() {
        boolean defaultValue = false;
        Optional<Boolean> hasWideColorProp = SurfaceFlingerProperties.has_wide_color_display();
        if (hasWideColorProp.isPresent()) {
            return hasWideColorProp.get();
        }
        try {
            ISurfaceFlingerConfigs surfaceFlinger = ISurfaceFlingerConfigs.getService();
            OptionalBool hasWideColor = surfaceFlinger.hasWideColorDisplay();
            if (hasWideColor != null) {
                return hasWideColor.value;
            }
        } catch (RemoteException e) {
            // Ignore, we're in big trouble if we can't talk to SurfaceFlinger's config store
        } catch (NoSuchElementException e) {
            return defaultValue;
        }
        return false;
    }

    private static boolean queryHdrSupport() {
        boolean defaultValue = false;
        Optional<Boolean> hasHdrProp = SurfaceFlingerProperties.has_HDR_display();
        if (hasHdrProp.isPresent()) {
            return hasHdrProp.get();
        }
        try {
            ISurfaceFlingerConfigs surfaceFlinger = ISurfaceFlingerConfigs.getService();
            OptionalBool hasHdr = surfaceFlinger.hasHDRDisplay();
            if (hasHdr != null) {
                return hasHdr.value;
            }
        } catch (RemoteException e) {
            // Ignore, we're in big trouble if we can't talk to SurfaceFlinger's config store
        } catch (NoSuchElementException e) {
            return defaultValue;
        }
        return false;
    }

    // Returns an input target which is mapped to the given input token. This can be a WindowState
    // or an embedded window.
    @Nullable InputTarget getInputTargetFromToken(IBinder inputToken) {
        WindowState windowState = mInputToWindowMap.get(inputToken);
        if (windowState != null) {
            return windowState;
        }

        EmbeddedWindowController.EmbeddedWindow embeddedWindow =
                mEmbeddedWindowController.get(inputToken);
        if (embeddedWindow != null) {
            return embeddedWindow;
        }

        return null;
    }

    @Nullable InputTarget getInputTargetFromWindowTokenLocked(IBinder windowToken) {
        InputTarget window = mWindowMap.get(windowToken);
        if (window != null) {
            return window;
        }
        window = mEmbeddedWindowController.getByWindowToken(windowToken);
        return window;
    }

    void reportFocusChanged(IBinder oldToken, IBinder newToken) {
        InputTarget lastTarget;
        InputTarget newTarget;
        synchronized (mGlobalLock) {
            lastTarget = getInputTargetFromToken(oldToken);
            newTarget = getInputTargetFromToken(newToken);
            if (newTarget == null && lastTarget == null) {
                Slog.v(TAG_WM, "Unknown focus tokens, dropping reportFocusChanged");
                return;
            }
            mFocusedInputTarget = newTarget;

            mAccessibilityController.onFocusChanged(lastTarget, newTarget);
            ProtoLog.i(WM_DEBUG_FOCUS_LIGHT, "Focus changing: %s -> %s", lastTarget, newTarget);
        }

        // Call WindowState focus change observers
        WindowState newFocusedWindow = newTarget != null ? newTarget.getWindowState() : null;
        if (newFocusedWindow != null && newFocusedWindow.mInputChannelToken == newToken) {
            mAnrController.onFocusChanged(newFocusedWindow);
            newFocusedWindow.reportFocusChangedSerialized(true);
            notifyFocusChanged();
        }

        WindowState lastFocusedWindow = lastTarget != null ? lastTarget.getWindowState() : null;
        if (lastFocusedWindow != null && lastFocusedWindow.mInputChannelToken == oldToken) {
            lastFocusedWindow.reportFocusChangedSerialized(false);
        }
    }

    // -------------------------------------------------------------
    // Async Handler
    // -------------------------------------------------------------

    final class H extends android.os.Handler {
        public static final int WINDOW_FREEZE_TIMEOUT = 11;

        public static final int PERSIST_ANIMATION_SCALE = 14;
        public static final int FORCE_GC = 15;
        public static final int ENABLE_SCREEN = 16;
        public static final int APP_FREEZE_TIMEOUT = 17;
        public static final int REPORT_WINDOWS_CHANGE = 19;

        public static final int REPORT_HARD_KEYBOARD_STATUS_CHANGE = 22;
        public static final int BOOT_TIMEOUT = 23;
        public static final int WAITING_FOR_DRAWN_TIMEOUT = 24;
        public static final int SHOW_STRICT_MODE_VIOLATION = 25;

        public static final int CLIENT_FREEZE_TIMEOUT = 30;
        public static final int NOTIFY_ACTIVITY_DRAWN = 32;

        public static final int ALL_WINDOWS_DRAWN = 33;

        public static final int NEW_ANIMATOR_SCALE = 34;

        public static final int SHOW_EMULATOR_DISPLAY_OVERLAY = 36;

        public static final int CHECK_IF_BOOT_ANIMATION_FINISHED = 37;
        public static final int RESET_ANR_MESSAGE = 38;
        public static final int WALLPAPER_DRAW_PENDING_TIMEOUT = 39;

        public static final int UPDATE_MULTI_WINDOW_STACKS = 41;

        public static final int WINDOW_REPLACEMENT_TIMEOUT = 46;

        public static final int UPDATE_ANIMATION_SCALE = 51;
        public static final int WINDOW_HIDE_TIMEOUT = 52;
        public static final int RESTORE_POINTER_ICON = 55;
        public static final int SET_HAS_OVERLAY_UI = 58;
        public static final int ANIMATION_FAILSAFE = 60;
        public static final int RECOMPUTE_FOCUS = 61;
        public static final int ON_POINTER_DOWN_OUTSIDE_FOCUS = 62;
        public static final int LAYOUT_AND_ASSIGN_WINDOW_LAYERS_IF_NEEDED = 63;
        public static final int WINDOW_STATE_BLAST_SYNC_TIMEOUT = 64;
        public static final int REPARENT_TASK_TO_DEFAULT_DISPLAY = 65;
        public static final int INSETS_CHANGED = 66;

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
                        if (mAnimator.isAnimationScheduled()) {
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
                        ProtoLog.w(WM_ERROR, "App freeze timeout expired.");
                        mWindowsFreezingScreen = WINDOWS_FREEZING_SCREENS_TIMEOUT;
                        for (int i = mAppFreezeListeners.size() - 1; i >= 0; --i) {
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
                    final WindowContainer container = (WindowContainer) msg.obj;
                    synchronized (mGlobalLock) {
                        ProtoLog.w(WM_ERROR, "Timeout waiting for drawn: undrawn=%s",
                                container.mWaitingForDrawn);
                        container.mWaitingForDrawn.clear();
                        callback = mWaitingForDrawnCallbacks.remove(container);
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

                case SHOW_EMULATOR_DISPLAY_OVERLAY: {
                    showEmulatorDisplayOverlay();
                    break;
                }

                case NOTIFY_ACTIVITY_DRAWN: {
                    final ActivityRecord activity = (ActivityRecord) msg.obj;
                    synchronized (mGlobalLock) {
                        if (activity.isAttached()) {
                            activity.getRootTask().notifyActivityDrawnLocked(activity);
                        }
                    }
                    break;
                }
                case ALL_WINDOWS_DRAWN: {
                    Runnable callback;
                    final WindowContainer container = (WindowContainer) msg.obj;
                    synchronized (mGlobalLock) {
                        callback = mWaitingForDrawnCallbacks.remove(container);
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
                        ProtoLog.i(WM_DEBUG_BOOT, "CHECK_IF_BOOT_ANIMATION_FINISHED:");
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
                        mAtmService.mLastANRState = null;
                    }
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
                case UPDATE_MULTI_WINDOW_STACKS: {
                    synchronized (mGlobalLock) {
                        final DisplayContent displayContent = (DisplayContent) msg.obj;
                        if (displayContent != null) {
                            displayContent.adjustForImeIfNeeded();
                        }
                    }
                    break;
                }
                case WINDOW_REPLACEMENT_TIMEOUT: {
                    synchronized (mGlobalLock) {
                        for (int i = mWindowReplacementTimeouts.size() - 1; i >= 0; i--) {
                            final ActivityRecord activity = mWindowReplacementTimeouts.get(i);
                            activity.onWindowReplacementTimeout();
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
                case SET_HAS_OVERLAY_UI: {
                    mAmInternal.setHasOverlayUi(msg.arg1, msg.arg2 == 1);
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
                case ON_POINTER_DOWN_OUTSIDE_FOCUS: {
                    synchronized (mGlobalLock) {
                        final IBinder touchedToken = (IBinder) msg.obj;
                        onPointerDownOutsideFocusLocked(touchedToken);
                    }
                    break;
                }
                case LAYOUT_AND_ASSIGN_WINDOW_LAYERS_IF_NEEDED: {
                    synchronized (mGlobalLock) {
                        final DisplayContent displayContent = (DisplayContent) msg.obj;
                        displayContent.mLayoutAndAssignWindowLayersScheduled = false;
                        displayContent.layoutAndAssignWindowLayersIfNeeded();
                    }
                    break;
                }
                case WINDOW_STATE_BLAST_SYNC_TIMEOUT: {
                    synchronized (mGlobalLock) {
                        final WindowState ws = (WindowState) msg.obj;
                        Slog.i(TAG, "Blast sync timeout: " + ws);
                        ws.immediatelyNotifyBlastSync();
                    }
                    break;
                }
                case REPARENT_TASK_TO_DEFAULT_DISPLAY: {
                    synchronized (mGlobalLock) {
                        Task task = (Task) msg.obj;
                        task.reparent(mRoot.getDefaultTaskDisplayArea(), true /* onTop */);
                        // Resume focusable root task after reparenting to another display area.
                        task.resumeNextFocusAfterReparent();
                    }
                    break;
                }
                case INSETS_CHANGED: {
                    synchronized (mGlobalLock) {
                        if (mWindowsInsetsChanged > 0) {
                            mWindowsInsetsChanged = 0;
                            // We need to update resizing windows and dispatch the new insets state
                            // to them.
                            mRoot.performSurfacePlacement();
                        }
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

    // -------------------------------------------------------------
    // IWindowManager API
    // -------------------------------------------------------------

    @Override
    public IWindowSession openSession(IWindowSessionCallback callback) {
        return new Session(this, callback);
    }

    @Override
    public boolean useBLAST() {
        return mUseBLAST;
    }

    public boolean useBLASTSync() {
        return mUseBLASTSync;
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

    void setSandboxDisplayApis(int displayId, boolean sandboxDisplayApis) {
        if (mContext.checkCallingOrSelfPermission(WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold permission " + WRITE_SECURE_SETTINGS);
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    displayContent.setSandboxDisplayApis(sandboxDisplayApis);
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
                    height = Integer.parseInt(sizeStr.substring(pos + 1));
                    if (displayContent.mBaseDisplayWidth != width
                            || displayContent.mBaseDisplayHeight != height) {
                        ProtoLog.i(WM_ERROR, "FORCED DISPLAY SIZE: %dx%d", width, height);
                        displayContent.updateBaseDisplayMetrics(width, height,
                                displayContent.mBaseDisplayDensity,
                                displayContent.mBaseDisplayPhysicalXDpi,
                                displayContent.mBaseDisplayPhysicalYDpi);
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
            ProtoLog.i(WM_ERROR, "FORCED DISPLAY SCALING DISABLED");
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

    @Override
    public void startWindowTrace(){
        mWindowTracing.startTrace(null /* printwriter */);
    }

    @Override
    public void stopWindowTrace(){
        mWindowTracing.stopTrace(null /* printwriter */);
    }

    @Override
    public void saveWindowTraceToFile() {
        mWindowTracing.saveForBugreport(null /* printwriter */);
    }

    @Override
    public boolean isWindowTraceEnabled() {
        return mWindowTracing.isEnabled();
    }

    @Override
    public void startTransitionTrace() {
        mTransitionTracer.startTrace(null /* printwriter */);
    }

    @Override
    public void stopTransitionTrace() {
        mTransitionTracer.stopTrace(null /* printwriter */);
    }

    @Override
    public boolean isTransitionTraceEnabled() {
        return mTransitionTracer.isEnabled();
    }

    @Override
    public boolean registerCrossWindowBlurEnabledListener(
                ICrossWindowBlurEnabledListener listener) {
        return mBlurController.registerCrossWindowBlurEnabledListener(listener);
    }

    @Override
    public void unregisterCrossWindowBlurEnabledListener(
                ICrossWindowBlurEnabledListener listener) {
        mBlurController.unregisterCrossWindowBlurEnabledListener(listener);
    }

    // -------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------

    final WindowState windowForClientLocked(Session session, IWindow client, boolean throwOnError) {
        return windowForClientLocked(session, client.asBinder(), throwOnError);
    }

    final WindowState windowForClientLocked(Session session, IBinder client, boolean throwOnError) {
        WindowState win = mWindowMap.get(client);
        if (DEBUG) Slog.v(TAG_WM, "Looking up client " + client + ": " + win);
        if (win == null) {
            if (throwOnError) {
                throw new IllegalArgumentException(
                        "Requested window " + client + " does not exist");
            }
            ProtoLog.w(WM_ERROR, "Failed looking up window session=%s callers=%s", session,
                    Debug.getCallers(3));
            return null;
        }
        if (session != null && win.mSession != session) {
            if (throwOnError) {
                throw new IllegalArgumentException("Requested window " + client + " is in session "
                        + win.mSession + ", not " + session);
            }
            ProtoLog.w(WM_ERROR, "Failed looking up window session=%s callers=%s", session,
                    Debug.getCallers(3));
            return null;
        }

        return win;
    }

    void makeWindowFreezingScreenIfNeededLocked(WindowState w) {
        // If the screen is currently frozen or off, then keep
        // it frozen/off until this window draws at its new
        // orientation.
        if (!w.mToken.okToDisplay() && mWindowsFreezingScreen != WINDOWS_FREEZING_SCREENS_TIMEOUT) {
            ProtoLog.v(WM_DEBUG_ORIENTATION, "Changing surface while display frozen: %s", w);
            w.setOrientationChanging(true);
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
        if (mWaitingForDrawnCallbacks.isEmpty()) {
            return;
        }
        mWaitingForDrawnCallbacks.forEach((container, callback) -> {
            for (int j = container.mWaitingForDrawn.size() - 1; j >= 0; j--) {
                final WindowState win = (WindowState) container.mWaitingForDrawn.get(j);
                ProtoLog.i(WM_DEBUG_SCREEN_ON,
                        "Waiting for drawn %s: removed=%b visible=%b mHasSurface=%b drawState=%d",
                        win, win.mRemoved, win.isVisible(), win.mHasSurface,
                        win.mWinAnimator.mDrawState);
                if (win.mRemoved || !win.mHasSurface || !win.isVisibleByPolicy()) {
                    // Window has been removed or hidden; no draw will now happen, so stop waiting.
                    ProtoLog.w(WM_DEBUG_SCREEN_ON, "Aborted waiting for drawn: %s", win);
                    container.mWaitingForDrawn.remove(win);
                } else if (win.hasDrawn()) {
                    // Window is now drawn (and shown).
                    ProtoLog.d(WM_DEBUG_SCREEN_ON, "Window drawn win=%s", win);
                    container.mWaitingForDrawn.remove(win);
                }
            }
            if (container.mWaitingForDrawn.isEmpty()) {
                ProtoLog.d(WM_DEBUG_SCREEN_ON, "All windows drawn!");
                mH.removeMessages(H.WAITING_FOR_DRAWN_TIMEOUT, container);
                mH.sendMessage(mH.obtainMessage(H.ALL_WINDOWS_DRAWN, container));
            }
        });
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
                ProtoLog.d(WM_DEBUG_KEEP_SCREEN_ON, "Acquiring screen wakelock due to %s",
                            mRoot.mHoldScreenWindow);
                mLastWakeLockHoldingWindow = mRoot.mHoldScreenWindow;
                mLastWakeLockObscuringWindow = null;
                mHoldingScreenWakeLock.acquire();
                mPolicy.keepScreenOnStartedLw();
            } else {
                ProtoLog.d(WM_DEBUG_KEEP_SCREEN_ON, "Releasing screen wakelock, obscured by %s",
                            mRoot.mObscuringWindow);
                mLastWakeLockHoldingWindow = null;
                mLastWakeLockObscuringWindow = mRoot.mObscuringWindow;
                mPolicy.keepScreenOnStoppedLw();
                mHoldingScreenWakeLock.release();
            }
        }
    }

    void requestTraversal() {
        mWindowPlacerLocked.requestTraversal();
    }

    /** Note that Locked in this case is on mLayoutToAnim */
    void scheduleAnimationLocked() {
        mAnimator.scheduleAnimation();
    }

    boolean updateFocusedWindowLocked(int mode, boolean updateInputWindows) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "wmUpdateFocus");
        boolean changed = mRoot.updateFocusedWindowLocked(mode, updateInputWindows);
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        return changed;
    }

    void startFreezingDisplay(int exitAnim, int enterAnim) {
        startFreezingDisplay(exitAnim, enterAnim, getDefaultDisplayContentLocked());
    }

    void startFreezingDisplay(int exitAnim, int enterAnim, DisplayContent displayContent) {
        startFreezingDisplay(exitAnim, enterAnim, displayContent,
                ROTATION_UNDEFINED /* overrideOriginalRotation */);
    }

    void startFreezingDisplay(int exitAnim, int enterAnim, DisplayContent displayContent,
            int overrideOriginalRotation) {
        if (mDisplayFrozen || displayContent.getDisplayRotation().isRotatingSeamlessly()) {
            return;
        }

        if (!displayContent.isReady() || !displayContent.getDisplayPolicy().isScreenOnFully()
                || displayContent.getDisplayInfo().state == Display.STATE_OFF
                || !displayContent.okToAnimate()) {
            // No need to freeze the screen before the display is ready,  if the screen is off,
            // or we can't currently animate.
            return;
        }

        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "WMS.doStartFreezingDisplay");
        doStartFreezingDisplay(exitAnim, enterAnim, displayContent, overrideOriginalRotation);
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    private void doStartFreezingDisplay(int exitAnim, int enterAnim, DisplayContent displayContent,
            int overrideOriginalRotation) {
        ProtoLog.d(WM_DEBUG_ORIENTATION,
                            "startFreezingDisplayLocked: exitAnim=%d enterAnim=%d called by %s",
                            exitAnim, enterAnim, Debug.getCallers(8));
        mScreenFrozenLock.acquire();
        // Apply launch power mode to reduce screen frozen time because orientation change may
        // relaunch activity and redraw windows. This may also help speed up user switching.
        mAtmService.startLaunchPowerMode(POWER_MODE_REASON_CHANGE_DISPLAY);

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
        mExitAnimId = exitAnim;
        mEnterAnimId = enterAnim;

        displayContent.updateDisplayInfo();
        final int originalRotation = overrideOriginalRotation != ROTATION_UNDEFINED
                ? overrideOriginalRotation
                : displayContent.getDisplayInfo().rotation;
        displayContent.setRotationAnimation(new ScreenRotationAnimation(displayContent,
                originalRotation));
    }

    void stopFreezingDisplayLocked() {
        if (!mDisplayFrozen) {
            return;
        }

        final DisplayContent displayContent = mRoot.getDisplayContent(mFrozenDisplayId);
        final int numOpeningApps;
        final boolean waitingForConfig;
        final boolean waitingForRemoteDisplayChange;
        if (displayContent != null) {
            numOpeningApps = displayContent.mOpeningApps.size();
            waitingForConfig = displayContent.mWaitingForConfig;
            waitingForRemoteDisplayChange = displayContent.mRemoteDisplayChangeController
                    .isWaitingForRemoteDisplayChange();
        } else {
            waitingForConfig = waitingForRemoteDisplayChange = false;
            numOpeningApps = 0;
        }
        if (waitingForConfig || waitingForRemoteDisplayChange || mAppsFreezingScreen > 0
                || mWindowsFreezingScreen == WINDOWS_FREEZING_SCREENS_ACTIVE
                || mClientFreezingScreen || numOpeningApps > 0) {
            ProtoLog.d(WM_DEBUG_ORIENTATION, "stopFreezingDisplayLocked: Returning "
                    + "waitingForConfig=%b, waitingForRemoteDisplayChange=%b, "
                    + "mAppsFreezingScreen=%d, mWindowsFreezingScreen=%d, "
                    + "mClientFreezingScreen=%b, mOpeningApps.size()=%d",
                    waitingForConfig, waitingForRemoteDisplayChange,
                    mAppsFreezingScreen, mWindowsFreezingScreen,
                    mClientFreezingScreen, numOpeningApps);
            return;
        }

        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "WMS.doStopFreezingDisplayLocked-"
                + mLastFinishedFreezeSource);
        doStopFreezingDisplayLocked(displayContent);
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    private void doStopFreezingDisplayLocked(DisplayContent displayContent) {
        ProtoLog.d(WM_DEBUG_ORIENTATION,
                    "stopFreezingDisplayLocked: Unfreezing now");

        // We must make a local copy of the displayId as it can be potentially overwritten later on
        // in this method. For example, {@link startFreezingDisplayLocked} may be called as a result
        // of update rotation, but we reference the frozen display after that call in this method.
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
        ProtoLog.i(WM_ERROR, "%s", sb.toString());
        mH.removeMessages(H.APP_FREEZE_TIMEOUT);
        mH.removeMessages(H.CLIENT_FREEZE_TIMEOUT);
        if (PROFILE_ORIENTATION) {
            Debug.stopMethodTracing();
        }

        boolean updateRotation = false;

        ScreenRotationAnimation screenRotationAnimation = displayContent == null ? null
                : displayContent.getRotationAnimation();
        if (screenRotationAnimation != null && screenRotationAnimation.hasScreenshot()) {
            ProtoLog.i(WM_DEBUG_ORIENTATION, "**** Dismissing screen rotation animation");
            DisplayInfo displayInfo = displayContent.getDisplayInfo();
            // Get rotation animation again, with new top window
            if (!displayContent.getDisplayRotation().validateRotationAnimation(
                    mExitAnimId, mEnterAnimId, false /* forceDefault */)) {
                mExitAnimId = mEnterAnimId = 0;
            }
            if (screenRotationAnimation.dismiss(mTransaction, MAX_ANIMATION_DURATION,
                    getTransitionAnimationScaleLocked(), displayInfo.logicalWidth,
                        displayInfo.logicalHeight, mExitAnimId, mEnterAnimId)) {
                mTransaction.apply();
            } else {
                screenRotationAnimation.kill();
                displayContent.setRotationAnimation(null);
                updateRotation = true;
            }
        } else {
            if (screenRotationAnimation != null) {
                screenRotationAnimation.kill();
                displayContent.setRotationAnimation(null);
            }
            updateRotation = true;
        }

        boolean configChanged;

        // While the display is frozen we don't re-compute the orientation
        // to avoid inconsistent states.  However, something interesting
        // could have actually changed during that time so re-evaluate it
        // now to catch that.
        configChanged = displayContent != null && displayContent.updateOrientation();

        // A little kludge: a lot could have happened while the
        // display was frozen, so now that we are coming back we
        // do a gc so that any remote references the system
        // processes holds on others can be released if they are
        // no longer needed.
        mH.removeMessages(H.FORCE_GC);
        mH.sendEmptyMessageDelayed(H.FORCE_GC, 2000);

        mScreenFrozenLock.release();

        if (updateRotation && displayContent != null) {
            ProtoLog.d(WM_DEBUG_ORIENTATION, "Performing post-rotate rotation");
            configChanged |= displayContent.updateRotationUnchecked();
        }

        if (configChanged) {
            displayContent.sendNewConfiguration();
        }
        mAtmService.endLaunchPowerMode(POWER_MODE_REASON_CHANGE_DISPLAY);
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

    void createWatermark() {
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
                            toks, mTransaction);
                    mTransaction.apply();
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
        if (!checkCallingPermission(
                android.Manifest.permission.STATUS_BAR, "setRecentsVisibility()")) {
            throw new SecurityException("Requires STATUS_BAR permission");
        }
        synchronized (mGlobalLock) {
            mPolicy.setRecentsVisibilityLw(visible);
        }
    }

    @Override
    public void hideTransientBars(int displayId) {
        if (!checkCallingPermission(
                android.Manifest.permission.STATUS_BAR, "hideTransientBars()")) {
            throw new SecurityException("Requires STATUS_BAR permission");
        }

        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent != null) {
                displayContent.getInsetsPolicy().hideTransient();
            } else {
                Slog.w(TAG, "hideTransientBars with invalid displayId=" + displayId);
            }
        }
    }

    @Override
    public void updateStaticPrivacyIndicatorBounds(int displayId,
            Rect[] staticBounds) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent != null) {
                displayContent.updatePrivacyIndicatorBounds(staticBounds);
            } else {
                Slog.w(TAG, "updateStaticPrivacyIndicatorBounds with invalid displayId="
                        + displayId);
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
            return displayContent.getDisplayPolicy().getNavBarPosition();
        }
    }

    @Override
    public void createInputConsumer(IBinder token, String name, int displayId,
            InputChannel inputChannel) {
        if (!mAtmService.isCallerRecents(Binder.getCallingUid())
                && mContext.checkCallingOrSelfPermission(INPUT_CONSUMER) != PERMISSION_GRANTED) {
            throw new SecurityException("createInputConsumer requires INPUT_CONSUMER permission");
        }

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
        if (!mAtmService.isCallerRecents(Binder.getCallingUid())
                && mContext.checkCallingOrSelfPermission(INPUT_CONSUMER) != PERMISSION_GRANTED) {
            throw new SecurityException("destroyInputConsumer requires INPUT_CONSUMER permission");
        }

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


    private void dumpHighRefreshRateBlacklist(PrintWriter pw) {
        pw.println("WINDOW MANAGER HIGH REFRESH RATE BLACKLIST (dumpsys window refresh)");
        mHighRefreshRateDenylist.dump(pw);
    }

    private void dumpTraceStatus(PrintWriter pw) {
        pw.println("WINDOW MANAGER TRACE (dumpsys window trace)");
        pw.print(mWindowTracing.getStatus() + "\n");
    }

    private void dumpLogStatus(PrintWriter pw) {
        pw.println("WINDOW MANAGER LOGGING (dumpsys window logging)");
        pw.println(ProtoLogImpl.getSingleInstance().getStatus());
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
    void dumpDebugLocked(ProtoOutputStream proto, @WindowTraceLogLevel int logLevel) {
        mPolicy.dumpDebug(proto, POLICY);
        mRoot.dumpDebug(proto, ROOT_WINDOW_CONTAINER, logLevel);
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
        proto.write(FOCUSED_DISPLAY_ID, topFocusedDisplayContent.getDisplayId());
        proto.write(HARD_KEYBOARD_AVAILABLE, mHardKeyboardAvailable);

        // This is always true for now since we still update the window frames at the server side.
        // Once we move the window layout to the client side, this can be false when we are waiting
        // for the frames.
        proto.write(WINDOW_FRAMES_VALID, true);
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
        if (!mWaitingForDrawnCallbacks.isEmpty()) {
            pw.println();
            pw.println("  Clients waiting for these windows to be drawn:");
            mWaitingForDrawnCallbacks.forEach((wc, callback) -> {
                pw.print("  WindowContainer ");
                pw.println(wc.getName());
                for (int i = wc.mWaitingForDrawn.size() - 1; i >= 0; i--) {
                    final WindowState win = (WindowState) wc.mWaitingForDrawn.get(i);
                    pw.print("  Waiting #"); pw.print(i); pw.print(' '); pw.print(win);
                }
            });

        }
        pw.println();
        pw.print("  mGlobalConfiguration="); pw.println(mRoot.getConfiguration());
        pw.print("  mHasPermanentDpad="); pw.println(mHasPermanentDpad);
        mRoot.dumpTopFocusedDisplayId(pw);
        mRoot.forAllDisplays(dc -> {
            final int displayId = dc.getDisplayId();
            final InsetsControlTarget imeLayeringTarget = dc.getImeTarget(IME_TARGET_LAYERING);
            final InputTarget imeInputTarget = dc.getImeInputTarget();
            final InsetsControlTarget imeControlTarget = dc.getImeTarget(IME_TARGET_CONTROL);
            if (imeLayeringTarget != null) {
                pw.print("  imeLayeringTarget in display# "); pw.print(displayId);
                pw.print(' '); pw.println(imeLayeringTarget);
            }
            if (imeInputTarget != null) {
                pw.print("  imeInputTarget in display# "); pw.print(displayId);
                pw.print(' '); pw.println(imeInputTarget);
            }
            if (imeControlTarget != null) {
                pw.print("  imeControlTarget in display# "); pw.print(displayId);
                pw.print(' '); pw.println(imeControlTarget);
            }
            pw.print("  Minimum task size of display#"); pw.print(displayId);
            pw.print(' '); pw.print(dc.mMinSizeOfResizeableTaskDp);
        });
        pw.print("  mInTouchMode="); pw.println(mInTouchMode);
        pw.print("  mBlurEnabled="); pw.println(mBlurController.getBlurEnabled());
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
        if (mAccessibilityController.hasCallbacks()) {
            mAccessibilityController.dump(pw, "  ");
        }

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
            pw.print("  mLastOrientation=");
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
                    if ((!visibleOnly || w.isVisible())
                            && (!appsOnly || w.mActivityRecord != null)) {
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
     * @param activity The application that ANR'd, may be null.
     * @param windowState The window that ANR'd, may be null.
     * @param reason The reason for the ANR, may be null.
     */
    void saveANRStateLocked(ActivityRecord activity, WindowState windowState, String reason) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new FastPrintWriter(sw, false, 1024);
        pw.println("  ANR time: " + DateFormat.getDateTimeInstance().format(new Date()));
        if (activity != null) {
            pw.println("  Application at fault: " + activity.stringName);
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

    @NeverCompile // Avoid size overhead of debugging code.
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
                pw.println("    package-config: installed packages having app-specific config");
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
                dumpDebugLocked(proto, WindowTraceLogLevel.ALL);
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
                return;
            } else if ("logging".equals(cmd)) {
                dumpLogStatus(pw);
                return;
            } else if ("refresh".equals(cmd)) {
                dumpHighRefreshRateBlacklist(pw);
                return;
            } else if ("constants".equals(cmd)) {
                mConstants.dump(pw);
                return;
            } else if ("package-config".equals(cmd)) {
                mAtmService.dumpInstalledPackagesConfig(pw);
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
            if (dumpAll) {
                pw.println(separator);
            }
            dumpLogStatus(pw);
            if (dumpAll) {
                pw.println(separator);
            }
            dumpHighRefreshRateBlacklist(pw);
            if (dumpAll) {
                pw.println(separator);
            }
            mAtmService.dumpInstalledPackagesConfig(pw);
            if (dumpAll) {
                pw.println(separator);
            }
            mConstants.dump(pw);
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

    @Override
    public Object getWindowManagerLock() {
        return mGlobalLock;
    }

    /**
     * Hint to a token that its activity will relaunch, which will trigger removal and addition of
     * a window.
     *
     * @param token Application token for which the activity will be relaunched.
     */
    void setWillReplaceWindow(IBinder token, boolean animate) {
        final ActivityRecord activity = mRoot.getActivityRecord(token);
        if (activity == null) {
            ProtoLog.w(WM_ERROR, "Attempted to set replacing window on non-existing app token %s",
                    token);
            return;
        }
        if (!activity.hasContentToDisplay()) {
            ProtoLog.w(WM_ERROR,
                    "Attempted to set replacing window on app token with no content %s",
                    token);
            return;
        }
        activity.setWillReplaceWindows(animate);
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
            final ActivityRecord activity = mRoot.getActivityRecord(token);
            if (activity == null) {
                ProtoLog.w(WM_ERROR,
                        "Attempted to set replacing window on non-existing app token %s",
                        token);
                return;
            }
            if (!activity.hasContentToDisplay()) {
                ProtoLog.w(WM_ERROR,
                        "Attempted to set replacing window on app token with no content %s",
                        token);
                return;
            }

            if (childrenOnly) {
                activity.setWillReplaceChildWindows();
            } else {
                activity.setWillReplaceWindows(false /* animate */);
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
     * @param token     Application token for the activity whose window might be replaced.
     * @param replacing Whether the window is being replaced or not.
     */
    void scheduleClearWillReplaceWindows(IBinder token, boolean replacing) {
        final ActivityRecord activity = mRoot.getActivityRecord(token);
        if (activity == null) {
            ProtoLog.w(WM_ERROR, "Attempted to reset replacing window on non-existing app token %s",
                    token);
            return;
        }
        if (replacing) {
            scheduleWindowReplacementTimeouts(activity);
        } else {
            activity.clearWillReplaceWindows();
        }
    }

    void scheduleWindowReplacementTimeouts(ActivityRecord activity) {
        if (!mWindowReplacementTimeouts.contains(activity)) {
            mWindowReplacementTimeouts.add(activity);
        }
        mH.removeMessages(H.WINDOW_REPLACEMENT_TIMEOUT);
        mH.sendEmptyMessageDelayed(
                H.WINDOW_REPLACEMENT_TIMEOUT, WINDOW_REPLACEMENT_TIMEOUT_DURATION);
    }

    @Override
    public int getDockedStackSide() {
        return 0;
    }

    void setDockedRootTaskResizing(boolean resizing) {
        getDefaultDisplayContentLocked().getDockedDividerController().setResizing(resizing);
        requestTraversal();
    }

    @Override
    public void setDockedTaskDividerTouchRegion(Rect touchRegion) {
        synchronized (mGlobalLock) {
            final DisplayContent dc = getDefaultDisplayContentLocked();
            dc.getDockedDividerController().setTouchRegion(touchRegion);
            dc.updateTouchExcludeRegion();
        }
    }

    void setForceDesktopModeOnExternalDisplays(boolean forceDesktopModeOnExternalDisplays) {
        synchronized (mGlobalLock) {
            mForceDesktopModeOnExternalDisplays = forceDesktopModeOnExternalDisplays;
            mRoot.updateDisplayImePolicyCache();
        }
    }

    @VisibleForTesting
    void setIsPc(boolean isPc) {
        synchronized (mGlobalLock) {
            mIsPc = isPc;
        }
    }

    static int dipToPixel(int dip, DisplayMetrics displayMetrics) {
        return (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip, displayMetrics);
    }

    public void registerPinnedTaskListener(int displayId, IPinnedTaskListener listener) {
        if (!checkCallingPermission(REGISTER_WINDOW_MANAGER_LISTENERS,
                "registerPinnedTaskListener()")) {
            return;
        }
        if (!mAtmService.mSupportsPictureInPicture) {
            return;
        }
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            displayContent.getPinnedTaskController().registerPinnedTaskListener(listener);
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
            dc.getDisplayPolicy().getStableInsetsLw(di.rotation, di.displayCutout, outInsets);
        }
    }

    MousePositionTracker mMousePositionTracker = new MousePositionTracker();

    private static class MousePositionTracker implements PointerEventListener {
        private boolean mLatestEventWasMouse;
        private float mLatestMouseX;
        private float mLatestMouseY;

        /**
         * The display that the pointer (mouse cursor) is currently shown on. This is updated
         * directly by InputManagerService when the pointer display changes.
         */
        private int mPointerDisplayId = INVALID_DISPLAY;

        /**
         * Update the mouse cursor position as a result of a mouse movement.
         * @return true if the position was successfully updated, false otherwise.
         */
        boolean updatePosition(int displayId, float x, float y) {
            synchronized (this) {
                mLatestEventWasMouse = true;

                if (displayId != mPointerDisplayId) {
                    // The display of the position update does not match the display on which the
                    // mouse pointer is shown, so do not update the position.
                    return false;
                }
                mLatestMouseX = x;
                mLatestMouseY = y;
                return true;
            }
        }

        void setPointerDisplayId(int displayId) {
            synchronized (this) {
                mPointerDisplayId = displayId;
            }
        }

        @Override
        public void onPointerEvent(MotionEvent motionEvent) {
            if (motionEvent.isFromSource(InputDevice.SOURCE_MOUSE)) {
                updatePosition(motionEvent.getDisplayId(), motionEvent.getRawX(),
                        motionEvent.getRawY());
            } else {
                synchronized (this) {
                    mLatestEventWasMouse = false;
                }
            }
        }
    };

    void updatePointerIcon(IWindow client) {
        int pointerDisplayId;
        float mouseX, mouseY;

        synchronized(mMousePositionTracker) {
            if (!mMousePositionTracker.mLatestEventWasMouse) {
                return;
            }
            mouseX = mMousePositionTracker.mLatestMouseX;
            mouseY = mMousePositionTracker.mLatestMouseY;
            pointerDisplayId = mMousePositionTracker.mPointerDisplayId;
        }

        synchronized (mGlobalLock) {
            if (mDragDropController.dragDropActiveLocked()) {
                // Drag cursor overrides the app cursor.
                return;
            }
            WindowState callingWin = windowForClientLocked(null, client, false);
            if (callingWin == null) {
                ProtoLog.w(WM_ERROR, "Bad requesting window %s", client);
                return;
            }
            final DisplayContent displayContent = callingWin.getDisplayContent();
            if (displayContent == null) {
                return;
            }
            if (pointerDisplayId != displayContent.getDisplayId()) {
                // Do not let the pointer icon be updated by a window on a different display.
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
                ProtoLog.w(WM_ERROR, "unable to update pointer icon");
            }
        }
    }

    void restorePointerIconLocked(DisplayContent displayContent, float latestX, float latestY) {
        // Mouse position tracker has not been getting updates while dragging, update it now.
        if (!mMousePositionTracker.updatePosition(
                displayContent.getDisplayId(), latestX, latestY)) {
            // The mouse position could not be updated, so ignore this request.
            return;
        }

        WindowState windowUnderPointer =
                displayContent.getTouchableWinAtPointLocked(latestX, latestY);
        if (windowUnderPointer != null) {
            try {
                windowUnderPointer.mClient.updatePointerIcon(
                        windowUnderPointer.translateToWindowX(latestX),
                        windowUnderPointer.translateToWindowY(latestY));
            } catch (RemoteException e) {
                ProtoLog.w(WM_ERROR, "unable to restore pointer icon");
            }
        } else {
            InputManager.getInstance().setPointerIconType(PointerIcon.TYPE_DEFAULT);
        }
    }

    PointF getLatestMousePosition() {
        synchronized (mMousePositionTracker) {
            return new PointF(mMousePositionTracker.mLatestMouseX,
                    mMousePositionTracker.mLatestMouseY);
        }
    }

    void setMousePointerDisplayId(int displayId) {
        mMousePositionTracker.setPointerDisplayId(displayId);
    }

    /**
     * Update a tap exclude region in the window identified by the provided id. Touches down on this
     * region will not:
     * <ol>
     * <li>Switch focus to this window.</li>
     * <li>Move the display of this window to top.</li>
     * <li>Send the touch events to this window.</li>
     * </ol>
     * Passing an invalid region will remove the area from the exclude region of this window.
     */
    void updateTapExcludeRegion(IWindow client, Region region) {
        synchronized (mGlobalLock) {
            final WindowState callingWin = windowForClientLocked(null, client, false);
            if (callingWin == null) {
                ProtoLog.w(WM_ERROR, "Bad requesting window %s", client);
                return;
            }
            callingWin.updateTapExcludeRegion(region);
        }
    }

    /**
     * Forwards a scroll capture request to the appropriate window, if available.
     *
     * @param displayId the display for the request
     * @param behindClient token for a window, used to filter the search to windows behind it
     * @param taskId specifies the id of a task the result must belong to or -1 to match any task
     * @param listener to receive the response
     */
    public void requestScrollCapture(int displayId, @Nullable IBinder behindClient, int taskId,
            IScrollCaptureResponseListener listener) {
        if (!checkCallingPermission(READ_FRAME_BUFFER, "requestScrollCapture()")) {
            throw new SecurityException("Requires READ_FRAME_BUFFER permission");
        }
        final long token = Binder.clearCallingIdentity();
        try {
            ScrollCaptureResponse.Builder responseBuilder = new ScrollCaptureResponse.Builder();
            synchronized (mGlobalLock) {
                DisplayContent dc = mRoot.getDisplayContent(displayId);
                if (dc == null) {
                    ProtoLog.e(WM_ERROR,
                            "Invalid displayId for requestScrollCapture: %d", displayId);
                    responseBuilder.setDescription(String.format("bad displayId: %d", displayId));
                    listener.onScrollCaptureResponse(responseBuilder.build());
                    return;
                }
                WindowState topWindow = null;
                if (behindClient != null) {
                    topWindow = windowForClientLocked(null, behindClient, /* throwOnError*/ false);
                }
                WindowState targetWindow = dc.findScrollCaptureTargetWindow(topWindow, taskId);
                if (targetWindow == null) {
                    responseBuilder.setDescription("findScrollCaptureTargetWindow returned null");
                    listener.onScrollCaptureResponse(responseBuilder.build());
                    return;
                }
                try {
                    // Forward to the window for handling, which will respond using the callback.
                    targetWindow.mClient.requestScrollCapture(listener);
                } catch (RemoteException e) {
                    ProtoLog.w(WM_ERROR,
                            "requestScrollCapture: caught exception dispatching to window."
                                    + "token=%s", targetWindow.mClient.asBinder());
                    responseBuilder.setWindowTitle(targetWindow.getName());
                    responseBuilder.setPackageName(targetWindow.getOwningPackage());
                    responseBuilder.setDescription(String.format("caught exception: %s", e));
                    listener.onScrollCaptureResponse(responseBuilder.build());
                }
            }
        } catch (RemoteException e) {
            ProtoLog.w(WM_ERROR,
                    "requestScrollCapture: caught exception dispatching callback: %s", e);
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
                ProtoLog.w(WM_ERROR,
                        "Attempted to get windowing mode of a display that does not exist: %d",
                        displayId);
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

        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent displayContent = getDisplayContentOrCreate(displayId, null);
                if (displayContent == null) {
                    ProtoLog.w(WM_ERROR,
                            "Attempted to set windowing mode to a display that does not exist: %d",
                            displayId);
                    return;
                }

                int lastWindowingMode = displayContent.getWindowingMode();
                mDisplayWindowSettings.setWindowingModeLocked(displayContent, mode);

                displayContent.reconfigureDisplayLocked();

                if (lastWindowingMode != displayContent.getWindowingMode()) {
                    // reconfigure won't detect this change in isolation because the windowing mode
                    // is already set on the display, so fire off a new config now.
                    displayContent.sendNewConfiguration();
                    // Now that all configurations are updated, execute pending transitions.
                    displayContent.executeAppTransition();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
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
                ProtoLog.w(WM_ERROR,
                        "Attempted to get remove mode of a display that does not exist: %d",
                        displayId);
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

        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent displayContent = getDisplayContentOrCreate(displayId, null);
                if (displayContent == null) {
                    ProtoLog.w(WM_ERROR,
                            "Attempted to set remove mode to a display that does not exist: %d",
                            displayId);
                    return;
                }

                mDisplayWindowSettings.setRemoveContentModeLocked(displayContent, mode);
                displayContent.reconfigureDisplayLocked();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
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
                ProtoLog.w(WM_ERROR, "Attempted to get flag of a display that does not exist: %d",
                        displayId);
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
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent displayContent = getDisplayContentOrCreate(displayId, null);
                if (displayContent == null) {
                    ProtoLog.w(WM_ERROR, "Attempted to set flag to a display that does not exist: "
                            + "%d", displayId);
                    return;
                }

                mDisplayWindowSettings.setShouldShowWithInsecureKeyguardLocked(displayContent,
                        shouldShow);

                displayContent.reconfigureDisplayLocked();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
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
                ProtoLog.w(WM_ERROR, "Attempted to get system decors flag of a display that does "
                        + "not exist: %d", displayId);
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
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent displayContent = getDisplayContentOrCreate(displayId, null);
                if (displayContent == null) {
                    ProtoLog.w(WM_ERROR, "Attempted to set system decors flag to a display that "
                            + "does not exist: %d", displayId);
                    return;
                }
                if (!displayContent.isTrusted()) {
                    throw new SecurityException("Attempted to set system decors flag to an "
                            + "untrusted virtual display: " + displayId);
                }

                mDisplayWindowSettings.setShouldShowSystemDecorsLocked(displayContent, shouldShow);

                displayContent.reconfigureDisplayLocked();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public @DisplayImePolicy int getDisplayImePolicy(int displayId) {
        if (!checkCallingPermission(INTERNAL_SYSTEM_WINDOW, "getDisplayImePolicy()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }
        final Map<Integer, Integer> displayImePolicyCache = mDisplayImePolicyCache;
        if (!displayImePolicyCache.containsKey(displayId)) {
            ProtoLog.w(WM_ERROR,
                    "Attempted to get IME policy of a display that does not exist: %d",
                    displayId);
            return DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
        }
        return displayImePolicyCache.get(displayId);
    }

    @Override
    public void setDisplayImePolicy(int displayId, @DisplayImePolicy int imePolicy) {
        if (!checkCallingPermission(INTERNAL_SYSTEM_WINDOW, "setDisplayImePolicy()")) {
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent displayContent = getDisplayContentOrCreate(displayId, null);
                if (displayContent == null) {
                    ProtoLog.w(WM_ERROR, "Attempted to set IME policy to a display"
                            + " that does not exist: %d", displayId);
                    return;
                }
                if (!displayContent.isTrusted()) {
                    throw new SecurityException("Attempted to set IME policy to an untrusted "
                            + "virtual display: " + displayId);
                }

                mDisplayWindowSettings.setDisplayImePolicy(displayContent, imePolicy);

                displayContent.reconfigureDisplayLocked();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
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

    private final class LocalService extends WindowManagerInternal {

        @Override
        public AccessibilityControllerInternal getAccessibilityController() {
            return AccessibilityController.getAccessibilityControllerInternal(
                    WindowManagerService.this);
        }

        @Override
        public void clearSnapshotCache() {
            synchronized (mGlobalLock) {
                mTaskSnapshotController.clearSnapshotCache();
            }
        }

        @Override
        public void requestTraversalFromDisplayManager() {
            synchronized (mGlobalLock) {
                requestTraversal();
            }
        }

        @Override
        public void setMagnificationSpec(int displayId, MagnificationSpec spec) {
            synchronized (mGlobalLock) {
                if (mAccessibilityController.hasCallbacks()) {
                    mAccessibilityController.setMagnificationSpec(displayId, spec);
                } else {
                    throw new IllegalStateException("Magnification callbacks not set!");
                }
            }
        }

        @Override
        public void setForceShowMagnifiableBounds(int displayId, boolean show) {
            synchronized (mGlobalLock) {
                if (mAccessibilityController.hasCallbacks()) {
                    mAccessibilityController.setForceShowMagnifiableBounds(displayId, show);
                } else {
                    throw new IllegalStateException("Magnification callbacks not set!");
                }
            }
        }

        @Override
        public void getMagnificationRegion(int displayId, @NonNull Region magnificationRegion) {
            synchronized (mGlobalLock) {
                if (mAccessibilityController.hasCallbacks()) {
                    mAccessibilityController.getMagnificationRegion(displayId, magnificationRegion);
                } else {
                    throw new IllegalStateException("Magnification callbacks not set!");
                }
            }
        }

        @Override
        public boolean setMagnificationCallbacks(int displayId,
                @Nullable MagnificationCallbacks callbacks) {
            synchronized (mGlobalLock) {
                return mAccessibilityController.setMagnificationCallbacks(displayId, callbacks);
            }
        }

        @Override
        public void setWindowsForAccessibilityCallback(int displayId,
                WindowsForAccessibilityCallback callback) {
            synchronized (mGlobalLock) {
                mAccessibilityController.setWindowsForAccessibilityCallback(displayId, callback);
            }
        }

        @Override
        public void setInputFilter(IInputFilter filter) {
            mInputManager.setInputFilter(filter);
        }

        @Override
        public IBinder getFocusedWindowToken() {
            synchronized (mGlobalLock) {
                return mAccessibilityController.getFocusedWindowToken();
            }
        }

        // TODO (b/229837707): Delete this method after changing the solution.
        @Override
        public IBinder getFocusedWindowTokenFromWindowStates() {
            synchronized (mGlobalLock) {
                final WindowState windowState = getFocusedWindowLocked();
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
                    outBounds.set(windowState.getFrame());
                } else {
                    outBounds.setEmpty();
                }
            }
        }

        @Override
        public Pair<Matrix, MagnificationSpec> getWindowTransformationMatrixAndMagnificationSpec(
                IBinder token) {
            return mAccessibilityController
                    .getWindowTransformationMatrixAndMagnificationSpec(token);
        }

        @Override
        public void waitForAllWindowsDrawn(Runnable callback, long timeout, int displayId) {
            final WindowContainer container = displayId == INVALID_DISPLAY
                    ? mRoot : mRoot.getDisplayContent(displayId);
            if (container == null) {
                // The waiting container doesn't exist, no need to wait to run the callback. Run and
                // return;
                callback.run();
                return;
            }
            boolean allWindowsDrawn = false;
            synchronized (mGlobalLock) {
                container.waitForAllWindowsDrawn();
                mWindowPlacerLocked.requestTraversal();
                mH.removeMessages(H.WAITING_FOR_DRAWN_TIMEOUT, container);
                if (container.mWaitingForDrawn.isEmpty()) {
                    allWindowsDrawn = true;
                } else {
                    mWaitingForDrawnCallbacks.put(container, callback);
                    mH.sendNewMessageDelayed(H.WAITING_FOR_DRAWN_TIMEOUT, container, timeout);
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
        public void addWindowToken(IBinder token, int type, int displayId,
                @Nullable Bundle options) {
            WindowManagerService.this.addWindowToken(token, type, displayId, options);
        }

        @Override
        public void removeWindowToken(IBinder binder, boolean removeWindows, boolean animateExit,
                int displayId) {
            WindowManagerService.this.removeWindowToken(binder, removeWindows, animateExit,
                    displayId);
        }

        @Override
        public void moveWindowTokenToDisplay(IBinder binder, int displayId) {
            WindowManagerService.this.moveWindowTokenToDisplay(binder, displayId);
        }

        // TODO(multi-display): currently only used by PWM to notify keyguard transitions as well
        // forwarding it to SystemUI for synchronizing status and navigation bar animations.
        @Override
        public void registerAppTransitionListener(AppTransitionListener listener) {
            synchronized (mGlobalLock) {
                getDefaultDisplayContentLocked().mAppTransition.registerListenerLocked(listener);
                mAtmService.getTransitionController().registerLegacyListener(listener);
            }
        }

        @Override
        public void registerTaskSystemBarsListener(TaskSystemBarsListener listener) {
            synchronized (mGlobalLock) {
                mTaskSystemBarsListenerController.registerListener(listener);
            }
        }

        @Override
        public void unregisterTaskSystemBarsListener(TaskSystemBarsListener listener) {
            synchronized (mGlobalLock) {
                mTaskSystemBarsListenerController.unregisterListener(listener);
            }
        }

        @Override
        public void registerKeyguardExitAnimationStartListener(
                KeyguardExitAnimationStartListener listener) {
            synchronized (mGlobalLock) {
                getDefaultDisplayContentLocked().mAppTransition
                        .registerKeygaurdExitAnimationStartListener(listener);
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
                return dc.getInputMethodWindowVisibleHeight();
            }
        }

        @Override
        public void setDismissImeOnBackKeyPressed(boolean dismissImeOnBackKeyPressed) {
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
            synchronized (mGlobalLock) {
                InputTarget imeTarget =
                    getInputTargetFromWindowTokenLocked(imeTargetWindowToken);
                if (imeTarget != null) {
                    imeTarget.getDisplayContent().updateImeInputAndControlTarget(imeTarget);
                }
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
        public void computeWindowsForAccessibility(int displayId) {
            mAccessibilityController.performComputeChangedWindowsNot(displayId, true);
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
                    return window.mShowUserId;
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
        public @ImeClientFocusResult int hasInputMethodClientFocus(IBinder windowToken,
                int uid, int pid, int displayId) {
            if (displayId == Display.INVALID_DISPLAY) {
                return ImeClientFocusResult.INVALID_DISPLAY_ID;
            }
            synchronized (mGlobalLock) {
                final DisplayContent displayContent = mRoot.getTopFocusedDisplayContent();
                InputTarget target = getInputTargetFromWindowTokenLocked(windowToken);
                if (target == null) {
                    return ImeClientFocusResult.NOT_IME_TARGET_WINDOW;
                }
                final int tokenDisplayId = target.getDisplayContent().getDisplayId();
                if (tokenDisplayId != displayId) {
                    Slog.e(TAG, "isInputMethodClientFocus: display ID mismatch."
                            + " from client: " + displayId
                            + " from window: " + tokenDisplayId);
                    return ImeClientFocusResult.DISPLAY_ID_MISMATCH;
                }
                if (displayContent == null
                        || displayContent.getDisplayId() != displayId
                        || !displayContent.hasAccess(uid)) {
                    return ImeClientFocusResult.INVALID_DISPLAY_ID;
                }

                if (target.isInputMethodClientFocus(uid, pid)) {
                    return ImeClientFocusResult.HAS_IME_FOCUS;
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
                    return currentFocus.canBeImeTarget() ? ImeClientFocusResult.HAS_IME_FOCUS
                            : ImeClientFocusResult.NOT_IME_TARGET_WINDOW;
                }
            }
            return ImeClientFocusResult.NOT_IME_TARGET_WINDOW;
        }

        @Override
        public void showImePostLayout(IBinder imeTargetWindowToken) {
            synchronized (mGlobalLock) {
                InputTarget imeTarget = getInputTargetFromWindowTokenLocked(imeTargetWindowToken);
                if (imeTarget == null) {
                    return;
                }
                Trace.asyncTraceBegin(TRACE_TAG_WINDOW_MANAGER, "WMS.showImePostLayout", 0);
                final InsetsControlTarget controlTarget = imeTarget.getImeControlTarget();
                imeTarget = controlTarget.getWindow();
                // If InsetsControlTarget doesn't have a window, its using remoteControlTarget which
                // is controlled by default display
                final DisplayContent dc = imeTarget != null
                        ? imeTarget.getDisplayContent() : getDefaultDisplayContentLocked();
                dc.getInsetsStateController().getImeSourceProvider()
                        .scheduleShowImePostLayout(controlTarget);
            }
        }

        @Override
        public void hideIme(IBinder imeTargetWindowToken, int displayId) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "WMS.hideIme");
            synchronized (mGlobalLock) {
                WindowState imeTarget = mWindowMap.get(imeTargetWindowToken);
                ProtoLog.d(WM_DEBUG_IME, "hideIme target: %s ", imeTarget);
                DisplayContent dc = mRoot.getDisplayContent(displayId);
                if (imeTarget != null) {
                    imeTarget = imeTarget.getImeControlTarget().getWindow();
                    if (imeTarget != null) {
                        dc = imeTarget.getDisplayContent();
                    }
                    // If there was a pending IME show(), reset it as IME has been
                    // requested to be hidden.
                    dc.getInsetsStateController().getImeSourceProvider().abortShowImePostLayout();
                }
                if (dc != null && dc.getImeTarget(IME_TARGET_CONTROL) != null) {
                    ProtoLog.d(WM_DEBUG_IME, "hideIme Control target: %s ",
                            dc.getImeTarget(IME_TARGET_CONTROL));
                    dc.getImeTarget(IME_TARGET_CONTROL).hideInsets(
                            WindowInsets.Type.ime(), true /* fromIme */);
                }
                if (dc != null) {
                    dc.getInsetsStateController().getImeSourceProvider().setImeShowing(false);
                }
            }
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
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
        public int getTopFocusedDisplayId() {
            synchronized (mGlobalLock) {
                return mRoot.getTopFocusedDisplayContent().getDisplayId();
            }
        }

        @Override
        public Context getTopFocusedDisplayUiContext() {
            synchronized (mGlobalLock) {
                return mRoot.getTopFocusedDisplayContent().getDisplayUiContext();
            }
        }

        @Override
        public boolean shouldShowSystemDecorOnDisplay(int displayId) {
            synchronized (mGlobalLock) {
                return WindowManagerService.this.shouldShowSystemDecors(displayId);
            }
        }

        @Override
        public @DisplayImePolicy int getDisplayImePolicy(int displayId) {
            return WindowManagerService.this.getDisplayImePolicy(displayId);
        }

        @Override
        public void addRefreshRateRangeForPackage(@NonNull String packageName,
                float minRefreshRate, float maxRefreshRate) {
            synchronized (mGlobalLock) {
                mRoot.forAllDisplays(dc -> dc.getDisplayPolicy().getRefreshRatePolicy()
                        .addRefreshRateRangeForPackage(
                                packageName, minRefreshRate, maxRefreshRate));
            }
        }

        @Override
        public void removeRefreshRateRangeForPackage(@NonNull String packageName) {
            synchronized (mGlobalLock) {
                mRoot.forAllDisplays(dc -> dc.getDisplayPolicy().getRefreshRatePolicy()
                        .removeRefreshRateRangeForPackage(packageName));
            }
        }

        @Override
        public boolean isTouchOrFaketouchDevice() {
            synchronized (mGlobalLock) {
                if (mIsTouchDevice && !mIsFakeTouchDevice) {
                    throw new IllegalStateException(
                            "touchscreen supported device must report faketouch.");
                }
                return mIsFakeTouchDevice;
            }
        }

        @Override
        public @Nullable KeyInterceptionInfo getKeyInterceptionInfoFromToken(IBinder inputToken) {
            return mKeyInterceptionInfoForToken.get(inputToken);
        }

        @Override
        public void setAccessibilityIdToSurfaceMetadata(
                IBinder windowToken, int accessibilityWindowId) {
            synchronized (mGlobalLock) {
                final WindowState state = mWindowMap.get(windowToken);
                if (state == null) {
                    Slog.w(TAG, "Cannot find window which accessibility connection is added to");
                    return;
                }
                mTransaction.setMetadata(state.mSurfaceControl,
                        SurfaceControl.METADATA_ACCESSIBILITY_ID, accessibilityWindowId).apply();
            }
        }

        @Override
        public String getWindowName(@NonNull IBinder binder) {
            synchronized (mGlobalLock) {
                final WindowState w = mWindowMap.get(binder);
                return w != null ? w.getName() : null;
            }
        }

        @Override
        public ImeTargetInfo onToggleImeRequested(boolean show, IBinder focusedToken,
                IBinder requestToken, int displayId) {
            final String focusedWindowName;
            final String requestWindowName;
            final String imeControlTargetName;
            final String imeLayerTargetName;
            synchronized (mGlobalLock) {
                final WindowState focusedWin = mWindowMap.get(focusedToken);
                focusedWindowName = focusedWin != null ? focusedWin.getName() : "null";
                final WindowState requestWin = mWindowMap.get(requestToken);
                requestWindowName = requestWin != null ? requestWin.getName() : "null";
                final DisplayContent dc = mRoot.getDisplayContent(displayId);
                if (dc != null) {
                    final InsetsControlTarget controlTarget = dc.getImeTarget(IME_TARGET_CONTROL);
                    if (controlTarget != null) {
                        final WindowState w = InsetsControlTarget.asWindowOrNull(controlTarget);
                        imeControlTargetName = w != null ? w.getName() : controlTarget.toString();
                    } else {
                        imeControlTargetName = "null";
                    }
                    final InsetsControlTarget target = dc.getImeTarget(IME_TARGET_LAYERING);
                    imeLayerTargetName = target != null ? target.getWindow().getName() : "null";
                    if (show) {
                        dc.onShowImeRequested();
                    }
                } else {
                    imeControlTargetName = imeLayerTargetName = "no-display";
                }
            }
            return new ImeTargetInfo(focusedWindowName, requestWindowName, imeControlTargetName,
                    imeLayerTargetName);
        }

        @Override
        public boolean shouldRestoreImeVisibility(IBinder imeTargetWindowToken) {
            return WindowManagerService.this.shouldRestoreImeVisibility(imeTargetWindowToken);
       }

        @Override
        public void addTrustedTaskOverlay(int taskId,
                SurfaceControlViewHost.SurfacePackage overlay) {
            synchronized (mGlobalLock) {
                final Task task = mRoot.getRootTask(taskId);
                if (task == null) {
                    throw new IllegalArgumentException("no task with taskId" + taskId);
                }
                task.addTrustedOverlay(overlay, task.getTopVisibleAppMainWindow());
            }
        }

        @Override
        public void removeTrustedTaskOverlay(int taskId,
                SurfaceControlViewHost.SurfacePackage overlay) {
            synchronized (mGlobalLock) {
                final Task task = mRoot.getRootTask(taskId);
                if (task == null) {
                    throw new IllegalArgumentException("no task with taskId" + taskId);
                }
                task.removeTrustedOverlay(overlay);
            }
        }

        @Override
        public SurfaceControl getHandwritingSurfaceForDisplay(int displayId) {
            synchronized (mGlobalLock) {
                final DisplayContent dc = mRoot.getDisplayContent(displayId);
                if (dc == null) {
                    Slog.e(TAG, "Failed to create a handwriting surface on display: "
                            + displayId + " - DisplayContent not found.");
                    return null;
                }
                //TODO (b/210039666): Use a method like add/removeDisplayOverlay if available.
                return makeSurfaceBuilder(dc.getSession())
                        .setContainerLayer()
                        .setName("IME Handwriting Surface")
                        .setCallsite("getHandwritingSurfaceForDisplay")
                        .setParent(dc.getOverlayLayer())
                        .build();
            }
        }

        @Override
        public boolean isPointInsideWindow(@NonNull IBinder windowToken, int displayId,
                float displayX, float displayY) {
            synchronized (mGlobalLock) {
                final WindowState w = mWindowMap.get(windowToken);
                if (w == null || w.getDisplayId() != displayId) {
                    return false;
                }

                return w.getBounds().contains((int) displayX, (int) displayY);
            }
        }

        @Override
        public void setContentRecordingSession(@Nullable ContentRecordingSession incomingSession) {
            synchronized (mGlobalLock) {
                mContentRecordingController.setContentRecordingSessionLocked(incomingSession,
                        WindowManagerService.this);
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
        if (surfaceShown && win.hideNonSystemOverlayWindowsWhenVisible()) {
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
        return mSurfaceControlFactory.apply(s);
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
            final PooledConsumer c = PooledLambda.obtainConsumer(
                    DisplayPolicy::onLockTaskStateChangedLw, PooledLambda.__(), lockTaskState);
            mRoot.forAllDisplayPolicies(c);
            c.recycle();
        }
    }

    @Override
    public void syncInputTransactions(boolean waitForAnimations) {
        final long token = Binder.clearCallingIdentity();
        try {
            if (waitForAnimations) {
                waitForAnimationsToComplete();
            }

            // Collect all input transactions from all displays to make sure we could sync all input
            // windows at same time.
            final SurfaceControl.Transaction t = mTransactionFactory.get();
            synchronized (mGlobalLock) {
                mWindowPlacerLocked.performSurfacePlacementIfScheduled();
                mRoot.forAllDisplays(displayContent ->
                        displayContent.getInputMonitor().updateInputWindowsImmediately(t));
            }

            t.syncInputWindows().apply();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Wait until all container animations and surface operations behalf of WindowManagerService
     * complete.
     */
    private void waitForAnimationsToComplete() {
        synchronized (mGlobalLock) {
            long timeoutRemaining = ANIMATION_COMPLETED_TIMEOUT_MS;
            // This could prevent if there is no container animation, we still have to apply the
            // pending transaction and exit waiting.
            mAnimator.mNotifyWhenNoAnimation = true;
            boolean animateStarting = false;
            while (timeoutRemaining > 0) {
                // Waiting until all starting windows has finished animating.
                animateStarting = !mAtmService.getTransitionController().isShellTransitionsEnabled()
                        && mRoot.forAllActivities(ActivityRecord::hasStartingWindow);
                boolean isAnimating = mAnimator.isAnimationScheduled()
                        || mRoot.isAnimating(TRANSITION | CHILDREN, ANIMATION_TYPE_ALL)
                        || animateStarting;
                if (!isAnimating) {
                    // isAnimating is a legacy transition query and will be removed, so also add
                    // a check for whether this is in a shell-transition when not using legacy.
                    if (!mAtmService.getTransitionController().inTransition()) {
                        break;
                    }
                }
                long startTime = System.currentTimeMillis();
                try {
                    mGlobalLock.wait(timeoutRemaining);
                } catch (InterruptedException e) {
                }
                timeoutRemaining -= (System.currentTimeMillis() - startTime);
            }
            mAnimator.mNotifyWhenNoAnimation = false;

            WindowContainer animatingContainer;
            animatingContainer = mRoot.getAnimatingContainer(TRANSITION | CHILDREN,
                    ANIMATION_TYPE_ALL);
            if (mAnimator.isAnimationScheduled() || animatingContainer != null || animateStarting) {
                Slog.w(TAG, "Timed out waiting for animations to complete,"
                        + " animatingContainer=" + animatingContainer
                        + " animationType=" + SurfaceAnimator.animationTypeToString(
                        animatingContainer != null
                                ? animatingContainer.mSurfaceAnimator.getAnimationType()
                                : SurfaceAnimator.ANIMATION_TYPE_NONE)
                        + " animateStarting=" + animateStarting);
            }
        }
    }

    void onAnimationFinished() {
        synchronized (mGlobalLock) {
            mGlobalLock.notifyAll();
        }
    }

    private void onPointerDownOutsideFocusLocked(IBinder touchedToken) {
        InputTarget t = getInputTargetFromToken(touchedToken);
        if (t == null || !t.receiveFocusFromTapOutside()) {
            // If the window that received the input event cannot receive keys, don't move the
            // display it's on to the top since that window won't be able to get focus anyway.
            return;
        }
        if (mRecentsAnimationController != null
            && mRecentsAnimationController.getTargetAppMainWindow() == t) {
            // If there is an active recents animation and touched window is the target, then ignore
            // the touch. The target already handles touches using its own input monitor and we
            // don't want to trigger any lifecycle changes from focusing another window.
            // TODO(b/186770026): We should remove this once we support multiple resumed activities
            //                    while in overview
            return;
        }

        ProtoLog.i(WM_DEBUG_FOCUS_LIGHT, "onPointerDownOutsideFocusLocked called on %s",
                t);
        if (mFocusedInputTarget != t && mFocusedInputTarget != null) {
            mFocusedInputTarget.handleTapOutsideFocusOutsideSelf();
        }
        t.handleTapOutsideFocusInsideSelf();
    }

    @VisibleForTesting
    void handleTaskFocusChange(Task task, ActivityRecord touchedActivity) {
        if (task == null) {
            return;
        }

        // We ignore root home task since we don't want root home task to move to front when
        // touched. Specifically, in freeform we don't want tapping on home to cause the freeform
        // apps to go behind home. See b/117376413
        if (task.isActivityTypeHome()) {
            // Only ignore root home task if the requested focus home Task is in the same
            // TaskDisplayArea as the current focus Task.
            TaskDisplayArea homeTda = task.getDisplayArea();
            WindowState curFocusedWindow = getFocusedWindow();
            if (curFocusedWindow != null && homeTda != null
                    && curFocusedWindow.isDescendantOf(homeTda)) {
                return;
            }
        }

        mAtmService.setFocusedTask(task.mTaskId, touchedActivity);
    }

    /**
     * You need ALLOW_SLIPPERY_TOUCHES permission to be able to set FLAG_SLIPPERY.
     */
    private int sanitizeFlagSlippery(int flags, String windowName, int callingUid, int callingPid) {
        if ((flags & FLAG_SLIPPERY) == 0) {
            return flags;
        }
        final int permissionResult = mContext.checkPermission(
                    android.Manifest.permission.ALLOW_SLIPPERY_TOUCHES, callingPid, callingUid);
        if (permissionResult != PackageManager.PERMISSION_GRANTED) {
            Slog.w(TAG, "Removing FLAG_SLIPPERY from '" + windowName
                    + "' because it doesn't have ALLOW_SLIPPERY_TOUCHES permission");
            return flags & ~FLAG_SLIPPERY;
        }
        return flags;
    }

    /**
     * You need MONITOR_INPUT permission to be able to set INPUT_FEATURE_SPY.
     */
    private int sanitizeSpyWindow(int inputFeatures, String windowName, int callingUid,
            int callingPid) {
        if ((inputFeatures & INPUT_FEATURE_SPY) == 0) {
            return inputFeatures;
        }
        final int permissionResult = mContext.checkPermission(
                permission.MONITOR_INPUT, callingPid, callingUid);
        if (permissionResult != PackageManager.PERMISSION_GRANTED) {
            throw new IllegalArgumentException("Cannot use INPUT_FEATURE_SPY from '" + windowName
                    + "' because it doesn't the have MONITOR_INPUT permission");
        }
        return inputFeatures;
    }

    /**
     * Assigns an InputChannel to a SurfaceControl and configures it to receive
     * touch input according to it's on-screen geometry.
     *
     * Used by WindowlessWindowManager to enable input on SurfaceControl embedded
     * views.
     */
    void grantInputChannel(Session session, int callingUid, int callingPid, int displayId,
            SurfaceControl surface, IWindow window, IBinder hostInputToken,
            int flags, int privateFlags, int type, IBinder focusGrantToken,
            String inputHandleName, InputChannel outInputChannel) {
        final InputApplicationHandle applicationHandle;
        final String name;
        final InputChannel clientChannel;
        synchronized (mGlobalLock) {
            EmbeddedWindowController.EmbeddedWindow win =
                    new EmbeddedWindowController.EmbeddedWindow(session, this, window,
                            mInputToWindowMap.get(hostInputToken), callingUid, callingPid, type,
                            displayId, focusGrantToken, inputHandleName);
            clientChannel = win.openInputChannel();
            mEmbeddedWindowController.add(clientChannel.getToken(), win);
            applicationHandle = win.getApplicationHandle();
            name = win.toString();
        }

        updateInputChannel(clientChannel.getToken(), callingUid, callingPid, displayId, surface,
                name, applicationHandle, flags, privateFlags, type, null /* region */, window);

        clientChannel.copyTo(outInputChannel);
    }

    private void updateInputChannel(IBinder channelToken, int callingUid, int callingPid,
            int displayId, SurfaceControl surface, String name,
            InputApplicationHandle applicationHandle, int flags,
            int privateFlags, int type, Region region, IWindow window) {
        final InputWindowHandle h = new InputWindowHandle(applicationHandle, displayId);
        h.token = channelToken;
        h.setWindowToken(window);
        h.name = name;

        flags = sanitizeFlagSlippery(flags, name, callingUid, callingPid);

        final int sanitizedLpFlags =
                (flags & (FLAG_NOT_TOUCHABLE | FLAG_SLIPPERY | LayoutParams.FLAG_NOT_FOCUSABLE))
                | LayoutParams.FLAG_NOT_TOUCH_MODAL;
        h.layoutParamsType = type;
        h.layoutParamsFlags = sanitizedLpFlags;

        // Do not allow any input features to be set without sanitizing them first.
        h.inputConfig = InputConfigAdapter.getInputConfigFromWindowParams(
                        type, sanitizedLpFlags, 0 /*inputFeatures*/);


        if ((flags & LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
            h.inputConfig |= InputConfig.NOT_FOCUSABLE;
        }

        //  Check private trusted overlay flag to set trustedOverlay field of input window handle.
        if ((privateFlags & PRIVATE_FLAG_TRUSTED_OVERLAY) != 0) {
            h.inputConfig |= InputConfig.TRUSTED_OVERLAY;
        }

        h.dispatchingTimeoutMillis = DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
        h.ownerUid = callingUid;
        h.ownerPid = callingPid;

        if (region == null) {
            h.replaceTouchableRegionWithCrop = true;
        } else {
            h.touchableRegion.set(region);
        }
        h.setTouchableRegionCrop(null /* use the input surface's bounds */);

        final SurfaceControl.Transaction t = mTransactionFactory.get();
        t.setInputWindowInfo(surface, h);
        t.apply();
        t.close();
        surface.release();
    }

    /**
     * Updates the flags on an existing surface's input channel. This assumes the surface provided
     * is the one associated with the provided input-channel. If this isn't the case, behavior
     * is undefined.
     */
    void updateInputChannel(IBinder channelToken, int displayId, SurfaceControl surface,
            int flags, int privateFlags, Region region) {
        final InputApplicationHandle applicationHandle;
        final String name;
        final EmbeddedWindowController.EmbeddedWindow win;
        synchronized (mGlobalLock) {
            win = mEmbeddedWindowController.get(channelToken);
            if (win == null) {
                Slog.e(TAG, "Couldn't find window for provided channelToken.");
                return;
            }
            name = win.toString();
            applicationHandle = win.getApplicationHandle();
        }

        updateInputChannel(channelToken, win.mOwnerUid, win.mOwnerPid, displayId, surface, name,
                applicationHandle, flags, privateFlags, win.mWindowType, region, win.mClient);
    }

    /** Return whether layer tracing is enabled */
    public boolean isLayerTracing() {
        if (!checkCallingPermission(
                android.Manifest.permission.DUMP, "isLayerTracing()")) {
            throw new SecurityException("Requires DUMP permission");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            Parcel data = null;
            Parcel reply = null;
            try {
                IBinder sf = ServiceManager.getService("SurfaceFlinger");
                if (sf != null) {
                    reply = Parcel.obtain();
                    data = Parcel.obtain();
                    data.writeInterfaceToken("android.ui.ISurfaceComposer");
                    sf.transact(/* LAYER_TRACE_STATUS_CODE */ 1026, data, reply, 0 /* flags */);
                    return reply.readBoolean();
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get layer tracing");
            } finally {
                if (data != null) {
                    data.recycle();
                }
                if (reply != null) {
                    reply.recycle();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return false;
    }

    /** Enable or disable layer tracing */
    public void setLayerTracing(boolean enabled) {
        if (!checkCallingPermission(
                android.Manifest.permission.DUMP, "setLayerTracing()")) {
            throw new SecurityException("Requires DUMP permission");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            Parcel data = null;
            try {
                IBinder sf = ServiceManager.getService("SurfaceFlinger");
                if (sf != null) {
                    data = Parcel.obtain();
                    data.writeInterfaceToken("android.ui.ISurfaceComposer");
                    data.writeInt(enabled ? 1 : 0);
                    sf.transact(/* LAYER_TRACE_CONTROL_CODE */ 1025, data, null, 0 /* flags */);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set layer tracing");
            } finally {
                if (data != null) {
                    data.recycle();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /** Set layer tracing flags. */
    public void setLayerTracingFlags(int flags) {
        if (!checkCallingPermission(
                android.Manifest.permission.DUMP, "setLayerTracingFlags")) {
            throw new SecurityException("Requires DUMP permission");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            Parcel data = null;
            try {
                IBinder sf = ServiceManager.getService("SurfaceFlinger");
                if (sf != null) {
                    data = Parcel.obtain();
                    data.writeInterfaceToken("android.ui.ISurfaceComposer");
                    data.writeInt(flags);
                    sf.transact(1033 /* LAYER_TRACE_FLAGS_CODE */, data, null, 0 /* flags */);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set layer tracing flags");
            } finally {
                if (data != null) {
                    data.recycle();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Toggle active transaction tracing.
     * Setting to true increases the buffer size for active debugging.
     * Setting to false resets the buffer size and dumps the trace to file.
     */
    public void setActiveTransactionTracing(boolean active) {
        if (!checkCallingPermission(
                android.Manifest.permission.DUMP, "setActiveTransactionTracing()")) {
            throw new SecurityException("Requires DUMP permission");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            Parcel data = null;
            try {
                IBinder sf = ServiceManager.getService("SurfaceFlinger");
                if (sf != null) {
                    data = Parcel.obtain();
                    data.writeInterfaceToken("android.ui.ISurfaceComposer");
                    data.writeInt(active ? 1 : 0);
                    sf.transact(/* TRANSACTION_TRACE_CONTROL_CODE */ 1041, data,
                            null, 0 /* flags */);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set transaction tracing");
            } finally {
                if (data != null) {
                    data.recycle();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean mirrorDisplay(int displayId, SurfaceControl outSurfaceControl) {
        if (!checkCallingPermission(READ_FRAME_BUFFER, "mirrorDisplay()")) {
            throw new SecurityException("Requires READ_FRAME_BUFFER permission");
        }

        final SurfaceControl displaySc;
        synchronized (mGlobalLock) {
            DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent == null) {
                Slog.e(TAG, "Invalid displayId " + displayId + " for mirrorDisplay");
                return false;
            }

            displaySc = displayContent.getWindowingLayer();
        }

        final SurfaceControl mirror = SurfaceControl.mirrorSurface(displaySc);
        outSurfaceControl.copyFrom(mirror, "WMS.mirrorDisplay");

        return true;
    }

    @Override
    public boolean getWindowInsets(WindowManager.LayoutParams attrs, int displayId,
            InsetsState outInsetsState) {
        final boolean fromLocal = Binder.getCallingPid() == MY_PID;
        final int uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent dc = getDisplayContentOrCreate(displayId, attrs.token);
                if (dc == null) {
                    throw new WindowManager.InvalidDisplayException("Display#" + displayId
                            + "could not be found!");
                }
                final WindowToken token = dc.getWindowToken(attrs.token);
                final float overrideScale = mAtmService.mCompatModePackages.getCompatScale(
                        attrs.packageName, uid);
                final InsetsState state = dc.getInsetsPolicy().getInsetsForWindowMetrics(attrs);
                final boolean hasCompatScale =
                        WindowState.hasCompatScale(attrs, token, overrideScale);
                outInsetsState.set(state, hasCompatScale || fromLocal);
                if (hasCompatScale) {
                    final float compatScale = token != null && token.hasSizeCompatBounds()
                            ? token.getSizeCompatScale() * overrideScale
                            : overrideScale;
                    outInsetsState.scale(1f / compatScale);
                }
                return dc.getDisplayPolicy().areSystemBarsForcedShownLw();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public List<DisplayInfo> getPossibleDisplayInfo(int displayId, String packageName) {
        final int callingUid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                if (packageName == null || !isRecentsComponent(packageName, callingUid)) {
                    Slog.e(TAG, "Unable to verify uid for package " + packageName
                            + " for getPossibleMaximumWindowMetrics");
                    return new ArrayList<>();
                }

                // Retrieve the DisplayInfo across all possible display layouts.
                return List.copyOf(mPossibleDisplayInfoMapper.getPossibleDisplayInfos(displayId));
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Returns {@code true} when the calling package is the recents component.
     */
    boolean isRecentsComponent(@NonNull String callingPackageName, int callingUid) {
        String recentsPackage;
        try {
            String recentsComponent = mContext.getResources().getString(
                    R.string.config_recentsComponentName);
            if (recentsComponent == null) {
                return false;
            }
            recentsPackage = ComponentName.unflattenFromString(recentsComponent).getPackageName();
        } catch (Resources.NotFoundException e) {
            Slog.e(TAG, "Unable to verify if recents component", e);
            return false;
        }
        try {
            return callingUid == mContext.getPackageManager().getPackageUid(callingPackageName, 0)
                    && callingPackageName.equals(recentsPackage);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Unable to verify if recents component", e);
            return false;
        }
    }

    void grantEmbeddedWindowFocus(Session session, IBinder focusToken, boolean grantFocus) {
        synchronized (mGlobalLock) {
            final EmbeddedWindowController.EmbeddedWindow embeddedWindow =
                    mEmbeddedWindowController.getByFocusToken(focusToken);
            if (embeddedWindow == null) {
                Slog.e(TAG, "Embedded window not found");
                return;
            }
            if (embeddedWindow.mSession != session) {
                Slog.e(TAG, "Window not in session:" + session);
                return;
            }
            IBinder inputToken = embeddedWindow.getInputChannelToken();
            if (inputToken == null) {
                Slog.e(TAG, "Focus token found but input channel token not found");
                return;
            }
            SurfaceControl.Transaction t = mTransactionFactory.get();
            final int displayId = embeddedWindow.mDisplayId;
            if (grantFocus) {
                t.setFocusedWindow(inputToken, embeddedWindow.toString(), displayId).apply();
                EventLog.writeEvent(LOGTAG_INPUT_FOCUS,
                        "Focus request " + embeddedWindow, "reason=grantEmbeddedWindowFocus(true)");
            } else {
                // Search for a new focus target
                DisplayContent displayContent = mRoot.getDisplayContent(displayId);
                WindowState newFocusTarget =  displayContent == null
                        ? null : displayContent.findFocusedWindow();
                if (newFocusTarget == null) {
                    t.setFocusedWindow(null, null, displayId).apply();
                    ProtoLog.v(WM_DEBUG_FOCUS, "grantEmbeddedWindowFocus win=%s"
                                    + " dropped focus so setting focus to null since no candidate"
                                    + " was found",
                            embeddedWindow);
                    return;
                }
                t.setFocusedWindow(newFocusTarget.mInputChannelToken, newFocusTarget.getName(),
                        displayId).apply();

                EventLog.writeEvent(LOGTAG_INPUT_FOCUS,
                        "Focus request " + newFocusTarget,
                        "reason=grantEmbeddedWindowFocus(false)");
            }
            ProtoLog.v(WM_DEBUG_FOCUS, "grantEmbeddedWindowFocus win=%s grantFocus=%s",
                    embeddedWindow, grantFocus);
        }
    }

    void grantEmbeddedWindowFocus(Session session, IWindow callingWindow, IBinder targetFocusToken,
                                  boolean grantFocus) {
        synchronized (mGlobalLock) {
            final WindowState hostWindow =
                    windowForClientLocked(session, callingWindow, false /* throwOnError*/);
            if (hostWindow == null) {
                Slog.e(TAG, "Host window not found");
                return;
            }
            if (hostWindow.mInputChannel == null) {
                Slog.e(TAG, "Host window does not have an input channel");
                return;
            }
            final EmbeddedWindowController.EmbeddedWindow embeddedWindow =
                    mEmbeddedWindowController.getByFocusToken(targetFocusToken);
            if (embeddedWindow == null) {
                Slog.e(TAG, "Embedded window not found");
                return;
            }
            if (embeddedWindow.mHostWindowState != hostWindow) {
                Slog.e(TAG, "Embedded window does not belong to the host");
                return;
            }
            SurfaceControl.Transaction t = mTransactionFactory.get();
            if (grantFocus) {
                t.requestFocusTransfer(embeddedWindow.getInputChannelToken(), embeddedWindow.toString(),
                        hostWindow.mInputChannel.getToken(),
                        hostWindow.getName(),
                        hostWindow.getDisplayId()).apply();
                EventLog.writeEvent(LOGTAG_INPUT_FOCUS,
                        "Transfer focus request " + embeddedWindow,
                        "reason=grantEmbeddedWindowFocus(true)");
            } else {
                t.requestFocusTransfer(hostWindow.mInputChannel.getToken(), hostWindow.getName(),
                        embeddedWindow.getInputChannelToken(),
                        embeddedWindow.toString(),
                        hostWindow.getDisplayId()).apply();
                EventLog.writeEvent(LOGTAG_INPUT_FOCUS,
                        "Transfer focus request " + hostWindow,
                        "reason=grantEmbeddedWindowFocus(false)");
            }
            ProtoLog.v(WM_DEBUG_FOCUS, "grantEmbeddedWindowFocus win=%s grantFocus=%s",
                    embeddedWindow, grantFocus);
        }
    }

    @Override
    public void holdLock(IBinder token, int durationMs) {
        mTestUtilityService.verifyHoldLockToken(token);

        synchronized (mGlobalLock) {
            SystemClock.sleep(durationMs);
        }
    }

    @Override
    public String[] getSupportedDisplayHashAlgorithms() {
        return mDisplayHashController.getSupportedHashAlgorithms();
    }

    @Override
    public VerifiedDisplayHash verifyDisplayHash(DisplayHash displayHash) {
        return mDisplayHashController.verifyDisplayHash(displayHash);
    }

    @Override
    public void setDisplayHashThrottlingEnabled(boolean enable) {
        if (!checkCallingPermission(READ_FRAME_BUFFER, "setDisplayHashThrottle()")) {
            throw new SecurityException("Requires READ_FRAME_BUFFER permission");
        }
        mDisplayHashController.setDisplayHashThrottlingEnabled(enable);
    }

    @Override
    public boolean isTaskSnapshotSupported() {
        synchronized (mGlobalLock) {
            return !mTaskSnapshotController.shouldDisableSnapshots();
        }
    }

    void generateDisplayHash(Session session, IWindow window, Rect boundsInWindow,
            String hashAlgorithm, RemoteCallback callback) {
        final SurfaceControl displaySurfaceControl;
        final Rect boundsInDisplay = new Rect(boundsInWindow);
        synchronized (mGlobalLock) {
            final WindowState win = windowForClientLocked(session, window, false);
            if (win == null) {
                Slog.w(TAG, "Failed to generate DisplayHash. Invalid window");
                mDisplayHashController.sendDisplayHashError(callback,
                        DISPLAY_HASH_ERROR_MISSING_WINDOW);
                return;
            }

            if (win.mActivityRecord == null || !win.mActivityRecord.isState(
                    ActivityRecord.State.RESUMED)) {
                mDisplayHashController.sendDisplayHashError(callback,
                        DISPLAY_HASH_ERROR_MISSING_WINDOW);
                return;
            }

            DisplayContent displayContent = win.getDisplayContent();
            if (displayContent == null) {
                Slog.w(TAG, "Failed to generate DisplayHash. Window is not on a display");
                mDisplayHashController.sendDisplayHashError(callback,
                        DISPLAY_HASH_ERROR_NOT_VISIBLE_ON_SCREEN);
                return;
            }

            displaySurfaceControl = displayContent.getSurfaceControl();
            mDisplayHashController.calculateDisplayHashBoundsLocked(win, boundsInWindow,
                    boundsInDisplay);

            if (boundsInDisplay.isEmpty()) {
                Slog.w(TAG, "Failed to generate DisplayHash. Bounds are not on screen");
                mDisplayHashController.sendDisplayHashError(callback,
                        DISPLAY_HASH_ERROR_NOT_VISIBLE_ON_SCREEN);
                return;
            }
        }

        // A screenshot of the entire display is taken rather than just the window. This is
        // because if we take a screenshot of the window, it will not include content that might
        // be covering it with the same uid. We want to make sure we include content that's
        // covering to ensure we get as close as possible to what the user sees
        final int uid = session.mUid;
        SurfaceControl.LayerCaptureArgs.Builder args =
                new SurfaceControl.LayerCaptureArgs.Builder(displaySurfaceControl)
                        .setUid(uid)
                        .setSourceCrop(boundsInDisplay);

        mDisplayHashController.generateDisplayHash(args, boundsInWindow, hashAlgorithm, uid,
                callback);
    }

    boolean shouldRestoreImeVisibility(IBinder imeTargetWindowToken) {
        final Task imeTargetWindowTask;
        synchronized (mGlobalLock) {
            final WindowState imeTargetWindow = mWindowMap.get(imeTargetWindowToken);
            if (imeTargetWindow == null) {
                return false;
            }
            imeTargetWindowTask = imeTargetWindow.getTask();
            if (imeTargetWindowTask == null) {
                return false;
            }
        }
        final TaskSnapshot snapshot = getTaskSnapshot(imeTargetWindowTask.mTaskId,
                imeTargetWindowTask.mUserId, false /* isLowResolution */,
                false /* restoreFromDisk */);
        return snapshot != null && snapshot.hasImeSurface();
    }

    @Override
    public int getImeDisplayId() {
        // TODO(b/189805422): Add a toast to notify users that IMS may get extra
        //  onConfigurationChanged callback when perDisplayFocus is enabled.
        //  Enabling perDisplayFocus means that we track focus on each display, so we don't have
        //  the "top focus" display and getTopFocusedDisplayContent returns the default display
        //  as the fallback. It leads to InputMethodService receives an extra onConfiguration
        //  callback when InputMethodService move from a secondary display to another display
        //  with the same display metrics because InputMethodService will always associate with
        //  the ImeContainer on the default display in onCreate and receive a configuration update
        //  to match default display ImeContainer and then receive another configuration update
        //  from attachToWindowToken.
        synchronized (mGlobalLock) {
            final DisplayContent dc = mRoot.getTopFocusedDisplayContent();
            return dc.getImePolicy() == DISPLAY_IME_POLICY_LOCAL ? dc.getDisplayId()
                    : DEFAULT_DISPLAY;
        }
    }

    @Override
    public void setTaskSnapshotEnabled(boolean enabled) {
        mTaskSnapshotController.setTaskSnapshotEnabled(enabled);
    }

    @Override
    public void setTaskTransitionSpec(TaskTransitionSpec spec) {
        if (!checkCallingPermission(MANAGE_ACTIVITY_TASKS, "setTaskTransitionSpec()")) {
            throw new SecurityException("Requires MANAGE_ACTIVITY_TASKS permission");
        }

        mTaskTransitionSpec = spec;
    }

    @Override
    public void clearTaskTransitionSpec() {
        if (!checkCallingPermission(MANAGE_ACTIVITY_TASKS, "clearTaskTransitionSpec()")) {
            throw new SecurityException("Requires MANAGE_ACTIVITY_TASKS permission");
        }

        mTaskTransitionSpec = null;
    }

    @Override
    @RequiresPermission(Manifest.permission.ACCESS_FPS_COUNTER)
    public void registerTaskFpsCallback(@IntRange(from = 0) int taskId,
            ITaskFpsCallback callback) {
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.ACCESS_FPS_COUNTER)
                != PackageManager.PERMISSION_GRANTED) {
            final int pid = Binder.getCallingPid();
            throw new SecurityException("Access denied to process: " + pid
                    + ", must have permission " + Manifest.permission.ACCESS_FPS_COUNTER);
        }

        if (mRoot.anyTaskForId(taskId) == null) {
            throw new IllegalArgumentException("no task with taskId: " + taskId);
        }

        mTaskFpsCallbackController.registerListener(taskId, callback);
    }

    @Override
    @RequiresPermission(Manifest.permission.ACCESS_FPS_COUNTER)
    public void unregisterTaskFpsCallback(ITaskFpsCallback callback) {
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.ACCESS_FPS_COUNTER)
                != PackageManager.PERMISSION_GRANTED) {
            final int pid = Binder.getCallingPid();
            throw new SecurityException("Access denied to process: " + pid
                    + ", must have permission " + Manifest.permission.ACCESS_FPS_COUNTER);
        }

        mTaskFpsCallbackController.unregisterListener(callback);
    }

    @Override
    public Bitmap snapshotTaskForRecents(int taskId) {
        if (!checkCallingPermission(READ_FRAME_BUFFER, "snapshotTaskForRecents()")) {
            throw new SecurityException("Requires READ_FRAME_BUFFER permission");
        }

        TaskSnapshot taskSnapshot;
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                Task task = mRoot.anyTaskForId(taskId, MATCH_ATTACHED_TASK_OR_RECENT_TASKS);
                if (task == null) {
                    throw new IllegalArgumentException(
                            "Failed to find matching task for taskId=" + taskId);
                }
                taskSnapshot = mTaskSnapshotController.captureTaskSnapshot(task, false);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        if (taskSnapshot == null || taskSnapshot.getHardwareBuffer() == null) {
            return null;
        }
        return Bitmap.wrapHardwareBuffer(taskSnapshot.getHardwareBuffer(),
                taskSnapshot.getColorSpace());
    }

    @Override
    public void setRecentsAppBehindSystemBars(boolean behindSystemBars) {
        if (!checkCallingPermission(START_TASKS_FROM_RECENTS, "setRecentsAppBehindSystemBars()")) {
            throw new SecurityException("Requires START_TASKS_FROM_RECENTS permission");
        }
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final Task recentsApp = mRoot.getTask(task -> task.isActivityTypeHomeOrRecents()
                        && task.getTopVisibleActivity() != null);
                if (recentsApp != null) {
                    recentsApp.getTask().setCanAffectSystemUiFlags(behindSystemBars);
                    mWindowPlacerLocked.requestTraversal();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
}
