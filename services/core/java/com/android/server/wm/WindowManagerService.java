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

import static android.Manifest.permission.ACCESS_SURFACE_FLINGER;
import static android.Manifest.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS;
import static android.Manifest.permission.INPUT_CONSUMER;
import static android.Manifest.permission.INTERNAL_SYSTEM_WINDOW;
import static android.Manifest.permission.MANAGE_APP_TOKENS;
import static android.Manifest.permission.MODIFY_TOUCH_MODE_STATE;
import static android.Manifest.permission.READ_FRAME_BUFFER;
import static android.Manifest.permission.REGISTER_WINDOW_MANAGER_LISTENERS;
import static android.Manifest.permission.START_TASKS_FROM_RECENTS;
import static android.Manifest.permission.STATUS_BAR_SERVICE;
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
import static android.permission.flags.Flags.sensitiveContentImprovements;
import static android.permission.flags.Flags.sensitiveContentMetricsBugfix;
import static android.permission.flags.Flags.sensitiveContentRecentsScreenshotBugfix;
import static android.provider.Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT;
import static android.provider.Settings.Global.DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW;
import static android.provider.Settings.Global.DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS;
import static android.provider.Settings.Global.DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES;
import static android.provider.Settings.Global.DEVELOPMENT_WM_DISPLAY_SETTINGS_PATH;
import static android.service.dreams.Flags.dreamHandlesConfirmKeys;
import static android.view.ContentRecordingSession.RECORD_CONTENT_TASK;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.flags.Flags.sensitiveContentAppProtection;
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
import static android.view.WindowManager.LayoutParams.INPUT_FEATURE_SENSITIVE_FOR_PRIVACY;
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
import static android.view.WindowManager.LayoutParams.TYPE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_QS_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManager.REMOVE_CONTENT_MODE_UNDEFINED;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.fixScale;
import static android.view.WindowManagerGlobal.ADD_OKAY;
import static android.view.WindowManagerGlobal.RELAYOUT_RES_CANCEL_AND_REDRAW;
import static android.view.WindowManagerGlobal.RELAYOUT_RES_SURFACE_CHANGED;
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
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_SCREEN_ON;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_STARTING_WINDOW;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WINDOW_MOVEMENT;
import static com.android.internal.protolog.ProtoLogGroup.WM_ERROR;
import static com.android.internal.protolog.ProtoLogGroup.WM_SHOW_TRANSACTIONS;
import static com.android.internal.util.FrameworkStatsLog.SENSITIVE_NOTIFICATION_APP_PROTECTION_APPLIED;
import static com.android.internal.util.LatencyTracker.ACTION_ROTATE_SCREEN;
import static com.android.server.LockGuard.INDEX_WINDOW;
import static com.android.server.LockGuard.installLock;
import static com.android.server.policy.PhoneWindowManager.TRACE_WAIT_FOR_ALL_WINDOWS_DRAWN_METHOD;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.ActivityTaskManagerService.POWER_MODE_REASON_CHANGE_DISPLAY;
import static com.android.server.wm.DisplayContent.IME_TARGET_CONTROL;
import static com.android.server.wm.DisplayContent.IME_TARGET_LAYERING;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_BACKGROUND_SOLID_COLOR;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_BACKGROUND_WALLPAPER;
import static com.android.server.wm.RootWindowContainer.MATCH_ATTACHED_TASK_OR_RECENT_TASKS;
import static com.android.server.wm.SensitiveContentPackages.PackageInfo;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_ALL;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_APP_TRANSITION;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_RECENTS;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION;
import static com.android.server.wm.WindowContainer.AnimationFlags.CHILDREN;
import static com.android.server.wm.WindowContainer.AnimationFlags.PARENTS;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
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
import static com.android.server.wm.WindowManagerInternal.OnWindowRemovedListener;
import static com.android.server.wm.WindowManagerServiceDumpProto.BACK_NAVIGATION;
import static com.android.server.wm.WindowManagerServiceDumpProto.DISPLAY_FROZEN;
import static com.android.server.wm.WindowManagerServiceDumpProto.FOCUSED_APP;
import static com.android.server.wm.WindowManagerServiceDumpProto.FOCUSED_DISPLAY_ID;
import static com.android.server.wm.WindowManagerServiceDumpProto.FOCUSED_WINDOW;
import static com.android.server.wm.WindowManagerServiceDumpProto.HARD_KEYBOARD_AVAILABLE;
import static com.android.server.wm.WindowManagerServiceDumpProto.INPUT_METHOD_WINDOW;
import static com.android.server.wm.WindowManagerServiceDumpProto.POLICY;
import static com.android.server.wm.WindowManagerServiceDumpProto.ROOT_WINDOW_CONTAINER;
import static com.android.server.wm.WindowManagerServiceDumpProto.WINDOW_FRAMES_VALID;
import static com.android.window.flags.Flags.multiCrop;
import static com.android.window.flags.Flags.setScPropertiesInClient;

import android.Manifest;
import android.Manifest.permission;
import android.animation.ValueAnimator;
import android.annotation.EnforcePermission;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityThread;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.IApplicationThread;
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
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.configstore.V1_0.OptionalBool;
import android.hardware.configstore.V1_1.ISurfaceFlingerConfigs;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.input.InputSettings;
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
import android.util.IntArray;
import android.util.MergedConfiguration;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
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
import android.view.IDecorViewGestureListener;
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
import android.view.InsetsFrameProvider;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.KeyEvent;
import android.view.MagnificationSpec;
import android.view.RemoteAnimationAdapter;
import android.view.ScrollCaptureResponse;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceSession;
import android.view.View;
import android.view.View.FocusDirection;
import android.view.ViewDebug;
import android.view.WindowContentFrameStats;
import android.view.WindowInsets;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowManager;
import android.view.WindowManager.DisplayImePolicy;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManager.RemoveContentMode;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerPolicyConstants.PointerEventListener;
import android.view.WindowRelayoutResult;
import android.view.displayhash.DisplayHash;
import android.view.displayhash.VerifiedDisplayHash;
import android.view.inputmethod.ImeTracker;
import android.widget.Toast;
import android.window.ActivityWindowInfo;
import android.window.AddToSurfaceSyncGroupResult;
import android.window.ClientWindowFrames;
import android.window.IGlobalDragListener;
import android.window.IScreenRecordingCallback;
import android.window.ISurfaceSyncGroupCompletedListener;
import android.window.ITaskFpsCallback;
import android.window.ITrustedPresentationListener;
import android.window.InputTransferToken;
import android.window.ScreenCapture;
import android.window.ScreenCapture.ScreenshotHardwareBuffer;
import android.window.SystemPerformanceHinter;
import android.window.TaskSnapshot;
import android.window.TrustedPresentationThresholds;
import android.window.WindowContainerToken;
import android.window.WindowContextInfo;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.IResultReceiver;
import com.android.internal.os.TransferPipe;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IKeyguardLockedStateListener;
import com.android.internal.policy.IShortcutService;
import com.android.internal.policy.KeyInterceptionInfo;
import com.android.internal.protolog.LegacyProtoLogImpl;
import com.android.internal.protolog.ProtoLogGroup;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.LatencyTracker;
import com.android.internal.view.WindowManagerPolicyThread;
import com.android.server.AnimationThread;
import com.android.server.DisplayThread;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemConfig;
import com.android.server.UiThread;
import com.android.server.Watchdog;
import com.android.server.input.InputManagerService;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.policy.WindowManagerPolicy.ScreenOffListener;
import com.android.server.power.ShutdownThread;
import com.android.server.utils.PriorityDump;
import com.android.server.wallpaper.WallpaperCropper.WallpaperCropUtils;
import com.android.window.flags.Flags;

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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/** {@hide} */
public class WindowManagerService extends IWindowManager.Stub
        implements Watchdog.Monitor, WindowManagerPolicy.WindowManagerFuncs {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "WindowManagerService" : TAG_WM;
    private static final int TRACE_MAX_SECTION_NAME_LENGTH = 127;

    static final int LAYOUT_REPEAT_THRESHOLD = 4;

    static final boolean PROFILE_ORIENTATION = false;

    /** The maximum length we will accept for a loaded animation duration:
     * this is 10 seconds.
     */
    static final int MAX_ANIMATION_DURATION = 10 * 1000;

    /** Amount of time (in milliseconds) to delay before declaring a window freeze timeout. */
    static final int WINDOW_FREEZE_TIMEOUT_DURATION = 2000;

    /** Amount of time to allow a last ANR message to exist before freeing the memory. */
    static final int LAST_ANR_LIFETIME_DURATION_MSECS = 2 * 60 * 60 * 1000; // Two hours

    // Maximum number of milliseconds to wait for input devices to be enumerated before
    // proceding with safe mode detection.
    private static final int INPUT_DEVICES_READY_FOR_SAFE_MODE_DETECTION_TIMEOUT_MILLIS = 1000;

    private static final int SYNC_INPUT_TRANSACTIONS_TIMEOUT_MS = 5000;

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

    private static final String PROPERTY_EMULATOR_CIRCULAR = "ro.boot.emulator.circular";

    static final int MY_PID = myPid();
    static final int MY_UID = myUid();

    static final int LOGTAG_INPUT_FOCUS = 62001;

    /**
     * Use WMShell for app transition.
     */
    private static final String ENABLE_SHELL_TRANSITIONS = "persist.wm.debug.shell_transit";

    /**
     * @see #ENABLE_SHELL_TRANSITIONS
     */
    public static final boolean sEnableShellTransitions = getShellTransitEnabled();

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

    private final List<OnWindowRemovedListener> mOnWindowRemovedListeners = new ArrayList<>();

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
                mRoot.forAllDisplayPolicies(p -> p.onVrStateChangedLw(enabled));
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
        public void dumpHigh(FileDescriptor fd, PrintWriter pw, String[] args,
                boolean asProto) {
            if (asProto) {
                return;
            }

            final long timeoutMs = 1000L;
            mAtmService.dumpActivity(fd, pw, /* name= */ "all", /* args= */ new String[]{},
                    /* opti= */ 0,
                    /* dumpAll= */ true,
                    /* dumpVisibleRootTasksOnly= */ true,
                    /* dumpFocusedRootTaskOnly= */ false, INVALID_DISPLAY, UserHandle.USER_ALL,
                    timeoutMs
            );
            dumpVisibleWindowClients(fd, pw, timeoutMs);
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
            doDump(fd, pw, args, asProto);
        }
    };

    /**
     * Current user when multi-user is enabled. Don't show windows of non-current user.
     */
    @UserIdInt int mCurrentUserId;

    final Context mContext;

    final boolean mHasPermanentDpad;
    final long mDrawLockTimeoutMillis;
    final boolean mAllowAnimationsInLowPowerMode;
    final boolean mSupportsHighPerfTransitions;
    final boolean mAllowBootMessages;

    // Indicates whether the Assistant should show on top of the Dream (respectively, above
    // everything else on screen). Otherwise, it will be put under always-on-top stacks.
    final boolean mAssistantOnTopOfDream;

    /**
     * If true, don't relaunch the activity upon receiving a configuration change to transition to
     * or from the {@link UI_MODE_TYPE_DESK} uiMode, which is sent when docking. The configuration
     * change will still be sent regardless, only the relaunch is skipped. Apps with desk resources
     * are exempt from this and will behave like normal, since they may expect the relaunch upon the
     * desk uiMode change.
     */
    @VisibleForTesting
    boolean mSkipActivityRelaunchWhenDocking;

    /** Device default insets types provided non-decor insets. */
    final int mDecorTypes;

    /** Device default insets types shall be excluded from config app sizes. */
    final int mConfigTypes;

    final int mOverrideConfigTypes;

    final int mOverrideDecorTypes;

    final boolean mLimitedAlphaCompositing;
    final int mMaxUiWidth;

    @VisibleForTesting
    WindowManagerPolicy mPolicy;

    final WindowManagerFlags mFlags;

    final IActivityManager mActivityManager;
    final ActivityManagerInternal mAmInternal;
    final UserManagerInternal mUmInternal;

    final AppOpsManager mAppOps;
    final PackageManagerInternal mPmInternal;
    private final TestUtilityService mTestUtilityService;

    @NonNull
    final DisplayWindowSettingsProvider mDisplayWindowSettingsProvider;
    @NonNull
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

    /** Global service lock used by the package that owns this service. */
    final WindowManagerGlobalLock mGlobalLock;

    /**
     * Windows that are being resized.  Used so we can tell the client about
     * the resize after closing the transaction in which we resized the
     * underlying surface.
     */
    final ArrayList<WindowState> mResizingWindows = new ArrayList<>();

    /**
     * Windows that their frames are being changed.  Used so we can clear the frame-changing states
     * after handling the moved or resized windows.
     */
    final ArrayList<WindowState> mFrameChangingWindows = new ArrayList<>();

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
    final ArrayMap<WindowContainer<?>, Message> mWaitingForDrawnCallbacks = new ArrayMap<>();

    /** List of window currently causing non-system overlay windows to be hidden. */
    private ArrayList<WindowState> mHidingNonSystemOverlayWindows = new ArrayList<>();

    /**
     * In some cases (e.g. when {@link R.bool.config_reverseDefaultRotation} has value
     * {@value true}) we need to map some orientation to others. This {@link SparseIntArray}
     * contains the relation between the source orientation and the one to use.
     */
    private final SparseIntArray mOrientationMapping = new SparseIntArray();

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

    /** Dump of the windows and app tokens at the time of the last ANR. Cleared after
     * LAST_ANR_LIFETIME_DURATION_MSECS */
    String mLastANRState;

    // The root of the device window hierarchy.
    @NonNull
    final RootWindowContainer mRoot;

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

    final RotationWatcherController mRotationWatcherController;
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
    final SnapshotController mSnapshotController;

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

    boolean mHardKeyboardAvailable;
    WindowManagerInternal.OnHardKeyboardStatusChangeListener mHardKeyboardStatusChangeListener;

    @Nullable ImeTargetChangeListener mImeTargetChangeListener;

    SettingsObserver mSettingsObserver;
    final EmbeddedWindowController mEmbeddedWindowController;
    final AnrController mAnrController;

    private final DisplayHashController mDisplayHashController;

    volatile float mMaximumObscuringOpacityForTouch =
            InputSettings.DEFAULT_MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH;

    @VisibleForTesting
    final WindowContextListenerController mWindowContextListenerController =
            new WindowContextListenerController();

    private InputTarget mFocusedInputTarget;

    @VisibleForTesting
    final ContentRecordingController mContentRecordingController = new ContentRecordingController();

    private final SurfaceSyncGroupController mSurfaceSyncGroupController =
            new SurfaceSyncGroupController();

    final TrustedPresentationListenerController mTrustedPresentationListenerController =
            new TrustedPresentationListenerController();

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
        private final Uri mDisableSecureWindowsUri =
                Settings.Secure.getUriFor(Settings.Secure.DISABLE_SECURE_WINDOWS);
        private final Uri mPolicyControlUri =
                Settings.Global.getUriFor(Settings.Global.POLICY_CONTROL);
        private final Uri mForceDesktopModeOnExternalDisplaysUri = Settings.Global.getUriFor(
                        Settings.Global.DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS);
        private final Uri mFreeformWindowUri = Settings.Global.getUriFor(
                Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT);
        private final Uri mForceResizableUri = Settings.Global.getUriFor(
                DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES);
        private final Uri mDevEnableNonResizableMultiWindowUri = Settings.Global.getUriFor(
                DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW);
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
            resolver.registerContentObserver(mDisableSecureWindowsUri, false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(mPolicyControlUri, false, this, UserHandle.USER_ALL);
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

            if (mDisableSecureWindowsUri.equals(uri)) {
                updateDisableSecureWindows();
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
            updateMaximumObscuringOpacityForTouch();
            updateDisableSecureWindows();
        }

        void updateMaximumObscuringOpacityForTouch() {
            ContentResolver resolver = mContext.getContentResolver();
            mMaximumObscuringOpacityForTouch = Settings.Global.getFloat(resolver,
                    Settings.Global.MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH,
                    InputSettings.DEFAULT_MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH);
            if (mMaximumObscuringOpacityForTouch < 0.0f
                    || mMaximumObscuringOpacityForTouch > 1.0f) {
                mMaximumObscuringOpacityForTouch =
                        InputSettings.DEFAULT_MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH;
            }
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

        void updateDisableSecureWindows() {
            if (!SystemProperties.getBoolean(SYSTEM_DEBUGGABLE, false)) {
                return;
            }

            final boolean disableSecureWindows;
            try {
                disableSecureWindows = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                        Settings.Secure.DISABLE_SECURE_WINDOWS, 0) != 0;
            } catch (Settings.SettingNotFoundException e) {
                return;
            }
            if (mDisableSecureWindows == disableSecureWindows) {
                return;
            }

            synchronized (mGlobalLock) {
                mDisableSecureWindows = disableSecureWindows;
                mRoot.refreshSecureSurfaceState();
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

    /** Whether or not a layout can cause a wake up when theater mode is enabled. */
    boolean mAllowTheaterModeWakeFromLayout;

    final TaskPositioningController mTaskPositioningController;
    final DragDropController mDragDropController;

    /** For frozen screen animations. */
    private int mExitAnimId, mEnterAnimId;

    /** The display that the rotation animation is applying to. */
    private int mFrozenDisplayId = INVALID_DISPLAY;

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

    private ViewServer mViewServer;
    final ArrayList<WindowChangeListener> mWindowChangeListeners = new ArrayList<>();
    boolean mWindowsChanged = false;

    public interface WindowChangeListener {
        /** Notify on windows changed */
        void windowsChanged();

        /** Notify on focus changed */
        void focusChanged();
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

    SystemPerformanceHinter mSystemPerformanceHinter;

    @GuardedBy("mGlobalLock")
    private long mSensitiveContentProtectionSessionId = 0;

    @GuardedBy("mGlobalLock")
    final SensitiveContentPackages mSensitiveContentPackages = new SensitiveContentPackages();
    /**
     * UIDs for which a Toast has been shown to indicate
     * {@link LocalService#addBlockScreenCaptureForApps(ArraySet) screen capture blocking}. This is
     * used to ensure we don't keep re-showing the Toast every time the window becomes visible.
     * UIDs are removed when the app is removed from the block list.
     */
    @GuardedBy("mGlobalLock")
    private final IntArray mCaptureBlockedToastShownUids = new IntArray();

    /** Listener to notify activity manager about app transitions. */
    final WindowManagerInternal.AppTransitionListener mActivityManagerAppTransitionNotifier
            = new WindowManagerInternal.AppTransitionListener() {

        @Override
        public void onAppTransitionCancelledLocked(boolean keyguardGoingAwayCancelled) {
        }

        @Override
        public void onAppTransitionFinishedLocked(IBinder token) {
            final ActivityRecord atoken = ActivityRecord.forTokenLocked(token);
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

    private final ScreenRecordingCallbackController mScreenRecordingCallbackController;

    private volatile boolean mDisableSecureWindows = false;

    public static WindowManagerService main(final Context context, final InputManagerService im,
            final boolean showBootMsgs, WindowManagerPolicy policy,
            ActivityTaskManagerService atm) {
        final WindowManagerService wms = main(context, im, showBootMsgs, policy, atm,
                new DisplayWindowSettingsProvider(), SurfaceControl.Transaction::new,
                SurfaceControl.Builder::new);
        WindowManagerGlobal.setWindowManagerServiceForSystemProcess(wms);
        return wms;
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
        mFlags = new WindowManagerFlags();
        mIsPc = mContext.getPackageManager().hasSystemFeature(FEATURE_PC);
        mAllowBootMessages = showBootMsgs;
        mLimitedAlphaCompositing = context.getResources().getBoolean(
                com.android.internal.R.bool.config_sf_limitedAlpha);
        mHasPermanentDpad = context.getResources().getBoolean(
                com.android.internal.R.bool.config_hasPermanentDpad);
        mDrawLockTimeoutMillis = context.getResources().getInteger(
                com.android.internal.R.integer.config_drawLockTimeoutMillis);
        mAllowAnimationsInLowPowerMode = context.getResources().getBoolean(
                com.android.internal.R.bool.config_allowAnimationsInLowPowerMode);
        mMaxUiWidth = context.getResources().getInteger(
                com.android.internal.R.integer.config_maxUiWidth);
        mSupportsHighPerfTransitions = context.getResources().getBoolean(
                com.android.internal.R.bool.config_deviceSupportsHighPerfTransitions);
        mDisableTransitionAnimation = context.getResources().getBoolean(
                com.android.internal.R.bool.config_disableTransitionAnimation);
        mPerDisplayFocusEnabled = context.getResources().getBoolean(
                com.android.internal.R.bool.config_perDisplayFocusEnabled);
        mAssistantOnTopOfDream = context.getResources().getBoolean(
                com.android.internal.R.bool.config_assistantOnTopOfDream);
        mSkipActivityRelaunchWhenDocking = context.getResources()
                .getBoolean(R.bool.config_skipActivityRelaunchWhenDocking);
        final boolean isScreenSizeDecoupledFromStatusBarAndCutout = context.getResources()
                .getBoolean(R.bool.config_decoupleStatusBarAndDisplayCutoutFromScreenSize)
                && mFlags.mAllowsScreenSizeDecoupledFromStatusBarAndCutout;

        if (mFlags.mInsetsDecoupledConfiguration) {
            mDecorTypes = 0;
            mConfigTypes = 0;
        } else {
            mDecorTypes = WindowInsets.Type.displayCutout() | WindowInsets.Type.navigationBars();
            mConfigTypes = WindowInsets.Type.displayCutout() | WindowInsets.Type.statusBars()
                    | WindowInsets.Type.navigationBars();
        }
        if (isScreenSizeDecoupledFromStatusBarAndCutout && !mFlags.mInsetsDecoupledConfiguration) {
            // If the global new behavior is not there, but the partial decouple flag is on.
            mOverrideConfigTypes = 0;
            mOverrideDecorTypes = 0;
        } else {
            mOverrideConfigTypes =
                    WindowInsets.Type.displayCutout() | WindowInsets.Type.statusBars()
                            | WindowInsets.Type.navigationBars();
            mOverrideDecorTypes = WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.navigationBars();
        }

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

        mSyncEngine = new BLASTSyncEngine(this);

        mWindowPlacerLocked = new WindowSurfacePlacer(this);
        mSnapshotController = new SnapshotController(this);
        mTaskSnapshotController = mSnapshotController.mTaskSnapshotController;

        mWindowTracing = WindowTracing.createDefaultAndStartLooper(this,
                Choreographer.getInstance());

        if (android.tracing.Flags.perfettoTransitionTracing()) {
            mTransitionTracer = new PerfettoTransitionTracer();
        } else {
            mTransitionTracer = new LegacyTransitionTracer();
        }

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

        mRotationWatcherController = new RotationWatcherController(this);
        mDisplayNotificationController = new DisplayWindowListenerController(this);
        mTaskSystemBarsListenerController = new TaskSystemBarsListenerController();

        mActivityManager = ActivityManager.getService();
        mAmInternal = LocalServices.getService(ActivityManagerInternal.class);
        mUmInternal = LocalServices.getService(UserManagerInternal.class);
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
        mWindowAnimationScaleSetting = getWindowAnimationScaleSetting();
        mTransitionAnimationScaleSetting = getTransitionAnimationScaleSetting();

        setAnimatorDurationScale(getAnimatorDurationScaleSetting());

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
        LocalServices.addService(
                ImeTargetVisibilityPolicy.class, new ImeTargetVisibilityPolicyImpl());
        mEmbeddedWindowController = new EmbeddedWindowController(mAtmService, inputManager);

        mDisplayAreaPolicyProvider = DisplayAreaPolicy.Provider.fromResources(
                mContext.getResources());

        mDisplayHashController = new DisplayHashController(mContext);
        setGlobalShadowSettings();
        mAnrController = new AnrController(this);
        mStartingSurfaceController = new StartingSurfaceController(this);

        mBlurController = new BlurController(mContext, mPowerManager);
        mTaskFpsCallbackController = new TaskFpsCallbackController(mContext);
        mAccessibilityController = new AccessibilityController(this);
        mScreenRecordingCallbackController = new ScreenRecordingCallbackController(this);
        mSystemPerformanceHinter = new SystemPerformanceHinter(mContext, displayId -> {
            synchronized (mGlobalLock) {
                DisplayContent dc = mRoot.getDisplayContent(displayId);
                return (dc == null) ? null : dc.getSurfaceControl();
            }
        }, mTransactionFactory);
        mSystemPerformanceHinter.mTraceTag = TRACE_TAG_WINDOW_MANAGER;
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

    private float getTransitionAnimationScaleSetting() {
        return fixScale(Settings.Global.getFloat(mContext.getContentResolver(),
                Settings.Global.TRANSITION_ANIMATION_SCALE, mContext.getResources().getFloat(
                                R.dimen.config_appTransitionAnimationDurationScaleDefault)));
    }

    private float getAnimatorDurationScaleSetting() {
        return fixScale(Settings.Global.getFloat(mContext.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, mAnimatorDurationScaleSetting));
    }

    private float getWindowAnimationScaleSetting() {
        return fixScale(Settings.Global.getFloat(mContext.getContentResolver(),
                Settings.Global.WINDOW_ANIMATION_SCALE, mWindowAnimationScaleSetting));
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

    public int addWindow(Session session, IWindow client, LayoutParams attrs, int viewVisibility,
            int displayId, int requestUserId, @InsetsType int requestedVisibleTypes,
            InputChannel outInputChannel, InsetsState outInsetsState,
            InsetsSourceControl.Array outActiveControls, Rect outAttachedFrame,
            float[] outSizeCompatScale) {
        outActiveControls.set(null);
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
            if (session.isClientDead()) {
                ProtoLog.w(WM_ERROR, "Attempted to add window with a client %s "
                        + "that is dead. Aborting.", session);
                return WindowManagerGlobal.ADD_APP_EXITING;
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

            if (type == TYPE_PRESENTATION || type == TYPE_PRIVATE_PRESENTATION) {
                mDisplayManagerInternal.onPresentation(displayContent.getDisplay().getDisplayId(),
                        /*isShown=*/ true);
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
            final DisplayPolicy displayPolicy = displayContent.getDisplayPolicy();
            displayPolicy.adjustWindowParamsLw(win, win.mAttrs);
            attrs.flags = sanitizeFlagSlippery(attrs.flags, win.getName(), callingUid, callingPid);
            attrs.inputFeatures = sanitizeInputFeatures(attrs.inputFeatures, win.getName(),
                    callingUid, callingPid, win.isTrustedOverlay());
            win.setRequestedVisibleTypes(requestedVisibleTypes);

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
                    mWindowContextListenerController.updateContainerForWindowContextListener(
                            windowContextToken, token);
                }
            }

            // From now on, no exceptions or errors allowed!
            if (displayContent.mCurrentFocus == null) {
                displayContent.mWinAddedSinceNullFocus.add(win);
            }

            win.mSession.onWindowAdded(win);
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

            if (displayPolicy.areSystemBarsForcedConsumedLw()) {
                res |= WindowManagerGlobal.ADD_FLAG_ALWAYS_CONSUME_SYSTEM_BARS;
            }
            if (displayContent.isInTouchMode()) {
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
                if (win.isImeOverlayLayeringTarget()) {
                    dispatchImeTargetOverlayVisibilityChanged(client.asBinder(), win.mAttrs.type,
                            win.isVisibleRequestedOrAdding(), false /* removed */);
                }
            }

            // Don't do layout here, the window must call
            // relayout to be displayed, so we'll do it there.
            if (win.mActivityRecord != null && win.mActivityRecord.isEmbedded()) {
                // Assign child layers from the parent Task if the Activity is embedded.
                win.getTask().assignChildLayers();
            } else {
                win.getParent().assignChildLayers();
            }

            if (focusChanged) {
                displayContent.getInputMonitor().setInputFocusLw(displayContent.mCurrentFocus,
                        false /*updateInputWindows*/);
            }
            displayContent.getInputMonitor().updateInputWindowsLw(false /*force*/);

            ProtoLog.v(WM_DEBUG_ADD_REMOVE, "addWindow: New client %s"
                    + ": window=%s Callers=%s", client.asBinder(), win, Debug.getCallers(5));

            boolean needToSendNewConfiguration =
                    win.isVisibleRequestedOrAdding() && displayContent.updateOrientation();
            if (win.providesDisplayDecorInsets()) {
                needToSendNewConfiguration |= displayPolicy.updateDecorInsetsInfo();
            }
            if (needToSendNewConfiguration) {
                displayContent.sendNewConfiguration();
            }

            // This window doesn't have a frame yet. Don't let this window cause the insets change.
            displayContent.getInsetsStateController().updateAboveInsetsState(
                    false /* notifyInsetsChanged */);

            outInsetsState.set(win.getCompatInsetsState(), true /* copySources */);
            getInsetsSourceControls(win, outActiveControls);

            if (win.mLayoutAttached) {
                outAttachedFrame.set(win.getParentWindow().getFrame());
                if (win.mInvGlobalScale != 1f) {
                    outAttachedFrame.scale(win.mInvGlobalScale);
                }
            } else {
                // Make this invalid which indicates a null attached frame.
                outAttachedFrame.set(0, 0, -1, -1);
            }
            outSizeCompatScale[0] = win.getCompatScaleForClient();
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
            final ApplicationInfo appInfo = mPmInternal.getApplicationInfo(
                    packageName, 0 /* flags */, SYSTEM_UID, UserHandle.getUserId(callingUid));
            if (appInfo == null || appInfo.uid != callingUid) {
                throw new SecurityException("Package " + packageName + " not in UID "
                        + callingUid);
            }
            return appInfo.targetSdkVersion >= Build.VERSION_CODES.O;
        }
    }

    /**
     * Set whether screen capture is disabled for all windows of a specific user from
     * the device policy cache, or specific windows based on sensitive content protections.
     */
    @Override
    public void refreshScreenCaptureDisabled() {
        int callingUid = Binder.getCallingUid();
        // MY_UID (Process.myUid()) should always be SYSTEM_UID here, but using MY_UID for tests
        if (callingUid != MY_UID) {
            throw new SecurityException("Only system can call refreshScreenCaptureDisabled.");
        }

        synchronized (mGlobalLock) {
            // Refresh secure surface for all windows.
            mRoot.refreshSecureSurfaceState();
        }
    }

    void removeClientToken(Session session, IBinder client) {
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
        final IBinder client = win.mClient.asBinder();
        mWindowMap.remove(client);
        if (sensitiveContentAppProtection()) {
            notifyWindowRemovedListeners(client);
        }

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
        } else if (dc.mWallpaperController.isWallpaperTarget(win)) {
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
                    final boolean wasGivenInsetsPending = w.mGivenInsetsPending;
                    w.mGivenInsetsPending = false;
                    if ((!wasGivenInsetsPending || !w.hasInsetsSourceProvider())
                            && w.mTouchableInsets == touchableInsets
                            && w.mGivenContentInsets.equals(contentInsets)
                            && w.mGivenVisibleInsets.equals(visibleInsets)
                            && w.mGivenTouchableRegion.equals(touchableRegion)) {
                        return;
                    }
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

    /** Relayouts window. */
    public int relayoutWindow(Session session, IWindow client, LayoutParams attrs,
            int requestedWidth, int requestedHeight, int viewVisibility, int flags, int seq,
            int lastSyncSeqId, WindowRelayoutResult outRelayoutResult) {
        final ClientWindowFrames outFrames;
        final MergedConfiguration outMergedConfiguration;
        final SurfaceControl outSurfaceControl;
        final InsetsState outInsetsState;
        final InsetsSourceControl.Array outActiveControls;
        if (outRelayoutResult != null) {
            outFrames = outRelayoutResult.frames;
            outMergedConfiguration = outRelayoutResult.mergedConfiguration;
            outSurfaceControl = outRelayoutResult.surfaceControl;
            outInsetsState = outRelayoutResult.insetsState;
            outActiveControls = outRelayoutResult.activeControls;
        } else {
            outFrames = null;
            outMergedConfiguration = null;
            outSurfaceControl = null;
            outInsetsState = null;
            outActiveControls = null;
        }
        return relayoutWindowInner(session, client, attrs, requestedWidth, requestedHeight,
                viewVisibility, flags, seq, lastSyncSeqId, outFrames, outMergedConfiguration,
                outSurfaceControl, outInsetsState, outActiveControls, null /* outBundle */,
                outRelayoutResult);
    }

    /** @deprecated */
    @Deprecated
    public int relayoutWindow(Session session, IWindow client, LayoutParams attrs,
            int requestedWidth, int requestedHeight, int viewVisibility, int flags, int seq,
            int lastSyncSeqId, ClientWindowFrames outFrames,
            MergedConfiguration outMergedConfiguration, SurfaceControl outSurfaceControl,
            InsetsState outInsetsState, InsetsSourceControl.Array outActiveControls,
            Bundle outBundle) {
        return relayoutWindowInner(session, client, attrs, requestedWidth, requestedHeight,
                viewVisibility, flags, seq, lastSyncSeqId, outFrames, outMergedConfiguration,
                outSurfaceControl, outInsetsState, outActiveControls, outBundle,
                null /* outRelayoutResult */);
    }

    private int relayoutWindowInner(Session session, IWindow client, LayoutParams attrs,
            int requestedWidth, int requestedHeight, int viewVisibility, int flags, int seq,
            int lastSyncSeqId, ClientWindowFrames outFrames,
            MergedConfiguration outMergedConfiguration, SurfaceControl outSurfaceControl,
            InsetsState outInsetsState, InsetsSourceControl.Array outActiveControls,
            Bundle outBundle, WindowRelayoutResult outRelayoutResult) {
        if (outActiveControls != null) {
            outActiveControls.set(null);
        }
        int result = 0;
        boolean configChanged = false;
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        synchronized (mGlobalLock) {
            final WindowState win = windowForClientLocked(session, client, false);
            if (win == null) {
                return 0;
            }
            if (win.mRelayoutSeq < seq) {
                win.mRelayoutSeq = seq;
            } else if (win.mRelayoutSeq > seq) {
                return 0;
            }

            if (win.cancelAndRedraw() && win.mPrepareSyncSeqId <= lastSyncSeqId) {
                // The client has reported the sync draw, but we haven't finished it yet.
                // Don't let the client perform a non-sync draw at this time.
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
                attrs.inputFeatures = sanitizeInputFeatures(attrs.inputFeatures, win.getName(), uid,
                        pid, win.isTrustedOverlay());
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
                                "Insets amount can not be changed after the window is added.");
                    } else {
                        final int insetsTypes = attrs.providedInsets.length;
                        for (int i = 0; i < insetsTypes; i++) {
                            if (!win.mAttrs.providedInsets[i].idEquals(attrs.providedInsets[i])) {
                                throw new IllegalArgumentException(
                                        "Insets ID can not be changed after the window is added.");
                            }
                            final InsetsFrameProvider.InsetsSizeOverride[] overrides =
                                    win.mAttrs.providedInsets[i].getInsetsSizeOverrides();
                            final InsetsFrameProvider.InsetsSizeOverride[] newOverrides =
                                    attrs.providedInsets[i].getInsetsSizeOverrides();
                            if (!(overrides == null && newOverrides == null)) {
                                if (overrides == null || newOverrides == null
                                        || (overrides.length != newOverrides.length)) {
                                    throw new IllegalArgumentException(
                                            "Insets override types can not be changed after the "
                                                    + "window is added.");
                                } else {
                                    final int overrideTypes = overrides.length;
                                    for (int j = 0; j < overrideTypes; j++) {
                                        if (overrides[j].getWindowType()
                                                != newOverrides[j].getWindowType()) {
                                            throw new IllegalArgumentException(
                                                    "Insets override types can not be changed after"
                                                            + " the window is added.");
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                final boolean wasTrustedOverlay = win.isWindowTrustedOverlay();
                flagChanges = win.mAttrs.flags ^ attrs.flags;
                privateFlagChanges = win.mAttrs.privateFlags ^ attrs.privateFlags;
                attrChanges = win.mAttrs.copyFrom(attrs);
                final boolean layoutChanged =
                        (attrChanges & WindowManager.LayoutParams.LAYOUT_CHANGED) != 0;
                if (layoutChanged || (attrChanges
                        & WindowManager.LayoutParams.SYSTEM_UI_VISIBILITY_CHANGED) != 0) {
                    win.mLayoutNeeded = true;
                }
                if (layoutChanged && win.providesDisplayDecorInsets()) {
                    configChanged = displayPolicy.updateDecorInsetsInfo();
                }
                if (wasTrustedOverlay != win.isWindowTrustedOverlay()) {
                    win.updateTrustedOverlay();
                }
                if (win.mActivityRecord != null && ((flagChanges & FLAG_SHOW_WHEN_LOCKED) != 0
                        || (flagChanges & FLAG_DISMISS_KEYGUARD) != 0)) {
                    win.mActivityRecord.checkKeyguardFlagsChanged();
                }

                if ((privateFlagChanges & SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS) != 0) {
                    updateNonSystemOverlayWindowsVisibilityIfNeeded(
                            win, win.mWinAnimator.getShown());
                }
                if (!setScPropertiesInClient()) {
                    if ((attrChanges & (WindowManager.LayoutParams.PRIVATE_FLAGS_CHANGED)) != 0) {
                        winAnimator.setColorSpaceAgnosticLocked((win.mAttrs.privateFlags
                                & WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC)
                                != 0);
                    }
                }
                // See if the DisplayWindowPolicyController wants to keep the activity on the window
                if (displayContent.mDwpcHelper.hasController()
                        && win.mActivityRecord != null && (!win.mRelayoutCalled || flagChanges != 0
                        || privateFlagChanges != 0)) {
                    int newOrChangedFlags = !win.mRelayoutCalled ? win.mAttrs.flags : flagChanges;
                    int newOrChangedPrivateFlags =
                            !win.mRelayoutCalled ? win.mAttrs.privateFlags : privateFlagChanges;

                    if (!displayContent.mDwpcHelper.keepActivityOnWindowFlagsChanged(
                            win.mActivityRecord.info, newOrChangedFlags, newOrChangedPrivateFlags,
                            win.mAttrs.flags,
                            win.mAttrs.privateFlags)) {
                        mH.sendMessage(mH.obtainMessage(H.REPARENT_TASK_TO_DEFAULT_DISPLAY,
                                win.mActivityRecord.getTask()));
                        Slog.w(TAG_WM, "Activity " + win.mActivityRecord + " window flag changed,"
                                + " can't remain on display " + displayContent.getDisplayId());
                        return 0;
                    }
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
            if ((flagChanges & FLAG_SECURE) != 0) {
                win.setSecureLocked(win.isSecureLocked());
            }

            final boolean wasVisible = win.isVisible();

            win.mRelayoutCalled = true;
            win.mInRelayout = true;

            win.setViewVisibility(viewVisibility);
            ProtoLog.i(WM_DEBUG_SCREEN_ON,
                    "Relayout %s: oldVis=%d newVis=%d. %s", win, oldVisibility,
                            viewVisibility, new RuntimeException().fillInStackTrace());
            if (becameVisible) {
                onWindowVisible(win);
            }

            win.setDisplayLayoutNeeded();
            win.mGivenInsetsPending = (flags & WindowManagerGlobal.RELAYOUT_INSETS_PENDING) != 0;

            // We should only relayout if the view is visible, it is a starting window, or the
            // associated appToken is not hidden.
            final boolean shouldRelayout = viewVisibility == View.VISIBLE &&
                    (win.mActivityRecord == null || win.mAttrs.type == TYPE_APPLICATION_STARTING
                            || win.mActivityRecord.isClientVisible());

            // If we are not currently running the exit animation, we need to see about starting
            // one.
            // This must be called before the call to performSurfacePlacement.
            if (!shouldRelayout && winAnimator.hasSurface() && !win.mAnimatingExit) {
                if (DEBUG_VISIBILITY) {
                    Slog.i(TAG_WM,
                            "Relayout invis " + win + ": mAnimatingExit=" + win.mAnimatingExit);
                }
                result |= RELAYOUT_RES_SURFACE_CHANGED;
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
                tryStartExitingAnimation(win, winAnimator);
            }

            // Create surfaceControl before surface placement otherwise layout will be skipped
            // (because WS.isGoneForLayout() is true when there is no surface.
            if (shouldRelayout && outSurfaceControl != null) {
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

                if (outSurfaceControl != null) {
                    if (viewVisibility == View.VISIBLE && winAnimator.hasSurface()) {
                        // We already told the client to go invisible, but the message may not be
                        // handled yet, or it might want to draw a last frame. If we already have a
                        // surface, let the client use that, but don't create new surface at this
                        // point.
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
            configChanged |= displayContent.updateOrientation();
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);

            if (toBeDisplayed && win.mIsWallpaper) {
                displayContent.mWallpaperController.updateWallpaperOffset(win, false /* sync */);
            }
            if (win.mActivityRecord != null) {
                win.mActivityRecord.updateReportedVisibilityLocked();
            }
            if (displayPolicy.areSystemBarsForcedConsumedLw()) {
                result |= WindowManagerGlobal.RELAYOUT_RES_CONSUME_ALWAYS_SYSTEM_BARS;
            }
            if (!win.isGoneForLayout()) {
                win.mResizedWhileGone = false;
            }

            if (outFrames != null && outMergedConfiguration != null) {
                final boolean shouldReportActivityWindowInfo;
                if (Flags.windowSessionRelayoutInfo()) {
                    shouldReportActivityWindowInfo = outRelayoutResult != null
                            && win.mLastReportedActivityWindowInfo != null;
                } else {
                    shouldReportActivityWindowInfo = outBundle != null
                            && win.mLastReportedActivityWindowInfo != null;
                }
                final ActivityWindowInfo outActivityWindowInfo = shouldReportActivityWindowInfo
                        ? new ActivityWindowInfo()
                        : null;

                win.fillClientWindowFramesAndConfiguration(outFrames, outMergedConfiguration,
                        outActivityWindowInfo, false /* useLatestConfig */, shouldRelayout);

                if (shouldReportActivityWindowInfo) {
                    if (Flags.windowSessionRelayoutInfo()) {
                        outRelayoutResult.activityWindowInfo = outActivityWindowInfo;
                    } else {
                        outBundle.putParcelable(
                                IWindowSession.KEY_RELAYOUT_BUNDLE_ACTIVITY_WINDOW_INFO,
                                outActivityWindowInfo);
                    }
                }

                // Set resize-handled here because the values are sent back to the client.
                win.onResizeHandled();
            }

            if (outInsetsState != null) {
                outInsetsState.set(win.getCompatInsetsState(), true /* copySources */);
            }

            ProtoLog.v(WM_DEBUG_FOCUS, "Relayout of %s: focusMayChange=%b",
                    win, focusMayChange);

            if (DEBUG_LAYOUT) {
                Slog.v(TAG_WM, "Relayout complete " + win + ": outFrames=" + outFrames);
            }
            win.mInRelayout = false;

            final boolean winVisibleChanged = win.isVisible() != wasVisible;
            if (win.isImeOverlayLayeringTarget() && winVisibleChanged) {
                dispatchImeTargetOverlayVisibilityChanged(client.asBinder(), win.mAttrs.type,
                        win.isVisible(), false /* removed */);
            }
            // Notify listeners about IME input target window visibility change.
            final boolean isImeInputTarget = win.getDisplayContent().getImeInputTarget() == win;
            if (isImeInputTarget && winVisibleChanged) {
                dispatchImeInputTargetVisibilityChanged(win.mClient.asBinder(),
                        win.isVisible() /* visible */, false /* removed */);
            }

            if (Flags.windowSessionRelayoutInfo()) {
                if (outRelayoutResult != null) {
                    if (win.syncNextBuffer() && viewVisibility == View.VISIBLE
                            && win.mSyncSeqId > lastSyncSeqId) {
                        outRelayoutResult.syncSeqId = win.shouldSyncWithBuffers()
                                ? win.mSyncSeqId
                                : -1;
                        win.markRedrawForSyncReported();
                    } else {
                        outRelayoutResult.syncSeqId = -1;
                    }
                }
            } else if (outBundle != null) {
                final int maybeSyncSeqId;
                if (win.syncNextBuffer() && viewVisibility == View.VISIBLE
                        && win.mSyncSeqId > lastSyncSeqId) {
                    maybeSyncSeqId = win.shouldSyncWithBuffers() ? win.mSyncSeqId : -1;
                    win.markRedrawForSyncReported();
                } else {
                    maybeSyncSeqId = -1;
                }
                outBundle.putInt(IWindowSession.KEY_RELAYOUT_BUNDLE_SEQID, maybeSyncSeqId);
            }

            if (configChanged) {
                Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER,
                        "relayoutWindow: postNewConfigurationToHandler");
                displayContent.sendNewConfiguration();
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            }
            if (outActiveControls != null) {
                getInsetsSourceControls(win, outActiveControls);
            }
        }

        Binder.restoreCallingIdentity(origId);
        return result;
    }

    private void getInsetsSourceControls(WindowState win, InsetsSourceControl.Array outArray) {
        final InsetsSourceControl[] controls =
                win.getDisplayContent().getInsetsStateController().getControlsForDispatch(win);
        if (controls != null) {
            final int length = controls.length;
            final InsetsSourceControl[] outControls = new InsetsSourceControl[length];
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
            outArray.set(outControls);
        }
    }

    private void tryStartExitingAnimation(WindowState win, WindowStateAnimator winAnimator) {
        // Try starting an animation; if there isn't one, we
        // can destroy the surface right away.
        int transit = WindowManagerPolicy.TRANSIT_EXIT;
        if (win.mAttrs.type == TYPE_APPLICATION_STARTING) {
            transit = WindowManagerPolicy.TRANSIT_PREVIEW_DONE;
        }

        if (win.isVisible() && win.isDisplayed() && win.mDisplayContent.okToAnimate()) {
            String reason = null;
            if (winAnimator.applyAnimationLocked(transit, false)) {
                // This is a WMCore-driven window animation.
                reason = "applyAnimation";
            } else if (win.isSelfAnimating(0 /* flags */, ANIMATION_TYPE_WINDOW_ANIMATION)) {
                // This is already animating via a WMCore-driven window animation.
                reason = "selfAnimating";
            } else {
                if (win.mTransitionController.isShellTransitionsEnabled()) {
                    // Already animating as part of a shell-transition. Currently this only handles
                    // activity window because other types should be WMCore-driven.
                    if ((win.mActivityRecord != null && win.mActivityRecord.inTransition())) {
                        win.mTransitionController.mAnimatingExitWindows.add(win);
                        reason = "inTransition";
                    }
                } else if (win.isAnimating(PARENTS | TRANSITION,
                        ANIMATION_TYPE_APP_TRANSITION | ANIMATION_TYPE_RECENTS)) {
                    // Already animating as part of a legacy app-transition.
                    reason = "inLegacyTransition";
                }
            }
            if (reason != null) {
                win.mAnimatingExit = true;
                ProtoLog.d(WM_DEBUG_ANIM,
                        "Set animatingExit: reason=startExitingAnimation/%s win=%s", reason, win);
            }
        }
        if (!win.mAnimatingExit) {
            boolean stopped = win.mActivityRecord == null || win.mActivityRecord.mAppStopped;
            // We set mDestroying=true so ActivityRecord#notifyAppStopped in-to destroy surfaces
            // will later actually destroy the surface if we do not do so here. Normally we leave
            // this to the exit animation.
            win.mDestroying = true;
            win.destroySurface(false, stopped);
        }
        if (mAccessibilityController.hasCallbacks()) {
            mAccessibilityController.onWindowTransition(win, transit);
        }
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
            postDrawTransaction.sanitize(Binder.getCallingPid(), Binder.getCallingUid());
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

    @Nullable
    @Override
    public WindowContextInfo attachWindowContextToDisplayArea(@NonNull IApplicationThread appThread,
            @NonNull IBinder clientToken, @LayoutParams.WindowType int type, int displayId,
            @Nullable Bundle options) {
        Objects.requireNonNull(appThread);
        Objects.requireNonNull(clientToken);
        final boolean callerCanManageAppTokens = checkCallingPermission(MANAGE_APP_TOKENS,
                "attachWindowContextToDisplayArea", false /* printLog */);
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final WindowProcessController wpc = mAtmService.getProcessController(appThread);
                if (wpc == null) {
                    ProtoLog.w(WM_ERROR, "attachWindowContextToDisplayArea: calling from"
                            + " non-existing process pid=%d uid=%d", callingPid, callingUid);
                    return null;
                }
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
                mWindowContextListenerController.registerWindowContainerListener(wpc, clientToken,
                        da, type, options, false /* shouldDispatchConfigWhenRegistering */);
                return new WindowContextInfo(da.getConfiguration(), displayId);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Nullable
    @Override
    public WindowContextInfo attachWindowContextToDisplayContent(
            @NonNull IApplicationThread appThread, @NonNull IBinder clientToken, int displayId) {
        Objects.requireNonNull(appThread);
        Objects.requireNonNull(clientToken);
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final WindowProcessController wpc = mAtmService.getProcessController(appThread);
                if (wpc == null) {
                    ProtoLog.w(WM_ERROR, "attachWindowContextToDisplayContent: calling from"
                            + " non-existing process pid=%d uid=%d", callingPid, callingUid);
                    return null;
                }
                // We use "getDisplayContent" instead of "getDisplayContentOrCreate" because
                // this method may be called in DisplayPolicy's constructor and may cause
                // infinite loop. In this scenario, we early return here and switch to do the
                // registration in DisplayContent#onParentChanged at DisplayContent initialization.
                final DisplayContent dc = mRoot.getDisplayContent(displayId);
                if (dc == null) {
                    if (callingPid != MY_PID) {
                        throw new WindowManager.InvalidDisplayException(
                                "attachWindowContextToDisplayContent: trying to attach to a"
                                        + " non-existing display:" + displayId);
                    }
                    // Early return if this method is invoked from system process.
                    // See above comments for more detail.
                    return null;
                }

                mWindowContextListenerController.registerWindowContainerListener(wpc, clientToken,
                        dc, INVALID_WINDOW_TYPE, null /* options */,
                        false /* shouldDispatchConfigWhenRegistering */);
                return new WindowContextInfo(dc.getConfiguration(), displayId);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Nullable
    @Override
    public WindowContextInfo attachWindowContextToWindowToken(@NonNull IApplicationThread appThread,
            @NonNull IBinder clientToken, @NonNull IBinder token) {
        Objects.requireNonNull(appThread);
        Objects.requireNonNull(clientToken);
        Objects.requireNonNull(token);
        final boolean callerCanManageAppTokens = checkCallingPermission(MANAGE_APP_TOKENS,
                "attachWindowContextToWindowToken", false /* printLog */);
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final WindowProcessController wpc = mAtmService.getProcessController(appThread);
                if (wpc == null) {
                    ProtoLog.w(WM_ERROR, "attachWindowContextToWindowToken: calling from"
                            + " non-existing process pid=%d uid=%d", callingPid, callingUid);
                    return null;
                }
                final WindowToken windowToken = mRoot.getWindowToken(token);
                if (windowToken == null) {
                    ProtoLog.w(WM_ERROR, "Then token:%s is invalid. It might be "
                            + "removed", token);
                    return null;
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
                    return null;
                }
                mWindowContextListenerController.registerWindowContainerListener(wpc, clientToken,
                        windowToken, windowToken.windowType, windowToken.mOptions,
                                               false /* shouldDispatchConfigWhenRegistering */);
                return new WindowContextInfo(windowToken.getConfiguration(),
                        windowToken.getDisplayContent().getDisplayId());
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void detachWindowContext(@NonNull IBinder clientToken) {
        Objects.requireNonNull(clientToken);
        final boolean callerCanManageAppTokens = checkCallingPermission(MANAGE_APP_TOKENS,
                "detachWindowContext", false /* printLog */);
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
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                if (mAtmService.mKeyguardController.isKeyguardShowing(DEFAULT_DISPLAY)) {
                    mRoot.ensureActivitiesVisible();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
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
        mRoot.forAllDisplayPolicies(p -> p.onPowerKeyDown(isScreenOn));
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
    public void moveDisplayToTopIfAllowed(int displayId) {
        moveDisplayToTopInternal(displayId);
        syncInputTransactions(true /* waitForAnimations */);
    }

    /**
     * Moves the given display to the top. If it cannot be moved to the top this method does
     * nothing (e.g. if the display has the flag FLAG_STEAL_TOP_FOCUS_DISABLED set).
     * @param displayId The display to move to the top.
     */
    void moveDisplayToTopInternal(int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent != null && mRoot.getTopChild() != displayContent) {
                // Check whether anything prevents us from moving the display to the top.
                if (!displayContent.canStealTopFocus()) {
                    ProtoLog.i(WM_DEBUG_FOCUS_LIGHT,
                            "Not moving display (displayId=%d) to top. Top focused displayId=%d. "
                                    + "Reason: FLAG_STEAL_TOP_FOCUS_DISABLED",
                            displayId, mRoot.getTopFocusedDisplayContent().getDisplayId());
                    return;
                }

                // Nothing prevented us from moving the display to the top. Let's do it!
                displayContent.getParent().positionChildAt(WindowContainer.POSITION_TOP,
                        displayContent, true /* includingParents */);
            }
        }
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

    @EnforcePermission(android.Manifest.permission.DISABLE_KEYGUARD)
    /**
     * @see android.app.KeyguardManager#exitKeyguardSecurely
     */
    @Override
    public void exitKeyguardSecurely(final IOnKeyguardExitResult callback) {
        exitKeyguardSecurely_enforcePermission();

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
        if (!dreamHandlesConfirmKeys() && mAtmService.mKeyguardController.isShowingDream()) {
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

    void dispatchImeTargetOverlayVisibilityChanged(@NonNull IBinder token,
            @WindowManager.LayoutParams.WindowType int windowType, boolean visible,
            boolean removed) {
        if (mImeTargetChangeListener != null) {
            if (DEBUG_INPUT_METHOD) {
                Slog.d(TAG, "onImeTargetOverlayVisibilityChanged, win=" + mWindowMap.get(token)
                        + ", type=" + ViewDebug.intToString(WindowManager.LayoutParams.class,
                        "type", windowType) + "visible=" + visible + ", removed=" + removed);
            }
            mH.post(() -> mImeTargetChangeListener.onImeTargetOverlayVisibilityChanged(token,
                    windowType, visible, removed));
        }
    }

    void dispatchImeInputTargetVisibilityChanged(@NonNull IBinder token, boolean visible,
            boolean removed) {
        if (mImeTargetChangeListener != null) {
            if (DEBUG_INPUT_METHOD) {
                Slog.d(TAG, "onImeInputTargetVisibilityChanged, win=" + mWindowMap.get(token)
                        + "visible=" + visible + ", removed=" + removed);
            }
            mH.post(() -> mImeTargetChangeListener.onImeInputTargetVisibilityChanged(token,
                    visible, removed));
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

    public void setCurrentUser(@UserIdInt int newUserId) {
        synchronized (mGlobalLock) {
            final TransitionController controller = mAtmService.getTransitionController();
            if (!controller.isCollecting() && controller.isShellTransitionsEnabled()) {
                controller.requestStartTransition(controller.createTransition(TRANSIT_OPEN),
                        null /* trigger */, null /* remote */, null /* disp */);
            }
            mCurrentUserId = newUserId;
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
                final int targetDensity = forcedDensity != 0
                        ? forcedDensity : displayContent.getInitialDisplayDensity();
                displayContent.setForcedDensity(targetDensity, UserHandle.USER_CURRENT);
            }
        }
    }

    /* Called by WindowState */
    boolean isUserVisible(@UserIdInt int userId) {
        return mUmInternal.isUserVisible(userId);
    }

    @UserIdInt int getUserAssignedToDisplay(int displayId) {
        return mUmInternal.getUserAssignedToDisplay(displayId);
    }

    boolean shouldPlacePrimaryHomeOnDisplay(int displayId) {
        int userId = mUmInternal.getUserAssignedToDisplay(displayId);
        return shouldPlacePrimaryHomeOnDisplay(displayId, userId);
    }

    boolean shouldPlacePrimaryHomeOnDisplay(int displayId, int userId) {
        return mUmInternal.getMainDisplayAssignedToUser(userId) == displayId;
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

            if (!SurfaceControl.bootFinished()) {
                ProtoLog.w(WM_ERROR, "performEnableScreen: bootFinished() failed.");
                return;
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

        synchronized (mGlobalLock) {
            mAtmService.getTransitionController().mIsWaitingForDisplayEnabled = false;
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Notified TransitionController "
                    + "that the display is ready.");
        }
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
     * If {@code com.android.internal.R.bool.config_perDisplayFocusEnabled} is set to true, then
     * only the display represented by the {@code displayId} parameter will be requested to switch
     * the touch mode state. Otherwise all displays that do not maintain their own focus and touch
     * mode will be requested to switch their touch mode state (disregarding {@code displayId}
     * parameter).
     *
     * To be able to change touch mode state, the caller must either own the focused window, or must
     * have the {@link android.Manifest.permission#MODIFY_TOUCH_MODE_STATE} permission. Instrumented
     * process, sourced with {@link android.Manifest.permission#MODIFY_TOUCH_MODE_STATE}, may switch
     * touch mode at any time.
     *
     * @param inTouch   the touch mode to set
     * @param displayId the target display id
     */
    @Override // Binder call
    public void setInTouchMode(boolean inTouch, int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (mPerDisplayFocusEnabled && (displayContent == null
                    || displayContent.isInTouchMode() == inTouch)) {
                return;
            }
            final boolean displayHasOwnTouchMode =
                    displayContent != null && displayContent.hasOwnFocus();
            if (displayHasOwnTouchMode && displayContent.isInTouchMode() == inTouch) {
                return;
            }
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final boolean hasPermission = hasTouchModePermission(pid);
            final long token = Binder.clearCallingIdentity();
            try {
                // If mPerDisplayFocusEnabled is set or the display maintains its own touch mode,
                // then just update the display pointed by displayId
                if (mPerDisplayFocusEnabled || displayHasOwnTouchMode) {
                    if (mInputManager.setInTouchMode(inTouch, pid, uid, hasPermission, displayId)) {
                        displayContent.setInTouchMode(inTouch);
                    }
                } else {  // Otherwise update all displays that do not maintain their own touch mode
                    final int displayCount = mRoot.mChildren.size();
                    for (int i = 0; i < displayCount; ++i) {
                        DisplayContent dc = mRoot.mChildren.get(i);
                        if (dc.isInTouchMode() == inTouch || dc.hasOwnFocus()) {
                            continue;
                        }
                        if (mInputManager.setInTouchMode(inTouch, pid, uid, hasPermission,
                                dc.mDisplayId)) {
                            dc.setInTouchMode(inTouch);
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    /**
     * Sets the touch mode state forcibly on all displays (disregarding both the value of
     * {@code com.android.internal.R.bool.config_perDisplayFocusEnabled} and whether the display
     * maintains its own focus and touch mode).
     *
     * @param inTouch the touch mode to set
     */
    @Override // Binder call
    public void setInTouchModeOnAllDisplays(boolean inTouch) {
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final boolean hasPermission = hasTouchModePermission(pid);
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                for (int i = 0; i < mRoot.mChildren.size(); ++i) {
                    DisplayContent dc = mRoot.mChildren.get(i);
                    if (dc.isInTouchMode() != inTouch
                            && mInputManager.setInTouchMode(inTouch, pid, uid, hasPermission,
                            dc.mDisplayId)) {
                        dc.setInTouchMode(inTouch);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean hasTouchModePermission(int pid) {
        return mAtmService.instrumentationSourceHasPermission(pid, MODIFY_TOUCH_MODE_STATE)
                || checkCallingPermission(MODIFY_TOUCH_MODE_STATE, "setInTouchMode()",
                /* printlog= */ false);
    }

    /**
     * Returns the touch mode state for the display id passed as argument.
     *
     * This method will return the default touch mode state (represented by
     * {@code com.android.internal.R.bool.config_defaultInTouchMode}) if the display passed as
     * argument is no longer registered in {@RootWindowContainer}).
     */
    @Override  // Binder call
    public boolean isInTouchMode(int displayId) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent == null) {
                return mContext.getResources().getBoolean(R.bool.config_defaultInTouchMode);
            }
            return displayContent.isInTouchMode();
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

    @Nullable
    private ScreenshotHardwareBuffer takeAssistScreenshot(Set<Integer> windowTypesToExclude) {
        if (!checkCallingPermission(READ_FRAME_BUFFER, "requestAssistScreenshot()")) {
            throw new SecurityException("Requires READ_FRAME_BUFFER permission");
        }

        ScreenCapture.LayerCaptureArgs captureArgs;
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(DEFAULT_DISPLAY);
            if (displayContent == null) {
                if (DEBUG_SCREENSHOT) {
                    Slog.i(TAG_WM, "Screenshot returning null. No Display for displayId="
                            + DEFAULT_DISPLAY);
                }
                captureArgs = null;
            } else {
                captureArgs = displayContent.getLayerCaptureArgs(windowTypesToExclude);
            }
        }

        final ScreenshotHardwareBuffer screenshotBuffer;
        if (captureArgs != null) {
            ScreenCapture.SynchronousScreenCaptureListener syncScreenCapture =
                    ScreenCapture.createSyncCaptureListener();

            ScreenCapture.captureLayers(captureArgs, syncScreenCapture);

            screenshotBuffer = syncScreenCapture.getBuffer();
        } else {
            screenshotBuffer = null;
        }

        if (screenshotBuffer == null) {
            Slog.w(TAG_WM, "Failed to take screenshot");
        }

        return screenshotBuffer;
    }

    /**
     * Takes a snapshot of the screen.  In landscape mode this grabs the whole screen.
     * In portrait mode, it grabs the upper region of the screen based on the vertical dimension
     * of the target image.
     */
    @Override
    public boolean requestAssistScreenshot(final IAssistDataReceiver receiver) {
        final ScreenshotHardwareBuffer shb =
                takeAssistScreenshot(/* windowTypesToExclude= */ Set.of());
        final Bitmap bm = shb != null ? shb.asBitmap() : null;
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
     * @param layerCaptureArgsBuilder A {@link ScreenCapture.LayerCaptureArgs.Builder} with
     *                                arguments for how to capture the Bitmap. The caller can
     *                                specify any arguments, but this method will ensure that the
     *                                specified task's SurfaceControl is used and the crop is set to
     *                                the bounds of that task.
     * @return The Bitmap, or null if no task with the specified ID can be found or the bitmap could
     * not be generated.
     */
    @Nullable
    public Bitmap captureTaskBitmap(int taskId,
            @NonNull ScreenCapture.LayerCaptureArgs.Builder layerCaptureArgsBuilder) {
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
            final ScreenshotHardwareBuffer buffer = ScreenCapture.captureLayers(
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
     * at runtime and how to optionally map some requested orientations to others.
     *
     * <p>Note: this assumes that {@link #mGlobalLock} is held by the caller.
     *
     * @param isIgnoreOrientationRequestDisabled when {@code true}, the system always ignores the
     *                   value of {@link DisplayArea#getIgnoreOrientationRequest} and app requested
     *                   orientation is respected.
     * @param fromOrientations The orientations we want to map to the correspondent orientations
     *                        in toOrientation.
     * @param toOrientations The orientations we map to the ones in fromOrientations at  the same
     *                       index
     */
    void setOrientationRequestPolicy(boolean isIgnoreOrientationRequestDisabled,
            @Nullable int[] fromOrientations, @Nullable int[] toOrientations) {
        mOrientationMapping.clear();
        if (fromOrientations != null && toOrientations != null
                && fromOrientations.length == toOrientations.length) {
            for (int i = 0; i < fromOrientations.length; i++) {
                mOrientationMapping.put(fromOrientations[i], toOrientations[i]);
            }
        }
        if (isIgnoreOrientationRequestDisabled == mIsIgnoreOrientationRequestDisabled) {
            return;
        }
        mIsIgnoreOrientationRequestDisabled = isIgnoreOrientationRequestDisabled;
        for (int i = mRoot.getChildCount() - 1; i >= 0; i--) {
            mRoot.getChildAt(i).onIsIgnoreOrientationRequestDisabledChanged();
        }
    }

    /**
     * When {@link mIsIgnoreOrientationRequestDisabled} is {@value true} this method returns the
     * orientation to use in place of the one in input. It returns the same requestedOrientation in
     * input otherwise.
     *
     * @param requestedOrientation The orientation that can be mapped.
     * @return The orientation to use in place of requestedOrientation.
     */
    int mapOrientationRequest(int requestedOrientation) {
        if (!mIsIgnoreOrientationRequestDisabled) {
            return requestedOrientation;
        }
        return mOrientationMapping.get(requestedOrientation, requestedOrientation);
    }

    /**
     * Whether the system ignores the value of {@link DisplayArea#getIgnoreOrientationRequest} and
     * app requested orientation is respected.
     *
     * <p>Note: this assumes that {@link #mGlobalLock} is held by the caller.
     */
    boolean isIgnoreOrientationRequestDisabled() {
        return mIsIgnoreOrientationRequestDisabled
                || !mLetterboxConfiguration.isIgnoreOrientationRequestAllowed();
    }

    @Override
    public void freezeRotation(int rotation, String caller) {
        freezeDisplayRotation(Display.DEFAULT_DISPLAY, rotation, caller);
    }

    /**
     * Freeze rotation changes.  (Enable "rotation lock".)
     * Persists across reboots.
     * @param displayId The ID of the display to freeze.
     * @param rotation The desired rotation to freeze to, or -1 to use the current rotation.
     */
    @Override
    public void freezeDisplayRotation(int displayId, int rotation, String caller) {
        // TODO(multi-display): Track which display is rotated.
        if (!checkCallingPermission(android.Manifest.permission.SET_ORIENTATION,
                "freezeRotation()")) {
            throw new SecurityException("Requires SET_ORIENTATION permission");
        }
        if (rotation < -1 || rotation > Surface.ROTATION_270) {
            throw new IllegalArgumentException("Rotation argument must be -1 or a valid "
                    + "rotation constant.");
        }
        ProtoLog.v(WM_DEBUG_ORIENTATION,
                "freezeDisplayRotation: current rotation=%d, new rotation=%d, caller=%s",
                getDefaultDisplayRotation(), rotation, caller);

        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent display = mRoot.getDisplayContent(displayId);
                if (display == null) {
                    Slog.w(TAG, "Trying to freeze rotation for a missing display.");
                    return;
                }
                display.getDisplayRotation().freezeRotation(rotation, caller);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        updateRotationUnchecked(false, false);
    }

    @Override
    public void thawRotation(String caller) {
        thawDisplayRotation(Display.DEFAULT_DISPLAY, caller);
    }

    /**
     * Thaw rotation changes.  (Disable "rotation lock".)
     * Persists across reboots.
     */
    @Override
    public void thawDisplayRotation(int displayId, String caller) {
        if (!checkCallingPermission(android.Manifest.permission.SET_ORIENTATION,
                "thawRotation()")) {
            throw new SecurityException("Requires SET_ORIENTATION permission");
        }

        ProtoLog.v(WM_DEBUG_ORIENTATION, "thawRotation: mRotation=%d, caller=%s",
                getDefaultDisplayRotation(), caller);

        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent display = mRoot.getDisplayContent(displayId);
                if (display == null) {
                    Slog.w(TAG, "Trying to thaw rotation for a missing display.");
                    return;
                }
                display.getDisplayRotation().thawRotation(caller);
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

    @Override
    public int getDisplayUserRotation(int displayId) {
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
                        // The layout-needed flag will be set if there is a rotation change, so
                        // only set it if the caller requests to force relayout.
                        if (forceRelayout) {
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
            throw new RuntimeException("Unable to set rotation controller", e);
        }
    }

    @EnforcePermission(android.Manifest.permission.MANAGE_APP_TOKENS)
    @Override
    public SurfaceControl addShellRoot(int displayId, IWindow client,
            @WindowManager.ShellRootLayer int shellRootLayer) {
        addShellRoot_enforcePermission();
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

    @EnforcePermission(android.Manifest.permission.MANAGE_APP_TOKENS)
    @Override
    public void setShellRootAccessibilityWindow(int displayId,
            @WindowManager.ShellRootLayer int shellRootLayer, IWindow target) {
        setShellRootAccessibilityWindow_enforcePermission();
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

    @EnforcePermission(android.Manifest.permission.MANAGE_APP_TOKENS)
    @Override
    public void setDisplayWindowInsetsController(
            int displayId, IDisplayWindowInsetsController insetsController) {
        setDisplayWindowInsetsController_enforcePermission();
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

    @EnforcePermission(android.Manifest.permission.MANAGE_APP_TOKENS)
    @Override
    public void updateDisplayWindowRequestedVisibleTypes(
            int displayId, @InsetsType int requestedVisibleTypes) {
        updateDisplayWindowRequestedVisibleTypes_enforcePermission();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent dc = mRoot.getDisplayContent(displayId);
                if (dc == null || dc.mRemoteInsetsControlTarget == null) {
                    return;
                }
                dc.mRemoteInsetsControlTarget.setRequestedVisibleTypes(requestedVisibleTypes);
                dc.getInsetsStateController().onRequestedVisibleTypesChanged(
                        dc.mRemoteInsetsControlTarget);
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
            if (displayContent == null) {
                throw new IllegalArgumentException("Trying to register rotation event "
                        + "for invalid display: " + displayId);
            }
            mRotationWatcherController.registerDisplayRotationWatcher(watcher, displayId);
            return displayContent.getRotation();
        }
    }

    @Override
    public void removeRotationWatcher(IRotationWatcher watcher) {
        synchronized (mGlobalLock) {
            mRotationWatcherController.removeRotationWatcher(watcher);
        }
    }

    @Surface.Rotation
    @Override
    public int registerProposedRotationListener(IBinder contextToken, IRotationWatcher listener) {
        synchronized (mGlobalLock) {
            final WindowContainer<?> wc =
                    mRotationWatcherController.getAssociatedWindowContainer(contextToken);
            if (wc == null) {
                Slog.w(TAG, "Register rotation listener from non-existing token, uid="
                        + Binder.getCallingUid());
                return Surface.ROTATION_0;
            }
            mRotationWatcherController.registerProposedRotationListener(listener, contextToken);
            final WindowOrientationListener orientationListener =
                    wc.mDisplayContent.getDisplayRotation().getOrientationListener();
            if (orientationListener != null) {
                // It may be -1 if sensor is disabled.
                final int rotation = orientationListener.getProposedRotation();
                if (rotation >= Surface.ROTATION_0) {
                    return rotation;
                }
            }
            return wc.getWindowConfiguration().getRotation();
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
                throw new IllegalArgumentException(
                        "Trying to register system gesture exclusion event for invalid display: "
                                + displayId);
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
                throw new IllegalArgumentException(
                        "Trying to unregister system gesture exclusion event for invalid display: "
                                + displayId);
            }
            displayContent.unregisterSystemGestureExclusionListener(listener);
        }
    }

    @Override
    public void registerDecorViewGestureListener(
            IDecorViewGestureListener listener, int displayId) {
        if (!checkCallingPermission(android.Manifest.permission.MONITOR_INPUT,
                "registerDecorViewGestureListener()")) {
            throw new SecurityException("Requires MONITOR_INPUT permission");
        }
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent == null) {
                throw new IllegalArgumentException(
                        "Trying to register DecorView gesture event listener"
                                + "for invalid display: "
                                + displayId);
            }
            displayContent.registerDecorViewGestureListener(listener);
        }
    }

    @Override
    public void unregisterDecorViewGestureListener(
            IDecorViewGestureListener listener, int displayId) {
        if (!checkCallingPermission(android.Manifest.permission.MONITOR_INPUT,
                "unregisterSystemGestureExclusionListener()")) {
            throw new SecurityException("Requires MONITOR_INPUT permission");
        }
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent == null) {
                throw new IllegalArgumentException(
                        "Trying to unregister DecorView gesture event listener"
                                + "for invalid display: "
                                + displayId);
            }
            displayContent.unregisterDecorViewGestureListener(listener);
        }
    }

    void reportDecorViewGestureChanged(Session session, IWindow window, boolean intercepted) {
        synchronized (mGlobalLock) {
            final WindowState win =
                    windowForClientLocked(session, window, false /* throwOnError */);
            if (win == null) {
                return;
            }
            win.getDisplayContent()
                    .updateDecorViewGestureIntercepted(win.mToken.token, intercepted);
        }
    }

    void reportSystemGestureExclusionChanged(Session session, IWindow window,
            List<Rect> exclusionRects) {
        synchronized (mGlobalLock) {
            final WindowState win = windowForClientLocked(session, window,
                    false /* throwOnError */);
            if (win == null) {
                Slog.i(TAG_WM,
                        "reportSystemGestureExclusionChanged(): No window state for package:"
                                + session.mPackageName);
                return;
            }
            if (win.setSystemGestureExclusion(exclusionRects)) {
                win.getDisplayContent().updateSystemGestureExclusion();
            }
        }
    }

    void reportKeepClearAreasChanged(Session session, IWindow window,
            List<Rect> restricted, List<Rect> unrestricted) {
        synchronized (mGlobalLock) {
            final WindowState win = windowForClientLocked(session, window,
                    false /* throwOnError */);
            if (win == null) {
                Slog.i(TAG_WM,
                        "reportKeepClearAreasChanged(): No window state for package:"
                                + session.mPackageName);
                return;
            }
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

    private void notifyWindowRemovedListeners(IBinder client) {
        OnWindowRemovedListener[] windowRemovedListeners;
        synchronized (mGlobalLock) {
            if (mOnWindowRemovedListeners.isEmpty()) {
                return;
            }
            windowRemovedListeners = new OnWindowRemovedListener[mOnWindowRemovedListeners.size()];
            mOnWindowRemovedListeners.toArray(windowRemovedListeners);
        }
        mH.post(() -> {
            int size = windowRemovedListeners.length;
            for (int i = 0; i < size; i++) {
                windowRemovedListeners[i].onWindowRemoved(client);
            }
        });
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
                mRoot.forAllDisplays(dc -> {
                    if (dc.mDisplay.getType() == Display.TYPE_INTERNAL) {
                        dc.setMaxUiWidth(mMaxUiWidth);
                    }
                });
            }
            applyForcedPropertiesForDefaultDisplay();
            mAnimator.ready();
            mDisplayReady = true;
            mHasWideColorGamutSupport = queryWideColorGamutSupport();
            mHasHdrSupport = queryHdrSupport();
            mIsTouchDevice = mContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_TOUCHSCREEN);
            mIsFakeTouchDevice = mContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_FAKETOUCH);
            // Reconfigure all displays to make sure that the forced properties and
            // DisplayWindowSettings are applied. In addition, wide-color/hdr/isTouchDevice also
            // affect the Configuration.
            mRoot.forAllDisplays(DisplayContent::reconfigureDisplayLocked);
        }
    }

    public void systemReady() {
        mSystemReady = true;
        mPolicy.systemReady();
        mRoot.forAllDisplayPolicies(DisplayPolicy::systemReady);
        mSnapshotController.systemReady();
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
        public static final int ENABLE_SCREEN = 16;
        public static final int APP_FREEZE_TIMEOUT = 17;
        public static final int REPORT_WINDOWS_CHANGE = 19;

        public static final int REPORT_HARD_KEYBOARD_STATUS_CHANGE = 22;
        public static final int BOOT_TIMEOUT = 23;
        public static final int WAITING_FOR_DRAWN_TIMEOUT = 24;
        public static final int SHOW_STRICT_MODE_VIOLATION = 25;

        public static final int CLIENT_FREEZE_TIMEOUT = 30;
        public static final int NOTIFY_ACTIVITY_DRAWN = 32;

        public static final int NEW_ANIMATOR_SCALE = 34;

        public static final int SHOW_EMULATOR_DISPLAY_OVERLAY = 36;

        public static final int CHECK_IF_BOOT_ANIMATION_FINISHED = 37;
        public static final int RESET_ANR_MESSAGE = 38;
        public static final int WALLPAPER_DRAW_PENDING_TIMEOUT = 39;

        public static final int UPDATE_MULTI_WINDOW_STACKS = 41;

        public static final int UPDATE_ANIMATION_SCALE = 51;
        public static final int WINDOW_HIDE_TIMEOUT = 52;
        public static final int SET_HAS_OVERLAY_UI = 58;
        public static final int ANIMATION_FAILSAFE = 60;
        public static final int RECOMPUTE_FOCUS = 61;
        public static final int ON_POINTER_DOWN_OUTSIDE_FOCUS = 62;
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
                            mWindowAnimationScaleSetting = getWindowAnimationScaleSetting();
                            break;
                        }
                        case TRANSITION_ANIMATION_SCALE: {
                            mTransitionAnimationScaleSetting =
                                    getTransitionAnimationScaleSetting();
                            break;
                        }
                        case ANIMATION_DURATION_SCALE: {
                            mAnimatorDurationScaleSetting = getAnimatorDurationScaleSetting();
                            dispatchNewAnimatorScaleLocked(null);
                            break;
                        }
                    }
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
                    final Message callback;
                    final WindowContainer<?> container = (WindowContainer<?>) msg.obj;
                    synchronized (mGlobalLock) {
                        ProtoLog.w(WM_ERROR, "Timeout waiting for drawn: undrawn=%s",
                                container.mWaitingForDrawn);
                        if (Trace.isTagEnabled(TRACE_TAG_WINDOW_MANAGER)) {
                            for (int i = 0; i < container.mWaitingForDrawn.size(); i++) {
                                traceEndWaitingForWindowDrawn(container.mWaitingForDrawn.get(i));
                            }
                        }
                        container.mWaitingForDrawn.clear();
                        callback = mWaitingForDrawnCallbacks.remove(container);
                    }
                    if (callback != null) {
                        callback.sendToTarget();
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
                        onPointerDownOutsideFocusLocked(getInputTargetFromToken(touchedToken));
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
                            // We need to update resizing windows and dispatch the new insets state
                            // to them.
                            mWindowPlacerLocked.performSurfacePlacement();
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

    @EnforcePermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    @Override
    public void setForcedDisplaySize(int displayId, int width, int height) {
        setForcedDisplaySize_enforcePermission();

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

    @EnforcePermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    @Override
    public void setForcedDisplayScalingMode(int displayId, int mode) {
        setForcedDisplayScalingMode_enforcePermission();

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
                try {
                    final Point size = displayContent.getValidForcedSize(
                            Integer.parseInt(sizeStr.substring(0, pos)),
                            Integer.parseInt(sizeStr.substring(pos + 1)));
                    final int width = size.x;
                    final int height = size.y;
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

    @EnforcePermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    @Override
    public void clearForcedDisplaySize(int displayId) {
        clearForcedDisplaySize_enforcePermission();

        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    displayContent.setForcedSize(displayContent.mInitialDisplayWidth,
                            displayContent.mInitialDisplayHeight,
                            displayContent.mInitialPhysicalXDpi,
                            displayContent.mInitialPhysicalXDpi);
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
                return displayContent.getInitialDisplayDensity();
            }

            DisplayInfo info = mDisplayManagerInternal.getDisplayInfo(displayId);
            if (info != null && info.hasAccess(Binder.getCallingUid())) {
                return info.logicalDensityDpi;
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

    /**
     * Return the display Id that has the given uniqueId. Unique ID is defined in
     * {@link DisplayInfo#uniqueId}.
     */
    @Override
    public int getDisplayIdByUniqueId(String uniqueId) {
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(uniqueId);
            if (displayContent != null && displayContent.hasAccess(Binder.getCallingUid())) {
                return displayContent.mDisplayId;
            }
        }
        return -1;
    }

    @EnforcePermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    @Override
    public void setForcedDisplayDensityForUser(int displayId, int density, int userId) {
        setForcedDisplayDensityForUser_enforcePermission();

        final int targetUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "setForcedDisplayDensityForUser",
                null);
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    displayContent.setForcedDensity(density, targetUserId);
                } else {
                    DisplayInfo info = mDisplayManagerInternal.getDisplayInfo(displayId);
                    if (info != null) {
                        mDisplayWindowSettings.setForcedDensity(info, density, userId);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @EnforcePermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    @Override
    public void clearForcedDisplayDensityForUser(int displayId, int userId) {
        clearForcedDisplayDensityForUser_enforcePermission();

        final int callingUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "clearForcedDisplayDensityForUser",
                null);
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
                if (displayContent != null) {
                    displayContent.setForcedDensity(displayContent.getInitialDisplayDensity(),
                            callingUserId);
                } else {
                    DisplayInfo info = mDisplayManagerInternal.getDisplayInfo(displayId);
                    if (info != null) {
                        mDisplayWindowSettings.setForcedDensity(info, info.logicalDensityDpi,
                                userId);
                    }
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
        return mTransitionTracer.isTracing();
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
        // If the screen is currently frozen, then keep it frozen until this window draws at its
        // new orientation.
        if (mFrozenDisplayId != INVALID_DISPLAY && mFrozenDisplayId == w.getDisplayId()
                && mWindowsFreezingScreen != WINDOWS_FREEZING_SCREENS_TIMEOUT) {
            ProtoLog.v(WM_DEBUG_ORIENTATION, "Changing surface while display frozen: %s", w);
            // WindowsState#reportResized won't tell invisible requested window to redraw,
            // so do not set it as changing orientation to avoid affecting draw state.
            if (w.isVisibleRequested()) {
                w.setOrientationChanging(true);
            }
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
        for (int i = mWaitingForDrawnCallbacks.size() - 1; i >= 0; i--) {
            final WindowContainer<?> container = mWaitingForDrawnCallbacks.keyAt(i);
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
                    if (Trace.isTagEnabled(TRACE_TAG_WINDOW_MANAGER)) {
                        traceEndWaitingForWindowDrawn(win);
                    }
                } else if (win.hasDrawn()) {
                    // Window is now drawn (and shown).
                    ProtoLog.d(WM_DEBUG_SCREEN_ON, "Window drawn win=%s", win);
                    container.mWaitingForDrawn.remove(win);
                    if (Trace.isTagEnabled(TRACE_TAG_WINDOW_MANAGER)) {
                        traceEndWaitingForWindowDrawn(win);
                    }
                }
            }
            if (container.mWaitingForDrawn.isEmpty()) {
                ProtoLog.d(WM_DEBUG_SCREEN_ON, "All windows drawn!");
                mH.removeMessages(H.WAITING_FOR_DRAWN_TIMEOUT, container);
                mWaitingForDrawnCallbacks.removeAt(i).sendToTarget();
            }
        }
    }

    private void traceStartWaitingForWindowDrawn(WindowState window) {
        final String traceName = TRACE_WAIT_FOR_ALL_WINDOWS_DRAWN_METHOD + "#"
                + window.getWindowTag();
        final String shortenedTraceName = traceName.substring(0, Math.min(
                TRACE_MAX_SECTION_NAME_LENGTH, traceName.length()));
        Trace.asyncTraceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, shortenedTraceName, /* cookie= */ 0);
    }

    private void traceEndWaitingForWindowDrawn(WindowState window) {
        final String traceName = TRACE_WAIT_FOR_ALL_WINDOWS_DRAWN_METHOD + "#"
                + window.getWindowTag();
        final String shortenedTraceName = traceName.substring(0, Math.min(
                TRACE_MAX_SECTION_NAME_LENGTH, traceName.length()));
        Trace.asyncTraceEnd(Trace.TRACE_TAG_WINDOW_MANAGER, shortenedTraceName, /* cookie= */ 0);
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

        displayContent.requestDisplayUpdate(() -> {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "WMS.doStartFreezingDisplay");
            doStartFreezingDisplay(exitAnim, enterAnim, displayContent, overrideOriginalRotation);
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        });
    }

    private void doStartFreezingDisplay(int exitAnim, int enterAnim, DisplayContent displayContent,
            int overrideOriginalRotation) {
        ProtoLog.d(WM_DEBUG_ORIENTATION,
                            "startFreezingDisplayLocked: exitAnim=%d enterAnim=%d called by %s",
                            exitAnim, enterAnim, Debug.getCallers(8));
        mScreenFrozenLock.acquire();
        // Apply launch power mode to reduce screen frozen time because orientation change may
        // relaunch activity and redraw windows. This may also help speed up user switching.
        mAtmService.startPowerMode(POWER_MODE_REASON_CHANGE_DISPLAY);

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

        mScreenFrozenLock.release();

        if (updateRotation && displayContent != null) {
            ProtoLog.d(WM_DEBUG_ORIENTATION, "Performing post-rotate rotation");
            configChanged |= displayContent.updateRotationUnchecked();
        }

        if (configChanged) {
            displayContent.sendNewConfiguration();
        }
        mAtmService.endPowerMode(POWER_MODE_REASON_CHANGE_DISPLAY);
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

    @EnforcePermission(android.Manifest.permission.STATUS_BAR)
    public void setNavBarVirtualKeyHapticFeedbackEnabled(boolean enabled) {
        setNavBarVirtualKeyHapticFeedbackEnabled_enforcePermission();

        synchronized (mGlobalLock) {
            mPolicy.setNavBarVirtualKeyHapticFeedbackEnabledLw(enabled);
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
    public boolean destroyInputConsumer(IBinder token, int displayId) {
        if (!mAtmService.isCallerRecents(Binder.getCallingUid())
                && mContext.checkCallingOrSelfPermission(INPUT_CONSUMER) != PERMISSION_GRANTED) {
            throw new SecurityException("destroyInputConsumer requires INPUT_CONSUMER permission");
        }

        synchronized (mGlobalLock) {
            DisplayContent display = mRoot.getDisplayContent(displayId);
            if (display != null) {
                return display.getInputMonitor().destroyInputConsumer(token);
            }
            return false;
        }
    }

    @EnforcePermission(android.Manifest.permission.RESTRICTED_VR_ACCESS)
    @Override
    public Region getCurrentImeTouchRegion() {
        getCurrentImeTouchRegion_enforcePermission();
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

    private void dumpPolicyLocked(PrintWriter pw, String[] args) {
        pw.println("WINDOW MANAGER POLICY STATE (dumpsys window policy)");
        mPolicy.dump("    ", pw, args);
    }

    private void dumpAnimatorLocked(PrintWriter pw, boolean dumpAll) {
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
        if (android.tracing.Flags.perfettoProtologTracing()) {
            pw.println("Deprecated legacy command. Use Perfetto commands instead.");
            return;
        }
        ((LegacyProtoLogImpl) ProtoLog.getSingleInstance()).getStatus();
    }

    private void dumpSessionsLocked(PrintWriter pw) {
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

        // Write the BackNavigationController's state into the protocol buffer
        mAtmService.mBackNavigationController.dumpDebug(proto, BACK_NAVIGATION);
    }

    private void dumpWindowsLocked(PrintWriter pw, boolean dumpAll,
            ArrayList<WindowState> windows) {
        pw.println("WINDOW MANAGER WINDOWS (dumpsys window windows)");
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
        if (mForceRemoves != null && !mForceRemoves.isEmpty()) {
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
        if (!mDestroySurface.isEmpty()) {
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
        if (!mResizingWindows.isEmpty()) {
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
        pw.print("  mBlurEnabled="); pw.println(mBlurController.getBlurEnabled());
        pw.print("  mLastDisplayFreezeDuration=");
                TimeUtils.formatDuration(mLastDisplayFreezeDuration, pw);
                if ( mLastFinishedFreezeSource != null) {
                    pw.print(" due to ");
                    pw.print(mLastFinishedFreezeSource);
                }
                pw.println();
        pw.print("  mDisableSecureWindows="); pw.println(mDisableSecureWindows);

        mInputManagerCallback.dump(pw, "  ");
        mSnapshotController.dump(pw, " ");

        dumpAccessibilityController(pw, /* force= */ false);

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
                    pw.print(" apps="); pw.println(mAppsFreezingScreen);
            final DisplayContent defaultDisplayContent = getDefaultDisplayContentLocked();
            pw.print("  mRotation="); pw.println(defaultDisplayContent.getRotation());
            pw.print("  mLastOrientation=");
                    pw.println(defaultDisplayContent.getLastOrientation());
            pw.print("  mWaitingForConfig=");
                    pw.println(defaultDisplayContent.mWaitingForConfig);
            pw.print("  mWindowsInsetsChanged="); pw.println(mWindowsInsetsChanged);
            mRotationWatcherController.dump(pw);

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

    private void dumpAccessibilityController(PrintWriter pw, boolean force) {
        boolean hasCallbacks = mAccessibilityController.hasCallbacks();
        if (!hasCallbacks && !force) {
            return;
        }
        if (!hasCallbacks) {
            pw.println("AccessibilityController doesn't have callbacks, but printing it anways:");
        } else {
            pw.println("AccessibilityController:");
        }
        mAccessibilityController.dump(pw, "  ");
    }

    private void dumpAccessibilityLocked(PrintWriter pw) {
        dumpAccessibilityController(pw, /* force= */ true);
    }

    private boolean dumpWindows(PrintWriter pw, String name, boolean dumpAll) {
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

        if (windows.isEmpty()) {
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
        pw.println();
        final ArrayList<WindowState> relatedWindows = new ArrayList<>();
        for (int i = mRoot.getChildCount() - 1; i >= 0; i--) {
            final DisplayContent dc = mRoot.getChildAt(i);
            final int displayId = dc.getDisplayId();
            final WindowState currentFocus = dc.mCurrentFocus;
            final ActivityRecord focusedApp = dc.mFocusedApp;
            pw.println("  Display #" + displayId + " currentFocus=" + currentFocus
                    + " focusedApp=" + focusedApp);
            if (!dc.mWinAddedSinceNullFocus.isEmpty()) {
                pw.println("  Windows added in display #" + displayId + " since null focus: "
                        + dc.mWinAddedSinceNullFocus);
            }
            if (!dc.mWinRemovedSinceNullFocus.isEmpty()) {
                pw.println("  Windows removed in display #" + displayId + " since null focus: "
                        + dc.mWinRemovedSinceNullFocus);
            }
            pw.println("  Tasks in top down Z order:");
            dc.forAllTaskDisplayAreas(tda -> {
                tda.dump(pw, "    ", false /* dumpAll */);
            });
            dc.getInputMonitor().dump(pw, "  ");
            pw.println();
            dc.forAllWindows(w -> {
                if ((currentFocus != null && Objects.equals(w.mAttrs.packageName,
                        currentFocus.mAttrs.packageName)) || (focusedApp != null
                        && Objects.equals(w.mAttrs.packageName, focusedApp.packageName))) {
                    relatedWindows.add(w);
                }
            }, true /* traverseTopToBottom */);
        }
        if (windowState != null && !relatedWindows.contains(windowState)) {
            relatedWindows.add(windowState);
        }
        mRoot.dumpWindowsNoHeader(pw, true /* dumpAll */, relatedWindows);
        pw.println();
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
                pw.println("    a11y[accessibility]: accessibility-related state");
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
                    dumpPolicyLocked(pw, args);
                }
                return;
            } else if ("animator".equals(cmd) || "a".equals(cmd)) {
                synchronized (mGlobalLock) {
                    dumpAnimatorLocked(pw, true);
                }
                return;
            } else if ("sessions".equals(cmd) || "s".equals(cmd)) {
                synchronized (mGlobalLock) {
                    dumpSessionsLocked(pw);
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
            } else if ("accessibility".equals(cmd) || "a11y".equals(cmd)) {
                synchronized (mGlobalLock) {
                    dumpAccessibilityLocked(pw);
                }
                return;
            } else if ("all".equals(cmd)) {
                synchronized (mGlobalLock) {
                    dumpWindowsLocked(pw, true, null);
                }
                return;
            } else if ("containers".equals(cmd)) {
                synchronized (mGlobalLock) {
                    mRoot.dumpChildrenNames(pw, "");
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
                if (!dumpWindows(pw, cmd, dumpAll)) {
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
            dumpPolicyLocked(pw, args);
            pw.println();
            if (dumpAll) {
                pw.println(separator);
            }
            dumpAnimatorLocked(pw, dumpAll);
            pw.println();
            if (dumpAll) {
                pw.println(separator);
            }
            dumpSessionsLocked(pw);
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
            if (dumpAll) {
                pw.println(separator);
            }
            mSystemPerformanceHinter.dump(pw, "");
            mTrustedPresentationListenerController.dump(pw);
            mSensitiveContentPackages.dump(pw);
            mScreenRecordingCallbackController.dump(pw);
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
        // Post to display thread so it can get the latest display info.
        mH.post(() -> {
            synchronized (mGlobalLock) {
                mAtmService.deferWindowLayout();
                mRoot.forAllDisplays(dc -> dc.getDisplayPolicy().onOverlayChanged());
                mAtmService.continueWindowLayout();
            }
        });
    }

    @Override
    public Object getWindowManagerLock() {
        return mGlobalLock;
    }

    @Override
    public int getDockedStackSide() {
        return 0;
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
        enforceRegisterWindowManagerListenersPermission("requestAppKeyboardShortcuts");

        WindowState focusedWindow = getFocusedWindow();
        if (focusedWindow == null || focusedWindow.mClient == null) {
            notifyReceiverWithEmptyBundle(receiver);
            return;
        }
        try {
            focusedWindow.mClient.requestAppKeyboardShortcuts(receiver, deviceId);
        } catch (RemoteException e) {
            notifyReceiverWithEmptyBundle(receiver);
        }
    }

    @Override
    public void requestImeKeyboardShortcuts(IResultReceiver receiver, int deviceId) {
        enforceRegisterWindowManagerListenersPermission("requestImeKeyboardShortcuts");

        WindowState imeWindow = mRoot.getCurrentInputMethodWindow();
        if (imeWindow == null || imeWindow.mClient == null) {
            notifyReceiverWithEmptyBundle(receiver);
            return;
        }
        try {
            imeWindow.mClient.requestAppKeyboardShortcuts(receiver, deviceId);
        } catch (RemoteException e) {
            notifyReceiverWithEmptyBundle(receiver);
        }
    }

    private void enforceRegisterWindowManagerListenersPermission(String message) {
        mContext.enforceCallingOrSelfPermission(REGISTER_WINDOW_MANAGER_LISTENERS, message);
    }

    private static void notifyReceiverWithEmptyBundle(IResultReceiver receiver) {
        try {
            receiver.send(0, Bundle.EMPTY);
        } catch (RemoteException e) {
            ProtoLog.e(WM_ERROR, "unable to call receiver for empty keyboard shortcuts");
        }
    }

    @Override
    public void getStableInsets(int displayId, Rect outInsets) throws RemoteException {
        synchronized (mGlobalLock) {
            getStableInsetsLocked(displayId, outInsets);
        }
    }

    /** This is used when there's no app info available and shall return the system default.*/
    void getStableInsetsLocked(int displayId, Rect outInsets) {
        outInsets.setEmpty();
        final DisplayContent dc = mRoot.getDisplayContent(displayId);
        if (dc != null) {
            final DisplayInfo di = dc.getDisplayInfo();
            outInsets.set(dc.getDisplayPolicy().getDecorInsetsInfo(
                    di.rotation, di.logicalWidth, di.logicalHeight).mConfigInsets);
        }
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
        public void onDisplayManagerReceivedDeviceState(int deviceState) {
            mH.post(() -> {
                synchronized (mGlobalLock) {
                    mRoot.onDisplayManagerReceivedDeviceState(deviceState);
                }
            });
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
        public void setFullscreenMagnificationActivated(int displayId, boolean activated) {
            synchronized (mGlobalLock) {
                if (mAccessibilityController.hasCallbacks()) {
                    mAccessibilityController
                            .setFullscreenMagnificationActivated(displayId, activated);
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
        public void moveDisplayToTopIfAllowed(int displayId) {
            WindowManagerService.this.moveDisplayToTopIfAllowed(displayId);
        }

        @Override
        public void requestWindowFocus(IBinder windowToken) {
            synchronized (mGlobalLock) {
                final InputTarget inputTarget =
                        WindowManagerService.this.getInputTargetFromWindowTokenLocked(windowToken);
                WindowManagerService.this.onPointerDownOutsideFocusLocked(inputTarget);
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
        public boolean isKeyguardSecure(@UserIdInt int userId) {
            return mPolicy.isKeyguardSecure(userId);
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
        public void waitForAllWindowsDrawn(Message message, long timeout, int displayId) {
            Objects.requireNonNull(message.getTarget());
            final WindowContainer<?> container = displayId == INVALID_DISPLAY
                    ? mRoot : mRoot.getDisplayContent(displayId);
            if (container == null) {
                // The waiting container doesn't exist, no need to wait to run the callback. Run and
                // return;
                message.sendToTarget();
                return;
            }
            boolean allWindowsDrawn = false;
            synchronized (mGlobalLock) {
                if (mRoot.getDefaultDisplay().mDisplayUpdater.waitForTransition(message)) {
                    // Use the ready-to-play of transition as the signal.
                    return;
                }
                container.waitForAllWindowsDrawn();
                mWindowPlacerLocked.requestTraversal();
                mH.removeMessages(H.WAITING_FOR_DRAWN_TIMEOUT, container);
                if (container.mWaitingForDrawn.isEmpty()) {
                    allWindowsDrawn = true;
                } else {
                    if (Trace.isTagEnabled(TRACE_TAG_WINDOW_MANAGER)) {
                        for (int i = 0; i < container.mWaitingForDrawn.size(); i++) {
                            traceStartWaitingForWindowDrawn(container.mWaitingForDrawn.get(i));
                        }
                    }

                    mWaitingForDrawnCallbacks.put(container, message);
                    mH.sendNewMessageDelayed(H.WAITING_FOR_DRAWN_TIMEOUT, container, timeout);
                    checkDrawnWindowsLocked();
                }
            }
            if (allWindowsDrawn) {
                message.sendToTarget();
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
        public void setWallpaperShowWhenLocked(IBinder binder, boolean showWhenLocked) {
            synchronized (mGlobalLock) {
                final WindowToken token = mRoot.getWindowToken(binder);
                if (token == null || token.asWallpaperToken() == null) {
                    ProtoLog.w(WM_ERROR,
                            "setWallpaperShowWhenLocked: non-existent wallpaper token: %s", binder);
                    return;
                }
                token.asWallpaperToken().setShowWhenLocked(showWhenLocked);
            }
        }

        @Override
        public void setWallpaperCropHints(IBinder binder, SparseArray<Rect> cropHints) {
            synchronized (mGlobalLock) {
                final WindowToken token = mRoot.getWindowToken(binder);
                if (token == null || token.asWallpaperToken() == null) {
                    ProtoLog.w(WM_ERROR,
                            "setWallpaperCropHints: non-existent wallpaper token: %s", binder);
                    return;
                }
                token.asWallpaperToken().setCropHints(cropHints);
            }
        }

        @Override
        public void setWallpaperCropUtils(WallpaperCropUtils wallpaperCropUtils) {
            mRoot.getDisplayContent(DEFAULT_DISPLAY).mWallpaperController
                    .setWallpaperCropUtils(wallpaperCropUtils);
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
        public void showImePostLayout(IBinder imeTargetWindowToken,
                @NonNull ImeTracker.Token statsToken) {
            synchronized (mGlobalLock) {
                InputTarget imeTarget = getInputTargetFromWindowTokenLocked(imeTargetWindowToken);
                if (imeTarget == null) {
                    ImeTracker.forLogging().onFailed(statsToken,
                            ImeTracker.PHASE_WM_HAS_IME_INSETS_CONTROL_TARGET);
                    return;
                }
                ImeTracker.forLogging().onProgress(statsToken,
                        ImeTracker.PHASE_WM_HAS_IME_INSETS_CONTROL_TARGET);

                final InsetsControlTarget controlTarget = imeTarget.getImeControlTarget();
                imeTarget = controlTarget.getWindow();
                // If InsetsControlTarget doesn't have a window, it's using remoteControlTarget
                // which is controlled by default display
                final DisplayContent dc = imeTarget != null
                        ? imeTarget.getDisplayContent() : getDefaultDisplayContentLocked();
                dc.getInsetsStateController().getImeSourceProvider()
                        .scheduleShowImePostLayout(controlTarget, statsToken);
            }
        }

        @Override
        public void hideIme(IBinder imeTargetWindowToken, int displayId,
                @NonNull ImeTracker.Token statsToken) {
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
                    ImeTracker.forLogging().onProgress(statsToken,
                            ImeTracker.PHASE_WM_HAS_IME_INSETS_CONTROL_TARGET);
                    ProtoLog.d(WM_DEBUG_IME, "hideIme Control target: %s ",
                            dc.getImeTarget(IME_TARGET_CONTROL));
                    dc.getImeTarget(IME_TARGET_CONTROL).hideInsets(WindowInsets.Type.ime(),
                            true /* fromIme */, statsToken);
                } else {
                    ImeTracker.forLogging().onFailed(statsToken,
                            ImeTracker.PHASE_WM_HAS_IME_INSETS_CONTROL_TARGET);
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
        public void setHomeSupportedOnDisplay(String displayUniqueId, int displayType,
                boolean supported) {
            final long origId = Binder.clearCallingIdentity();
            try {
                synchronized (mGlobalLock) {
                    mDisplayWindowSettings.setHomeSupportedOnDisplayLocked(
                            displayUniqueId, displayType, supported);
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        @Override
        public boolean isHomeSupportedOnDisplay(int displayId) {
            synchronized (mGlobalLock) {
                final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
                if (displayContent == null) {
                    ProtoLog.w(WM_ERROR, "Attempted to get home support flag of a display that "
                            + "does not exist: %d", displayId);
                    return false;
                }
                return displayContent.isHomeSupported();
            }
        }

        @Override
        public void clearDisplaySettings(String displayUniqueId, int displayType) {
            final long origId = Binder.clearCallingIdentity();
            try {
                synchronized (mGlobalLock) {
                    mDisplayWindowSettings.clearDisplaySettings(displayUniqueId, displayType);
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
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
            final String imeSurfaceParentName;
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
                    final SurfaceControl imeParent = dc.mInputMethodSurfaceParent;
                    imeSurfaceParentName = imeParent != null ? imeParent.toString() : "null";
                    if (show) {
                        dc.onShowImeRequested();
                    }
                } else {
                    imeControlTargetName = imeLayerTargetName = imeSurfaceParentName = "no-display";
                }
            }
            return new ImeTargetInfo(focusedWindowName, requestWindowName, imeControlTargetName,
                    imeLayerTargetName, imeSurfaceParentName);
        }

        @Override
        public boolean shouldRestoreImeVisibility(IBinder imeTargetWindowToken) {
            return WindowManagerService.this.shouldRestoreImeVisibility(imeTargetWindowToken);
       }

        @Override
        public void addTrustedTaskOverlay(int taskId,
                SurfaceControlViewHost.SurfacePackage overlay) {
            if (overlay == null) {
                throw new IllegalArgumentException("Invalid overlay passed in for task=" + taskId);
            }
            synchronized (mGlobalLock) {
                if (overlay.getSurfaceControl() == null
                    || !overlay.getSurfaceControl().isValid()) {
                    throw new IllegalArgumentException(
                            "Invalid overlay surfacecontrol passed in for task=" + taskId);
                }
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
            if (overlay == null) {
                throw new IllegalArgumentException("Invalid overlay passed in for task=" + taskId);
            }
            synchronized (mGlobalLock) {
                if (overlay.getSurfaceControl() == null
                        || !overlay.getSurfaceControl().isValid()) {
                    throw new IllegalArgumentException(
                            "Invalid overlay surfacecontrol passed in for task=" + taskId);
                }
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
                final SurfaceControl inputOverlay = dc.getInputOverlayLayer();
                if (inputOverlay == null) {
                    Slog.e(TAG, "Failed to create a gesture monitor on display: " + displayId
                            + " - Input overlay layer is not initialized.");
                    return null;
                }
                // TODO(b/210039666): Use a method like add/removeDisplayOverlay if available.
                return makeSurfaceBuilder(dc.getSession())
                        .setContainerLayer()
                        .setName("IME Handwriting Surface")
                        .setCallsite("getHandwritingSurfaceForDisplay")
                        .setParent(inputOverlay)
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
        public boolean setContentRecordingSession(
                @Nullable ContentRecordingSession incomingSession) {
            synchronized (mGlobalLock) {
                // Allow the controller to handle teardown of a non-task session.
                if (incomingSession == null
                        || incomingSession.getContentToRecord() != RECORD_CONTENT_TASK) {
                    mContentRecordingController.setContentRecordingSessionLocked(incomingSession,
                            WindowManagerService.this);
                    return true;
                }
                // For a task session, find the activity identified by the launch cookie.
                final WindowContainerInfo wci = getTaskWindowContainerInfoForLaunchCookie(
                        incomingSession.getTokenToRecord());
                if (wci == null) {
                    Slog.w(TAG, "Handling a new recording session; unable to find the "
                            + "WindowContainerToken");
                    return false;
                }
                // Replace the launch cookie in the session details with the task's
                // WindowContainerToken.
                incomingSession.setTokenToRecord(wci.getToken().asBinder());
                // Also replace the UNKNOWN target UID with the actual UID.
                incomingSession.setTargetUid(wci.getUid());
                mContentRecordingController.setContentRecordingSessionLocked(incomingSession,
                        WindowManagerService.this);
                return true;
            }
        }

        @Override
        public SurfaceControl getA11yOverlayLayer(int displayId) {
            synchronized (mGlobalLock) {
                DisplayContent dc = mRoot.getDisplayContent(displayId);
                if (dc != null) {
                    return dc.getA11yOverlayLayer();
                }
            }
            return null;
        }

        @Override
        public void captureDisplay(int displayId, @Nullable ScreenCapture.CaptureArgs captureArgs,
                                   ScreenCapture.ScreenCaptureListener listener) {
            WindowManagerService.this.captureDisplay(displayId, captureArgs, listener);
        }

        @Override
        public boolean hasNavigationBar(int displayId) {
            return WindowManagerService.this.hasNavigationBar(displayId);
        }

        @Override
        public void setInputMethodTargetChangeListener(@NonNull ImeTargetChangeListener listener) {
            synchronized (mGlobalLock) {
                mImeTargetChangeListener = listener;
            }
        }

        @Override
        public void setOrientationRequestPolicy(boolean respected,
                int[] fromOrientations, int[] toOrientations) {
            synchronized (mGlobalLock) {
                WindowManagerService.this.setOrientationRequestPolicy(respected,
                        fromOrientations, toOrientations);
            }
        }

        @Override
        public @Nullable IBinder getTargetWindowTokenFromInputToken(IBinder inputToken) {
            InputTarget inputTarget = WindowManagerService.this.getInputTargetFromToken(inputToken);
            return inputTarget == null ? null : inputTarget.getWindowToken();
        }

        @Override
        public void setBlockScreenCaptureForAppsSessionId(long sessionId) {
            synchronized (mGlobalLock) {
                if (sensitiveContentMetricsBugfix()
                        && mSensitiveContentProtectionSessionId != sessionId) {
                    mSensitiveContentProtectionSessionId = sessionId;
                }
            }
        }

        @Override
        public void addBlockScreenCaptureForApps(ArraySet<PackageInfo> packageInfos) {
            synchronized (mGlobalLock) {
                boolean modified =
                        mSensitiveContentPackages.addBlockScreenCaptureForApps(packageInfos);
                if (modified) {
                    WindowManagerService.this.refreshScreenCaptureDisabled();
                    if (sensitiveContentImprovements()) {
                        // TODO(b/331842561): Combine this traversal with the one inside
                        // refreshScreenCaptureDisabled above.
                        mRoot.forAllWindows((w) -> {
                            if (w.isVisible()) {
                                WindowManagerService.this.showToastIfBlockingScreenCapture(w);
                            } else if (sensitiveContentRecentsScreenshotBugfix()
                                    && shouldInvalidateSnapshot(w)) {
                                final Task task = w.getTask();
                                // preventing from showing up in starting window.
                                mTaskSnapshotController.removeAndDeleteSnapshot(
                                        task.mTaskId, task.mUserId);
                                // Refresh TaskThumbnailCache
                                task.onSnapshotInvalidated();
                            }
                        }, /* traverseTopToBottom= */ true);
                    }
                }
            }
        }

        private boolean shouldInvalidateSnapshot(WindowState w) {
            return w.getTask() != null
                    && mSensitiveContentPackages.shouldBlockScreenCaptureForApp(
                    w.getOwningPackage(), w.getOwningUid(), w.getWindowToken());
        }

        @Override
        public void removeBlockScreenCaptureForApps(ArraySet<PackageInfo> packageInfos) {
            synchronized (mGlobalLock) {
                boolean modified =
                        mSensitiveContentPackages.removeBlockScreenCaptureForApps(packageInfos);
                if (modified) {
                    WindowManagerService.this.refreshScreenCaptureDisabled();
                }
                if (sensitiveContentImprovements()) {
                    for (int i = 0; i < packageInfos.size(); i++) {
                        int uid = packageInfos.valueAt(i).getUid();
                        if (mCaptureBlockedToastShownUids.contains(uid)) {
                            mCaptureBlockedToastShownUids.remove(
                                    mCaptureBlockedToastShownUids.indexOf(uid));
                        }
                    }
                }
            }
        }

        @Override
        public void clearBlockedApps() {
            synchronized (mGlobalLock) {
                boolean modified = mSensitiveContentPackages.clearBlockedApps();
                if (modified) {
                    WindowManagerService.this.refreshScreenCaptureDisabled();
                }
                if (sensitiveContentImprovements()) {
                    mCaptureBlockedToastShownUids.clear();
                }
            }
        }

        @Override
        public void registerOnWindowRemovedListener(OnWindowRemovedListener listener) {
            synchronized (mGlobalLock) {
                mOnWindowRemovedListeners.add(listener);
            }
        }

        @Override
        public void unregisterOnWindowRemovedListener(OnWindowRemovedListener listener) {
            synchronized (mGlobalLock) {
                mOnWindowRemovedListeners.remove(listener);
            }
        }

        @Override
        public boolean moveFocusToAdjacentEmbeddedActivityIfNeeded() {
            synchronized (mGlobalLock) {
                final WindowState focusedWindow = getFocusedWindow();
                if (focusedWindow == null) {
                    return false;
                }

                if (moveFocusToAdjacentEmbeddedWindow(focusedWindow)) {
                    // Sync the input transactions to ensure the input focus updates as well.
                    syncInputTransactions(false);
                    return true;
                }

                return false;
            }
        }

        @Override
        public ScreenshotHardwareBuffer takeAssistScreenshot(Set<Integer> windowTypesToExclude) {
            // WMS.takeAssistScreenshot takes care of the locking.
            return WindowManagerService.this.takeAssistScreenshot(windowTypesToExclude);
        }
    }

    private final class ImeTargetVisibilityPolicyImpl extends ImeTargetVisibilityPolicy {

        @Override
        public boolean showImeScreenshot(@NonNull IBinder imeTarget, int displayId) {
            synchronized (mGlobalLock) {
                final WindowState imeTargetWindow = mWindowMap.get(imeTarget);
                if (imeTargetWindow == null) {
                    return false;
                }
                final DisplayContent dc = mRoot.getDisplayContent(displayId);
                if (dc == null) {
                    Slog.w(TAG, "Invalid displayId:" + displayId + ", fail to show ime screenshot");
                    return false;
                }

                dc.showImeScreenshot(imeTargetWindow);
                return true;
            }
        }
        @Override
        public boolean removeImeScreenshot(int displayId) {
            synchronized (mGlobalLock) {
                final DisplayContent dc = mRoot.getDisplayContent(displayId);
                if (dc == null) {
                    Slog.w(TAG, "Invalid displayId:" + displayId
                            + ", fail to remove ime screenshot");
                    return false;
                }
                dc.removeImeSurfaceImmediately();
            }
            return true;
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
            mRoot.forAllDisplayPolicies(p -> p.onLockTaskStateChangedLw(lockTaskState));
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

            CountDownLatch countDownLatch = new CountDownLatch(1);
            t.addWindowInfosReportedListener(countDownLatch::countDown).apply();
            countDownLatch.await(SYNC_INPUT_TRANSACTIONS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Slog.e(TAG_WM, "Exception thrown while waiting for window infos to be reported",
                    exception);
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

    private void onPointerDownOutsideFocusLocked(InputTarget t) {
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
        final WindowState w = t.getWindowState();
        if (w != null) {
            final Task task = w.getTask();
            if (task != null && w.mTransitionController.isTransientHide(task)) {
                // Don't disturb transient animation by accident touch.
                return;
            }
        }

        ProtoLog.i(WM_DEBUG_FOCUS_LIGHT, "onPointerDownOutsideFocusLocked called on %s",
                t);
        if (mFocusedInputTarget != t && mFocusedInputTarget != null) {
            mFocusedInputTarget.handleTapOutsideFocusOutsideSelf();
        }
        // Trigger Activity#onUserLeaveHint() if the order change of task pauses any activities.
        mAtmService.mTaskSupervisor.mUserLeaving = true;
        t.handleTapOutsideFocusInsideSelf();
        mAtmService.mTaskSupervisor.mUserLeaving = false;
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

    @VisibleForTesting
    static class WindowContainerInfo {
        private final int mUid;
        @NonNull private final WindowContainerToken mToken;

        private WindowContainerInfo(int uid, @NonNull WindowContainerToken token) {
            this.mUid = uid;
            this.mToken = token;
        }

        public int getUid() {
            return mUid;
        }

        @NonNull
        public WindowContainerToken getToken() {
            return mToken;
        }
    }

    /**
     * Retrieve the {@link WindowContainerInfo} of the task that contains the activity started with
     * the given launch cookie.
     *
     * @param launchCookie the launch cookie set on the {@link ActivityOptions} when starting an
     *     activity
     * @return a token representing the task containing the activity started with the given launch
     *     cookie, or {@code null} if the token couldn't be found.
     */
    @VisibleForTesting
    @Nullable
    WindowContainerInfo getTaskWindowContainerInfoForLaunchCookie(@NonNull IBinder launchCookie) {
        // Find the activity identified by the launch cookie.
        final ActivityRecord targetActivity =
                mRoot.getActivity(activity -> activity.mLaunchCookie == launchCookie);
        if (targetActivity == null) {
            Slog.w(TAG, "Unable to find the activity for this launch cookie");
            return null;
        }
        if (targetActivity.getTask() == null) {
            Slog.w(TAG, "Unable to find the task for this launch cookie");
            return null;
        }
        WindowContainerToken taskWindowContainerToken =
                targetActivity.getTask().mRemoteToken.toWindowContainerToken();
        if (taskWindowContainerToken == null) {
            Slog.w(TAG, "Unable to find the WindowContainerToken for " + targetActivity.getName());
            return null;
        }
        return new WindowContainerInfo(targetActivity.getUid(), taskWindowContainerToken);
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
     * Ensure the caller has the right permissions to be able to set the requested input features.
     */
    private int sanitizeInputFeatures(int inputFeatures, String windowName, int callingUid,
            int callingPid, boolean isTrustedOverlay) {
        // You need MONITOR_INPUT permission to be able to set INPUT_FEATURE_SPY.
        if ((inputFeatures & INPUT_FEATURE_SPY) != 0) {
            final int permissionResult = mContext.checkPermission(
                    permission.MONITOR_INPUT, callingPid, callingUid);
            if (permissionResult != PackageManager.PERMISSION_GRANTED) {
                throw new IllegalArgumentException(
                        "Cannot use INPUT_FEATURE_SPY from '" + windowName
                                + "' because it doesn't the have MONITOR_INPUT permission");
            }
        }

        // You can only use INPUT_FEATURE_SENSITIVE_FOR_PRIVACY on a trusted overlay.
        if ((inputFeatures & INPUT_FEATURE_SENSITIVE_FOR_PRIVACY) != 0 && !isTrustedOverlay) {
            Slog.w(TAG, "Removing INPUT_FEATURE_SENSITIVE_FOR_PRIVACY from '" + windowName
                    + "' because it isn't a trusted overlay");
            return inputFeatures & ~INPUT_FEATURE_SENSITIVE_FOR_PRIVACY;
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
            SurfaceControl surface, IBinder clientToken,
            @Nullable InputTransferToken hostInputTransferToken, int flags, int privateFlags,
            int inputFeatures, int type, IBinder windowToken, InputTransferToken inputTransferToken,
            String inputHandleName, InputChannel outInputChannel) {
        final int sanitizedType = sanitizeWindowType(session, displayId, windowToken, type);
        final InputApplicationHandle applicationHandle;
        final String name;
        Objects.requireNonNull(outInputChannel);
        synchronized (mGlobalLock) {
            WindowState hostWindowState = hostInputTransferToken != null
                    ? mInputToWindowMap.get(hostInputTransferToken.getToken()) : null;
            EmbeddedWindowController.EmbeddedWindow win =
                    new EmbeddedWindowController.EmbeddedWindow(session, this, clientToken,
                            hostWindowState, callingUid, callingPid, sanitizedType, displayId,
                            inputTransferToken, inputHandleName, (flags & FLAG_NOT_FOCUSABLE) == 0);
            win.openInputChannel(outInputChannel);
            mEmbeddedWindowController.add(outInputChannel.getToken(), win);
            applicationHandle = win.getApplicationHandle();
            name = win.toString();
        }

        updateInputChannel(outInputChannel.getToken(), callingUid, callingPid, displayId, surface,
                name, applicationHandle, flags, privateFlags, inputFeatures, sanitizedType,
                null /* region */, clientToken);
    }

    @Override
    public boolean transferTouchGesture(@NonNull InputTransferToken transferFromToken,
            @NonNull InputTransferToken transferToToken) {
        Objects.requireNonNull(transferFromToken);
        Objects.requireNonNull(transferToToken);

        final long identity = Binder.clearCallingIdentity();
        boolean didTransfer;
        try {
            synchronized (mGlobalLock) {
                // If the transferToToken exists in the input to window map, it means the request
                // is to transfer from embedded to host. Otherwise, the transferToToken
                // represents an embedded window so transfer from host to embedded.
                WindowState windowStateTo = mInputToWindowMap.get(transferToToken.getToken());
                if (windowStateTo != null) {
                    didTransfer = mEmbeddedWindowController.transferToHost(transferFromToken,
                            windowStateTo);
                } else {
                    WindowState windowStateFrom = mInputToWindowMap.get(
                            transferFromToken.getToken());
                    didTransfer = mEmbeddedWindowController.transferToEmbedded(windowStateFrom,
                            transferToToken);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return didTransfer;
    }

    private void updateInputChannel(IBinder channelToken, int callingUid, int callingPid,
            int displayId, SurfaceControl surface, String name,
            InputApplicationHandle applicationHandle, int flags,
            int privateFlags, int inputFeatures, int type, Region region, IBinder clientToken) {
        final InputWindowHandle h = new InputWindowHandle(applicationHandle, displayId);
        h.token = channelToken;
        h.setWindowToken(clientToken);
        h.name = name;

        final boolean isTrustedOverlay = (privateFlags & PRIVATE_FLAG_TRUSTED_OVERLAY) != 0;
        flags = sanitizeFlagSlippery(flags, name, callingUid, callingPid);
        inputFeatures = sanitizeInputFeatures(inputFeatures, name, callingUid, callingPid,
                isTrustedOverlay);

        final int sanitizedLpFlags =
                (flags & (FLAG_NOT_TOUCHABLE | FLAG_SLIPPERY | LayoutParams.FLAG_NOT_FOCUSABLE))
                | LayoutParams.FLAG_NOT_TOUCH_MODAL;
        h.layoutParamsType = type;
        h.layoutParamsFlags = sanitizedLpFlags;

        // Do not allow any input features to be set without sanitizing them first.
        h.inputConfig = InputConfigAdapter.getInputConfigFromWindowParams(
                        type, sanitizedLpFlags, inputFeatures);


        if ((flags & LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
            h.inputConfig |= InputConfig.NOT_FOCUSABLE;
        }

        h.dispatchingTimeoutMillis = DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
        h.ownerUid = callingUid;
        h.ownerPid = callingPid;

        if (region == null) {
            h.replaceTouchableRegionWithCrop(null);
        } else {
            h.touchableRegion.set(region);
            h.replaceTouchableRegionWithCrop = false;

            // Task managers may need to receive input events around task layers to resize tasks.
            final int permissionResult = mContext.checkPermission(
                    permission.MANAGE_ACTIVITY_TASKS, callingPid, callingUid);
            if (permissionResult != PackageManager.PERMISSION_GRANTED) {
                h.setTouchableRegionCrop(surface);
            }
        }

        final SurfaceControl.Transaction t = mTransactionFactory.get();
        //  Check private trusted overlay flag to set trustedOverlay field of input window handle.
        h.setTrustedOverlay(t, surface, isTrustedOverlay);
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
            int flags, int privateFlags, int inputFeatures, Region region) {
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
            win.setIsFocusable((flags & FLAG_NOT_FOCUSABLE) == 0);
        }

        updateInputChannel(channelToken, win.mOwnerUid, win.mOwnerPid, displayId, surface, name,
                applicationHandle, flags, privateFlags, inputFeatures, win.mWindowType, region,
                win.mClient);
    }

    /**
     * Move focus to the adjacent embedded activity if the adjacent activity is more recently
     * created or has a window more recently added.
     */
    boolean moveFocusToAdjacentEmbeddedWindow(@NonNull WindowState focusedWindow) {
        final TaskFragment taskFragment = focusedWindow.getTaskFragment();
        if (taskFragment == null) {
            // Skip if not an Activity window.
            return false;
        }

        if (!Flags.embeddedActivityBackNavFlag()) {
            // Skip if flag is not enabled.
            return false;
        }

        if (!focusedWindow.mActivityRecord.isEmbedded()) {
            // Skip if the focused activity is not embedded
            return false;
        }

        final TaskFragment adjacentTaskFragment = taskFragment.getAdjacentTaskFragment();
        final ActivityRecord adjacentTopActivity =
                adjacentTaskFragment != null ? adjacentTaskFragment.topRunningActivity() : null;
        if (adjacentTopActivity == null) {
            return false;
        }

        if (adjacentTopActivity.getLastWindowCreateTime()
                < focusedWindow.mActivityRecord.getLastWindowCreateTime()) {
            // Skip if the current focus activity has more recently active window.
            return false;
        }

        moveFocusToActivity(adjacentTopActivity);
        return !focusedWindow.isFocused();
    }

    boolean moveFocusToAdjacentWindow(@NonNull WindowState fromWin, @FocusDirection int direction) {
        if (!fromWin.isFocused()) {
            return false;
        }
        final TaskFragment fromFragment = fromWin.getTaskFragment();
        if (fromFragment == null) {
            return false;
        }
        final TaskFragment adjacentFragment = fromFragment.getAdjacentTaskFragment();
        if (adjacentFragment == null || adjacentFragment.asTask() != null) {
            // Don't move the focus to another task.
            return false;
        }
        if (adjacentFragment.isIsolatedNav()) {
            // Don't move the focus if the adjacent TF is isolated navigation.
            return false;
        }
        final Rect fromBounds = fromFragment.getBounds();
        final Rect adjacentBounds = adjacentFragment.getBounds();
        switch (direction) {
            case View.FOCUS_LEFT:
                if (adjacentBounds.left >= fromBounds.left) {
                    return false;
                }
                break;
            case View.FOCUS_UP:
                if (adjacentBounds.top >= fromBounds.top) {
                    return false;
                }
                break;
            case View.FOCUS_RIGHT:
                if (adjacentBounds.right <= fromBounds.right) {
                    return false;
                }
                break;
            case View.FOCUS_DOWN:
                if (adjacentBounds.bottom <= fromBounds.bottom) {
                    return false;
                }
                break;
            case View.FOCUS_BACKWARD:
            case View.FOCUS_FORWARD:
                // These are not absolute directions. Skip checking the bounds.
                break;
            default:
                return false;
        }
        final ActivityRecord topRunningActivity = adjacentFragment.topRunningActivity(
                true /* focusableOnly */);
        if (topRunningActivity == null) {
            return false;
        }
        moveFocusToActivity(topRunningActivity);
        return !fromWin.isFocused();
    }

    @VisibleForTesting
    void moveFocusToActivity(@NonNull ActivityRecord activity) {
        moveDisplayToTopInternal(activity.getDisplayId());
        handleTaskFocusChange(activity.getTask(), activity);
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
        mirror.release();
        return true;
    }

    @Override
    public boolean getWindowInsets(int displayId, IBinder token, InsetsState outInsetsState) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent dc = getDisplayContentOrCreate(displayId, token);
                if (dc == null) {
                    throw new WindowManager.InvalidDisplayException("Display#" + displayId
                            + "could not be found!");
                }
                final WindowToken winToken = dc.getWindowToken(token);
                dc.getInsetsPolicy().getInsetsForWindowMetrics(winToken, outInsetsState);
                return dc.getDisplayPolicy().areSystemBarsForcedConsumedLw();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public List<DisplayInfo> getPossibleDisplayInfo(int displayId) {
        final int callingUid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                if (!mAtmService.isCallerRecents(callingUid)
                        && (!multiCrop() || callingUid != SYSTEM_UID)) {
                    Slog.e(TAG, "Unable to verify uid for getPossibleDisplayInfo"
                            + " on uid " + callingUid);
                    return new ArrayList<>();
                }

                // Retrieve the DisplayInfo across all possible display layouts.
                return mPossibleDisplayInfoMapper.getPossibleDisplayInfos(displayId);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    List<DisplayInfo> getPossibleDisplayInfoLocked(int displayId) {
        // Retrieve the DisplayInfo for all possible rotations across all possible display
        // layouts.
        return mPossibleDisplayInfoMapper.getPossibleDisplayInfos(displayId);
    }

    void grantEmbeddedWindowFocus(Session session, InputTransferToken inputTransferToken,
            boolean grantFocus) {
        synchronized (mGlobalLock) {
            final EmbeddedWindowController.EmbeddedWindow embeddedWindow =
                    mEmbeddedWindowController.getByInputTransferToken(inputTransferToken);
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

    void grantEmbeddedWindowFocus(Session session, IWindow callingWindow,
            InputTransferToken inputTransferToken, boolean grantFocus) {
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
                    mEmbeddedWindowController.getByInputTransferToken(inputTransferToken);
            if (embeddedWindow == null) {
                Slog.e(TAG, "Embedded window not found");
                return;
            }
            if (embeddedWindow.mHostWindowState != hostWindow) {
                Slog.e(TAG, "Embedded window does not belong to the host");
                return;
            }
            if (grantFocus) {
                hostWindow.mInputWindowHandle.setFocusTransferTarget(
                        embeddedWindow.getInputChannelToken());
                EventLog.writeEvent(LOGTAG_INPUT_FOCUS,
                        "Transfer focus request " + embeddedWindow,
                        "reason=grantEmbeddedWindowFocus(true)");
            } else {
                hostWindow.mInputWindowHandle.setFocusTransferTarget(null);
                EventLog.writeEvent(LOGTAG_INPUT_FOCUS,
                        "Transfer focus request " + hostWindow,
                        "reason=grantEmbeddedWindowFocus(false)");
            }
            DisplayContent dc = mRoot.getDisplayContent(hostWindow.getDisplayId());
            if (dc != null) {
                dc.getInputMonitor().updateInputWindowsLw(true);
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
        ScreenCapture.LayerCaptureArgs.Builder args =
                new ScreenCapture.LayerCaptureArgs.Builder(displaySurfaceControl)
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
            if (imeTargetWindow.mActivityRecord != null
                    && imeTargetWindow.mActivityRecord.mLastImeShown) {
                return true;
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
        mTaskSnapshotController.setSnapshotEnabled(enabled);
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
                taskSnapshot = mTaskSnapshotController.captureSnapshot(task);
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
                InputMethodManagerInternal.get().maybeFinishStylusHandwriting();
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Gets the background color of the letterbox. Considered invalid if the background has
     * multiple colors {@link #isLetterboxBackgroundMultiColored}
     */
    @Override
    public int getLetterboxBackgroundColorInArgb() {
        return mLetterboxConfiguration.getLetterboxBackgroundColor().toArgb();
    }

    /**
     *  Whether the outer area of the letterbox has multiple colors (e.g. blurred background).
     */
    @Override
    public boolean isLetterboxBackgroundMultiColored() {
        @LetterboxConfiguration.LetterboxBackgroundType int letterboxBackgroundType =
                mLetterboxConfiguration.getLetterboxBackgroundType();
        switch (letterboxBackgroundType) {
            case LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING:
            case LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND:
            case LETTERBOX_BACKGROUND_WALLPAPER:
                return true;
            case LETTERBOX_BACKGROUND_SOLID_COLOR:
                return false;
            default:
                throw new AssertionError(
                        "Unexpected letterbox background type: " + letterboxBackgroundType);
        }
    }

    @Override
    public void captureDisplay(int displayId, @Nullable ScreenCapture.CaptureArgs captureArgs,
            ScreenCapture.ScreenCaptureListener listener) {
        Slog.d(TAG, "captureDisplay");
        if (!checkCallingPermission(READ_FRAME_BUFFER, "captureDisplay()")) {
            throw new SecurityException("Requires READ_FRAME_BUFFER permission");
        }

        ScreenCapture.LayerCaptureArgs layerCaptureArgs = getCaptureArgs(displayId, captureArgs);
        ScreenCapture.captureLayers(layerCaptureArgs, listener);

        if (Binder.getCallingUid() != SYSTEM_UID) {
            // Release the SurfaceControl objects only if the caller is not in system server as no
            // parcelling occurs in this case.
            layerCaptureArgs.release();
        }
    }

    @VisibleForTesting
    ScreenCapture.LayerCaptureArgs getCaptureArgs(int displayId,
            @Nullable ScreenCapture.CaptureArgs captureArgs) {
        final SurfaceControl displaySurfaceControl;
        synchronized (mGlobalLock) {
            DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent == null) {
                throw new IllegalArgumentException("Trying to screenshot and invalid display: "
                        + displayId);
            }

            displaySurfaceControl = displayContent.getSurfaceControl();

            if (captureArgs == null) {
                captureArgs = new ScreenCapture.CaptureArgs.Builder<>()
                        .build();
            }

            if (captureArgs.mSourceCrop.isEmpty()) {
                displayContent.getBounds(mTmpRect);
                mTmpRect.offsetTo(0, 0);
            } else {
                mTmpRect.set(captureArgs.mSourceCrop);
            }
        }

        return new ScreenCapture.LayerCaptureArgs.Builder(displaySurfaceControl, captureArgs)
                        .setSourceCrop(mTmpRect)
                        .build();
    }

    @Override
    public boolean isGlobalKey(int keyCode) {
        return mPolicy.isGlobalKey(keyCode);
    }

    private int sanitizeWindowType(Session session, int displayId, IBinder windowToken, int type) {
        // Determine whether this window type is valid for this process.
        final boolean isTypeValid;
        if (type == TYPE_ACCESSIBILITY_OVERLAY && windowToken != null) {
            // Only accessibility services can add accessibility overlays.
            // Accessibility services will have a WindowToken with type
            // TYPE_ACCESSIBILITY_OVERLAY.
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            final WindowToken token = displayContent.getWindowToken(windowToken);
            if (token == null) {
                isTypeValid = false;
            } else if (type == token.getWindowType()) {
                isTypeValid = true;
            } else {
                isTypeValid = false;
            }
        } else if (!session.mCanAddInternalSystemWindow && type != 0) {
            Slog.w(
                    TAG_WM,
                    "Requires INTERNAL_SYSTEM_WINDOW permission if assign type to"
                            + " input. New type will be 0.");
            isTypeValid = false;
        } else {
            isTypeValid = true;
        }

        if (!isTypeValid) {
            return 0;
        }
        return type;
    }
    @Override
    public boolean addToSurfaceSyncGroup(IBinder syncGroupToken, boolean parentSyncGroupMerge,
            @Nullable ISurfaceSyncGroupCompletedListener completedListener,
            AddToSurfaceSyncGroupResult outAddToSyncGroupResult) {
        return mSurfaceSyncGroupController.addToSyncGroup(syncGroupToken, parentSyncGroupMerge,
                completedListener, outAddToSyncGroupResult);
    }

    @Override
    public void markSurfaceSyncGroupReady(IBinder syncGroupToken) {
        mSurfaceSyncGroupController.markSyncGroupReady(syncGroupToken);
    }


    /**
     * Must be called when a screenshot is taken via hardware chord.
     *
     * Notifies all registered visible activities that have registered for screencapture callback,
     * Returns a list of visible apps component names.
     */
    @Override
    public List<ComponentName> notifyScreenshotListeners(int displayId) {
        // make sure caller is SysUI.
        if (!checkCallingPermission(STATUS_BAR_SERVICE,
                "notifyScreenshotListeners()")) {
            throw new SecurityException("Requires STATUS_BAR_SERVICE permission");
        }
        synchronized (mGlobalLock) {
            final DisplayContent displayContent = mRoot.getDisplayContent(displayId);
            if (displayContent == null) {
                return new ArrayList<>();
            }
            ArraySet<ComponentName> notifiedApps = new ArraySet<>();
            displayContent.forAllActivities(
                    (ar) -> {
                        if (!notifiedApps.contains(ar.mActivityComponent) && ar.isVisible()
                                && ar.isRegisteredForScreenCaptureCallback()) {
                            ar.reportScreenCaptured();
                            notifiedApps.add(ar.mActivityComponent);
                        }
                    },
                    true /* traverseTopToBottom */);
            return List.copyOf(notifiedApps);
        }
    }

    @RequiresPermission(ACCESS_SURFACE_FLINGER)
    @Override
    public boolean replaceContentOnDisplay(int displayId, SurfaceControl sc) {
        if (!checkCallingPermission(ACCESS_SURFACE_FLINGER,
                "replaceDisplayContent()")) {
            throw new SecurityException("Requires ACCESS_SURFACE_FLINGER permission");
        }

        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                DisplayContent dc = mRoot.getDisplayContentOrCreate(displayId);
                if (dc == null) {
                    return false;
                }
                dc.replaceContent(sc);
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void registerTrustedPresentationListener(IBinder window,
            ITrustedPresentationListener listener,
            TrustedPresentationThresholds thresholds, int id) {
        mTrustedPresentationListenerController.registerListener(window, listener, thresholds, id);
    }

    @Override
    public void unregisterTrustedPresentationListener(ITrustedPresentationListener listener,
            int id) {
        mTrustedPresentationListenerController.unregisterListener(listener, id);
    }

    @EnforcePermission(android.Manifest.permission.DETECT_SCREEN_RECORDING)
    @Override
    public boolean registerScreenRecordingCallback(IScreenRecordingCallback callback) {
        registerScreenRecordingCallback_enforcePermission();
        return mScreenRecordingCallbackController.register(callback);
    }

    @EnforcePermission(android.Manifest.permission.DETECT_SCREEN_RECORDING)
    @Override
    public void unregisterScreenRecordingCallback(IScreenRecordingCallback callback) {
        unregisterScreenRecordingCallback_enforcePermission();
        mScreenRecordingCallbackController.unregister(callback);
    }

    void onProcessActivityVisibilityChanged(int uid, boolean visible) {
        mScreenRecordingCallbackController.onProcessActivityVisibilityChanged(uid, visible);
    }

    /**
     * Sets the listener to be called back when a cross-window drag and drop operation happens.
     */
    @Override
    public void setGlobalDragListener(IGlobalDragListener listener) throws RemoteException {
        mAtmService.enforceTaskPermission("setUnhandledDragListener");
        synchronized (mGlobalLock) {
            mDragDropController.setGlobalDragListener(listener);
        }
    }

    boolean getDisableSecureWindows() {
        return mDisableSecureWindows;
    }

    /**
     * Called to notify WMS that the specified window has become visible. This shows a Toast if the
     * window is deemed to hold sensitive content.
     */
    private void onWindowVisible(@NonNull WindowState w) {
        showToastIfBlockingScreenCapture(w);
    }

    /**
     * Shows a Toast if the specified window is
     * {@link LocalService#addBlockScreenCaptureForApps(ArraySet) blocked} from screen capture based
     * on sensitive content protections.
     */
    private void showToastIfBlockingScreenCapture(@NonNull WindowState w) {
        int uid = w.getOwningUid();
        if (mCaptureBlockedToastShownUids.contains(uid)) {
            return;
        }
        if (mSensitiveContentPackages.shouldBlockScreenCaptureForApp(w.getOwningPackage(), uid,
                w.getWindowToken())) {
            mCaptureBlockedToastShownUids.add(uid);
            mH.post(() -> {
                Toast.makeText(mContext, Looper.getMainLooper(),
                                mContext.getString(R.string.screen_not_shared_sensitive_content),
                                Toast.LENGTH_SHORT)
                        .show();
            });
            // If blocked due to notification protection (null window token) log protection applied
            if (sensitiveContentMetricsBugfix()
                    && mSensitiveContentPackages
                    .shouldBlockScreenCaptureForApp(w.getOwningPackage(), uid, null)) {
                FrameworkStatsLog.write(
                        SENSITIVE_NOTIFICATION_APP_PROTECTION_APPLIED,
                        mSensitiveContentProtectionSessionId,
                        uid);
            }
        }
    }

    private static boolean getShellTransitEnabled() {
        android.content.pm.FeatureInfo autoFeature = SystemConfig.getInstance()
                .getAvailableFeatures().get(PackageManager.FEATURE_AUTOMOTIVE);
        if (autoFeature != null && autoFeature.version >= 0) {
            return SystemProperties.getBoolean(ENABLE_SHELL_TRANSITIONS, true);
        }
        return true;
    }

    /**
     * Dump ViewRootImpl for visible non-activity windows.
     */
    private void dumpVisibleWindowClients(FileDescriptor fd, PrintWriter pw, long timeout) {
        final ArrayList<WindowState> systemWindows = new ArrayList<>();
        synchronized (mGlobalLock) {
            mRoot.forAllWindows(w -> {
                if (!w.isActivityWindow() && w.isVisibleNow()) {
                    systemWindows.add(w);
                }
            }, false /* traverseTopToBottom */);
        }

        systemWindows.forEach(w -> {
            pw.println("---------------------------------");
            pw.println(w.toString());
            pw.flush();
            try (TransferPipe tp = new TransferPipe()) {
                w.mClient.dumpWindow(tp.getWriteFd());
                tp.go(fd, timeout);
            } catch (IOException e) {
                pw.println("Failure while dumping the window: " + e);
            } catch (RemoteException e) {
                pw.println("Got a RemoteException while dumping the window");
            }
        });
    }
}
