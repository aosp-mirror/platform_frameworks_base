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

import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_COMPATIBLE_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_BOOT_PROGRESS;
import static android.view.WindowManager.LayoutParams.TYPE_DREAM;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD;
import static android.view.WindowManager.LayoutParams.TYPE_RECENTS_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.TYPE_UNIVERSE_BACKGROUND;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import com.android.internal.app.IBatteryStats;
import com.android.internal.policy.PolicyManager;
import com.android.internal.policy.impl.PhoneWindowManager;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.view.IInputMethodManager;
import com.android.internal.view.WindowManagerPolicyThread;
import com.android.server.AttributeCache;
import com.android.server.EventLogTags;
import com.android.server.Watchdog;
import com.android.server.am.BatteryStatsService;
import com.android.server.display.DisplayManagerService;
import com.android.server.input.InputManagerService;
import com.android.server.power.PowerManagerService;
import com.android.server.power.ShutdownThread;

import android.Manifest;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.IActivityManager;
import android.app.StatusBarManager;
import android.app.admin.DevicePolicyManager;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.os.Binder;
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
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.FloatMath;
import android.util.Log;
import android.util.SparseArray;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IApplicationToken;
import android.view.IDisplayContentChangeListener;
import android.view.IInputFilter;
import android.view.IOnKeyguardExitResult;
import android.view.IRotationWatcher;
import android.view.IWindow;
import android.view.IWindowManager;
import android.view.IWindowSession;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceSession;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerPolicy;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManagerPolicy.FakeWindow;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.Transformation;

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
import java.net.Socket;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/** {@hide} */
public class WindowManagerService extends IWindowManager.Stub
        implements Watchdog.Monitor, WindowManagerPolicy.WindowManagerFuncs,
                DisplayManagerService.WindowManagerFuncs, DisplayManager.DisplayListener {
    static final String TAG = "WindowManager";
    static final boolean DEBUG = false;
    static final boolean DEBUG_ADD_REMOVE = false;
    static final boolean DEBUG_FOCUS = false;
    static final boolean DEBUG_ANIM = false;
    static final boolean DEBUG_LAYOUT = false;
    static final boolean DEBUG_RESIZE = false;
    static final boolean DEBUG_LAYERS = false;
    static final boolean DEBUG_INPUT = false;
    static final boolean DEBUG_INPUT_METHOD = false;
    static final boolean DEBUG_VISIBILITY = false;
    static final boolean DEBUG_WINDOW_MOVEMENT = false;
    static final boolean DEBUG_TOKEN_MOVEMENT = false;
    static final boolean DEBUG_ORIENTATION = false;
    static final boolean DEBUG_APP_ORIENTATION = false;
    static final boolean DEBUG_CONFIGURATION = false;
    static final boolean DEBUG_APP_TRANSITIONS = false;
    static final boolean DEBUG_STARTING_WINDOW = false;
    static final boolean DEBUG_REORDER = false;
    static final boolean DEBUG_WALLPAPER = false;
    static final boolean DEBUG_WALLPAPER_LIGHT = false || DEBUG_WALLPAPER;
    static final boolean DEBUG_DRAG = false;
    static final boolean DEBUG_SCREEN_ON = false;
    static final boolean DEBUG_SCREENSHOT = false;
    static final boolean DEBUG_BOOT = false;
    static final boolean DEBUG_LAYOUT_REPEATS = true;
    static final boolean DEBUG_SURFACE_TRACE = false;
    static final boolean DEBUG_WINDOW_TRACE = false;
    static final boolean SHOW_SURFACE_ALLOC = false;
    static final boolean SHOW_TRANSACTIONS = false;
    static final boolean SHOW_LIGHT_TRANSACTIONS = false || SHOW_TRANSACTIONS;
    static final boolean HIDE_STACK_CRAWLS = true;
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
     * Blur surface layer is immediately below dim layer.
     */
    static final int LAYER_OFFSET_BLUR = 2;

    /**
     * Animation thumbnail is as far as possible below the window above
     * the thumbnail (or in other words as far as possible above the window
     * below it).
     */
    static final int LAYER_OFFSET_THUMBNAIL = WINDOW_LAYER_MULTIPLIER-1;

    /**
     * Layer at which to put the rotation freeze snapshot.
     */
    static final int FREEZE_LAYER = (TYPE_LAYER_MULTIPLIER * 200) + 1;

    /**
     * Layer at which to put the mask for emulated screen sizes.
     */
    static final int MASK_LAYER = TYPE_LAYER_MULTIPLIER * 200;

    /** The maximum length we will accept for a loaded animation duration:
     * this is 10 seconds.
     */
    static final int MAX_ANIMATION_DURATION = 10*1000;

    /** Amount of time (in milliseconds) to animate the dim surface from one
     * value to another, when no window animation is driving it.
     */
    static final int DEFAULT_DIM_DURATION = 200;

    /** Amount of time (in milliseconds) to animate the fade-in-out transition for
     * compatible windows.
     */
    static final int DEFAULT_FADE_IN_OUT_DURATION = 400;

    /** Amount of time (in milliseconds) to delay before declaring a window freeze timeout. */
    static final int WINDOW_FREEZE_TIMEOUT_DURATION = 2000;

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

    static final int UPDATE_FOCUS_NORMAL = 0;
    static final int UPDATE_FOCUS_WILL_ASSIGN_LAYERS = 1;
    static final int UPDATE_FOCUS_PLACING_SURFACES = 2;
    static final int UPDATE_FOCUS_WILL_PLACE_SURFACES = 3;

    private static final String SYSTEM_SECURE = "ro.secure";
    private static final String SYSTEM_DEBUGGABLE = "ro.debuggable";

    final private KeyguardDisableHandler mKeyguardDisableHandler;

    private final boolean mHeadless;

    private static final float THUMBNAIL_ANIMATION_DECELERATE_FACTOR = 1.5f;

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

    // Current user when multi-user is enabled. Don't show windows of non-current user.
    int mCurrentUserId;

    final Context mContext;

    final boolean mHaveInputMethods;

    final boolean mAllowBootMessages;

    final boolean mLimitedAlphaCompositing;

    final WindowManagerPolicy mPolicy = PolicyManager.makeNewWindowManager();

    final IActivityManager mActivityManager;

    final IBatteryStats mBatteryStats;

    /**
     * All currently active sessions with clients.
     */
    final HashSet<Session> mSessions = new HashSet<Session>();

    /**
     * Mapping from an IWindow IBinder to the server's Window object.
     * This is also used as the lock for all of our state.
     */
    final HashMap<IBinder, WindowState> mWindowMap = new HashMap<IBinder, WindowState>();

    /**
     * Mapping from a token IBinder to a WindowToken object.
     */
    final HashMap<IBinder, WindowToken> mTokenMap =
            new HashMap<IBinder, WindowToken>();

    /**
     * Window tokens that are in the process of exiting, but still
     * on screen for animations.
     */
    final ArrayList<WindowToken> mExitingTokens = new ArrayList<WindowToken>();

    /**
     * List controlling the ordering of windows in different applications which must
     * be kept in sync with ActivityManager.
     */
    final ArrayList<AppWindowToken> mAppTokens = new ArrayList<AppWindowToken>();

    /**
     * AppWindowTokens in the Z order they were in at the start of an animation. Between
     * animations this list is maintained in the exact order of mAppTokens. If tokens
     * are added to mAppTokens during an animation an attempt is made to insert them at the same
     * logical location in this list. Note that this list is always in sync with mWindows.
     */
    ArrayList<AppWindowToken> mAnimatingAppTokens = new ArrayList<AppWindowToken>();

    /**
     * Application tokens that are in the process of exiting, but still
     * on screen for animations.
     */
    final ArrayList<AppWindowToken> mExitingAppTokens = new ArrayList<AppWindowToken>();

    /**
     * List of window tokens that have finished starting their application,
     * and now need to have the policy remove their windows.
     */
    final ArrayList<AppWindowToken> mFinishedStarting = new ArrayList<AppWindowToken>();

    /**
     * Fake windows added to the window manager.  Note: ordered from top to
     * bottom, opposite of mWindows.
     */
    final ArrayList<FakeWindowImpl> mFakeWindows = new ArrayList<FakeWindowImpl>();

    /**
     * Windows that are being resized.  Used so we can tell the client about
     * the resize after closing the transaction in which we resized the
     * underlying surface.
     */
    final ArrayList<WindowState> mResizingWindows = new ArrayList<WindowState>();

    /**
     * Windows whose animations have ended and now must be removed.
     */
    final ArrayList<WindowState> mPendingRemove = new ArrayList<WindowState>();

    /**
     * Used when processing mPendingRemove to avoid working on the original array.
     */
    WindowState[] mPendingRemoveTmp = new WindowState[20];

    /**
     * Windows whose surface should be destroyed.
     */
    final ArrayList<WindowState> mDestroySurface = new ArrayList<WindowState>();

    /**
     * Windows that have lost input focus and are waiting for the new
     * focus window to be displayed before they are told about this.
     */
    ArrayList<WindowState> mLosingFocus = new ArrayList<WindowState>();

    /**
     * This is set when we have run out of memory, and will either be an empty
     * list or contain windows that need to be force removed.
     */
    ArrayList<WindowState> mForceRemoves;

    /**
     * Windows that clients are waiting to have drawn.
     */
    ArrayList<Pair<WindowState, IRemoteCallback>> mWaitingForDrawn
            = new ArrayList<Pair<WindowState, IRemoteCallback>>();

    /**
     * Windows that have called relayout() while we were running animations,
     * so we need to tell when the animation is done.
     */
    final ArrayList<WindowState> mRelayoutWhileAnimating = new ArrayList<WindowState>();

    /**
     * Used when rebuilding window list to keep track of windows that have
     * been removed.
     */
    WindowState[] mRebuildTmp = new WindowState[20];

    IInputMethodManager mInputMethodManager;

    final SurfaceSession mFxSession;
    Watermark mWatermark;
    StrictModeFlash mStrictModeFlash;

    final float[] mTmpFloats = new float[9];

    boolean mDisplayReady;
    boolean mSafeMode;
    boolean mDisplayEnabled = false;
    boolean mSystemBooted = false;
    boolean mForceDisplayEnabled = false;
    boolean mShowingBootMessages = false;

    String mLastANRState;

    /** All DisplayDontents in the world, kept here */
    private SparseArray<DisplayContent> mDisplayContents = new SparseArray<DisplayContent>();

    int mRotation = 0;
    int mForcedAppOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    boolean mAltOrientation = false;
    ArrayList<IRotationWatcher> mRotationWatchers
            = new ArrayList<IRotationWatcher>();
    int mDeferredRotationPauseCount;

    final Rect mSystemDecorRect = new Rect();
    int mSystemDecorLayer = 0;
    final Rect mScreenRect = new Rect();

    boolean mTraversalScheduled = false;
    boolean mDisplayFrozen = false;
    boolean mWaitingForConfig = false;
    boolean mWindowsFreezingScreen = false;
    boolean mClientFreezingScreen = false;
    int mAppsFreezingScreen = 0;
    int mLastWindowForcedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    int mLayoutSeq = 0;

    int mLastStatusBarVisibility = 0;

    // State while inside of layoutAndPlaceSurfacesLocked().
    boolean mFocusMayChange;

    Configuration mCurConfiguration = new Configuration();

    // This is held as long as we have the screen frozen, to give us time to
    // perform a rotation animation when turning off shows the lock screen which
    // changes the orientation.
    PowerManager.WakeLock mScreenFrozenLock;

    // State management of app transitions.  When we are preparing for a
    // transition, mNextAppTransition will be the kind of transition to
    // perform or TRANSIT_NONE if we are not waiting.  If we are waiting,
    // mOpeningApps and mClosingApps are the lists of tokens that will be
    // made visible or hidden at the next transition.
    int mNextAppTransition = WindowManagerPolicy.TRANSIT_UNSET;
    int mNextAppTransitionType = ActivityOptions.ANIM_NONE;
    String mNextAppTransitionPackage;
    Bitmap mNextAppTransitionThumbnail;
    // Used for thumbnail transitions. True if we're scaling up, false if scaling down
    boolean mNextAppTransitionScaleUp;
    IRemoteCallback mNextAppTransitionCallback;
    int mNextAppTransitionEnter;
    int mNextAppTransitionExit;
    int mNextAppTransitionStartX;
    int mNextAppTransitionStartY;
    int mNextAppTransitionStartWidth;
    int mNextAppTransitionStartHeight;
    boolean mAppTransitionReady = false;
    boolean mAppTransitionRunning = false;
    boolean mAppTransitionTimeout = false;
    boolean mStartingIconInTransition = false;
    boolean mSkipAppTransitionAnimation = false;
    final ArrayList<AppWindowToken> mOpeningApps = new ArrayList<AppWindowToken>();
    final ArrayList<AppWindowToken> mClosingApps = new ArrayList<AppWindowToken>();

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
    int mInputMethodAnimLayerAdjustment;

    WindowState mInputMethodWindow = null;
    final ArrayList<WindowState> mInputMethodDialogs = new ArrayList<WindowState>();

    boolean mHardKeyboardAvailable;
    boolean mHardKeyboardEnabled;
    OnHardKeyboardStatusChangeListener mHardKeyboardStatusChangeListener;

    final ArrayList<WindowToken> mWallpaperTokens = new ArrayList<WindowToken>();

    // If non-null, this is the currently visible window that is associated
    // with the wallpaper.
    WindowState mWallpaperTarget = null;
    // If non-null, we are in the middle of animating from one wallpaper target
    // to another, and this is the lower one in Z-order.
    WindowState mLowerWallpaperTarget = null;
    // If non-null, we are in the middle of animating from one wallpaper target
    // to another, and this is the higher one in Z-order.
    private WindowState mUpperWallpaperTarget = null;
    int mWallpaperAnimLayerAdjustment;
    float mLastWallpaperX = -1;
    float mLastWallpaperY = -1;
    float mLastWallpaperXStep = -1;
    float mLastWallpaperYStep = -1;
    // This is set when we are waiting for a wallpaper to tell us it is done
    // changing its scroll position.
    WindowState mWaitingOnWallpaper;
    // The last time we had a timeout when waiting for a wallpaper.
    long mLastWallpaperTimeoutTime;
    // We give a wallpaper up to 150ms to finish scrolling.
    static final long WALLPAPER_TIMEOUT = 150;
    // Time we wait after a timeout before trying to wait again.
    static final long WALLPAPER_TIMEOUT_RECOVERY = 10000;

    AppWindowToken mFocusedApp = null;

    PowerManagerService mPowerManager;

    float mWindowAnimationScale = 1.0f;
    float mTransitionAnimationScale = 1.0f;
    float mAnimatorDurationScale = 1.0f;

    final InputManagerService mInputManager;
    final DisplayManagerService mDisplayManagerService;
    final DisplayManager mDisplayManager;

    // Who is holding the screen on.
    Session mHoldingScreenOn;
    PowerManager.WakeLock mHoldingScreenWakeLock;

    boolean mTurnOnScreen;

    DragState mDragState = null;

    /** Pulled out of performLayoutAndPlaceSurfacesLockedInner in order to refactor into multiple
     * methods. */
    class LayoutFields {
        static final int SET_UPDATE_ROTATION                = 1 << 0;
        static final int SET_WALLPAPER_MAY_CHANGE           = 1 << 1;
        static final int SET_FORCE_HIDING_CHANGED           = 1 << 2;
        static final int SET_ORIENTATION_CHANGE_COMPLETE    = 1 << 3;
        static final int SET_TURN_ON_SCREEN                 = 1 << 4;

        boolean mWallpaperForceHidingChanged = false;
        boolean mWallpaperMayChange = false;
        boolean mOrientationChangeComplete = true;
        int mAdjResult = 0;
        private Session mHoldScreen = null;
        private boolean mObscured = false;
        boolean mDimming = false;
        private boolean mSyswin = false;
        private float mScreenBrightness = -1;
        private float mButtonBrightness = -1;
        private long mUserActivityTimeout = -1;
        private boolean mUpdateRotation = false;

        private static final int DISPLAY_CONTENT_UNKNOWN = 0;
        private static final int DISPLAY_CONTENT_MIRROR = 1;
        private static final int DISPLAY_CONTENT_UNIQUE = 2;
        private int mDisplayHasContent = DISPLAY_CONTENT_UNKNOWN;
    }
    final LayoutFields mInnerFields = new LayoutFields();

    static class AppWindowAnimParams {
        AppWindowAnimator mAppAnimator;
        ArrayList<WindowStateAnimator> mWinAnimators;

        public AppWindowAnimParams(final AppWindowAnimator appAnimator) {
            mAppAnimator = appAnimator;

            final AppWindowToken atoken = appAnimator.mAppToken;
            mWinAnimators = new ArrayList<WindowStateAnimator>();
            final int N = atoken.allAppWindows.size();
            for (int i = 0; i < N; i++) {
                mWinAnimators.add(atoken.allAppWindows.get(i).mWinAnimator);
            }
        }
    }

    static class LayoutToAnimatorParams {
        boolean mParamsModified;

        static final long WALLPAPER_TOKENS_CHANGED = 1 << 0;
        long mChanges;

        boolean mAnimationScheduled;
        SparseArray<WinAnimatorList> mWinAnimatorLists = new SparseArray<WinAnimatorList>();
        WindowState mWallpaperTarget;
        WindowState mLowerWallpaperTarget;
        WindowState mUpperWallpaperTarget;
        SparseArray<DimAnimator.Parameters> mDimParams = new SparseArray<DimAnimator.Parameters>();
        ArrayList<WindowToken> mWallpaperTokens = new ArrayList<WindowToken>();
        ArrayList<AppWindowAnimParams> mAppWindowAnimParams = new ArrayList<AppWindowAnimParams>();
    }
    /** Params from WindowManagerService to WindowAnimator. Do not modify or read without first
     * locking on either mWindowMap or mAnimator and then on mLayoutToAnim */
    final LayoutToAnimatorParams mLayoutToAnim = new LayoutToAnimatorParams();

    /** The lowest wallpaper target with a detached wallpaper animation on it. */
    WindowState mWindowDetachedWallpaper = null;

    /** Skip repeated AppWindowTokens initialization. Note that AppWindowsToken's version of this
     * is a long initialized to Long.MIN_VALUE so that it doesn't match this value on startup. */
    private int mTransactionSequence;

    /** Only do a maximum of 6 repeated layouts. After that quit */
    private int mLayoutRepeatCount;

    final WindowAnimator mAnimator;

    final class DragInputEventReceiver extends InputEventReceiver {
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

                    switch (motionEvent.getAction()) {
                    case MotionEvent.ACTION_DOWN: {
                        if (DEBUG_DRAG) {
                            Slog.w(TAG, "Unexpected ACTION_DOWN in drag layer");
                        }
                    } break;

                    case MotionEvent.ACTION_MOVE: {
                        synchronized (mWindowMap) {
                            // move the surface and tell the involved window(s) where we are
                            mDragState.notifyMoveLw(newX, newY);
                        }
                    } break;

                    case MotionEvent.ACTION_UP: {
                        if (DEBUG_DRAG) Slog.d(TAG, "Got UP on move channel; dropping at "
                                + newX + "," + newY);
                        synchronized (mWindowMap) {
                            endDrag = mDragState.notifyDropLw(newX, newY);
                        }
                    } break;

                    case MotionEvent.ACTION_CANCEL: {
                        if (DEBUG_DRAG) Slog.d(TAG, "Drag cancelled!");
                        endDrag = true;
                    } break;
                    }

                    if (endDrag) {
                        if (DEBUG_DRAG) Slog.d(TAG, "Drag ended; tearing down state");
                        // tell all the windows that the drag has ended
                        synchronized (mWindowMap) {
                            mDragState.endDragLw();
                        }
                    }

                    handled = true;
                }
            } catch (Exception e) {
                Slog.e(TAG, "Exception caught by drag handleMotion", e);
            } finally {
                finishInputEvent(event, handled);
            }
        }
    }

    /**
     * Whether the UI is currently running in touch mode (not showing
     * navigational focus because the user is directly pressing the screen).
     */
    boolean mInTouchMode = true;

    // Temp regions for intermediary calculations.
    private final Region mTempRegion = new Region();

    private ViewServer mViewServer;
    private ArrayList<WindowChangeListener> mWindowChangeListeners =
        new ArrayList<WindowChangeListener>();
    private boolean mWindowsChanged = false;

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

    public static WindowManagerService main(final Context context,
            final PowerManagerService pm, final DisplayManagerService dm,
            final InputManagerService im,
            final Handler uiHandler, final Handler wmHandler,
            final boolean haveInputMethods, final boolean showBootMsgs,
            final boolean onlyCore) {
        final WindowManagerService[] holder = new WindowManagerService[1];
        wmHandler.runWithScissors(new Runnable() {
            @Override
            public void run() {
                holder[0] = new WindowManagerService(context, pm, dm, im,
                        uiHandler, haveInputMethods, showBootMsgs, onlyCore);
            }
        }, 0);
        return holder[0];
    }

    private void initPolicy(Handler uiHandler) {
        uiHandler.runWithScissors(new Runnable() {
            @Override
            public void run() {
                WindowManagerPolicyThread.set(Thread.currentThread(), Looper.myLooper());

                mPolicy.init(mContext, WindowManagerService.this, WindowManagerService.this);
                mAnimator.mAboveUniverseLayer = mPolicy.getAboveUniverseLayer()
                        * TYPE_LAYER_MULTIPLIER
                        + TYPE_LAYER_OFFSET;
            }
        }, 0);
    }

    private WindowManagerService(Context context, PowerManagerService pm,
            DisplayManagerService displayManager, InputManagerService inputManager,
            Handler uiHandler,
            boolean haveInputMethods, boolean showBootMsgs, boolean onlyCore) {
        mContext = context;
        mHaveInputMethods = haveInputMethods;
        mAllowBootMessages = showBootMsgs;
        mOnlyCore = onlyCore;
        mLimitedAlphaCompositing = context.getResources().getBoolean(
                com.android.internal.R.bool.config_sf_limitedAlpha);
        mDisplayManagerService = displayManager;
        mHeadless = displayManager.isHeadless();

        mDisplayManager = (DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);
        mDisplayManager.registerDisplayListener(this, null);
        Display[] displays = mDisplayManager.getDisplays();
        for (Display display : displays) {
            createDisplayContentLocked(display);
        }

        mKeyguardDisableHandler = new KeyguardDisableHandler(mContext, mPolicy);

        mPowerManager = pm;
        mPowerManager.setPolicy(mPolicy);
        PowerManager pmc = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mScreenFrozenLock = pmc.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "SCREEN_FROZEN");
        mScreenFrozenLock.setReferenceCounted(false);

        mActivityManager = ActivityManagerNative.getDefault();
        mBatteryStats = BatteryStatsService.getService();

        // Get persisted window scale setting
        mWindowAnimationScale = Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.WINDOW_ANIMATION_SCALE, mWindowAnimationScale);
        mTransitionAnimationScale = Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.TRANSITION_ANIMATION_SCALE, mTransitionAnimationScale);
        setAnimatorDurationScale(Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, mTransitionAnimationScale));

        // Track changes to DevicePolicyManager state so we can enable/disable keyguard.
        IntentFilter filter = new IntentFilter();
        filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        mContext.registerReceiver(mBroadcastReceiver, filter);

        mHoldingScreenWakeLock = pmc.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ON_AFTER_RELEASE, TAG);
        mHoldingScreenWakeLock.setReferenceCounted(false);

        mInputManager = inputManager;
        mFxSession = new SurfaceSession();
        mAnimator = new WindowAnimator(this);

        initPolicy(uiHandler);

        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);

        Surface.openTransaction();
        try {
            createWatermarkInTransaction();
        } finally {
            Surface.closeTransaction();
        }
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
                Log.wtf(TAG, "Window Manager Crash", e);
            }
            throw e;
        }
    }

    private void placeWindowAfter(WindowState pos, WindowState window) {
        final WindowList windows = pos.getWindowList();
        final int i = windows.indexOf(pos);
        if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(
            TAG, "Adding window " + window + " at "
            + (i+1) + " of " + windows.size() + " (after " + pos + ")");
        windows.add(i+1, window);
        mWindowsChanged = true;
    }

    private void placeWindowBefore(WindowState pos, WindowState window) {
        final WindowList windows = pos.getWindowList();
        final int i = windows.indexOf(pos);
        if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(
            TAG, "Adding window " + window + " at "
            + i + " of " + windows.size() + " (before " + pos + ")");
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
    WindowList getTokenWindowsOnDisplay(WindowToken token, DisplayContent displayContent) {
        final WindowList windowList = new WindowList();
        final int count = token.windows.size();
        for (int i = 0; i < count; i++) {
            final WindowState win = token.windows.get(i);
            if (win.mDisplayContent == displayContent) {
                windowList.add(win);
            }
        }
        return windowList;
    }

    private void addWindowToListInOrderLocked(WindowState win, boolean addToToken) {
        final IWindow client = win.mClient;
        final WindowToken token = win.mToken;
        final DisplayContent displayContent = win.mDisplayContent;

        final WindowList windows = win.getWindowList();
        final int N = windows.size();
        final WindowState attached = win.mAttachedWindow;
        int i;
        WindowList tokenWindowList = getTokenWindowsOnDisplay(token, displayContent);
        if (attached == null) {
            int tokenWindowsPos = 0;
            int windowListPos = tokenWindowList.size();
            if (token.appWindowToken != null) {
                int index = windowListPos - 1;
                if (index >= 0) {
                    // If this application has existing windows, we
                    // simply place the new window on top of them... but
                    // keep the starting window on top.
                    if (win.mAttrs.type == TYPE_BASE_APPLICATION) {
                        // Base windows go behind everything else.
                        WindowState lowestWindow = tokenWindowList.get(0);
                        placeWindowBefore(lowestWindow, win);
                        tokenWindowsPos = token.windows.indexOf(lowestWindow);
                    } else {
                        AppWindowToken atoken = win.mAppToken;
                        WindowState lastWindow = tokenWindowList.get(index);
                        if (atoken != null && lastWindow == atoken.startingWindow) {
                            placeWindowBefore(lastWindow, win);
                            tokenWindowsPos = token.windows.indexOf(lastWindow);
                        } else {
                            int newIdx = findIdxBasedOnAppTokens(win);
                            //there is a window above this one associated with the same
                            //apptoken note that the window could be a floating window
                            //that was created later or a window at the top of the list of
                            //windows associated with this token.
                            if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) {
                                Slog.v(TAG, "Adding window " + win + " at "
                                        + (newIdx + 1) + " of " + N);
                            }
                            windows.add(newIdx + 1, win);
                            if (newIdx < 0) {
                                // No window from token found on win's display.
                                tokenWindowsPos = 0;
                            } else {
                                tokenWindowsPos = token.windows.indexOf(windows.get(newIdx)) + 1;
                            }
                            mWindowsChanged = true;
                        }
                    }
                } else {
                    // No windows from this token on this display
                    if (localLOGV) Slog.v(
                        TAG, "Figuring out where to add app window "
                        + client.asBinder() + " (token=" + token + ")");
                    // Figure out where the window should go, based on the
                    // order of applications.
                    final int NA = mAnimatingAppTokens.size();
                    WindowState pos = null;
                    for (i=NA-1; i>=0; i--) {
                        AppWindowToken t = mAnimatingAppTokens.get(i);
                        if (t == token) {
                            i--;
                            break;
                        }

                        // We haven't reached the token yet; if this token
                        // is not going to the bottom and has windows on this display, we can
                        // use it as an anchor for when we do reach the token.
                        tokenWindowList = getTokenWindowsOnDisplay(t, win.mDisplayContent);
                        if (!t.sendingToBottom && tokenWindowList.size() > 0) {
                            pos = tokenWindowList.get(0);
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
                                    getTokenWindowsOnDisplay(atoken, win.mDisplayContent);
                            final int NC = tokenWindowList.size();
                            if (NC > 0) {
                                WindowState bottom = tokenWindowList.get(0);
                                if (bottom.mSubLayer < 0) {
                                    pos = bottom;
                                }
                            }
                        }
                        placeWindowBefore(pos, win);
                    } else {
                        // Continue looking down until we find the first
                        // token that has windows on this display.
                        while (i >= 0) {
                            AppWindowToken t = mAnimatingAppTokens.get(i);
                            tokenWindowList = getTokenWindowsOnDisplay(t, win.mDisplayContent);
                            final int NW = tokenWindowList.size();
                            if (NW > 0) {
                                pos = tokenWindowList.get(NW-1);
                                break;
                            }
                            i--;
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
                        } else {
                            // Just search for the start of this layer.
                            final int myLayer = win.mBaseLayer;
                            for (i=0; i<N; i++) {
                                WindowState w = windows.get(i);
                                if (w.mBaseLayer > myLayer) {
                                    break;
                                }
                            }
                            if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) {
                                Slog.v(TAG, "Adding window " + win + " at "
                                        + i + " of " + N);
                            }
                            windows.add(i, win);
                            mWindowsChanged = true;
                        }
                    }
                }
            } else {
                // Figure out where window should go, based on layer.
                final int myLayer = win.mBaseLayer;
                for (i=N-1; i>=0; i--) {
                    if (windows.get(i).mBaseLayer <= myLayer) {
                        break;
                    }
                }
                i++;
                if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(
                        TAG, "Adding window " + win + " at "
                        + i + " of " + N);
                windows.add(i, win);
                mWindowsChanged = true;
            }

            if (addToToken) {
                if (DEBUG_ADD_REMOVE) Slog.v(TAG, "Adding " + win + " to " + token);
                token.windows.add(tokenWindowsPos, win);
            }

        } else {
            // Figure out this window's ordering relative to the window
            // it is attached to.
            final int NA = tokenWindowList.size();
            final int sublayer = win.mSubLayer;
            int largestSublayer = Integer.MIN_VALUE;
            WindowState windowWithLargestSublayer = null;
            for (i=0; i<NA; i++) {
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
                            if (DEBUG_ADD_REMOVE) Slog.v(TAG, "Adding " + win + " to " + token);
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
                            if (DEBUG_ADD_REMOVE) Slog.v(TAG, "Adding " + win + " to " + token);
                            token.windows.add(i, win);
                        }
                        placeWindowBefore(w, win);
                        break;
                    }
                }
            }
            if (i >= NA) {
                if (addToToken) {
                    if (DEBUG_ADD_REMOVE) Slog.v(TAG, "Adding " + win + " to " + token);
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

        if (win.mAppToken != null && addToToken) {
            win.mAppToken.allAppWindows.add(win);
        }
    }

    /** TODO(cmautner): Is this the same as {@link WindowState#canReceiveKeys()} */
    static boolean canBeImeTarget(WindowState w) {
        final int fl = w.mAttrs.flags
                & (FLAG_NOT_FOCUSABLE|FLAG_ALT_FOCUSABLE_IM);
        if (fl == 0 || fl == (FLAG_NOT_FOCUSABLE|FLAG_ALT_FOCUSABLE_IM)
                || w.mAttrs.type == TYPE_APPLICATION_STARTING) {
            if (DEBUG_INPUT_METHOD) {
                Slog.i(TAG, "isVisibleOrAdding " + w + ": " + w.isVisibleOrAdding());
                if (!w.isVisibleOrAdding()) {
                    Slog.i(TAG, "  mSurface=" + w.mWinAnimator.mSurface
                            + " relayoutCalled=" + w.mRelayoutCalled + " viewVis=" + w.mViewVisibility
                            + " policyVis=" + w.mPolicyVisibility
                            + " policyVisAfterAnim=" + w.mPolicyVisibilityAfterAnim
                            + " attachHid=" + w.mAttachedHidden
                            + " exiting=" + w.mExiting + " destroying=" + w.mDestroying);
                    if (w.mAppToken != null) {
                        Slog.i(TAG, "  mAppToken.hiddenRequested=" + w.mAppToken.hiddenRequested);
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
        final int N = windows.size();
        WindowState w = null;
        int i = N;
        while (i > 0) {
            i--;
            w = windows.get(i);

            if (DEBUG_INPUT_METHOD && willMove) Slog.i(TAG, "Checking window @" + i
                    + " " + w + " fl=0x" + Integer.toHexString(w.mAttrs.flags));
            if (canBeImeTarget(w)) {
                //Slog.i(TAG, "Putting input method here!");

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

        if (DEBUG_INPUT_METHOD && willMove) Slog.v(TAG, "Proposed new IME target: " + w);

        // Now, a special case -- if the last target's window is in the
        // process of exiting, and is above the new target, keep on the
        // last target to avoid flicker.  Consider for example a Dialog with
        // the IME shown: when the Dialog is dismissed, we want to keep
        // the IME above it until it is completely gone so it doesn't drop
        // behind the dialog or its full-screen scrim.
        final WindowState curTarget = mInputMethodTarget;
        if (curTarget != null && w != null
                && curTarget.isDisplayedLw()
                && curTarget.isClosing()
                && (curTarget.mWinAnimator.mAnimLayer > w.mWinAnimator.mAnimLayer)) {
            if (DEBUG_INPUT_METHOD) Slog.v(TAG, "Current target higher, not changing");
            return windows.indexOf(curTarget) + 1;
        }

        if (DEBUG_INPUT_METHOD) Slog.v(TAG, "Desired input method target="
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
                    if (DEBUG_INPUT_METHOD) Slog.v(TAG, "mNextAppTransition="
                            + mNextAppTransition + " " + highestTarget
                            + " animating=" + highestTarget.mWinAnimator.isAnimating()
                            + " layer=" + highestTarget.mWinAnimator.mAnimLayer
                            + " new layer=" + w.mWinAnimator.mAnimLayer);

                    if (mNextAppTransition != WindowManagerPolicy.TRANSIT_UNSET) {
                        // If we are currently setting up for an animation,
                        // hold everything until we can find out what will happen.
                        mInputMethodTargetWaitingAnim = true;
                        mInputMethodTarget = highestTarget;
                        return highestPos + 1;
                    } else if (highestTarget.mWinAnimator.isAnimating() &&
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

        //Slog.i(TAG, "Placing input method @" + (i+1));
        if (w != null) {
            if (willMove) {
                if (DEBUG_INPUT_METHOD) Slog.w(TAG, "Moving IM target from " + curTarget + " to "
                        + w + (HIDE_STACK_CRAWLS ? "" : " Callers=" + Debug.getCallers(4)));
                mInputMethodTarget = w;
                mInputMethodTargetWaitingAnim = false;
                if (w.mAppToken != null) {
                    setInputMethodAnimLayerAdjustment(w.mAppToken.mAppAnimator.animLayerAdjustment);
                } else {
                    setInputMethodAnimLayerAdjustment(0);
                }
            }
            return i+1;
        }
        if (willMove) {
            if (DEBUG_INPUT_METHOD) Slog.w(TAG, "Moving IM target from " + curTarget + " to null."
                    + (HIDE_STACK_CRAWLS ? "" : " Callers=" + Debug.getCallers(4)));
            mInputMethodTarget = null;
            setInputMethodAnimLayerAdjustment(0);
        }
        return -1;
    }

    void addInputMethodWindowToListLocked(WindowState win) {
        int pos = findDesiredInputMethodWindowIndexLocked(true);
        if (pos >= 0) {
            win.mTargetAppToken = mInputMethodTarget.mAppToken;
            if (DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(
                    TAG, "Adding input method window " + win + " at " + pos);
            // TODO(multidisplay): IMEs are only supported on the default display.
            getDefaultWindowListLocked().add(pos, win);
            mWindowsChanged = true;
            moveInputMethodDialogsLocked(pos+1);
            return;
        }
        win.mTargetAppToken = null;
        addWindowToListInOrderLocked(win, true);
        moveInputMethodDialogsLocked(pos);
    }

    void setInputMethodAnimLayerAdjustment(int adj) {
        if (DEBUG_LAYERS) Slog.v(TAG, "Setting im layer adj to " + adj);
        mInputMethodAnimLayerAdjustment = adj;
        WindowState imw = mInputMethodWindow;
        if (imw != null) {
            imw.mWinAnimator.mAnimLayer = imw.mLayer + adj;
            if (DEBUG_LAYERS) Slog.v(TAG, "IM win " + imw
                    + " anim layer: " + imw.mWinAnimator.mAnimLayer);
            int wi = imw.mChildWindows.size();
            while (wi > 0) {
                wi--;
                WindowState cw = imw.mChildWindows.get(wi);
                cw.mWinAnimator.mAnimLayer = cw.mLayer + adj;
                if (DEBUG_LAYERS) Slog.v(TAG, "IM win " + cw
                        + " anim layer: " + cw.mWinAnimator.mAnimLayer);
            }
        }
        int di = mInputMethodDialogs.size();
        while (di > 0) {
            di --;
            imw = mInputMethodDialogs.get(di);
            imw.mWinAnimator.mAnimLayer = imw.mLayer + adj;
            if (DEBUG_LAYERS) Slog.v(TAG, "IM win " + imw
                    + " anim layer: " + imw.mWinAnimator.mAnimLayer);
        }
    }

    private int tmpRemoveWindowLocked(int interestingPos, WindowState win) {
        WindowList windows = win.getWindowList();
        int wpos = windows.indexOf(win);
        if (wpos >= 0) {
            if (wpos < interestingPos) interestingPos--;
            if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG, "Temp removing at " + wpos + ": " + win);
            windows.remove(wpos);
            mWindowsChanged = true;
            int NC = win.mChildWindows.size();
            while (NC > 0) {
                NC--;
                WindowState cw = win.mChildWindows.get(NC);
                int cpos = windows.indexOf(cw);
                if (cpos >= 0) {
                    if (cpos < interestingPos) interestingPos--;
                    if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG, "Temp removing child at "
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
            if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG, "ReAdd removing from " + wpos + ": " + win);
            windows.remove(wpos);
            mWindowsChanged = true;
            reAddWindowLocked(wpos, win);
        }
    }

    void logWindowList(final WindowList windows, String prefix) {
        int N = windows.size();
        while (N > 0) {
            N--;
            Slog.v(TAG, prefix + "#" + N + ": " + windows.get(N));
        }
    }

    void moveInputMethodDialogsLocked(int pos) {
        ArrayList<WindowState> dialogs = mInputMethodDialogs;

        // TODO(multidisplay): IMEs are only supported on the default display.
        WindowList windows = getDefaultWindowListLocked();
        final int N = dialogs.size();
        if (DEBUG_INPUT_METHOD) Slog.v(TAG, "Removing " + N + " dialogs w/pos=" + pos);
        for (int i=0; i<N; i++) {
            pos = tmpRemoveWindowLocked(pos, dialogs.get(i));
        }
        if (DEBUG_INPUT_METHOD) {
            Slog.v(TAG, "Window list w/pos=" + pos);
            logWindowList(windows, "  ");
        }

        if (pos >= 0) {
            final AppWindowToken targetAppToken = mInputMethodTarget.mAppToken;
            if (pos < windows.size()) {
                WindowState wp = windows.get(pos);
                if (wp == mInputMethodWindow) {
                    pos++;
                }
            }
            if (DEBUG_INPUT_METHOD) Slog.v(TAG, "Adding " + N + " dialogs at pos=" + pos);
            for (int i=0; i<N; i++) {
                WindowState win = dialogs.get(i);
                win.mTargetAppToken = targetAppToken;
                pos = reAddWindowLocked(pos, win);
            }
            if (DEBUG_INPUT_METHOD) {
                Slog.v(TAG, "Final window list:");
                logWindowList(windows, "  ");
            }
            return;
        }
        for (int i=0; i<N; i++) {
            WindowState win = dialogs.get(i);
            win.mTargetAppToken = null;
            reAddWindowToListInOrderLocked(win);
            if (DEBUG_INPUT_METHOD) {
                Slog.v(TAG, "No IM target, final list:");
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
                    // All is good!
                    return false;
                }
            }

            if (imWin != null) {
                if (DEBUG_INPUT_METHOD) {
                    Slog.v(TAG, "Moving IM from " + imPos);
                    logWindowList(windows, "  ");
                }
                imPos = tmpRemoveWindowLocked(imPos, imWin);
                if (DEBUG_INPUT_METHOD) {
                    Slog.v(TAG, "List after removing with new pos " + imPos + ":");
                    logWindowList(windows, "  ");
                }
                imWin.mTargetAppToken = mInputMethodTarget.mAppToken;
                reAddWindowLocked(imPos, imWin);
                if (DEBUG_INPUT_METHOD) {
                    Slog.v(TAG, "List after moving IM to " + imPos + ":");
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
                if (DEBUG_INPUT_METHOD) Slog.v(TAG, "Moving IM from " + imPos);
                tmpRemoveWindowLocked(0, imWin);
                imWin.mTargetAppToken = null;
                reAddWindowToListInOrderLocked(imWin);
                if (DEBUG_INPUT_METHOD) {
                    Slog.v(TAG, "List with no IM target:");
                    logWindowList(windows, "  ");
                }
                if (DN > 0) moveInputMethodDialogsLocked(-1);
            } else {
                moveInputMethodDialogsLocked(-1);
            }

        }

        if (needAssignLayers) {
            assignLayersLocked(windows);
        }

        return true;
    }

    void adjustInputMethodDialogsLocked() {
        moveInputMethodDialogsLocked(findDesiredInputMethodWindowIndexLocked(true));
    }

    final boolean isWallpaperVisible(WindowState wallpaperTarget) {
        if (DEBUG_WALLPAPER) Slog.v(TAG, "Wallpaper vis: target " + wallpaperTarget + ", obscured="
                + (wallpaperTarget != null ? Boolean.toString(wallpaperTarget.mObscured) : "??")
                + " anim=" + ((wallpaperTarget != null && wallpaperTarget.mAppToken != null)
                        ? wallpaperTarget.mAppToken.mAppAnimator.animation : null)
                + " upper=" + mUpperWallpaperTarget
                + " lower=" + mLowerWallpaperTarget);
        return (wallpaperTarget != null
                        && (!wallpaperTarget.mObscured || (wallpaperTarget.mAppToken != null
                                && wallpaperTarget.mAppToken.mAppAnimator.animation != null)))
                || mUpperWallpaperTarget != null
                || mLowerWallpaperTarget != null;
    }

    static final int ADJUST_WALLPAPER_LAYERS_CHANGED = 1<<1;
    static final int ADJUST_WALLPAPER_VISIBILITY_CHANGED = 1<<2;

    int adjustWallpaperWindowsLocked() {
        mInnerFields.mWallpaperMayChange = false;
        int changed = 0;
        boolean targetChanged = false;

        // TODO(multidisplay): Wallpapers on main screen only.
        final DisplayInfo displayInfo = getDefaultDisplayContentLocked().getDisplayInfo();
        final int dw = displayInfo.appWidth;
        final int dh = displayInfo.appHeight;

        // First find top-most window that has asked to be on top of the
        // wallpaper; all wallpapers go behind it.
        final WindowList windows = getDefaultWindowListLocked();
        int N = windows.size();
        WindowState w = null;
        WindowState foundW = null;
        int foundI = 0;
        WindowState topCurW = null;
        int topCurI = 0;
        int windowDetachedI = -1;
        int i = N;
        while (i > 0) {
            i--;
            w = windows.get(i);
            if ((w.mAttrs.type == TYPE_WALLPAPER)) {
                if (topCurW == null) {
                    topCurW = w;
                    topCurI = i;
                }
                continue;
            }
            topCurW = null;
            if (w != mWindowDetachedWallpaper && w.mAppToken != null) {
                // If this window's app token is hidden and not animating,
                // it is of no interest to us.
                if (w.mAppToken.hidden && w.mAppToken.mAppAnimator.animation == null) {
                    if (DEBUG_WALLPAPER) Slog.v(TAG,
                            "Skipping hidden and not animating token: " + w);
                    continue;
                }
            }
            if (DEBUG_WALLPAPER) Slog.v(TAG, "Win #" + i + " " + w + ": readyfordisplay="
                    + w.isReadyForDisplay() + " mDrawState=" + w.mWinAnimator.mDrawState);
            if ((w.mAttrs.flags&FLAG_SHOW_WALLPAPER) != 0 && w.isReadyForDisplay()
                    && (mWallpaperTarget == w || w.isDrawnLw())) {
                if (DEBUG_WALLPAPER) Slog.v(TAG,
                        "Found wallpaper target: #" + i + "=" + w);
                foundW = w;
                foundI = i;
                if (w == mWallpaperTarget && w.mWinAnimator.isAnimating()) {
                    // The current wallpaper target is animating, so we'll
                    // look behind it for another possible target and figure
                    // out what is going on below.
                    if (DEBUG_WALLPAPER) Slog.v(TAG, "Win " + w
                            + ": token animating, looking behind.");
                    continue;
                }
                break;
            } else if (w == mWindowDetachedWallpaper) {
                windowDetachedI = i;
            }
        }

        if (foundW == null && windowDetachedI >= 0) {
            if (DEBUG_WALLPAPER_LIGHT) Slog.v(TAG,
                    "Found animating detached wallpaper activity: #" + i + "=" + w);
            foundW = w;
            foundI = windowDetachedI;
        }

        if (mNextAppTransition != WindowManagerPolicy.TRANSIT_UNSET) {
            // If we are currently waiting for an app transition, and either
            // the current target or the next target are involved with it,
            // then hold off on doing anything with the wallpaper.
            // Note that we are checking here for just whether the target
            // is part of an app token...  which is potentially overly aggressive
            // (the app token may not be involved in the transition), but good
            // enough (we'll just wait until whatever transition is pending
            // executes).
            if (mWallpaperTarget != null && mWallpaperTarget.mAppToken != null) {
                if (DEBUG_WALLPAPER_LIGHT) Slog.v(TAG,
                        "Wallpaper not changing: waiting for app anim in current target");
                return 0;
            }
            if (foundW != null && foundW.mAppToken != null) {
                if (DEBUG_WALLPAPER_LIGHT) Slog.v(TAG,
                        "Wallpaper not changing: waiting for app anim in found target");
                return 0;
            }
        }

        if (mWallpaperTarget != foundW
                && (mLowerWallpaperTarget == null || mLowerWallpaperTarget != foundW)) {
            if (DEBUG_WALLPAPER_LIGHT) {
                Slog.v(TAG, "New wallpaper target: " + foundW
                        + " oldTarget: " + mWallpaperTarget);
            }

            mLowerWallpaperTarget = null;
            mUpperWallpaperTarget = null;

            WindowState oldW = mWallpaperTarget;
            mWallpaperTarget = foundW;
            targetChanged = true;

            // Now what is happening...  if the current and new targets are
            // animating, then we are in our super special mode!
            if (foundW != null && oldW != null) {
                boolean oldAnim = oldW.mWinAnimator.mAnimation != null
                        || (oldW.mAppToken != null
                            && oldW.mAppToken.mAppAnimator.animation != null);
                boolean foundAnim = foundW.mWinAnimator.mAnimation != null
                        || (foundW.mAppToken != null &&
                            foundW.mAppToken.mAppAnimator.animation != null);
                if (DEBUG_WALLPAPER_LIGHT) {
                    Slog.v(TAG, "New animation: " + foundAnim
                            + " old animation: " + oldAnim);
                }
                if (foundAnim && oldAnim) {
                    int oldI = windows.indexOf(oldW);
                    if (DEBUG_WALLPAPER_LIGHT) {
                        Slog.v(TAG, "New i: " + foundI + " old i: " + oldI);
                    }
                    if (oldI >= 0) {
                        if (DEBUG_WALLPAPER_LIGHT) {
                            Slog.v(TAG, "Animating wallpapers: old#" + oldI
                                    + "=" + oldW + "; new#" + foundI
                                    + "=" + foundW);
                        }

                        // Set the new target correctly.
                        if (foundW.mAppToken != null && foundW.mAppToken.hiddenRequested) {
                            if (DEBUG_WALLPAPER_LIGHT) {
                                Slog.v(TAG, "Old wallpaper still the target.");
                            }
                            mWallpaperTarget = oldW;
                            foundW = oldW;
                            foundI = oldI;
                        }
                        // Now set the upper and lower wallpaper targets
                        // correctly, and make sure that we are positioning
                        // the wallpaper below the lower.
                        else if (foundI > oldI) {
                            // The new target is on top of the old one.
                            if (DEBUG_WALLPAPER_LIGHT) {
                                Slog.v(TAG, "Found target above old target.");
                            }
                            mUpperWallpaperTarget = foundW;
                            mLowerWallpaperTarget = oldW;
                            foundW = oldW;
                            foundI = oldI;
                        } else {
                            // The new target is below the old one.
                            if (DEBUG_WALLPAPER_LIGHT) {
                                Slog.v(TAG, "Found target below old target.");
                            }
                            mUpperWallpaperTarget = oldW;
                            mLowerWallpaperTarget = foundW;
                        }
                    }
                }
            }

        } else if (mLowerWallpaperTarget != null) {
            // Is it time to stop animating?
            boolean lowerAnimating = mLowerWallpaperTarget.mWinAnimator.mAnimation != null
                    || (mLowerWallpaperTarget.mAppToken != null
                            && mLowerWallpaperTarget.mAppToken.mAppAnimator.animation != null);
            boolean upperAnimating = mUpperWallpaperTarget.mWinAnimator.mAnimation != null
                    || (mUpperWallpaperTarget.mAppToken != null
                            && mUpperWallpaperTarget.mAppToken.mAppAnimator.animation != null);
            if (!lowerAnimating || !upperAnimating) {
                if (DEBUG_WALLPAPER_LIGHT) {
                    Slog.v(TAG, "No longer animating wallpaper targets!");
                }
                mLowerWallpaperTarget = null;
                mUpperWallpaperTarget = null;
                mWallpaperTarget = foundW;
                targetChanged = true;
            }
        }

        boolean visible = foundW != null;
        if (visible) {
            // The window is visible to the compositor...  but is it visible
            // to the user?  That is what the wallpaper cares about.
            visible = isWallpaperVisible(foundW);
            if (DEBUG_WALLPAPER) Slog.v(TAG, "Wallpaper visibility: " + visible);

            // If the wallpaper target is animating, we may need to copy
            // its layer adjustment.  Only do this if we are not transfering
            // between two wallpaper targets.
            mWallpaperAnimLayerAdjustment =
                    (mLowerWallpaperTarget == null && foundW.mAppToken != null)
                    ? foundW.mAppToken.mAppAnimator.animLayerAdjustment : 0;

            final int maxLayer = mPolicy.getMaxWallpaperLayer()
                    * TYPE_LAYER_MULTIPLIER
                    + TYPE_LAYER_OFFSET;

            // Now w is the window we are supposed to be behind...  but we
            // need to be sure to also be behind any of its attached windows,
            // AND any starting window associated with it, AND below the
            // maximum layer the policy allows for wallpapers.
            while (foundI > 0) {
                WindowState wb = windows.get(foundI-1);
                if (wb.mBaseLayer < maxLayer &&
                        wb.mAttachedWindow != foundW &&
                        (foundW.mAttachedWindow == null ||
                                wb.mAttachedWindow != foundW.mAttachedWindow) &&
                        (wb.mAttrs.type != TYPE_APPLICATION_STARTING ||
                                foundW.mToken == null || wb.mToken != foundW.mToken)) {
                    // This window is not related to the previous one in any
                    // interesting way, so stop here.
                    break;
                }
                foundW = wb;
                foundI--;
            }
        } else {
            if (DEBUG_WALLPAPER) Slog.v(TAG, "No wallpaper target");
        }

        if (foundW == null && topCurW != null) {
            // There is no wallpaper target, so it goes at the bottom.
            // We will assume it is the same place as last time, if known.
            foundW = topCurW;
            foundI = topCurI+1;
        } else {
            // Okay i is the position immediately above the wallpaper.  Look at
            // what is below it for later.
            foundW = foundI > 0 ? windows.get(foundI-1) : null;
        }

        if (visible) {
            if (mWallpaperTarget.mWallpaperX >= 0) {
                mLastWallpaperX = mWallpaperTarget.mWallpaperX;
                mLastWallpaperXStep = mWallpaperTarget.mWallpaperXStep;
            }
            if (mWallpaperTarget.mWallpaperY >= 0) {
                mLastWallpaperY = mWallpaperTarget.mWallpaperY;
                mLastWallpaperYStep = mWallpaperTarget.mWallpaperYStep;
            }
        }

        // Start stepping backwards from here, ensuring that our wallpaper windows
        // are correctly placed.
        int curTokenIndex = mWallpaperTokens.size();
        while (curTokenIndex > 0) {
            curTokenIndex--;
            WindowToken token = mWallpaperTokens.get(curTokenIndex);
            if (token.hidden == visible) {
                if (DEBUG_WALLPAPER_LIGHT) Slog.d(TAG,
                        "Wallpaper token " + token + " hidden=" + !visible);
                changed |= ADJUST_WALLPAPER_VISIBILITY_CHANGED;
                token.hidden = !visible;
                // Need to do a layout to ensure the wallpaper now has the
                // correct size.
                getDefaultDisplayContentLocked().layoutNeeded = true;
            }

            int curWallpaperIndex = token.windows.size();
            while (curWallpaperIndex > 0) {
                curWallpaperIndex--;
                WindowState wallpaper = token.windows.get(curWallpaperIndex);

                if (visible) {
                    updateWallpaperOffsetLocked(wallpaper, dw, dh, false);
                }

                // First, make sure the client has the current visibility
                // state.
                dispatchWallpaperVisibility(wallpaper, visible);

                wallpaper.mWinAnimator.mAnimLayer = wallpaper.mLayer + mWallpaperAnimLayerAdjustment;
                if (DEBUG_LAYERS || DEBUG_WALLPAPER_LIGHT) Slog.v(TAG, "adjustWallpaper win "
                        + wallpaper + " anim layer: " + wallpaper.mWinAnimator.mAnimLayer);

                // First, if this window is at the current index, then all
                // is well.
                if (wallpaper == foundW) {
                    foundI--;
                    foundW = foundI > 0
                            ? windows.get(foundI-1) : null;
                    continue;
                }

                // The window didn't match...  the current wallpaper window,
                // wherever it is, is in the wrong place, so make sure it is
                // not in the list.
                int oldIndex = windows.indexOf(wallpaper);
                if (oldIndex >= 0) {
                    if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG, "Wallpaper removing at "
                            + oldIndex + ": " + wallpaper);
                    windows.remove(oldIndex);
                    mWindowsChanged = true;
                    if (oldIndex < foundI) {
                        foundI--;
                    }
                }

                // Now stick it in.
                if (DEBUG_WALLPAPER_LIGHT || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) {
                    Slog.v(TAG, "Moving wallpaper " + wallpaper
                            + " from " + oldIndex + " to " + foundI);
                }

                windows.add(foundI, wallpaper);
                mWindowsChanged = true;
                changed |= ADJUST_WALLPAPER_LAYERS_CHANGED;
            }
        }

        if (targetChanged && DEBUG_WALLPAPER_LIGHT) {
            Slog.d(TAG, "New wallpaper: target=" + mWallpaperTarget
                    + " lower=" + mLowerWallpaperTarget + " upper="
                    + mUpperWallpaperTarget);
        }

        return changed;
    }

    void setWallpaperAnimLayerAdjustmentLocked(int adj) {
        if (DEBUG_LAYERS || DEBUG_WALLPAPER) Slog.v(TAG,
                "Setting wallpaper layer adj to " + adj);
        mWallpaperAnimLayerAdjustment = adj;
        int curTokenIndex = mWallpaperTokens.size();
        while (curTokenIndex > 0) {
            curTokenIndex--;
            WindowToken token = mWallpaperTokens.get(curTokenIndex);
            int curWallpaperIndex = token.windows.size();
            while (curWallpaperIndex > 0) {
                curWallpaperIndex--;
                WindowState wallpaper = token.windows.get(curWallpaperIndex);
                wallpaper.mWinAnimator.mAnimLayer = wallpaper.mLayer + adj;
                if (DEBUG_LAYERS || DEBUG_WALLPAPER) Slog.v(TAG, "setWallpaper win "
                        + wallpaper + " anim layer: " + wallpaper.mWinAnimator.mAnimLayer);
            }
        }
    }

    boolean updateWallpaperOffsetLocked(WindowState wallpaperWin, int dw, int dh,
            boolean sync) {
        boolean changed = false;
        boolean rawChanged = false;
        float wpx = mLastWallpaperX >= 0 ? mLastWallpaperX : 0.5f;
        float wpxs = mLastWallpaperXStep >= 0 ? mLastWallpaperXStep : -1.0f;
        int availw = wallpaperWin.mFrame.right-wallpaperWin.mFrame.left-dw;
        int offset = availw > 0 ? -(int)(availw*wpx+.5f) : 0;
        changed = wallpaperWin.mXOffset != offset;
        if (changed) {
            if (DEBUG_WALLPAPER) Slog.v(TAG, "Update wallpaper "
                    + wallpaperWin + " x: " + offset);
            wallpaperWin.mXOffset = offset;
        }
        if (wallpaperWin.mWallpaperX != wpx || wallpaperWin.mWallpaperXStep != wpxs) {
            wallpaperWin.mWallpaperX = wpx;
            wallpaperWin.mWallpaperXStep = wpxs;
            rawChanged = true;
        }

        float wpy = mLastWallpaperY >= 0 ? mLastWallpaperY : 0.5f;
        float wpys = mLastWallpaperYStep >= 0 ? mLastWallpaperYStep : -1.0f;
        int availh = wallpaperWin.mFrame.bottom-wallpaperWin.mFrame.top-dh;
        offset = availh > 0 ? -(int)(availh*wpy+.5f) : 0;
        if (wallpaperWin.mYOffset != offset) {
            if (DEBUG_WALLPAPER) Slog.v(TAG, "Update wallpaper "
                    + wallpaperWin + " y: " + offset);
            changed = true;
            wallpaperWin.mYOffset = offset;
        }
        if (wallpaperWin.mWallpaperY != wpy || wallpaperWin.mWallpaperYStep != wpys) {
            wallpaperWin.mWallpaperY = wpy;
            wallpaperWin.mWallpaperYStep = wpys;
            rawChanged = true;
        }

        if (rawChanged && (wallpaperWin.mAttrs.privateFlags &
                    WindowManager.LayoutParams.PRIVATE_FLAG_WANTS_OFFSET_NOTIFICATIONS) != 0) {
            try {
                if (DEBUG_WALLPAPER) Slog.v(TAG, "Report new wp offset "
                        + wallpaperWin + " x=" + wallpaperWin.mWallpaperX
                        + " y=" + wallpaperWin.mWallpaperY);
                if (sync) {
                    mWaitingOnWallpaper = wallpaperWin;
                }
                wallpaperWin.mClient.dispatchWallpaperOffsets(
                        wallpaperWin.mWallpaperX, wallpaperWin.mWallpaperY,
                        wallpaperWin.mWallpaperXStep, wallpaperWin.mWallpaperYStep, sync);
                if (sync) {
                    if (mWaitingOnWallpaper != null) {
                        long start = SystemClock.uptimeMillis();
                        if ((mLastWallpaperTimeoutTime+WALLPAPER_TIMEOUT_RECOVERY)
                                < start) {
                            try {
                                if (DEBUG_WALLPAPER) Slog.v(TAG,
                                        "Waiting for offset complete...");
                                mWindowMap.wait(WALLPAPER_TIMEOUT);
                            } catch (InterruptedException e) {
                            }
                            if (DEBUG_WALLPAPER) Slog.v(TAG, "Offset complete!");
                            if ((start+WALLPAPER_TIMEOUT)
                                    < SystemClock.uptimeMillis()) {
                                Slog.i(TAG, "Timeout waiting for wallpaper to offset: "
                                        + wallpaperWin);
                                mLastWallpaperTimeoutTime = start;
                            }
                        }
                        mWaitingOnWallpaper = null;
                    }
                }
            } catch (RemoteException e) {
            }
        }

        return changed;
    }

    void wallpaperOffsetsComplete(IBinder window) {
        synchronized (mWindowMap) {
            if (mWaitingOnWallpaper != null &&
                    mWaitingOnWallpaper.mClient.asBinder() == window) {
                mWaitingOnWallpaper = null;
                mWindowMap.notifyAll();
            }
        }
    }

    void updateWallpaperOffsetLocked(WindowState changingTarget, boolean sync) {
        final DisplayContent displayContent = changingTarget.mDisplayContent;
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final int dw = displayInfo.appWidth;
        final int dh = displayInfo.appHeight;

        WindowState target = mWallpaperTarget;
        if (target != null) {
            if (target.mWallpaperX >= 0) {
                mLastWallpaperX = target.mWallpaperX;
            } else if (changingTarget.mWallpaperX >= 0) {
                mLastWallpaperX = changingTarget.mWallpaperX;
            }
            if (target.mWallpaperY >= 0) {
                mLastWallpaperY = target.mWallpaperY;
            } else if (changingTarget.mWallpaperY >= 0) {
                mLastWallpaperY = changingTarget.mWallpaperY;
            }
        }

        int curTokenIndex = mWallpaperTokens.size();
        while (curTokenIndex > 0) {
            curTokenIndex--;
            WindowToken token = mWallpaperTokens.get(curTokenIndex);
            int curWallpaperIndex = token.windows.size();
            while (curWallpaperIndex > 0) {
                curWallpaperIndex--;
                WindowState wallpaper = token.windows.get(curWallpaperIndex);
                if (updateWallpaperOffsetLocked(wallpaper, dw, dh, sync)) {
                    WindowStateAnimator winAnimator = wallpaper.mWinAnimator;
                    winAnimator.computeShownFrameLocked();
                    // No need to lay out the windows - we can just set the wallpaper position
                    // directly.
                    // TODO(cmautner): Don't move this from here, just lock the WindowAnimator.
                    if (winAnimator.mSurfaceX != wallpaper.mShownFrame.left
                            || winAnimator.mSurfaceY != wallpaper.mShownFrame.top) {
                        winAnimator.setWallpaperOffset((int) wallpaper.mShownFrame.left,
                                (int) wallpaper.mShownFrame.top);
                    }
                    // We only want to be synchronous with one wallpaper.
                    sync = false;
                }
            }
        }
    }

    /**
     * Check wallpaper for visiblity change and notify window if so.
     * @param wallpaper The wallpaper to test and notify.
     * @param visible Current visibility.
     */
    void dispatchWallpaperVisibility(final WindowState wallpaper, final boolean visible) {
        if (wallpaper.mWallpaperVisible != visible) {
            wallpaper.mWallpaperVisible = visible;
            try {
                if (DEBUG_VISIBILITY || DEBUG_WALLPAPER_LIGHT) Slog.v(TAG,
                        "Updating vis of wallpaper " + wallpaper
                        + ": " + visible + " from:\n" + Debug.getCallers(4, "  "));
                wallpaper.mClient.dispatchAppVisibility(visible);
            } catch (RemoteException e) {
            }
        }
    }

    void updateWallpaperVisibilityLocked() {
        final boolean visible = isWallpaperVisible(mWallpaperTarget);
        final DisplayContent displayContent = mWallpaperTarget.mDisplayContent;
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final int dw = displayInfo.appWidth;
        final int dh = displayInfo.appHeight;

        int curTokenIndex = mWallpaperTokens.size();
        while (curTokenIndex > 0) {
            curTokenIndex--;
            WindowToken token = mWallpaperTokens.get(curTokenIndex);
            if (token.hidden == visible) {
                token.hidden = !visible;
                // Need to do a layout to ensure the wallpaper now has the
                // correct size.
                getDefaultDisplayContentLocked().layoutNeeded = true;
            }

            int curWallpaperIndex = token.windows.size();
            while (curWallpaperIndex > 0) {
                curWallpaperIndex--;
                WindowState wallpaper = token.windows.get(curWallpaperIndex);
                if (visible) {
                    updateWallpaperOffsetLocked(wallpaper, dw, dh, false);
                }

                dispatchWallpaperVisibility(wallpaper, visible);
            }
        }
    }

    public int addWindow(Session session, IWindow client, int seq,
            WindowManager.LayoutParams attrs, int viewVisibility, int displayId,
            Rect outContentInsets, InputChannel outInputChannel) {
        int res = mPolicy.checkAddPermission(attrs);
        if (res != WindowManagerGlobal.ADD_OKAY) {
            return res;
        }

        boolean reportNewConfig = false;
        WindowState attachedWindow = null;
        WindowState win = null;
        long origId;
        final int type = attrs.type;

        synchronized(mWindowMap) {
            if (!mDisplayReady) {
                throw new IllegalStateException("Display has not been initialialized");
            }

            final DisplayContent displayContent = getDisplayContentLocked(displayId);
            if (displayContent == null) {
                return WindowManagerGlobal.ADD_INVALID_DISPLAY;
            }

            if (mWindowMap.containsKey(client.asBinder())) {
                Slog.w(TAG, "Window " + client + " is already added");
                return WindowManagerGlobal.ADD_DUPLICATE_ADD;
            }

            if (type >= FIRST_SUB_WINDOW && type <= LAST_SUB_WINDOW) {
                attachedWindow = windowForClientLocked(null, attrs.token, false);
                if (attachedWindow == null) {
                    Slog.w(TAG, "Attempted to add window with token that is not a window: "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_SUBWINDOW_TOKEN;
                }
                if (attachedWindow.mAttrs.type >= FIRST_SUB_WINDOW
                        && attachedWindow.mAttrs.type <= LAST_SUB_WINDOW) {
                    Slog.w(TAG, "Attempted to add window with token that is a sub-window: "
                            + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_SUBWINDOW_TOKEN;
                }
            }

            boolean addToken = false;
            WindowToken token = mTokenMap.get(attrs.token);
            if (token == null) {
                if (type >= FIRST_APPLICATION_WINDOW && type <= LAST_APPLICATION_WINDOW) {
                    Slog.w(TAG, "Attempted to add application window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
                if (type == TYPE_INPUT_METHOD) {
                    Slog.w(TAG, "Attempted to add input method window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
                if (type == TYPE_WALLPAPER) {
                    Slog.w(TAG, "Attempted to add wallpaper window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
                if (type == TYPE_DREAM) {
                    Slog.w(TAG, "Attempted to add Dream window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
                token = new WindowToken(this, attrs.token, -1, false);
                addToken = true;
            } else if (type >= FIRST_APPLICATION_WINDOW && type <= LAST_APPLICATION_WINDOW) {
                AppWindowToken atoken = token.appWindowToken;
                if (atoken == null) {
                    Slog.w(TAG, "Attempted to add window with non-application token "
                          + token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_NOT_APP_TOKEN;
                } else if (atoken.removed) {
                    Slog.w(TAG, "Attempted to add window with exiting application token "
                          + token + ".  Aborting.");
                    return WindowManagerGlobal.ADD_APP_EXITING;
                }
                if (type == TYPE_APPLICATION_STARTING && atoken.firstWindowDrawn) {
                    // No need for this guy!
                    if (localLOGV) Slog.v(
                            TAG, "**** NO NEED TO START: " + attrs.getTitle());
                    return WindowManagerGlobal.ADD_STARTING_NOT_NEEDED;
                }
            } else if (type == TYPE_INPUT_METHOD) {
                if (token.windowType != TYPE_INPUT_METHOD) {
                    Slog.w(TAG, "Attempted to add input method window with bad token "
                            + attrs.token + ".  Aborting.");
                      return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (type == TYPE_WALLPAPER) {
                if (token.windowType != TYPE_WALLPAPER) {
                    Slog.w(TAG, "Attempted to add wallpaper window with bad token "
                            + attrs.token + ".  Aborting.");
                      return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            } else if (type == TYPE_DREAM) {
                if (token.windowType != TYPE_DREAM) {
                    Slog.w(TAG, "Attempted to add Dream window with bad token "
                            + attrs.token + ".  Aborting.");
                      return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
                }
            }

            win = new WindowState(this, session, client, token,
                    attachedWindow, seq, attrs, viewVisibility, displayContent);
            if (win.mDeathRecipient == null) {
                // Client has apparently died, so there is no reason to
                // continue.
                Slog.w(TAG, "Adding window client " + client.asBinder()
                        + " that is dead, aborting.");
                return WindowManagerGlobal.ADD_APP_EXITING;
            }

            mPolicy.adjustWindowParamsLw(win.mAttrs);
            win.setShowToOwnerOnlyLocked(mPolicy.checkShowToOwnerOnly(attrs));

            res = mPolicy.prepareAddWindowLw(win, attrs);
            if (res != WindowManagerGlobal.ADD_OKAY) {
                return res;
            }

            if (outInputChannel != null && (attrs.inputFeatures
                    & WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL) == 0) {
                String name = win.makeInputChannelName();
                InputChannel[] inputChannels = InputChannel.openInputChannelPair(name);
                win.setInputChannel(inputChannels[0]);
                inputChannels[1].transferTo(outInputChannel);

                mInputManager.registerInputChannel(win.mInputChannel, win.mInputWindowHandle);
            }

            // From now on, no exceptions or errors allowed!

            res = WindowManagerGlobal.ADD_OKAY;

            origId = Binder.clearCallingIdentity();

            if (addToken) {
                mTokenMap.put(attrs.token, token);
            }
            win.attach();
            mWindowMap.put(client.asBinder(), win);

            if (type == TYPE_APPLICATION_STARTING && token.appWindowToken != null) {
                token.appWindowToken.startingWindow = win;
                if (DEBUG_STARTING_WINDOW) Slog.v (TAG, "addWindow: " + token.appWindowToken
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
                adjustInputMethodDialogsLocked();
                imMayMove = false;
            } else {
                addWindowToListInOrderLocked(win, true);
                if (type == TYPE_WALLPAPER) {
                    mLastWallpaperTimeoutTime = 0;
                    adjustWallpaperWindowsLocked();
                } else if ((attrs.flags&FLAG_SHOW_WALLPAPER) != 0) {
                    adjustWallpaperWindowsLocked();
                } else if (mWallpaperTarget != null
                        && mWallpaperTarget.mLayer >= win.mBaseLayer) {
                    // If there is currently a wallpaper being shown, and
                    // the base layer of the new window is below the current
                    // layer of the target window, then adjust the wallpaper.
                    // This is to avoid a new window being placed between the
                    // wallpaper and its target.
                    adjustWallpaperWindowsLocked();
                }
            }

            win.mWinAnimator.mEnterAnimationPending = true;

            if (displayContent.isDefaultDisplay) {
                mPolicy.getContentInsetHintLw(attrs, outContentInsets);
            } else {
                outContentInsets.setEmpty();
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

            assignLayersLocked(displayContent.getWindowList());
            // Don't do layout here, the window must call
            // relayout to be displayed, so we'll do it there.

            //dump();

            if (focusChanged) {
                finishUpdateFocusedWindowAfterAssignLayersLocked(false /*updateInputWindows*/);
            }
            mInputMonitor.updateInputWindowsLw(false /*force*/);

            if (localLOGV) Slog.v(
                TAG, "New client " + client.asBinder()
                + ": window=" + win);

            if (win.isVisibleOrAdding() && updateOrientationFromAppTokensLocked(false)) {
                reportNewConfig = true;
            }
        }

        if (reportNewConfig) {
            sendNewConfiguration();
        }

        Binder.restoreCallingIdentity(origId);

        return res;
    }

    public void removeWindow(Session session, IWindow client) {
        synchronized(mWindowMap) {
            WindowState win = windowForClientLocked(session, client, false);
            if (win == null) {
                return;
            }
            removeWindowLocked(session, win);
        }
    }

    public void removeWindowLocked(Session session, WindowState win) {

        if (localLOGV || DEBUG_FOCUS) Slog.v(
            TAG, "Remove " + win + " client="
            + Integer.toHexString(System.identityHashCode(
                win.mClient.asBinder()))
            + ", surface=" + win.mWinAnimator.mSurface);

        final long origId = Binder.clearCallingIdentity();

        win.disposeInputChannel();

        if (DEBUG_APP_TRANSITIONS) Slog.v(
                TAG, "Remove " + win + ": mSurface=" + win.mWinAnimator.mSurface
                + " mExiting=" + win.mExiting
                + " isAnimating=" + win.mWinAnimator.isAnimating()
                + " app-animation="
                + (win.mAppToken != null ? win.mAppToken.mAppAnimator.animation : null)
                + " inPendingTransaction="
                + (win.mAppToken != null ? win.mAppToken.inPendingTransaction : false)
                + " mDisplayFrozen=" + mDisplayFrozen);
        // Visibility of the removed window. Will be used later to update orientation later on.
        boolean wasVisible = false;
        // First, see if we need to run an animation.  If we do, we have
        // to hold off on removing the window until the animation is done.
        // If the display is frozen, just remove immediately, since the
        // animation wouldn't be seen.
        if (win.mHasSurface && okToDisplay()) {
            // If we are not currently running the exit animation, we
            // need to see about starting one.
            wasVisible = win.isWinVisibleLw();
            if (wasVisible) {

                int transit = WindowManagerPolicy.TRANSIT_EXIT;
                if (win.mAttrs.type == TYPE_APPLICATION_STARTING) {
                    transit = WindowManagerPolicy.TRANSIT_PREVIEW_DONE;
                }
                // Try starting an animation.
                if (win.mWinAnimator.applyAnimationLocked(transit, false)) {
                    win.mExiting = true;
                }
                scheduleNotifyWindowTranstionIfNeededLocked(win, transit);
            }
            if (win.mExiting || win.mWinAnimator.isAnimating()) {
                // The exit animation is running... wait for it!
                //Slog.i(TAG, "*** Running exit animation...");
                win.mExiting = true;
                win.mRemoveOnExit = true;
                win.mDisplayContent.layoutNeeded = true;
                updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                        false /*updateInputWindows*/);
                performLayoutAndPlaceSurfacesLocked();
                mInputMonitor.updateInputWindowsLw(false /*force*/);
                if (win.mAppToken != null) {
                    win.mAppToken.updateReportedVisibilityLocked();
                }
                //dump();
                Binder.restoreCallingIdentity(origId);
                return;
            }
        }

        removeWindowInnerLocked(session, win);
        // Removing a visible window will effect the computed orientation
        // So just update orientation if needed.
        if (wasVisible && computeForcedAppOrientationLocked()
                != mForcedAppOrientation
                && updateOrientationFromAppTokensLocked(false)) {
            mH.sendEmptyMessage(H.SEND_NEW_CONFIGURATION);
        }
        updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, true /*updateInputWindows*/);
        Binder.restoreCallingIdentity(origId);
    }

    private void removeWindowInnerLocked(Session session, WindowState win) {
        if (win.mRemoved) {
            // Nothing to do.
            return;
        }

        for (int i=win.mChildWindows.size()-1; i>=0; i--) {
            WindowState cwin = win.mChildWindows.get(i);
            Slog.w(TAG, "Force-removing child win " + cwin + " from container "
                    + win);
            removeWindowInnerLocked(cwin.mSession, cwin);
        }

        win.mRemoved = true;

        if (mInputMethodTarget == win) {
            moveInputMethodWindowsIfNeededLocked(false);
        }

        if (false) {
            RuntimeException e = new RuntimeException("here");
            e.fillInStackTrace();
            Slog.w(TAG, "Removing window " + win, e);
        }

        mPolicy.removeWindowLw(win);
        win.removeLocked();

        if (DEBUG_ADD_REMOVE) Slog.v(TAG, "removeWindowInnerLocked: " + win);
        mWindowMap.remove(win.mClient.asBinder());

        final WindowList windows = win.getWindowList();
        windows.remove(win);
        mPendingRemove.remove(win);
        mResizingWindows.remove(win);
        mWindowsChanged = true;
        if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG, "Final remove of window: " + win);

        if (mInputMethodWindow == win) {
            mInputMethodWindow = null;
        } else if (win.mAttrs.type == TYPE_INPUT_METHOD_DIALOG) {
            mInputMethodDialogs.remove(win);
        }

        final WindowToken token = win.mToken;
        final AppWindowToken atoken = win.mAppToken;
        if (DEBUG_ADD_REMOVE) Slog.v(TAG, "Removing " + win + " from " + token);
        token.windows.remove(win);
        if (atoken != null) {
            atoken.allAppWindows.remove(win);
        }
        if (localLOGV) Slog.v(
                TAG, "**** Removing window " + win + ": count="
                + token.windows.size());
        if (token.windows.size() == 0) {
            if (!token.explicit) {
                mTokenMap.remove(token.token);
            } else if (atoken != null) {
                atoken.firstWindowDrawn = false;
            }
        }

        if (atoken != null) {
            if (atoken.startingWindow == win) {
                if (DEBUG_STARTING_WINDOW) Slog.v(TAG, "Nulling startingWindow " + win);
                atoken.startingWindow = null;
            } else if (atoken.allAppWindows.size() == 0 && atoken.startingData != null) {
                // If this is the last window and we had requested a starting
                // transition window, well there is no point now.
                if (DEBUG_STARTING_WINDOW) Slog.v(TAG, "Nulling last startingWindow");
                atoken.startingData = null;
            } else if (atoken.allAppWindows.size() == 1 && atoken.startingView != null) {
                // If this is the last window except for a starting transition
                // window, we need to get rid of the starting transition.
                if (DEBUG_STARTING_WINDOW) {
                    Slog.v(TAG, "Schedule remove starting " + token
                            + ": no more real windows");
                }
                Message m = mH.obtainMessage(H.REMOVE_STARTING, atoken);
                mH.sendMessage(m);
            }
        }

        if (win.mAttrs.type == TYPE_WALLPAPER) {
            mLastWallpaperTimeoutTime = 0;
            adjustWallpaperWindowsLocked();
        } else if ((win.mAttrs.flags&FLAG_SHOW_WALLPAPER) != 0) {
            adjustWallpaperWindowsLocked();
        }

        if (!mInLayout) {
            assignLayersLocked(windows);
            win.mDisplayContent.layoutNeeded = true;
            performLayoutAndPlaceSurfacesLocked();
            if (win.mAppToken != null) {
                win.mAppToken.updateReportedVisibilityLocked();
            }
        }

        mInputMonitor.updateInputWindowsLw(true /*force*/);
    }

    static void logSurface(WindowState w, String msg, RuntimeException where) {
        String str = "  SURFACE " + msg + ": " + w;
        if (where != null) {
            Slog.i(TAG, str, where);
        } else {
            Slog.i(TAG, str);
        }
    }

    static void logSurface(Surface s, String title, String msg, RuntimeException where) {
        String str = "  SURFACE " + s + ": " + msg + " / " + title;
        if (where != null) {
            Slog.i(TAG, str, where);
        } else {
            Slog.i(TAG, str);
        }
    }

    // TODO(cmautner): Move to WindowStateAnimator.
    void setTransparentRegionHint(final WindowStateAnimator winAnimator, final Region region) {
        mH.sendMessage(mH.obtainMessage(H.SET_TRANSPARENT_REGION,
                new Pair<WindowStateAnimator, Region>(winAnimator, region)));
    }

    void setTransparentRegionWindow(Session session, IWindow client, Region region) {
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mWindowMap) {
                WindowState w = windowForClientLocked(session, client, false);
                if ((w != null) && w.mHasSurface) {
                    setTransparentRegionHint(w.mWinAnimator, region);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void setInsetsWindow(Session session, IWindow client,
            int touchableInsets, Rect contentInsets,
            Rect visibleInsets, Region touchableRegion) {
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mWindowMap) {
                WindowState w = windowForClientLocked(session, client, false);
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
                    w.mDisplayContent.layoutNeeded = true;
                    performLayoutAndPlaceSurfacesLocked();
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

    public void setWindowWallpaperPositionLocked(WindowState window, float x, float y,
            float xStep, float yStep) {
        if (window.mWallpaperX != x || window.mWallpaperY != y)  {
            window.mWallpaperX = x;
            window.mWallpaperY = y;
            window.mWallpaperXStep = xStep;
            window.mWallpaperYStep = yStep;
            updateWallpaperOffsetLocked(window, true);
        }
    }

    void wallpaperCommandComplete(IBinder window, Bundle result) {
        synchronized (mWindowMap) {
            if (mWaitingOnWallpaper != null &&
                    mWaitingOnWallpaper.mClient.asBinder() == window) {
                mWaitingOnWallpaper = null;
                mWindowMap.notifyAll();
            }
        }
    }

    public Bundle sendWindowWallpaperCommandLocked(WindowState window,
            String action, int x, int y, int z, Bundle extras, boolean sync) {
        if (window == mWallpaperTarget || window == mLowerWallpaperTarget
                || window == mUpperWallpaperTarget) {
            boolean doWait = sync;
            int curTokenIndex = mWallpaperTokens.size();
            while (curTokenIndex > 0) {
                curTokenIndex--;
                WindowToken token = mWallpaperTokens.get(curTokenIndex);
                int curWallpaperIndex = token.windows.size();
                while (curWallpaperIndex > 0) {
                    curWallpaperIndex--;
                    WindowState wallpaper = token.windows.get(curWallpaperIndex);
                    try {
                        wallpaper.mClient.dispatchWallpaperCommand(action,
                                x, y, z, extras, sync);
                        // We only want to be synchronous with one wallpaper.
                        sync = false;
                    } catch (RemoteException e) {
                    }
                }
            }

            if (doWait) {
                // XXX Need to wait for result.
            }
        }

        return null;
    }

    public void setUniverseTransformLocked(WindowState window, float alpha,
            float offx, float offy, float dsdx, float dtdx, float dsdy, float dtdy) {
        Transformation transform = window.mWinAnimator.mUniverseTransform;
        transform.setAlpha(alpha);
        Matrix matrix = transform.getMatrix();
        matrix.getValues(mTmpFloats);
        mTmpFloats[Matrix.MTRANS_X] = offx;
        mTmpFloats[Matrix.MTRANS_Y] = offy;
        mTmpFloats[Matrix.MSCALE_X] = dsdx;
        mTmpFloats[Matrix.MSKEW_Y] = dtdx;
        mTmpFloats[Matrix.MSKEW_X] = dsdy;
        mTmpFloats[Matrix.MSCALE_Y] = dtdy;
        matrix.setValues(mTmpFloats);
        final DisplayInfo displayInfo = window.mDisplayContent.getDisplayInfo();
        final RectF dispRect = new RectF(0, 0,
                displayInfo.logicalWidth, displayInfo.logicalHeight);
        matrix.mapRect(dispRect);
        window.mGivenTouchableRegion.set(0, 0,
                displayInfo.logicalWidth, displayInfo.logicalHeight);
        window.mGivenTouchableRegion.op((int)dispRect.left, (int)dispRect.top,
                (int)dispRect.right, (int)dispRect.bottom, Region.Op.DIFFERENCE);
        window.mTouchableInsets = ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION;
        window.mDisplayContent.layoutNeeded = true;
        performLayoutAndPlaceSurfacesLocked();
    }

    public void onRectangleOnScreenRequested(IBinder token, Rect rectangle, boolean immediate) {
        synchronized (mWindowMap) {
            WindowState window = mWindowMap.get(token);
            if (window != null) {
                scheduleNotifyRectangleOnScreenRequestedIfNeededLocked(window, rectangle,
                        immediate);
            }
        }
    }

    private void scheduleNotifyRectangleOnScreenRequestedIfNeededLocked(WindowState window,
            Rect rectangle, boolean immediate) {
        DisplayContent displayContent = window.mDisplayContent;
        if (displayContent.mDisplayContentChangeListeners != null
                && displayContent.mDisplayContentChangeListeners.getRegisteredCallbackCount() > 0) {
            mH.obtainMessage(H.NOTIFY_RECTANGLE_ON_SCREEN_REQUESTED, displayContent.getDisplayId(),
                    immediate? 1 : 0, new Rect(rectangle)).sendToTarget();
        }
    }

    private void handleNotifyRectangleOnScreenRequested(int displayId, Rect rectangle,
            boolean immediate) {
        RemoteCallbackList<IDisplayContentChangeListener> callbacks = null;
        synchronized (mWindowMap) {
            DisplayContent displayContent = getDisplayContentLocked(displayId);
            if (displayContent == null) {
                return;
            }
            callbacks = displayContent.mDisplayContentChangeListeners;
            if (callbacks == null) {
                return;
            }
        }
        final int callbackCount = callbacks.beginBroadcast();
        try {
            for (int i = 0; i < callbackCount; i++) {
                try {
                    callbacks.getBroadcastItem(i).onRectangleOnScreenRequested(displayId,
                            rectangle, immediate);
                } catch (RemoteException re) {
                    /* ignore */
                }
            }
        } finally {
            callbacks.finishBroadcast();
        }
    }

    public int relayoutWindow(Session session, IWindow client, int seq,
            WindowManager.LayoutParams attrs, int requestedWidth,
            int requestedHeight, int viewVisibility, int flags,
            Rect outFrame, Rect outContentInsets,
            Rect outVisibleInsets, Configuration outConfig, Surface outSurface) {
        boolean toBeDisplayed = false;
        boolean inTouchMode;
        boolean configChanged;
        boolean surfaceChanged = false;
        boolean animating;

        // if they don't have this permission, mask out the status bar bits
        int systemUiVisibility = 0;
        if (attrs != null) {
            systemUiVisibility = (attrs.systemUiVisibility|attrs.subtreeSystemUiVisibility);
            if ((systemUiVisibility & StatusBarManager.DISABLE_MASK) != 0) {
                if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.STATUS_BAR)
                        != PackageManager.PERMISSION_GRANTED) {
                    systemUiVisibility &= ~StatusBarManager.DISABLE_MASK;
                }
            }
        }
        long origId = Binder.clearCallingIdentity();

        synchronized(mWindowMap) {
            // TODO(cmautner): synchronize on mAnimator or win.mWinAnimator.
            WindowState win = windowForClientLocked(session, client, false);
            if (win == null) {
                return 0;
            }
            WindowStateAnimator winAnimator = win.mWinAnimator;
            if (win.mRequestedWidth != requestedWidth
                    || win.mRequestedHeight != requestedHeight) {
                win.mLayoutNeeded = true;
                win.mRequestedWidth = requestedWidth;
                win.mRequestedHeight = requestedHeight;
            }
            if (attrs != null && seq == win.mSeq) {
                win.mSystemUiVisibility = systemUiVisibility;
            }

            if (attrs != null) {
                mPolicy.adjustWindowParamsLw(attrs);
            }

            winAnimator.mSurfaceDestroyDeferred =
                    (flags&WindowManagerGlobal.RELAYOUT_DEFER_SURFACE_DESTROY) != 0;

            int attrChanges = 0;
            int flagChanges = 0;
            if (attrs != null) {
                if (win.mAttrs.type != attrs.type) {
                    throw new IllegalArgumentException(
                            "Window type can not be changed after the window is added.");
                }
                flagChanges = win.mAttrs.flags ^= attrs.flags;
                attrChanges = win.mAttrs.copyFrom(attrs);
                if ((attrChanges & (WindowManager.LayoutParams.LAYOUT_CHANGED
                        | WindowManager.LayoutParams.SYSTEM_UI_VISIBILITY_CHANGED)) != 0) {
                    win.mLayoutNeeded = true;
                }
            }

            if (DEBUG_LAYOUT) Slog.v(TAG, "Relayout " + win + ": viewVisibility=" + viewVisibility
                    + " req=" + requestedWidth + "x" + requestedHeight + " " + win.mAttrs);

            win.mEnforceSizeCompat = (win.mAttrs.flags & FLAG_COMPATIBLE_WINDOW) != 0;

            if ((attrChanges & WindowManager.LayoutParams.ALPHA_CHANGED) != 0) {
                winAnimator.mAlpha = attrs.alpha;
            }

            final boolean scaledWindow =
                ((win.mAttrs.flags & WindowManager.LayoutParams.FLAG_SCALED) != 0);

            if (scaledWindow) {
                // requested{Width|Height} Surface's physical size
                // attrs.{width|height} Size on screen
                win.mHScale = (attrs.width  != requestedWidth)  ?
                        (attrs.width  / (float)requestedWidth) : 1.0f;
                win.mVScale = (attrs.height != requestedHeight) ?
                        (attrs.height / (float)requestedHeight) : 1.0f;
            } else {
                win.mHScale = win.mVScale = 1;
            }

            boolean imMayMove = (flagChanges & (FLAG_ALT_FOCUSABLE_IM | FLAG_NOT_FOCUSABLE)) != 0;

            final boolean isDefaultDisplay = win.isDefaultDisplay();
            boolean focusMayChange = isDefaultDisplay && (win.mViewVisibility != viewVisibility
                    || ((flagChanges & FLAG_NOT_FOCUSABLE) != 0)
                    || (!win.mRelayoutCalled));

            boolean wallpaperMayMove = win.mViewVisibility != viewVisibility
                    && (win.mAttrs.flags & FLAG_SHOW_WALLPAPER) != 0;
            wallpaperMayMove |= (flagChanges & FLAG_SHOW_WALLPAPER) != 0;

            win.mRelayoutCalled = true;
            final int oldVisibility = win.mViewVisibility;
            win.mViewVisibility = viewVisibility;
            if (DEBUG_SCREEN_ON) {
                RuntimeException stack = new RuntimeException();
                stack.fillInStackTrace();
                Slog.i(TAG, "Relayout " + win + ": oldVis=" + oldVisibility
                        + " newVis=" + viewVisibility, stack);
            }
            if (viewVisibility == View.VISIBLE &&
                    (win.mAppToken == null || !win.mAppToken.clientHidden)) {
                toBeDisplayed = !win.isVisibleLw();
                if (win.mExiting) {
                    winAnimator.cancelExitAnimationForNextAnimationLocked();
                    win.mExiting = false;
                }
                if (win.mDestroying) {
                    win.mDestroying = false;
                    mDestroySurface.remove(win);
                }
                if (oldVisibility == View.GONE) {
                    winAnimator.mEnterAnimationPending = true;
                }
                if (toBeDisplayed) {
                    if (win.isDrawnLw() && okToDisplay()) {
                        winAnimator.applyEnterAnimationLocked();
                    }
                    if ((win.mAttrs.flags
                            & WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON) != 0) {
                        if (DEBUG_VISIBILITY) Slog.v(TAG,
                                "Relayout window turning screen on: " + win);
                        win.mTurnOnScreen = true;
                    }
                    if (win.isConfigChanged()) {
                        if (DEBUG_CONFIGURATION) Slog.i(TAG, "Window " + win
                                + " visible with new config: " + win.mConfiguration);
                        outConfig.setTo(mCurConfiguration);
                    }
                }
                if ((attrChanges&WindowManager.LayoutParams.FORMAT_CHANGED) != 0) {
                    // To change the format, we need to re-build the surface.
                    winAnimator.destroySurfaceLocked(false);
                    toBeDisplayed = true;
                    surfaceChanged = true;
                }
                try {
                    if (!win.mHasSurface) {
                        surfaceChanged = true;
                    }
                    Surface surface = winAnimator.createSurfaceLocked();
                    if (surface != null) {
                        outSurface.copyFrom(surface);
                        if (SHOW_TRANSACTIONS) Slog.i(TAG,
                                "  OUT SURFACE " + outSurface + ": copied");
                    } else {
                        // For some reason there isn't a surface.  Clear the
                        // caller's object so they see the same state.
                        outSurface.release();
                    }
                } catch (Exception e) {
                    mInputMonitor.updateInputWindowsLw(true /*force*/);

                    Slog.w(TAG, "Exception thrown when creating surface for client "
                             + client + " (" + win.mAttrs.getTitle() + ")",
                             e);
                    Binder.restoreCallingIdentity(origId);
                    return 0;
                }
                if (toBeDisplayed) {
                    focusMayChange = isDefaultDisplay;
                }
                if (win.mAttrs.type == TYPE_INPUT_METHOD
                        && mInputMethodWindow == null) {
                    mInputMethodWindow = win;
                    imMayMove = true;
                }
                if (win.mAttrs.type == TYPE_BASE_APPLICATION
                        && win.mAppToken != null
                        && win.mAppToken.startingWindow != null) {
                    // Special handling of starting window over the base
                    // window of the app: propagate lock screen flags to it,
                    // to provide the correct semantics while starting.
                    final int mask =
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
                    WindowManager.LayoutParams sa = win.mAppToken.startingWindow.mAttrs;
                    sa.flags = (sa.flags&~mask) | (win.mAttrs.flags&mask);
                }
            } else {
                winAnimator.mEnterAnimationPending = false;
                if (winAnimator.mSurface != null) {
                    if (DEBUG_VISIBILITY) Slog.i(TAG, "Relayout invis " + win
                            + ": mExiting=" + win.mExiting);
                    // If we are not currently running the exit animation, we
                    // need to see about starting one.
                    if (!win.mExiting) {
                        surfaceChanged = true;
                        // Try starting an animation; if there isn't one, we
                        // can destroy the surface right away.
                        int transit = WindowManagerPolicy.TRANSIT_EXIT;
                        if (win.mAttrs.type == TYPE_APPLICATION_STARTING) {
                            transit = WindowManagerPolicy.TRANSIT_PREVIEW_DONE;
                        }
                        if (win.isWinVisibleLw() &&
                                winAnimator.applyAnimationLocked(transit, false)) {
                            focusMayChange = isDefaultDisplay;
                            win.mExiting = true;
                        } else if (win.mWinAnimator.isAnimating()) {
                            // Currently in a hide animation... turn this into
                            // an exit.
                            win.mExiting = true;
                        } else if (win == mWallpaperTarget) {
                            // If the wallpaper is currently behind this
                            // window, we need to change both of them inside
                            // of a transaction to avoid artifacts.
                            win.mExiting = true;
                            win.mWinAnimator.mAnimating = true;
                        } else {
                            if (mInputMethodWindow == win) {
                                mInputMethodWindow = null;
                            }
                            winAnimator.destroySurfaceLocked(false);
                        }
                        scheduleNotifyWindowTranstionIfNeededLocked(win, transit);
                    }
                }

                outSurface.release();
                if (DEBUG_VISIBILITY) Slog.i(TAG, "Releasing surface in: " + win);
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
            boolean assignLayers = false;

            if (imMayMove) {
                if (moveInputMethodWindowsIfNeededLocked(false) || toBeDisplayed) {
                    // Little hack here -- we -should- be able to rely on the
                    // function to return true if the IME has moved and needs
                    // its layer recomputed.  However, if the IME was hidden
                    // and isn't actually moved in the list, its layer may be
                    // out of data so we make sure to recompute it.
                    assignLayers = true;
                }
            }
            if (wallpaperMayMove) {
                if ((adjustWallpaperWindowsLocked()&ADJUST_WALLPAPER_LAYERS_CHANGED) != 0) {
                    assignLayers = true;
                }
            }

            win.mDisplayContent.layoutNeeded = true;
            win.mGivenInsetsPending = (flags&WindowManagerGlobal.RELAYOUT_INSETS_PENDING) != 0;
            if (assignLayers) {
                assignLayersLocked(win.getWindowList());
            }
            configChanged = updateOrientationFromAppTokensLocked(false);
            performLayoutAndPlaceSurfacesLocked();
            if (toBeDisplayed && win.mIsWallpaper) {
                DisplayInfo displayInfo = getDefaultDisplayInfoLocked();
                updateWallpaperOffsetLocked(win,
                        displayInfo.appWidth, displayInfo.appHeight, false);
            }
            if (win.mAppToken != null) {
                win.mAppToken.updateReportedVisibilityLocked();
            }
            outFrame.set(win.mCompatFrame);
            outContentInsets.set(win.mContentInsets);
            outVisibleInsets.set(win.mVisibleInsets);
            if (localLOGV) Slog.v(
                TAG, "Relayout given client " + client.asBinder()
                + ", requestedWidth=" + requestedWidth
                + ", requestedHeight=" + requestedHeight
                + ", viewVisibility=" + viewVisibility
                + "\nRelayout returning frame=" + outFrame
                + ", surface=" + outSurface);

            if (localLOGV || DEBUG_FOCUS) Slog.v(
                TAG, "Relayout of " + win + ": focusMayChange=" + focusMayChange);

            inTouchMode = mInTouchMode;
            animating = mAnimator.mAnimating;
            if (animating && !mRelayoutWhileAnimating.contains(win)) {
                mRelayoutWhileAnimating.add(win);
            }

            mInputMonitor.updateInputWindowsLw(true /*force*/);

            if (DEBUG_LAYOUT) {
                Slog.v(TAG, "Relayout complete " + win + ": outFrame=" + outFrame.toShortString());
            }
        }

        if (configChanged) {
            sendNewConfiguration();
        }

        Binder.restoreCallingIdentity(origId);

        return (inTouchMode ? WindowManagerGlobal.RELAYOUT_RES_IN_TOUCH_MODE : 0)
                | (toBeDisplayed ? WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME : 0)
                | (surfaceChanged ? WindowManagerGlobal.RELAYOUT_RES_SURFACE_CHANGED : 0)
                | (animating ? WindowManagerGlobal.RELAYOUT_RES_ANIMATING : 0);
    }

    public void performDeferredDestroyWindow(Session session, IWindow client) {
        long origId = Binder.clearCallingIdentity();

        try {
            synchronized(mWindowMap) {
                WindowState win = windowForClientLocked(session, client, false);
                if (win == null) {
                    return;
                }
                win.mWinAnimator.destroyDeferredSurfaceLocked(false);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public boolean outOfMemoryWindow(Session session, IWindow client) {
        long origId = Binder.clearCallingIdentity();

        try {
            synchronized(mWindowMap) {
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
        synchronized(mWindowMap) {
            WindowState win = windowForClientLocked(session, client, false);
            if (win != null && win.mWinAnimator.finishDrawingLocked()) {
                if ((win.mAttrs.flags&FLAG_SHOW_WALLPAPER) != 0) {
                    adjustWallpaperWindowsLocked();
                }
                win.mDisplayContent.layoutNeeded = true;
                performLayoutAndPlaceSurfacesLocked();
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public float getWindowCompatibilityScale(IBinder windowToken) {
        if (!checkCallingPermission(android.Manifest.permission.RETRIEVE_WINDOW_INFO,
                "getWindowCompatibilityScale()")) {
            throw new SecurityException("Requires RETRIEVE_WINDOW_INFO permission.");
        }
        synchronized (mWindowMap) {
            WindowState windowState = mWindowMap.get(windowToken);
            return (windowState != null) ? windowState.mGlobalScale : 1.0f;
        }
    }

    @Override
    public WindowInfo getWindowInfo(IBinder token) {
        if (!checkCallingPermission(android.Manifest.permission.RETRIEVE_WINDOW_INFO,
                "getWindowInfo()")) {
            throw new SecurityException("Requires RETRIEVE_WINDOW_INFO permission.");
        }
        synchronized (mWindowMap) {
            WindowState window = mWindowMap.get(token);
            if (window != null) {
                return getWindowInfoForWindowStateLocked(window);
            }
            return null;
        }
    }

    @Override
    public void getVisibleWindowsForDisplay(int displayId, List<WindowInfo> outInfos) {
        if (!checkCallingPermission(android.Manifest.permission.RETRIEVE_WINDOW_INFO,
                "getWindowInfos()")) {
            throw new SecurityException("Requires RETRIEVE_WINDOW_INFO permission.");
        }
        synchronized (mWindowMap) {
            DisplayContent displayContent = getDisplayContentLocked(displayId);
            if (displayContent == null) {
                return;
            }
            WindowList windows = displayContent.getWindowList();
            final int windowCount = windows.size();
            for (int i = 0; i < windowCount; i++) {
                WindowState window = windows.get(i);
                if (window.isVisibleLw() || window.mAttrs.type == TYPE_UNIVERSE_BACKGROUND) {
                    WindowInfo info = getWindowInfoForWindowStateLocked(window);
                    outInfos.add(info);
                }
            }
        }
    }

    @Override
    public void magnifyDisplay(int displayId, float scale, float offsetX, float offsetY) {
        if (!checkCallingPermission(
                android.Manifest.permission.MAGNIFY_DISPLAY, "magnifyDisplay()")) {
            throw new SecurityException("Requires MAGNIFY_DISPLAY permission");
        }
        synchronized (mWindowMap) {
            MagnificationSpec spec = getDisplayMagnificationSpecLocked(displayId);
            if (spec != null) {
                final boolean scaleChanged = spec.mScale != scale;
                final boolean offsetChanged = spec.mOffsetX != offsetX || spec.mOffsetY != offsetY;
                if (!scaleChanged && !offsetChanged) {
                    return;
                }
                spec.initialize(scale, offsetX, offsetY);
                // If the offset has changed we need to re-add the input windows
                // since the offsets have to be propagated to the input system.
                if (offsetChanged) {
                    // TODO(multidisplay): Input only occurs on the default display.
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        mInputMonitor.updateInputWindowsLw(true);
                    }
                }
                scheduleAnimationLocked();
            }
        }
    }

    MagnificationSpec getDisplayMagnificationSpecLocked(int displayId) {
        DisplayContent displayContent = getDisplayContentLocked(displayId);
        if (displayContent != null) {
            if (displayContent.mMagnificationSpec == null) {
                displayContent.mMagnificationSpec = new MagnificationSpec();
            }
            return displayContent.mMagnificationSpec;
        }
        return null;
    }

    private WindowInfo getWindowInfoForWindowStateLocked(WindowState window) {
        WindowInfo info = WindowInfo.obtain();
        info.token = window.mToken.token;
        info.frame.set(window.mFrame);
        info.type = window.mAttrs.type;
        info.displayId = window.getDisplayId();
        info.compatibilityScale = window.mGlobalScale;
        info.visible = window.isVisibleLw() || info.type == TYPE_UNIVERSE_BACKGROUND;
        info.layer = window.mLayer;
        window.getTouchableRegion(mTempRegion);
        mTempRegion.getBounds(info.touchableRegion);
        return info;
    }

    private AttributeCache.Entry getCachedAnimations(WindowManager.LayoutParams lp) {
        if (DEBUG_ANIM) Slog.v(TAG, "Loading animations: layout params pkg="
                + (lp != null ? lp.packageName : null)
                + " resId=0x" + (lp != null ? Integer.toHexString(lp.windowAnimations) : null));
        if (lp != null && lp.windowAnimations != 0) {
            // If this is a system resource, don't try to load it from the
            // application resources.  It is nice to avoid loading application
            // resources if we can.
            String packageName = lp.packageName != null ? lp.packageName : "android";
            int resId = lp.windowAnimations;
            if ((resId&0xFF000000) == 0x01000000) {
                packageName = "android";
            }
            if (DEBUG_ANIM) Slog.v(TAG, "Loading animations: picked package="
                    + packageName);
            return AttributeCache.instance().get(packageName, resId,
                    com.android.internal.R.styleable.WindowAnimation);
        }
        return null;
    }

    private AttributeCache.Entry getCachedAnimations(String packageName, int resId) {
        if (DEBUG_ANIM) Slog.v(TAG, "Loading animations: package="
                + packageName + " resId=0x" + Integer.toHexString(resId));
        if (packageName != null) {
            if ((resId&0xFF000000) == 0x01000000) {
                packageName = "android";
            }
            if (DEBUG_ANIM) Slog.v(TAG, "Loading animations: picked package="
                    + packageName);
            return AttributeCache.instance().get(packageName, resId,
                    com.android.internal.R.styleable.WindowAnimation);
        }
        return null;
    }

    Animation loadAnimation(WindowManager.LayoutParams lp, int animAttr) {
        int anim = 0;
        Context context = mContext;
        if (animAttr >= 0) {
            AttributeCache.Entry ent = getCachedAnimations(lp);
            if (ent != null) {
                context = ent.context;
                anim = ent.array.getResourceId(animAttr, 0);
            }
        }
        if (anim != 0) {
            return AnimationUtils.loadAnimation(context, anim);
        }
        return null;
    }

    private Animation loadAnimation(String packageName, int resId) {
        int anim = 0;
        Context context = mContext;
        if (resId >= 0) {
            AttributeCache.Entry ent = getCachedAnimations(packageName, resId);
            if (ent != null) {
                context = ent.context;
                anim = resId;
            }
        }
        if (anim != 0) {
            return AnimationUtils.loadAnimation(context, anim);
        }
        return null;
    }

    private Animation createExitAnimationLocked(int transit, int duration) {
        if (transit == WindowManagerPolicy.TRANSIT_WALLPAPER_INTRA_OPEN ||
                transit == WindowManagerPolicy.TRANSIT_WALLPAPER_INTRA_CLOSE) {
            // If we are on top of the wallpaper, we need an animation that
            // correctly handles the wallpaper staying static behind all of
            // the animated elements.  To do this, will just have the existing
            // element fade out.
            Animation a = new AlphaAnimation(1, 0);
            a.setDetachWallpaper(true);
            a.setDuration(duration);
            return a;
        }
        // For normal animations, the exiting element just holds in place.
        Animation a = new AlphaAnimation(1, 1);
        a.setDuration(duration);
        return a;
    }

    /**
     * Compute the pivot point for an animation that is scaling from a small
     * rect on screen to a larger rect.  The pivot point varies depending on
     * the distance between the inner and outer edges on both sides.  This
     * function computes the pivot point for one dimension.
     * @param startPos  Offset from left/top edge of outer rectangle to
     * left/top edge of inner rectangle.
     * @param finalScale The scaling factor between the size of the outer
     * and inner rectangles.
     */
    private static float computePivot(int startPos, float finalScale) {
        final float denom = finalScale-1;
        if (Math.abs(denom) < .0001f) {
            return startPos;
        }
        return -startPos / denom;
    }

    private Animation createScaleUpAnimationLocked(int transit, boolean enter) {
        Animation a;
        // Pick the desired duration.  If this is an inter-activity transition,
        // it  is the standard duration for that.  Otherwise we use the longer
        // task transition duration.
        int duration;
        switch (transit) {
            case WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN:
            case WindowManagerPolicy.TRANSIT_ACTIVITY_CLOSE:
                duration = mContext.getResources().getInteger(
                        com.android.internal.R.integer.config_shortAnimTime);
                break;
            default:
                duration = 300;
                break;
        }
        // TODO(multidisplay): For now assume all app animation is on main display.
        final DisplayInfo displayInfo = getDefaultDisplayInfoLocked();
        if (enter) {
            // Entering app zooms out from the center of the initial rect.
            float scaleW = mNextAppTransitionStartWidth / (float) displayInfo.appWidth;
            float scaleH = mNextAppTransitionStartHeight / (float) displayInfo.appHeight;
            Animation scale = new ScaleAnimation(scaleW, 1, scaleH, 1,
                    computePivot(mNextAppTransitionStartX, scaleW),
                    computePivot(mNextAppTransitionStartY, scaleH));
            scale.setDuration(duration);
            AnimationSet set = new AnimationSet(true);
            Animation alpha = new AlphaAnimation(0, 1);
            scale.setDuration(duration);
            set.addAnimation(scale);
            alpha.setDuration(duration);
            set.addAnimation(alpha);
            set.setDetachWallpaper(true);
            a = set;
        } else {
            a = createExitAnimationLocked(transit, duration);
        }
        a.setFillAfter(true);
        final Interpolator interpolator = AnimationUtils.loadInterpolator(mContext,
                com.android.internal.R.interpolator.decelerate_cubic);
        a.setInterpolator(interpolator);
        a.initialize(displayInfo.appWidth, displayInfo.appHeight,
                displayInfo.appWidth, displayInfo.appHeight);
        return a;
    }

    private Animation createThumbnailAnimationLocked(int transit,
            boolean enter, boolean thumb, boolean scaleUp) {
        Animation a;
        final int thumbWidthI = mNextAppTransitionThumbnail.getWidth();
        final float thumbWidth = thumbWidthI > 0 ? thumbWidthI : 1;
        final int thumbHeightI = mNextAppTransitionThumbnail.getHeight();
        final float thumbHeight = thumbHeightI > 0 ? thumbHeightI : 1;
        // Pick the desired duration.  If this is an inter-activity transition,
        // it  is the standard duration for that.  Otherwise we use the longer
        // task transition duration.
        int duration;
        switch (transit) {
            case WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN:
            case WindowManagerPolicy.TRANSIT_ACTIVITY_CLOSE:
                duration = mContext.getResources().getInteger(
                        com.android.internal.R.integer.config_shortAnimTime);
                break;
            default:
                duration = 250;
                break;
        }
        // TOOD(multidisplay): For now assume all app animation is on the main screen.
        DisplayInfo displayInfo = getDefaultDisplayInfoLocked();
        if (thumb) {
            // Animation for zooming thumbnail from its initial size to
            // filling the screen.
            if (scaleUp) {
                float scaleW = displayInfo.appWidth / thumbWidth;
                float scaleH = displayInfo.appHeight / thumbHeight;

                Animation scale = new ScaleAnimation(1, scaleW, 1, scaleH,
                        computePivot(mNextAppTransitionStartX, 1 / scaleW),
                        computePivot(mNextAppTransitionStartY, 1 / scaleH));
                AnimationSet set = new AnimationSet(true);
                Animation alpha = new AlphaAnimation(1, 0);
                scale.setDuration(duration);
                scale.setInterpolator(
                        new DecelerateInterpolator(THUMBNAIL_ANIMATION_DECELERATE_FACTOR));
                set.addAnimation(scale);
                alpha.setDuration(duration);
                set.addAnimation(alpha);
                set.setFillBefore(true);
                a = set;
            } else {
                float scaleW = displayInfo.appWidth / thumbWidth;
                float scaleH = displayInfo.appHeight / thumbHeight;

                Animation scale = new ScaleAnimation(scaleW, 1, scaleH, 1,
                        computePivot(mNextAppTransitionStartX, 1 / scaleW),
                        computePivot(mNextAppTransitionStartY, 1 / scaleH));
                AnimationSet set = new AnimationSet(true);
                Animation alpha = new AlphaAnimation(1, 1);
                scale.setDuration(duration);
                scale.setInterpolator(
                        new DecelerateInterpolator(THUMBNAIL_ANIMATION_DECELERATE_FACTOR));
                set.addAnimation(scale);
                alpha.setDuration(duration);
                set.addAnimation(alpha);
                set.setFillBefore(true);

                a = set;
            }
        } else if (enter) {
            // Entering app zooms out from the center of the thumbnail.
            if (scaleUp) {
                float scaleW = thumbWidth / displayInfo.appWidth;
                float scaleH = thumbHeight / displayInfo.appHeight;
                Animation scale = new ScaleAnimation(scaleW, 1, scaleH, 1,
                        computePivot(mNextAppTransitionStartX, scaleW),
                        computePivot(mNextAppTransitionStartY, scaleH));
                scale.setDuration(duration);
                scale.setInterpolator(
                        new DecelerateInterpolator(THUMBNAIL_ANIMATION_DECELERATE_FACTOR));
                scale.setFillBefore(true);
                a = scale;
            } else {
                // noop animation
                a = new AlphaAnimation(1, 1);
                a.setDuration(duration);
            }
        } else {
            // Exiting app
            if (scaleUp) {
                if (transit == WindowManagerPolicy.TRANSIT_WALLPAPER_INTRA_OPEN) {
                    // Fade out while bringing up selected activity. This keeps the
                    // current activity from showing through a launching wallpaper
                    // activity.
                    a = new AlphaAnimation(1, 0);
                } else {
                    // noop animation
                    a = new AlphaAnimation(1, 1);
                }
                a.setDuration(duration);
            } else {
                float scaleW = thumbWidth / displayInfo.appWidth;
                float scaleH = thumbHeight / displayInfo.appHeight;
                Animation scale = new ScaleAnimation(1, scaleW, 1, scaleH,
                        computePivot(mNextAppTransitionStartX, scaleW),
                        computePivot(mNextAppTransitionStartY, scaleH));
                scale.setDuration(duration);
                scale.setInterpolator(
                        new DecelerateInterpolator(THUMBNAIL_ANIMATION_DECELERATE_FACTOR));
                scale.setFillBefore(true);
                AnimationSet set = new AnimationSet(true);
                Animation alpha = new AlphaAnimation(1, 0);
                set.addAnimation(scale);
                alpha.setDuration(duration);
                alpha.setInterpolator(new DecelerateInterpolator(
                        THUMBNAIL_ANIMATION_DECELERATE_FACTOR));
                set.addAnimation(alpha);
                set.setFillBefore(true);
                set.setZAdjustment(Animation.ZORDER_TOP);
                a = set;
            }
        }
        a.setFillAfter(true);
        final Interpolator interpolator = AnimationUtils.loadInterpolator(mContext,
                com.android.internal.R.interpolator.decelerate_quad);
        a.setInterpolator(interpolator);
        a.initialize(displayInfo.appWidth, displayInfo.appHeight,
                displayInfo.appWidth, displayInfo.appHeight);
        return a;
    }

    private boolean applyAnimationLocked(AppWindowToken atoken,
            WindowManager.LayoutParams lp, int transit, boolean enter) {
        // Only apply an animation if the display isn't frozen.  If it is
        // frozen, there is no reason to animate and it can cause strange
        // artifacts when we unfreeze the display if some different animation
        // is running.
        if (okToDisplay()) {
            Animation a;
            boolean initialized = false;
            if (mNextAppTransitionType == ActivityOptions.ANIM_CUSTOM) {
                a = loadAnimation(mNextAppTransitionPackage, enter ?
                        mNextAppTransitionEnter : mNextAppTransitionExit);
                if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG,
                        "applyAnimation: atoken=" + atoken
                        + " anim=" + a + " nextAppTransition=ANIM_CUSTOM"
                        + " transit=" + transit + " isEntrance=" + enter
                        + " Callers=" + Debug.getCallers(3));
            } else if (mNextAppTransitionType == ActivityOptions.ANIM_SCALE_UP) {
                a = createScaleUpAnimationLocked(transit, enter);
                initialized = true;
                if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG,
                        "applyAnimation: atoken=" + atoken
                        + " anim=" + a + " nextAppTransition=ANIM_SCALE_UP"
                        + " transit=" + transit + " isEntrance=" + enter
                        + " Callers=" + Debug.getCallers(3));
            } else if (mNextAppTransitionType == ActivityOptions.ANIM_THUMBNAIL_SCALE_UP ||
                    mNextAppTransitionType == ActivityOptions.ANIM_THUMBNAIL_SCALE_DOWN) {
                boolean scaleUp = (mNextAppTransitionType == ActivityOptions.ANIM_THUMBNAIL_SCALE_UP);
                a = createThumbnailAnimationLocked(transit, enter, false, scaleUp);
                initialized = true;
                if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) {
                    String animName = scaleUp ? "ANIM_THUMBNAIL_SCALE_UP" : "ANIM_THUMBNAIL_SCALE_DOWN";
                    Slog.v(TAG, "applyAnimation: atoken=" + atoken
                            + " anim=" + a + " nextAppTransition=" + animName
                            + " transit=" + transit + " isEntrance=" + enter
                            + " Callers=" + Debug.getCallers(3));
                }
            } else {
                int animAttr = 0;
                switch (transit) {
                    case WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN:
                        animAttr = enter
                                ? com.android.internal.R.styleable.WindowAnimation_activityOpenEnterAnimation
                                : com.android.internal.R.styleable.WindowAnimation_activityOpenExitAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_ACTIVITY_CLOSE:
                        animAttr = enter
                                ? com.android.internal.R.styleable.WindowAnimation_activityCloseEnterAnimation
                                : com.android.internal.R.styleable.WindowAnimation_activityCloseExitAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_TASK_OPEN:
                        animAttr = enter
                                ? com.android.internal.R.styleable.WindowAnimation_taskOpenEnterAnimation
                                : com.android.internal.R.styleable.WindowAnimation_taskOpenExitAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_TASK_CLOSE:
                        animAttr = enter
                                ? com.android.internal.R.styleable.WindowAnimation_taskCloseEnterAnimation
                                : com.android.internal.R.styleable.WindowAnimation_taskCloseExitAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_TASK_TO_FRONT:
                        animAttr = enter
                                ? com.android.internal.R.styleable.WindowAnimation_taskToFrontEnterAnimation
                                : com.android.internal.R.styleable.WindowAnimation_taskToFrontExitAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_TASK_TO_BACK:
                        animAttr = enter
                                ? com.android.internal.R.styleable.WindowAnimation_taskToBackEnterAnimation
                                : com.android.internal.R.styleable.WindowAnimation_taskToBackExitAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_WALLPAPER_OPEN:
                        animAttr = enter
                                ? com.android.internal.R.styleable.WindowAnimation_wallpaperOpenEnterAnimation
                                : com.android.internal.R.styleable.WindowAnimation_wallpaperOpenExitAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_WALLPAPER_CLOSE:
                        animAttr = enter
                                ? com.android.internal.R.styleable.WindowAnimation_wallpaperCloseEnterAnimation
                                : com.android.internal.R.styleable.WindowAnimation_wallpaperCloseExitAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_WALLPAPER_INTRA_OPEN:
                        animAttr = enter
                                ? com.android.internal.R.styleable.WindowAnimation_wallpaperIntraOpenEnterAnimation
                                : com.android.internal.R.styleable.WindowAnimation_wallpaperIntraOpenExitAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_WALLPAPER_INTRA_CLOSE:
                        animAttr = enter
                                ? com.android.internal.R.styleable.WindowAnimation_wallpaperIntraCloseEnterAnimation
                                : com.android.internal.R.styleable.WindowAnimation_wallpaperIntraCloseExitAnimation;
                        break;
                }
                a = animAttr != 0 ? loadAnimation(lp, animAttr) : null;
                if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG,
                        "applyAnimation: atoken=" + atoken
                        + " anim=" + a
                        + " animAttr=0x" + Integer.toHexString(animAttr)
                        + " transit=" + transit + " isEntrance=" + enter
                        + " Callers=" + Debug.getCallers(3));
            }
            if (a != null) {
                if (DEBUG_ANIM) {
                    RuntimeException e = null;
                    if (!HIDE_STACK_CRAWLS) {
                        e = new RuntimeException();
                        e.fillInStackTrace();
                    }
                    Slog.v(TAG, "Loaded animation " + a + " for " + atoken, e);
                }
                atoken.mAppAnimator.setAnimation(a, initialized);
            }
        } else {
            atoken.mAppAnimator.clearAnimation();
        }

        return atoken.mAppAnimator.animation != null;
    }

    // -------------------------------------------------------------
    // Application Window Tokens
    // -------------------------------------------------------------

    public void validateAppTokens(List<IBinder> tokens) {
        int v = tokens.size()-1;
        int m = mAppTokens.size()-1;
        while (v >= 0 && m >= 0) {
            AppWindowToken atoken = mAppTokens.get(m);
            if (atoken.removed) {
                m--;
                continue;
            }
            if (tokens.get(v) != atoken.token) {
                Slog.w(TAG, "Tokens out of sync: external is " + tokens.get(v)
                      + " @ " + v + ", internal is " + atoken.token + " @ " + m);
            }
            v--;
            m--;
        }
        while (v >= 0) {
            Slog.w(TAG, "External token not found: " + tokens.get(v) + " @ " + v);
            v--;
        }
        while (m >= 0) {
            AppWindowToken atoken = mAppTokens.get(m);
            if (!atoken.removed) {
                Slog.w(TAG, "Invalid internal atoken: " + atoken.token + " @ " + m);
            }
            m--;
        }
    }

    boolean checkCallingPermission(String permission, String func) {
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
        Slog.w(TAG, msg);
        return false;
    }

    boolean okToDisplay() {
        return !mDisplayFrozen && mDisplayEnabled && mPolicy.isScreenOnFully();
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
                Slog.w(TAG, "Attempted to add existing input method token: " + token);
                return;
            }
            wtoken = new WindowToken(this, token, type, true);
            mTokenMap.put(token, wtoken);
            if (type == TYPE_WALLPAPER) {
                mWallpaperTokens.add(wtoken);
                updateLayoutToAnimWallpaperTokens();
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
            WindowToken wtoken = mTokenMap.remove(token);
            if (wtoken != null) {
                boolean delayed = false;
                if (!wtoken.hidden) {
                    final int N = wtoken.windows.size();
                    boolean changed = false;

                    for (int i=0; i<N; i++) {
                        WindowState win = wtoken.windows.get(i);

                        if (win.mWinAnimator.isAnimating()) {
                            delayed = true;
                        }

                        if (win.isVisibleNow()) {
                            win.mWinAnimator.applyAnimationLocked(WindowManagerPolicy.TRANSIT_EXIT,
                                    false);
                            scheduleNotifyWindowTranstionIfNeededLocked(win,
                                    WindowManagerPolicy.TRANSIT_EXIT);
                            changed = true;
                            win.mDisplayContent.layoutNeeded = true;
                        }
                    }

                    wtoken.hidden = true;

                    if (changed) {
                        performLayoutAndPlaceSurfacesLocked();
                        updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL,
                                false /*updateInputWindows*/);
                    }

                    if (delayed) {
                        mExitingTokens.add(wtoken);
                    } else if (wtoken.windowType == TYPE_WALLPAPER) {
                        mWallpaperTokens.remove(wtoken);
                        updateLayoutToAnimWallpaperTokens();
                    }
                }

                mInputMonitor.updateInputWindowsLw(true /*force*/);
            } else {
                Slog.w(TAG, "Attempted to remove non-existing token: " + token);
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    /**
     *  Find the location to insert a new AppWindowToken into the window-ordered app token list.
     *  Note that mAppTokens.size() == mAnimatingAppTokens.size() + 1.
     * @param addPos The location the token was inserted into in mAppTokens.
     * @param atoken The token to insert.
     */
    private void addAppTokenToAnimating(final int addPos, final AppWindowToken atoken) {
        if (addPos == 0 || addPos == mAnimatingAppTokens.size()) {
            // It was inserted into the beginning or end of mAppTokens. Honor that.
            mAnimatingAppTokens.add(addPos, atoken);
            return;
        }
        // Find the item immediately above the mAppTokens insertion point and put the token
        // immediately below that one in mAnimatingAppTokens.
        final AppWindowToken aboveAnchor = mAppTokens.get(addPos + 1);
        mAnimatingAppTokens.add(mAnimatingAppTokens.indexOf(aboveAnchor), atoken);
    }

    @Override
    public void addAppToken(int addPos, IApplicationToken token,
            int groupId, int requestedOrientation, boolean fullscreen, boolean showWhenLocked) {
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
            Slog.w(TAG, "Could not get dispatching timeout.", ex);
            inputDispatchingTimeoutNanos = DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;
        }

        synchronized(mWindowMap) {
            AppWindowToken atoken = findAppWindowToken(token.asBinder());
            if (atoken != null) {
                Slog.w(TAG, "Attempted to add existing app token: " + token);
                return;
            }
            atoken = new AppWindowToken(this, token);
            atoken.inputDispatchingTimeoutNanos = inputDispatchingTimeoutNanos;
            atoken.groupId = groupId;
            atoken.appFullscreen = fullscreen;
            atoken.showWhenLocked = showWhenLocked;
            atoken.requestedOrientation = requestedOrientation;
            if (DEBUG_TOKEN_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(TAG, "addAppToken: " + atoken
                    + " at " + addPos);
            mAppTokens.add(addPos, atoken);
            addAppTokenToAnimating(addPos, atoken);
            mTokenMap.put(token.asBinder(), atoken);

            // Application tokens start out hidden.
            atoken.hidden = true;
            atoken.hiddenRequested = true;

            //dump();
        }
    }

    @Override
    public void setAppGroupId(IBinder token, int groupId) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppGroupId()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            AppWindowToken atoken = findAppWindowToken(token);
            if (atoken == null) {
                Slog.w(TAG, "Attempted to set group id of non-existing app token: " + token);
                return;
            }
            atoken.groupId = groupId;
        }
    }

    public int getOrientationFromWindowsLocked() {
        if (mDisplayFrozen || mOpeningApps.size() > 0 || mClosingApps.size() > 0) {
            // If the display is frozen, some activities may be in the middle
            // of restarting, and thus have removed their old window.  If the
            // window has the flag to hide the lock screen, then the lock screen
            // can re-appear and inflict its own orientation on us.  Keep the
            // orientation stable until this all settles down.
            return mLastWindowForcedOrientation;
        }

        // TODO(multidisplay): Change to the correct display.
        final WindowList windows = getDefaultWindowListLocked();
        int pos = windows.size() - 1;
        while (pos >= 0) {
            WindowState wtoken = windows.get(pos);
            pos--;
            if (wtoken.mAppToken != null) {
                // We hit an application window. so the orientation will be determined by the
                // app window. No point in continuing further.
                return (mLastWindowForcedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
            if (!wtoken.isVisibleLw() || !wtoken.mPolicyVisibilityAfterAnim) {
                continue;
            }
            int req = wtoken.mAttrs.screenOrientation;
            if((req == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) ||
                    (req == ActivityInfo.SCREEN_ORIENTATION_BEHIND)){
                continue;
            }

            return (mLastWindowForcedOrientation=req);
        }
        return (mLastWindowForcedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    public int getOrientationFromAppTokensLocked() {
        int curGroup = 0;
        int lastOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        boolean findingBehind = false;
        boolean haveGroup = false;
        boolean lastFullscreen = false;
        for (int pos = mAppTokens.size() - 1; pos >= 0; pos--) {
            AppWindowToken atoken = mAppTokens.get(pos);

            if (DEBUG_APP_ORIENTATION) Slog.v(TAG, "Checking app orientation: " + atoken);

            // if we're about to tear down this window and not seek for
            // the behind activity, don't use it for orientation
            if (!findingBehind
                    && (!atoken.hidden && atoken.hiddenRequested)) {
                if (DEBUG_ORIENTATION) Slog.v(TAG, "Skipping " + atoken
                        + " -- going to hide");
                continue;
            }

            if (haveGroup == true && curGroup != atoken.groupId) {
                // If we have hit a new application group, and the bottom
                // of the previous group didn't explicitly say to use
                // the orientation behind it, and the last app was
                // full screen, then we'll stick with the
                // user's orientation.
                if (lastOrientation != ActivityInfo.SCREEN_ORIENTATION_BEHIND
                        && lastFullscreen) {
                    if (DEBUG_ORIENTATION) Slog.v(TAG, "Done at " + atoken
                            + " -- end of group, return " + lastOrientation);
                    return lastOrientation;
                }
            }

            // We ignore any hidden applications on the top.
            if (atoken.hiddenRequested || atoken.willBeHidden) {
                if (DEBUG_ORIENTATION) Slog.v(TAG, "Skipping " + atoken
                        + " -- hidden on top");
                continue;
            }

            if (!haveGroup) {
                haveGroup = true;
                curGroup = atoken.groupId;
                lastOrientation = atoken.requestedOrientation;
            }

            int or = atoken.requestedOrientation;
            // If this application is fullscreen, and didn't explicitly say
            // to use the orientation behind it, then just take whatever
            // orientation it has and ignores whatever is under it.
            lastFullscreen = atoken.appFullscreen;
            if (lastFullscreen
                    && or != ActivityInfo.SCREEN_ORIENTATION_BEHIND) {
                if (DEBUG_ORIENTATION) Slog.v(TAG, "Done at " + atoken
                        + " -- full screen, return " + or);
                return or;
            }
            // If this application has requested an explicit orientation,
            // then use it.
            if (or != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    && or != ActivityInfo.SCREEN_ORIENTATION_BEHIND) {
                if (DEBUG_ORIENTATION) Slog.v(TAG, "Done at " + atoken
                        + " -- explicitly set, return " + or);
                return or;
            }
            findingBehind |= (or == ActivityInfo.SCREEN_ORIENTATION_BEHIND);
        }
        if (DEBUG_ORIENTATION) Slog.v(TAG, "No app is requesting an orientation");
        return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
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
        Configuration config = null;

        if (updateOrientationFromAppTokensLocked(false)) {
            if (freezeThisOneIfNeeded != null) {
                AppWindowToken atoken = findAppWindowToken(
                        freezeThisOneIfNeeded);
                if (atoken != null) {
                    startAppFreezingScreenLocked(atoken,
                            ActivityInfo.CONFIG_ORIENTATION);
                }
            }
            config = computeNewConfigurationLocked();

        } else if (currentConfig != null) {
            // No obvious action we need to take, but if our current
            // state mismatches the activity manager's, update it,
            // disregarding font scale, which should remain set to
            // the value of the previous configuration.
            mTempConfiguration.setToDefaults();
            mTempConfiguration.fontScale = currentConfig.fontScale;
            if (computeScreenConfigurationLocked(mTempConfiguration)) {
                if (currentConfig.diff(mTempConfiguration) != 0) {
                    mWaitingForConfig = true;
                    getDefaultDisplayContentLocked().layoutNeeded = true;
                    startFreezingDisplayLocked(false, 0, 0);
                    config = new Configuration(mTempConfiguration);
                }
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
            int req = computeForcedAppOrientationLocked();

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

    int computeForcedAppOrientationLocked() {
        int req = getOrientationFromWindowsLocked();
        if (req == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            req = getOrientationFromAppTokensLocked();
        }
        return req;
    }

    @Override
    public void setNewConfiguration(Configuration config) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setNewConfiguration()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            mCurConfiguration = new Configuration(config);
            mWaitingForConfig = false;
            performLayoutAndPlaceSurfacesLocked();
        }
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
                Slog.w(TAG, "Attempted to set orientation of non-existing app token: " + token);
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

    @Override
    public void setFocusedApp(IBinder token, boolean moveFocusNow) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setFocusedApp()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            boolean changed = false;
            if (token == null) {
                if (DEBUG_FOCUS) Slog.v(TAG, "Clearing focused app, was " + mFocusedApp);
                changed = mFocusedApp != null;
                mFocusedApp = null;
                if (changed) {
                    mInputMonitor.setFocusedAppLw(null);
                }
            } else {
                AppWindowToken newFocus = findAppWindowToken(token);
                if (newFocus == null) {
                    Slog.w(TAG, "Attempted to set focus to non-existing app token: " + token);
                    return;
                }
                changed = mFocusedApp != newFocus;
                mFocusedApp = newFocus;
                if (DEBUG_FOCUS) Slog.v(TAG, "Set focused app to: " + mFocusedApp
                        + " moveFocusNow=" + moveFocusNow);
                if (changed) {
                    mInputMonitor.setFocusedAppLw(newFocus);
                }
            }

            if (moveFocusNow && changed) {
                final long origId = Binder.clearCallingIdentity();
                updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, true /*updateInputWindows*/);
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public void prepareAppTransition(int transit, boolean alwaysKeepCurrent) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "prepareAppTransition()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            if (DEBUG_APP_TRANSITIONS) Slog.v(
                    TAG, "Prepare app transition: transit=" + transit
                    + " mNextAppTransition=" + mNextAppTransition
                    + " alwaysKeepCurrent=" + alwaysKeepCurrent
                    + " Callers=" + Debug.getCallers(3));
            if (okToDisplay()) {
                if (mNextAppTransition == WindowManagerPolicy.TRANSIT_UNSET
                        || mNextAppTransition == WindowManagerPolicy.TRANSIT_NONE) {
                    mNextAppTransition = transit;
                } else if (!alwaysKeepCurrent) {
                    if (transit == WindowManagerPolicy.TRANSIT_TASK_OPEN
                            && mNextAppTransition == WindowManagerPolicy.TRANSIT_TASK_CLOSE) {
                        // Opening a new task always supersedes a close for the anim.
                        mNextAppTransition = transit;
                    } else if (transit == WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN
                            && mNextAppTransition == WindowManagerPolicy.TRANSIT_ACTIVITY_CLOSE) {
                        // Opening a new activity always supersedes a close for the anim.
                        mNextAppTransition = transit;
                    }
                }
                mAppTransitionReady = false;
                mAppTransitionTimeout = false;
                mStartingIconInTransition = false;
                mSkipAppTransitionAnimation = false;
                mH.removeMessages(H.APP_TRANSITION_TIMEOUT);
                mH.sendMessageDelayed(mH.obtainMessage(H.APP_TRANSITION_TIMEOUT),
                        5000);
            }
        }
    }

    @Override
    public int getPendingAppTransition() {
        return mNextAppTransition;
    }

    private void scheduleAnimationCallback(IRemoteCallback cb) {
        if (cb != null) {
            mH.sendMessage(mH.obtainMessage(H.DO_ANIMATION_CALLBACK, cb));
        }
    }

    @Override
    public void overridePendingAppTransition(String packageName,
            int enterAnim, int exitAnim, IRemoteCallback startedCallback) {
        synchronized(mWindowMap) {
            if (mNextAppTransition != WindowManagerPolicy.TRANSIT_UNSET) {
                mNextAppTransitionType = ActivityOptions.ANIM_CUSTOM;
                mNextAppTransitionPackage = packageName;
                mNextAppTransitionThumbnail = null;
                mNextAppTransitionEnter = enterAnim;
                mNextAppTransitionExit = exitAnim;
                scheduleAnimationCallback(mNextAppTransitionCallback);
                mNextAppTransitionCallback = startedCallback;
            } else {
                scheduleAnimationCallback(startedCallback);
            }
        }
    }

    @Override
    public void overridePendingAppTransitionScaleUp(int startX, int startY, int startWidth,
            int startHeight) {
        synchronized(mWindowMap) {
            if (mNextAppTransition != WindowManagerPolicy.TRANSIT_UNSET) {
                mNextAppTransitionType = ActivityOptions.ANIM_SCALE_UP;
                mNextAppTransitionPackage = null;
                mNextAppTransitionThumbnail = null;
                mNextAppTransitionStartX = startX;
                mNextAppTransitionStartY = startY;
                mNextAppTransitionStartWidth = startWidth;
                mNextAppTransitionStartHeight = startHeight;
                scheduleAnimationCallback(mNextAppTransitionCallback);
                mNextAppTransitionCallback = null;
            }
        }
    }

    @Override
    public void overridePendingAppTransitionThumb(Bitmap srcThumb, int startX,
            int startY, IRemoteCallback startedCallback, boolean scaleUp) {
        synchronized(mWindowMap) {
            if (mNextAppTransition != WindowManagerPolicy.TRANSIT_UNSET) {
                mNextAppTransitionType = scaleUp
                        ? ActivityOptions.ANIM_THUMBNAIL_SCALE_UP : ActivityOptions.ANIM_THUMBNAIL_SCALE_DOWN;
                mNextAppTransitionPackage = null;
                mNextAppTransitionThumbnail = srcThumb;
                mNextAppTransitionScaleUp = scaleUp;
                mNextAppTransitionStartX = startX;
                mNextAppTransitionStartY = startY;
                scheduleAnimationCallback(mNextAppTransitionCallback);
                mNextAppTransitionCallback = startedCallback;
            } else {
                scheduleAnimationCallback(startedCallback);
            }
        }
    }

    @Override
    public void executeAppTransition() {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "executeAppTransition()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            if (DEBUG_APP_TRANSITIONS) {
                RuntimeException e = new RuntimeException("here");
                e.fillInStackTrace();
                Slog.w(TAG, "Execute app transition: mNextAppTransition="
                        + mNextAppTransition, e);
            }
            if (mNextAppTransition != WindowManagerPolicy.TRANSIT_UNSET) {
                mAppTransitionReady = true;
                final long origId = Binder.clearCallingIdentity();
                performLayoutAndPlaceSurfacesLocked();
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public void setAppStartingWindow(IBinder token, String pkg,
            int theme, CompatibilityInfo compatInfo,
            CharSequence nonLocalizedLabel, int labelRes, int icon,
            int windowFlags, IBinder transferFrom, boolean createIfNeeded) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppStartingWindow()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            if (DEBUG_STARTING_WINDOW) Slog.v(
                    TAG, "setAppStartingWindow: token=" + token + " pkg=" + pkg
                    + " transferFrom=" + transferFrom);

            AppWindowToken wtoken = findAppWindowToken(token);
            if (wtoken == null) {
                Slog.w(TAG, "Attempted to set icon of non-existing app token: " + token);
                return;
            }

            // If the display is frozen, we won't do anything until the
            // actual window is displayed so there is no reason to put in
            // the starting window.
            if (!okToDisplay()) {
                return;
            }

            if (wtoken.startingData != null) {
                return;
            }

            if (transferFrom != null) {
                AppWindowToken ttoken = findAppWindowToken(transferFrom);
                if (ttoken != null) {
                    WindowState startingWindow = ttoken.startingWindow;
                    if (startingWindow != null) {
                        if (mStartingIconInTransition) {
                            // In this case, the starting icon has already
                            // been displayed, so start letting windows get
                            // shown immediately without any more transitions.
                            mSkipAppTransitionAnimation = true;
                        }
                        if (DEBUG_STARTING_WINDOW) Slog.v(TAG,
                                "Moving existing starting " + startingWindow + " from " + ttoken
                                + " to " + wtoken);
                        final long origId = Binder.clearCallingIdentity();

                        // Transfer the starting window over to the new
                        // token.
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
                        startingWindow.mWinAnimator.mAppAnimator = wtoken.mAppAnimator;

                        if (DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE || DEBUG_STARTING_WINDOW) {
                            Slog.v(TAG, "Removing starting window: " + startingWindow);
                        }
                        startingWindow.getWindowList().remove(startingWindow);
                        mWindowsChanged = true;
                        if (DEBUG_ADD_REMOVE) Slog.v(TAG,
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
                        }
                        if (ttoken.firstWindowDrawn) {
                            wtoken.firstWindowDrawn = true;
                        }
                        if (!ttoken.hidden) {
                            wtoken.hidden = false;
                            wtoken.hiddenRequested = false;
                            wtoken.willBeHidden = false;
                        }
                        if (wtoken.clientHidden != ttoken.clientHidden) {
                            wtoken.clientHidden = ttoken.clientHidden;
                            wtoken.sendAppVisibilityToClients();
                        }
                        final AppWindowAnimator tAppAnimator = ttoken.mAppAnimator;
                        final AppWindowAnimator wAppAnimator = wtoken.mAppAnimator;
                        if (tAppAnimator.animation != null) {
                            wAppAnimator.animation = tAppAnimator.animation;
                            wAppAnimator.animating = tAppAnimator.animating;
                            wAppAnimator.animLayerAdjustment = tAppAnimator.animLayerAdjustment;
                            tAppAnimator.animation = null;
                            tAppAnimator.animLayerAdjustment = 0;
                            wAppAnimator.updateLayers();
                            tAppAnimator.updateLayers();
                        }

                        updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                                true /*updateInputWindows*/);
                        getDefaultDisplayContentLocked().layoutNeeded = true;
                        performLayoutAndPlaceSurfacesLocked();
                        Binder.restoreCallingIdentity(origId);
                        return;
                    } else if (ttoken.startingData != null) {
                        // The previous app was getting ready to show a
                        // starting window, but hasn't yet done so.  Steal it!
                        if (DEBUG_STARTING_WINDOW) Slog.v(TAG,
                                "Moving pending starting from " + ttoken
                                + " to " + wtoken);
                        wtoken.startingData = ttoken.startingData;
                        ttoken.startingData = null;
                        ttoken.startingMoved = true;
                        Message m = mH.obtainMessage(H.ADD_STARTING, wtoken);
                        // Note: we really want to do sendMessageAtFrontOfQueue() because we
                        // want to process the message ASAP, before any other queued
                        // messages.
                        mH.sendMessageAtFrontOfQueue(m);
                        return;
                    }
                    final AppWindowAnimator tAppAnimator = ttoken.mAppAnimator;
                    final AppWindowAnimator wAppAnimator = wtoken.mAppAnimator;
                    if (tAppAnimator.thumbnail != null) {
                        // The old token is animating with a thumbnail, transfer
                        // that to the new token.
                        if (wAppAnimator.thumbnail != null) {
                            wAppAnimator.thumbnail.destroy();
                        }
                        wAppAnimator.thumbnail = tAppAnimator.thumbnail;
                        wAppAnimator.thumbnailX = tAppAnimator.thumbnailX;
                        wAppAnimator.thumbnailY = tAppAnimator.thumbnailY;
                        wAppAnimator.thumbnailLayer = tAppAnimator.thumbnailLayer;
                        wAppAnimator.thumbnailAnimation = tAppAnimator.thumbnailAnimation;
                        tAppAnimator.thumbnail = null;
                    }
                }
            }

            // There is no existing starting window, and the caller doesn't
            // want us to create one, so that's it!
            if (!createIfNeeded) {
                return;
            }

            // If this is a translucent window, then don't
            // show a starting window -- the current effect (a full-screen
            // opaque starting window that fades away to the real contents
            // when it is ready) does not work for this.
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG, "Checking theme of starting window: 0x"
                    + Integer.toHexString(theme));
            if (theme != 0) {
                AttributeCache.Entry ent = AttributeCache.instance().get(pkg, theme,
                        com.android.internal.R.styleable.Window);
                if (ent == null) {
                    // Whoops!  App doesn't exist.  Um.  Okay.  We'll just
                    // pretend like we didn't see that.
                    return;
                }
                if (DEBUG_STARTING_WINDOW) Slog.v(TAG, "Translucent="
                        + ent.array.getBoolean(
                                com.android.internal.R.styleable.Window_windowIsTranslucent, false)
                        + " Floating="
                        + ent.array.getBoolean(
                                com.android.internal.R.styleable.Window_windowIsFloating, false)
                        + " ShowWallpaper="
                        + ent.array.getBoolean(
                                com.android.internal.R.styleable.Window_windowShowWallpaper, false));
                if (ent.array.getBoolean(
                        com.android.internal.R.styleable.Window_windowIsTranslucent, false)) {
                    return;
                }
                if (ent.array.getBoolean(
                        com.android.internal.R.styleable.Window_windowIsFloating, false)) {
                    return;
                }
                if (ent.array.getBoolean(
                        com.android.internal.R.styleable.Window_windowShowWallpaper, false)) {
                    if (mWallpaperTarget == null) {
                        // If this theme is requesting a wallpaper, and the wallpaper
                        // is not curently visible, then this effectively serves as
                        // an opaque window and our starting window transition animation
                        // can still work.  We just need to make sure the starting window
                        // is also showing the wallpaper.
                        windowFlags |= FLAG_SHOW_WALLPAPER;
                    } else {
                        return;
                    }
                }
            }

            if (DEBUG_STARTING_WINDOW) Slog.v(TAG, "Creating StartingData");
            mStartingIconInTransition = true;
            wtoken.startingData = new StartingData(pkg, theme, compatInfo, nonLocalizedLabel,
                    labelRes, icon, windowFlags);
            Message m = mH.obtainMessage(H.ADD_STARTING, wtoken);
            // Note: we really want to do sendMessageAtFrontOfQueue() because we
            // want to process the message ASAP, before any other queued
            // messages.
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG, "Enqueueing ADD_STARTING");
            mH.sendMessageAtFrontOfQueue(m);
        }
    }

    public void setAppWillBeHidden(IBinder token) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppWillBeHidden()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        AppWindowToken wtoken;

        synchronized(mWindowMap) {
            wtoken = findAppWindowToken(token);
            if (wtoken == null) {
                Slog.w(TAG, "Attempted to set will be hidden of non-existing app token: " + token);
                return;
            }
            wtoken.willBeHidden = true;
        }
    }

    boolean setTokenVisibilityLocked(AppWindowToken wtoken, WindowManager.LayoutParams lp,
            boolean visible, int transit, boolean performLayout) {
        boolean delayed = false;

        if (wtoken.clientHidden == visible) {
            wtoken.clientHidden = !visible;
            wtoken.sendAppVisibilityToClients();
        }

        wtoken.willBeHidden = false;
        if (wtoken.hidden == visible) {
            boolean changed = false;
            if (DEBUG_APP_TRANSITIONS) Slog.v(
                TAG, "Changing app " + wtoken + " hidden=" + wtoken.hidden
                + " performLayout=" + performLayout);

            boolean runningAppAnimation = false;

            if (transit != WindowManagerPolicy.TRANSIT_UNSET) {
                if (wtoken.mAppAnimator.animation == AppWindowAnimator.sDummyAnimation) {
                    wtoken.mAppAnimator.animation = null;
                }
                if (applyAnimationLocked(wtoken, lp, transit, visible)) {
                    delayed = runningAppAnimation = true;
                }
                WindowState window = wtoken.findMainWindow();
                if (window != null) {
                    scheduleNotifyWindowTranstionIfNeededLocked(window, transit);
                }
                changed = true;
            }

            final int N = wtoken.allAppWindows.size();
            for (int i=0; i<N; i++) {
                WindowState win = wtoken.allAppWindows.get(i);
                if (win == wtoken.startingWindow) {
                    continue;
                }

                //Slog.i(TAG, "Window " + win + ": vis=" + win.isVisible());
                //win.dump("  ");
                if (visible) {
                    if (!win.isVisibleNow()) {
                        if (!runningAppAnimation) {
                            win.mWinAnimator.applyAnimationLocked(
                                    WindowManagerPolicy.TRANSIT_ENTER, true);
                            scheduleNotifyWindowTranstionIfNeededLocked(win,
                                    WindowManagerPolicy.TRANSIT_ENTER);
                        }
                        changed = true;
                        win.mDisplayContent.layoutNeeded = true;
                    }
                } else if (win.isVisibleNow()) {
                    if (!runningAppAnimation) {
                        win.mWinAnimator.applyAnimationLocked(
                                WindowManagerPolicy.TRANSIT_EXIT, false);
                        scheduleNotifyWindowTranstionIfNeededLocked(win,
                                WindowManagerPolicy.TRANSIT_EXIT);
                    }
                    changed = true;
                    win.mDisplayContent.layoutNeeded = true;
                }
            }

            wtoken.hidden = wtoken.hiddenRequested = !visible;
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

            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "setTokenVisibilityLocked: " + wtoken
                      + ": hidden=" + wtoken.hidden + " hiddenRequested="
                      + wtoken.hiddenRequested);

            if (changed) {
                mInputMonitor.setUpdateInputWindowsNeededLw();
                if (performLayout) {
                    updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                            false /*updateInputWindows*/);
                    performLayoutAndPlaceSurfacesLocked();
                }
                mInputMonitor.updateInputWindowsLw(false /*force*/);
            }
        }

        if (wtoken.mAppAnimator.animation != null) {
            delayed = true;
        }

        for (int i = wtoken.allAppWindows.size() - 1; i >= 0 && !delayed; i--) {
            if (wtoken.allAppWindows.get(i).mWinAnimator.isWindowAnimating()) {
                delayed = true;
            }
        }

        return delayed;
    }

    public void setAppVisibility(IBinder token, boolean visible) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppVisibility()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        AppWindowToken wtoken;

        synchronized(mWindowMap) {
            wtoken = findAppWindowToken(token);
            if (wtoken == null) {
                Slog.w(TAG, "Attempted to set visibility of non-existing app token: " + token);
                return;
            }

            if (DEBUG_APP_TRANSITIONS || DEBUG_ORIENTATION) {
                RuntimeException e = null;
                if (!HIDE_STACK_CRAWLS) {
                    e = new RuntimeException();
                    e.fillInStackTrace();
                }
                Slog.v(TAG, "setAppVisibility(" + token + ", visible=" + visible
                        + "): mNextAppTransition=" + mNextAppTransition
                        + " hidden=" + wtoken.hidden
                        + " hiddenRequested=" + wtoken.hiddenRequested, e);
            }

            // If we are preparing an app transition, then delay changing
            // the visibility of this token until we execute that transition.
            if (okToDisplay() && mNextAppTransition != WindowManagerPolicy.TRANSIT_UNSET) {
                // Already in requested state, don't do anything more.
                if (wtoken.hiddenRequested != visible) {
                    return;
                }
                wtoken.hiddenRequested = !visible;

                if (!wtoken.startingDisplayed) {
                    if (DEBUG_APP_TRANSITIONS) Slog.v(
                            TAG, "Setting dummy animation on: " + wtoken);
                    wtoken.mAppAnimator.setDummyAnimation();
                }
                mOpeningApps.remove(wtoken);
                mClosingApps.remove(wtoken);
                wtoken.waitingToShow = wtoken.waitingToHide = false;
                wtoken.inPendingTransaction = true;
                if (visible) {
                    mOpeningApps.add(wtoken);
                    wtoken.startingMoved = false;

                    // If the token is currently hidden (should be the
                    // common case), then we need to set up to wait for
                    // its windows to be ready.
                    if (wtoken.hidden) {
                        wtoken.allDrawn = false;
                        wtoken.waitingToShow = true;

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
                } else {
                    mClosingApps.add(wtoken);

                    // If the token is currently visible (should be the
                    // common case), then set up to wait for it to be hidden.
                    if (!wtoken.hidden) {
                        wtoken.waitingToHide = true;
                    }
                }
                return;
            }

            final long origId = Binder.clearCallingIdentity();
            setTokenVisibilityLocked(wtoken, null, visible, WindowManagerPolicy.TRANSIT_UNSET,
                    true);
            wtoken.updateReportedVisibilityLocked();
            Binder.restoreCallingIdentity(origId);
        }
    }

    void unsetAppFreezingScreenLocked(AppWindowToken wtoken,
            boolean unfreezeSurfaceNow, boolean force) {
        if (wtoken.mAppAnimator.freezingScreen) {
            if (DEBUG_ORIENTATION) Slog.v(TAG, "Clear freezing of " + wtoken
                    + " force=" + force);
            final int N = wtoken.allAppWindows.size();
            boolean unfrozeWindows = false;
            for (int i=0; i<N; i++) {
                WindowState w = wtoken.allAppWindows.get(i);
                if (w.mAppFreezing) {
                    w.mAppFreezing = false;
                    if (w.mHasSurface && !w.mOrientationChanging) {
                        if (DEBUG_ORIENTATION) Slog.v(TAG, "set mOrientationChanging of " + w);
                        w.mOrientationChanging = true;
                        mInnerFields.mOrientationChangeComplete = false;
                    }
                    unfrozeWindows = true;
                    w.mDisplayContent.layoutNeeded = true;
                }
            }
            if (force || unfrozeWindows) {
                if (DEBUG_ORIENTATION) Slog.v(TAG, "No longer freezing: " + wtoken);
                wtoken.mAppAnimator.freezingScreen = false;
                mAppsFreezingScreen--;
            }
            if (unfreezeSurfaceNow) {
                if (unfrozeWindows) {
                    performLayoutAndPlaceSurfacesLocked();
                }
                stopFreezingDisplayLocked();
            }
        }
    }

    public void startAppFreezingScreenLocked(AppWindowToken wtoken,
            int configChanges) {
        if (DEBUG_ORIENTATION) {
            RuntimeException e = null;
            if (!HIDE_STACK_CRAWLS) {
                e = new RuntimeException();
                e.fillInStackTrace();
            }
            Slog.i(TAG, "Set freezing of " + wtoken.appToken
                    + ": hidden=" + wtoken.hidden + " freezing="
                    + wtoken.mAppAnimator.freezingScreen, e);
        }
        if (!wtoken.hiddenRequested) {
            if (!wtoken.mAppAnimator.freezingScreen) {
                wtoken.mAppAnimator.freezingScreen = true;
                mAppsFreezingScreen++;
                if (mAppsFreezingScreen == 1) {
                    startFreezingDisplayLocked(false, 0, 0);
                    mH.removeMessages(H.APP_FREEZE_TIMEOUT);
                    mH.sendMessageDelayed(mH.obtainMessage(H.APP_FREEZE_TIMEOUT),
                            5000);
                }
            }
            final int N = wtoken.allAppWindows.size();
            for (int i=0; i<N; i++) {
                WindowState w = wtoken.allAppWindows.get(i);
                w.mAppFreezing = true;
            }
        }
    }

    public void startAppFreezingScreen(IBinder token, int configChanges) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppFreezingScreen()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            if (configChanges == 0 && okToDisplay()) {
                if (DEBUG_ORIENTATION) Slog.v(TAG, "Skipping set freeze of " + token);
                return;
            }

            AppWindowToken wtoken = findAppWindowToken(token);
            if (wtoken == null || wtoken.appToken == null) {
                Slog.w(TAG, "Attempted to freeze screen with non-existing app token: " + wtoken);
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            startAppFreezingScreenLocked(wtoken, configChanges);
            Binder.restoreCallingIdentity(origId);
        }
    }

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
            if (DEBUG_ORIENTATION) Slog.v(TAG, "Clear freezing of " + token
                    + ": hidden=" + wtoken.hidden + " freezing=" + wtoken.mAppAnimator.freezingScreen);
            unsetAppFreezingScreenLocked(wtoken, true, force);
            Binder.restoreCallingIdentity(origId);
        }
    }

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
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "Removing app token: " + wtoken);
                delayed = setTokenVisibilityLocked(wtoken, null, false,
                        WindowManagerPolicy.TRANSIT_UNSET, true);
                wtoken.inPendingTransaction = false;
                mOpeningApps.remove(wtoken);
                wtoken.waitingToShow = false;
                if (mClosingApps.contains(wtoken)) {
                    delayed = true;
                } else if (mNextAppTransition != WindowManagerPolicy.TRANSIT_UNSET) {
                    mClosingApps.add(wtoken);
                    wtoken.waitingToHide = true;
                    delayed = true;
                }
                if (DEBUG_APP_TRANSITIONS) Slog.v(
                        TAG, "Removing app " + wtoken + " delayed=" + delayed
                        + " animation=" + wtoken.mAppAnimator.animation
                        + " animating=" + wtoken.mAppAnimator.animating);
                if (delayed) {
                    // set the token aside because it has an active animation to be finished
                    if (DEBUG_ADD_REMOVE || DEBUG_TOKEN_MOVEMENT) Slog.v(TAG,
                            "removeAppToken make exiting: " + wtoken);
                    mExitingAppTokens.add(wtoken);
                } else {
                    // Make sure there is no animation running on this token,
                    // so any windows associated with it will be removed as
                    // soon as their animations are complete
                    wtoken.mAppAnimator.clearAnimation();
                    wtoken.mAppAnimator.animating = false;
                }
                if (DEBUG_ADD_REMOVE || DEBUG_TOKEN_MOVEMENT) Slog.v(TAG,
                        "removeAppToken: " + wtoken);
                mAppTokens.remove(wtoken);
                mAnimatingAppTokens.remove(wtoken);
                wtoken.removed = true;
                if (wtoken.startingData != null) {
                    startingToken = wtoken;
                }
                unsetAppFreezingScreenLocked(wtoken, true, true);
                if (mFocusedApp == wtoken) {
                    if (DEBUG_FOCUS) Slog.v(TAG, "Removing focused app token:" + wtoken);
                    mFocusedApp = null;
                    updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, true /*updateInputWindows*/);
                    mInputMonitor.setFocusedAppLw(null);
                }
            } else {
                Slog.w(TAG, "Attempted to remove non-existing app token: " + token);
            }

            if (!delayed && wtoken != null) {
                wtoken.updateReportedVisibilityLocked();
            }
        }
        Binder.restoreCallingIdentity(origId);

        if (startingToken != null) {
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG, "Schedule remove starting "
                    + startingToken + ": app token removed");
            Message m = mH.obtainMessage(H.REMOVE_STARTING, startingToken);
            mH.sendMessage(m);
        }
    }

    private boolean tmpRemoveAppWindowsLocked(WindowToken token) {
        final int NW = token.windows.size();
        if (NW > 0) {
            mWindowsChanged = true;
        }
        for (int i=0; i<NW; i++) {
            WindowState win = token.windows.get(i);
            if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG, "Tmp removing app window " + win);
            win.getWindowList().remove(win);
            int j = win.mChildWindows.size();
            while (j > 0) {
                j--;
                WindowState cwin = win.mChildWindows.get(j);
                if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG,
                        "Tmp removing child window " + cwin);
                cwin.getWindowList().remove(cwin);
            }
        }
        return NW > 0;
    }

    void dumpAppTokensLocked() {
        for (int i=mAppTokens.size()-1; i>=0; i--) {
            Slog.v(TAG, "  #" + i + ": " + mAppTokens.get(i).token);
        }
    }

    void dumpAnimatingAppTokensLocked() {
        for (int i=mAnimatingAppTokens.size()-1; i>=0; i--) {
            Slog.v(TAG, "  #" + i + ": " + mAnimatingAppTokens.get(i).token);
        }
    }

    void dumpWindowsLocked() {
        int i = 0;
        final AllWindowsIterator iterator = new AllWindowsIterator(REVERSE_ITERATOR);
        while (iterator.hasNext()) {
            final WindowState w = iterator.next();
            Slog.v(TAG, "  #" + i++ + ": " + w);
        }
    }

    private int findWindowOffsetLocked(WindowList windows, int tokenPos) {
        final int NW = windows.size();

        if (tokenPos >= mAnimatingAppTokens.size()) {
            int i = NW;
            while (i > 0) {
                i--;
                WindowState win = windows.get(i);
                if (win.getAppToken() != null) {
                    return i+1;
                }
            }
        }

        while (tokenPos > 0) {
            // Find the first app token below the new position that has
            // a window displayed.
            final AppWindowToken wtoken = mAppTokens.get(tokenPos-1);
            if (DEBUG_REORDER) Slog.v(TAG, "Looking for lower windows @ "
                    + tokenPos + " -- " + wtoken.token);
            if (wtoken.sendingToBottom) {
                if (DEBUG_REORDER) Slog.v(TAG,
                        "Skipping token -- currently sending to bottom");
                tokenPos--;
                continue;
            }
            int i = wtoken.windows.size();
            while (i > 0) {
                i--;
                WindowState win = wtoken.windows.get(i);
                int j = win.mChildWindows.size();
                while (j > 0) {
                    j--;
                    WindowState cwin = win.mChildWindows.get(j);
                    if (cwin.mSubLayer >= 0) {
                        for (int pos=NW-1; pos>=0; pos--) {
                            if (windows.get(pos) == cwin) {
                                if (DEBUG_REORDER) Slog.v(TAG,
                                        "Found child win @" + (pos+1));
                                return pos+1;
                            }
                        }
                    }
                }
                for (int pos=NW-1; pos>=0; pos--) {
                    if (windows.get(pos) == win) {
                        if (DEBUG_REORDER) Slog.v(TAG, "Found win @" + (pos+1));
                        return pos+1;
                    }
                }
            }
            tokenPos--;
        }

        return 0;
    }

    private final int reAddWindowLocked(int index, WindowState win) {
        final WindowList windows = win.getWindowList();
        final int NCW = win.mChildWindows.size();
        boolean added = false;
        for (int j=0; j<NCW; j++) {
            WindowState cwin = win.mChildWindows.get(j);
            if (!added && cwin.mSubLayer >= 0) {
                if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG, "Re-adding child window at "
                        + index + ": " + cwin);
                win.mRebuilding = false;
                windows.add(index, win);
                index++;
                added = true;
            }
            if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG, "Re-adding window at "
                    + index + ": " + cwin);
            cwin.mRebuilding = false;
            windows.add(index, cwin);
            index++;
        }
        if (!added) {
            if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG, "Re-adding window at "
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
            if (win.mDisplayContent == displayContent) {
                index = reAddWindowLocked(index, win);
            }
        }
        return index;
    }

    public void moveAppToken(int index, IBinder token) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "moveAppToken()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            if (DEBUG_REORDER) Slog.v(TAG, "Initial app tokens:");
            if (DEBUG_REORDER) dumpAppTokensLocked();
            final AppWindowToken wtoken = findAppWindowToken(token);
            final int oldIndex = mAppTokens.indexOf(wtoken);
            if (DEBUG_TOKEN_MOVEMENT || DEBUG_REORDER) Slog.v(TAG,
                    "Start moving token " + wtoken + " initially at "
                    + oldIndex);
            if (oldIndex > index && mNextAppTransition != WindowManagerPolicy.TRANSIT_UNSET
                        && !mAppTransitionRunning) {
                // animation towards back has not started, copy old list for duration of animation.
                mAnimatingAppTokens.clear();
                mAnimatingAppTokens.addAll(mAppTokens);
            }
            if (wtoken == null || !mAppTokens.remove(wtoken)) {
                Slog.w(TAG, "Attempting to reorder token that doesn't exist: "
                      + token + " (" + wtoken + ")");
                return;
            }
            mAppTokens.add(index, wtoken);
            if (DEBUG_REORDER) Slog.v(TAG, "Moved " + token + " to " + index + ":");
            else if (DEBUG_TOKEN_MOVEMENT) Slog.v(TAG, "Moved " + token + " to " + index);
            if (DEBUG_REORDER) dumpAppTokensLocked();
            if (mNextAppTransition == WindowManagerPolicy.TRANSIT_UNSET && !mAppTransitionRunning) {
                // Not animating, bring animating app list in line with mAppTokens.
                mAnimatingAppTokens.clear();
                mAnimatingAppTokens.addAll(mAppTokens);

                // Bring window ordering, window focus and input window in line with new app token
                final long origId = Binder.clearCallingIdentity();
                if (DEBUG_REORDER) Slog.v(TAG, "Removing windows in " + token + ":");
                if (DEBUG_REORDER) dumpWindowsLocked();
                if (tmpRemoveAppWindowsLocked(wtoken)) {
                    if (DEBUG_REORDER) Slog.v(TAG, "Adding windows back in:");
                    if (DEBUG_REORDER) dumpWindowsLocked();
                    DisplayContentsIterator iterator = new DisplayContentsIterator();
                    while(iterator.hasNext()) {
                        final DisplayContent displayContent = iterator.next();
                        final WindowList windows = displayContent.getWindowList();
                        final int pos = findWindowOffsetLocked(windows, index);
                        final int newPos = reAddAppWindowsLocked(displayContent, pos, wtoken);
                        if (pos != newPos) {
                            displayContent.layoutNeeded = true;
                        }
                    }
                    if (DEBUG_REORDER) Slog.v(TAG, "Final window list:");
                    if (DEBUG_REORDER) dumpWindowsLocked();
                    updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                            false /*updateInputWindows*/);
                    mInputMonitor.setUpdateInputWindowsNeededLw();
                    performLayoutAndPlaceSurfacesLocked();
                    mInputMonitor.updateInputWindowsLw(false /*force*/);
                }
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    private void removeAppTokensLocked(List<IBinder> tokens) {
        // XXX This should be done more efficiently!
        // (take advantage of the fact that both lists should be
        // ordered in the same way.)
        int N = tokens.size();
        for (int i=0; i<N; i++) {
            IBinder token = tokens.get(i);
            final AppWindowToken wtoken = findAppWindowToken(token);
            if (DEBUG_REORDER || DEBUG_TOKEN_MOVEMENT) Slog.v(TAG,
                    "Temporarily removing " + wtoken + " from " + mAppTokens.indexOf(wtoken));
            if (!mAppTokens.remove(wtoken)) {
                Slog.w(TAG, "Attempting to reorder token that doesn't exist: "
                      + token + " (" + wtoken + ")");
                i--;
                N--;
            }
        }
    }

    private void moveAppWindowsLocked(AppWindowToken wtoken, int tokenPos,
            boolean updateFocusAndLayout) {
        // First remove all of the windows from the list.
        tmpRemoveAppWindowsLocked(wtoken);

        // And now add them back at the correct place.
        DisplayContentsIterator iterator = new DisplayContentsIterator();
        while (iterator.hasNext()) {
            final DisplayContent displayContent = iterator.next();
            final WindowList windows = displayContent.getWindowList();
            final int pos = findWindowOffsetLocked(windows, tokenPos);
            final int newPos = reAddAppWindowsLocked(displayContent, pos, wtoken);
            if (pos != newPos) {
                displayContent.layoutNeeded = true;
            }

            if (updateFocusAndLayout && !updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                    false /*updateInputWindows*/)) {
                assignLayersLocked(windows);
            }
        }

        if (updateFocusAndLayout) {
            mInputMonitor.setUpdateInputWindowsNeededLw();

            // Note that the above updateFocusedWindowLocked conditional used to sit here.

            if (!mInLayout) {
                performLayoutAndPlaceSurfacesLocked();
            }
            mInputMonitor.updateInputWindowsLw(false /*force*/);
        }
    }

    private void moveAppWindowsLocked(List<IBinder> tokens, int tokenPos) {
        // First remove all of the windows from the list.
        final int N = tokens.size();
        int i;
        for (i=0; i<N; i++) {
            WindowToken token = mTokenMap.get(tokens.get(i));
            if (token != null) {
                tmpRemoveAppWindowsLocked(token);
            }
        }

        // And now add them back at the correct place.
        DisplayContentsIterator iterator = new DisplayContentsIterator();
        while (iterator.hasNext()) {
            final DisplayContent displayContent = iterator.next();
            final WindowList windows = displayContent.getWindowList();
            // Where to start adding?
            int pos = findWindowOffsetLocked(windows, tokenPos);
            for (i=0; i<N; i++) {
                WindowToken token = mTokenMap.get(tokens.get(i));
                if (token != null) {
                    final int newPos = reAddAppWindowsLocked(displayContent, pos, token);
                    if (newPos != pos) {
                        displayContent.layoutNeeded = true;
                    }
                    pos = newPos;
                }
            }
            if (!updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                    false /*updateInputWindows*/)) {
                assignLayersLocked(windows);
            }
        }

        mInputMonitor.setUpdateInputWindowsNeededLw();

        // Note that the above updateFocusedWindowLocked used to sit here.

        performLayoutAndPlaceSurfacesLocked();
        mInputMonitor.updateInputWindowsLw(false /*force*/);

        //dump();
    }

    public void moveAppTokensToTop(List<IBinder> tokens) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "moveAppTokensToTop()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        final long origId = Binder.clearCallingIdentity();
        synchronized(mWindowMap) {
            removeAppTokensLocked(tokens);
            final int N = tokens.size();
            for (int i=0; i<N; i++) {
                AppWindowToken wt = findAppWindowToken(tokens.get(i));
                if (wt != null) {
                    if (DEBUG_TOKEN_MOVEMENT || DEBUG_REORDER) Slog.v(TAG,
                            "Adding next to top: " + wt);
                    mAppTokens.add(wt);
                    if (mNextAppTransition != WindowManagerPolicy.TRANSIT_UNSET) {
                        wt.sendingToBottom = false;
                    }
                }
            }

            if (!mAppTransitionRunning) {
                mAnimatingAppTokens.clear();
                mAnimatingAppTokens.addAll(mAppTokens);
                moveAppWindowsLocked(tokens, mAppTokens.size());
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public void moveAppTokensToBottom(List<IBinder> tokens) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "moveAppTokensToBottom()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        final long origId = Binder.clearCallingIdentity();
        synchronized(mWindowMap) {
            final int N = tokens.size();
            if (N > 0 && !mAppTransitionRunning) {
                // animating towards back, hang onto old list for duration of animation.
                mAnimatingAppTokens.clear();
                mAnimatingAppTokens.addAll(mAppTokens);
            }
            removeAppTokensLocked(tokens);
            int pos = 0;
            for (int i=0; i<N; i++) {
                AppWindowToken wt = findAppWindowToken(tokens.get(i));
                if (wt != null) {
                    if (DEBUG_TOKEN_MOVEMENT) Slog.v(TAG,
                            "Adding next to bottom: " + wt + " at " + pos);
                    mAppTokens.add(pos, wt);
                    if (mNextAppTransition != WindowManagerPolicy.TRANSIT_UNSET) {
                        wt.sendingToBottom = true;
                    }
                    pos++;
                }
            }

            if (!mAppTransitionRunning) {
                mAnimatingAppTokens.clear();
                mAnimatingAppTokens.addAll(mAppTokens);
                moveAppWindowsLocked(tokens, 0);
            }
        }
        Binder.restoreCallingIdentity(origId);
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
                    mH.sendMessageDelayed(mH.obtainMessage(H.CLIENT_FREEZE_TIMEOUT),
                            5000);
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

        mKeyguardDisableHandler.sendMessage(mKeyguardDisableHandler.obtainMessage(
                KeyguardDisableHandler.KEYGUARD_DISABLE, new Pair<IBinder, String>(token, tag)));
    }

    @Override
    public void reenableKeyguard(IBinder token) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DISABLE_KEYGUARD)
            != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }

        mKeyguardDisableHandler.sendMessage(mKeyguardDisableHandler.obtainMessage(
                KeyguardDisableHandler.KEYGUARD_REENABLE, token));
    }

    /**
     * @see android.app.KeyguardManager#exitKeyguardSecurely
     */
    public void exitKeyguardSecurely(final IOnKeyguardExitResult callback) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DISABLE_KEYGUARD)
            != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }
        mPolicy.exitKeyguardSecurely(new WindowManagerPolicy.OnKeyguardExitResult() {
            public void onKeyguardExitResult(boolean success) {
                try {
                    callback.onKeyguardExitResult(success);
                } catch (RemoteException e) {
                    // Client has died, we don't care.
                }
            }
        });
    }

    public boolean inKeyguardRestrictedInputMode() {
        return mPolicy.inKeyguardRestrictedKeyInputMode();
    }

    public boolean isKeyguardLocked() {
        return mPolicy.isKeyguardLocked();
    }

    public boolean isKeyguardSecure() {
        return mPolicy.isKeyguardSecure();
    }

    public void dismissKeyguard() {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DISABLE_KEYGUARD)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }
        synchronized(mWindowMap) {
            mPolicy.dismissKeyguardLw();
        }
    }

    public void closeSystemDialogs(String reason) {
        synchronized(mWindowMap) {
            final AllWindowsIterator iterator = new AllWindowsIterator();
            while (iterator.hasNext()) {
                final WindowState w = iterator.next();
                if (w.mHasSurface) {
                    try {
                        w.mClient.closeSystemDialogs(reason);
                    } catch (RemoteException e) {
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

    public void setAnimationScale(int which, float scale) {
        if (!checkCallingPermission(android.Manifest.permission.SET_ANIMATION_SCALE,
                "setAnimationScale()")) {
            throw new SecurityException("Requires SET_ANIMATION_SCALE permission");
        }

        if (scale < 0) scale = 0;
        else if (scale > 20) scale = 20;
        scale = Math.abs(scale);
        switch (which) {
            case 0: mWindowAnimationScale = fixScale(scale); break;
            case 1: mTransitionAnimationScale = fixScale(scale); break;
            case 2: mAnimatorDurationScale = fixScale(scale); break;
        }

        // Persist setting
        mH.obtainMessage(H.PERSIST_ANIMATION_SCALE).sendToTarget();
    }

    public void setAnimationScales(float[] scales) {
        if (!checkCallingPermission(android.Manifest.permission.SET_ANIMATION_SCALE,
                "setAnimationScale()")) {
            throw new SecurityException("Requires SET_ANIMATION_SCALE permission");
        }

        if (scales != null) {
            if (scales.length >= 1) {
                mWindowAnimationScale = fixScale(scales[0]);
            }
            if (scales.length >= 2) {
                mTransitionAnimationScale = fixScale(scales[1]);
            }
            if (scales.length >= 3) {
                setAnimatorDurationScale(fixScale(scales[2]));
            }
        }

        // Persist setting
        mH.obtainMessage(H.PERSIST_ANIMATION_SCALE).sendToTarget();
    }

    private void setAnimatorDurationScale(float scale) {
        mAnimatorDurationScale = scale;
        ValueAnimator.setDurationScale(scale);
    }

    public float getAnimationScale(int which) {
        switch (which) {
            case 0: return mWindowAnimationScale;
            case 1: return mTransitionAnimationScale;
            case 2: return mAnimatorDurationScale;
        }
        return 0;
    }

    public float[] getAnimationScales() {
        return new float[] { mWindowAnimationScale, mTransitionAnimationScale,
                mAnimatorDurationScale };
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

    // Called by window manager policy.  Not exposed externally.
    @Override
    public InputChannel monitorInput(String inputChannelName) {
        return mInputManager.monitorInput(inputChannelName);
    }

    // Called by window manager policy.  Not exposed externally.
    @Override
    public void switchKeyboardLayout(int deviceId, int direction) {
        mInputManager.switchKeyboardLayout(deviceId, direction);
    }

    // Called by window manager policy.  Not exposed externally.
    @Override
    public void shutdown(boolean confirm) {
        ShutdownThread.shutdown(mContext, confirm);
    }

    // Called by window manager policy.  Not exposed externally.
    @Override
    public void rebootSafeMode(boolean confirm) {
        ShutdownThread.rebootSafeMode(mContext, confirm);
    }

    public void setInputFilter(IInputFilter filter) {
        if (!checkCallingPermission(android.Manifest.permission.FILTER_EVENTS, "setInputFilter()")) {
            throw new SecurityException("Requires FILTER_EVENTS permission");
        }
        mInputManager.setInputFilter(filter);
    }

    public void setCurrentUser(final int newUserId) {
        synchronized (mWindowMap) {
            mCurrentUserId = newUserId;
            mPolicy.setCurrentUserLw(newUserId);

            // Hide windows that should not be seen by the new user.
            DisplayContentsIterator iterator = new DisplayContentsIterator();
            while (iterator.hasNext()) {
                final WindowList windows = iterator.next().getWindowList();
                for (int i = 0; i < windows.size(); i++) {
                    final WindowState win = windows.get(i);
                    if (win.isHiddenFromUserLocked()) {
                        Slog.w(TAG, "current user violation " + newUserId + " hiding "
                                + win + ", attrs=" + win.mAttrs.type + ", belonging to "
                                + win.mOwnerUid);
                        win.hideLw(false);
                    }
                }
            }
            performLayoutAndPlaceSurfacesLocked();
        }
    }

    public void enableScreenAfterBoot() {
        synchronized(mWindowMap) {
            if (DEBUG_BOOT) {
                RuntimeException here = new RuntimeException("here");
                here.fillInStackTrace();
                Slog.i(TAG, "enableScreenAfterBoot: mDisplayEnabled=" + mDisplayEnabled
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
            Message msg = mH.obtainMessage(H.BOOT_TIMEOUT);
            mH.sendMessageDelayed(msg, 30*1000);
        }

        mPolicy.systemBooted();

        performEnableScreen();
    }

    void enableScreenIfNeededLocked() {
        if (DEBUG_BOOT) {
            RuntimeException here = new RuntimeException("here");
            here.fillInStackTrace();
            Slog.i(TAG, "enableScreenIfNeededLocked: mDisplayEnabled=" + mDisplayEnabled
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
        mH.sendMessage(mH.obtainMessage(H.ENABLE_SCREEN));
    }

    public void performBootTimeout() {
        synchronized(mWindowMap) {
            if (mDisplayEnabled || mHeadless) {
                return;
            }
            Slog.w(TAG, "***** BOOT TIMEOUT: forcing display enabled");
            mForceDisplayEnabled = true;
        }
        performEnableScreen();
    }

    public void performEnableScreen() {
        synchronized(mWindowMap) {
            if (DEBUG_BOOT) {
                RuntimeException here = new RuntimeException("here");
                here.fillInStackTrace();
                Slog.i(TAG, "performEnableScreen: mDisplayEnabled=" + mDisplayEnabled
                        + " mForceDisplayEnabled=" + mForceDisplayEnabled
                        + " mShowingBootMessages=" + mShowingBootMessages
                        + " mSystemBooted=" + mSystemBooted
                        + " mOnlyCore=" + mOnlyCore, here);
            }
            if (mDisplayEnabled) {
                return;
            }
            if (!mSystemBooted && !mShowingBootMessages) {
                return;
            }

            if (!mForceDisplayEnabled) {
                // Don't enable the screen until all existing windows
                // have been drawn.
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
                    if (w.mAttrs.type == TYPE_KEYGUARD) {
                        // Only if there is a keyguard attached to the window manager
                        // will we consider ourselves as having a keyguard.  If it
                        // isn't attached, we don't know if it wants to be shown or
                        // hidden.  If it is attached, we will say we have a keyguard
                        // if the window doesn't want to be visible, because in that
                        // case it explicitly doesn't want to be shown so we should
                        // not delay turning the screen on for it.
                        boolean vis = w.mViewVisibility == View.VISIBLE
                                && w.mPolicyVisibility;
                        haveKeyguard = !vis;
                    }
                    if (w.isVisibleLw() && !w.mObscured && !w.isDrawnLw()) {
                        return;
                    }
                    if (w.isDrawnLw()) {
                        if (w.mAttrs.type == TYPE_BOOT_PROGRESS) {
                            haveBootMsg = true;
                        } else if (w.mAttrs.type == TYPE_APPLICATION) {
                            haveApp = true;
                        } else if (w.mAttrs.type == TYPE_WALLPAPER) {
                            haveWallpaper = true;
                        } else if (w.mAttrs.type == TYPE_KEYGUARD) {
                            haveKeyguard = true;
                        }
                    }
                }

                if (DEBUG_SCREEN_ON || DEBUG_BOOT) {
                    Slog.i(TAG, "******** booted=" + mSystemBooted + " msg=" + mShowingBootMessages
                            + " haveBoot=" + haveBootMsg + " haveApp=" + haveApp
                            + " haveWall=" + haveWallpaper + " wallEnabled=" + wallpaperEnabled
                            + " haveKeyguard=" + haveKeyguard);
                }

                // If we are turning on the screen to show the boot message,
                // don't do it until the boot message is actually displayed.
                if (!mSystemBooted && !haveBootMsg) {
                    return;
                }
    
                // If we are turning on the screen after the boot is completed
                // normally, don't do so until we have the application and
                // wallpaper.
                if (mSystemBooted && ((!haveApp && !haveKeyguard) ||
                        (wallpaperEnabled && !haveWallpaper))) {
                    return;
                }
            }

            mDisplayEnabled = true;
            if (DEBUG_SCREEN_ON || DEBUG_BOOT) Slog.i(TAG, "******************** ENABLING SCREEN!");
            if (false) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                this.dump(null, pw, null);
                Slog.i(TAG, sw.toString());
            }
            try {
                IBinder surfaceFlinger = ServiceManager.getService("SurfaceFlinger");
                if (surfaceFlinger != null) {
                    //Slog.i(TAG, "******* TELLING SURFACE FLINGER WE ARE BOOTED!");
                    Parcel data = Parcel.obtain();
                    data.writeInterfaceToken("android.ui.ISurfaceComposer");
                    surfaceFlinger.transact(IBinder.FIRST_CALL_TRANSACTION, // BOOT_FINISHED
                                            data, null, 0);
                    data.recycle();
                }
            } catch (RemoteException ex) {
                Slog.e(TAG, "Boot completed: SurfaceFlinger is dead!");
            }

            // Enable input dispatch.
            mInputMonitor.setEventDispatchingLw(mEventDispatchingEnabled);
        }

        mPolicy.enableScreenAfterBoot();

        // Make sure the last requested orientation has been applied.
        updateRotationUnchecked(false, false);
    }

    public void showBootMessage(final CharSequence msg, final boolean always) {
        boolean first = false;
        synchronized(mWindowMap) {
            if (DEBUG_BOOT) {
                RuntimeException here = new RuntimeException("here");
                here.fillInStackTrace();
                Slog.i(TAG, "showBootMessage: msg=" + msg + " always=" + always
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
            Slog.i(TAG, "hideBootMessagesLocked: mDisplayEnabled=" + mDisplayEnabled
                    + " mForceDisplayEnabled=" + mForceDisplayEnabled
                    + " mShowingBootMessages=" + mShowingBootMessages
                    + " mSystemBooted=" + mSystemBooted, here);
        }
        if (mShowingBootMessages) {
            mShowingBootMessages = false;
            mPolicy.hideBootMessages();
        }
    }

    public void setInTouchMode(boolean mode) {
        synchronized(mWindowMap) {
            mInTouchMode = mode;
        }
    }

    // TODO: more accounting of which pid(s) turned it on, keep count,
    // only allow disables from pids which have count on, etc.
    @Override
    public void showStrictModeViolation(boolean on) {
        if (mHeadless) return;
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
                final AllWindowsIterator iterator = new AllWindowsIterator();
                while (iterator.hasNext()) {
                    final WindowState ws = iterator.next();
                    if (ws.mSession.mPid == pid && ws.isVisibleLw()) {
                        isVisible = true;
                        break;
                    }
                }
                if (!isVisible) {
                    return;
                }
            }

            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                    ">>> OPEN TRANSACTION showStrictModeViolation");
            Surface.openTransaction();
            try {
                // TODO(multi-display): support multiple displays
                if (mStrictModeFlash == null) {
                    mStrictModeFlash = new StrictModeFlash(
                            getDefaultDisplayContentLocked().getDisplay(), mFxSession);
                }
                mStrictModeFlash.setVisibility(on);
            } finally {
                Surface.closeTransaction();
                if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                        "<<< CLOSE TRANSACTION showStrictModeViolation");
            }
        }
    }

    public void setStrictModeVisualIndicatorPreference(String value) {
        SystemProperties.set(StrictMode.VISUAL_PROPERTY, value);
    }

    /**
     * Takes a snapshot of the screen.  In landscape mode this grabs the whole screen.
     * In portrait mode, it grabs the upper region of the screen based on the vertical dimension
     * of the target image.
     * 
     * @param displayId the Display to take a screenshot of.
     * @param width the width of the target bitmap
     * @param height the height of the target bitmap
     */
    @Override
    public Bitmap screenshotApplications(IBinder appToken, int displayId, int width, int height) {
        if (!checkCallingPermission(android.Manifest.permission.READ_FRAME_BUFFER,
                "screenshotApplications()")) {
            throw new SecurityException("Requires READ_FRAME_BUFFER permission");
        }

        Bitmap rawss;

        int maxLayer = 0;
        final Rect frame = new Rect();

        float scale;
        int dw, dh;
        int rot;

        synchronized(mWindowMap) {
            long ident = Binder.clearCallingIdentity();

            final DisplayContent displayContent = getDisplayContentLocked(displayId);
            if (displayContent == null) {
                return null;
            }
            final DisplayInfo displayInfo = displayContent.getDisplayInfo();
            dw = displayInfo.logicalWidth;
            dh = displayInfo.logicalHeight;

            int aboveAppLayer = mPolicy.windowTypeToLayerLw(TYPE_APPLICATION)
                    * TYPE_LAYER_MULTIPLIER + TYPE_LAYER_OFFSET;
            aboveAppLayer += TYPE_LAYER_MULTIPLIER;

            boolean isImeTarget = mInputMethodTarget != null
                    && mInputMethodTarget.mAppToken != null
                    && mInputMethodTarget.mAppToken.appToken != null
                    && mInputMethodTarget.mAppToken.appToken.asBinder() == appToken;

            // Figure out the part of the screen that is actually the app.
            boolean including = false;
            final WindowList windows = displayContent.getWindowList();
            for (int i = windows.size() - 1; i >= 0; i--) {
                WindowState ws = windows.get(i);
                if (!ws.mHasSurface) {
                    continue;
                }
                if (ws.mLayer >= aboveAppLayer) {
                    continue;
                }
                // When we will skip windows: when we are not including
                // ones behind a window we didn't skip, and we are actually
                // taking a screenshot of a specific app.
                if (!including && appToken != null) {
                    // Also, we can possibly skip this window if it is not
                    // an IME target or the application for the screenshot
                    // is not the current IME target.
                    if (!ws.mIsImWindow || !isImeTarget) {
                        // And finally, this window is of no interest if it
                        // is not associated with the screenshot app.
                        if (ws.mAppToken == null || ws.mAppToken.token != appToken) {
                            continue;
                        }
                    }
                }

                // We keep on including windows until we go past a full-screen
                // window.
                including = !ws.mIsImWindow && !ws.isFullscreen(dw, dh);

                if (maxLayer < ws.mWinAnimator.mSurfaceLayer) {
                    maxLayer = ws.mWinAnimator.mSurfaceLayer;
                }
                
                // Don't include wallpaper in bounds calculation
                if (!ws.mIsWallpaper) {
                    final Rect wf = ws.mFrame;
                    final Rect cr = ws.mContentInsets;
                    int left = wf.left + cr.left;
                    int top = wf.top + cr.top;
                    int right = wf.right - cr.right;
                    int bottom = wf.bottom - cr.bottom;
                    frame.union(left, top, right, bottom);
                }
            }
            Binder.restoreCallingIdentity(ident);

            // Constrain frame to the screen size.
            frame.intersect(0, 0, dw, dh);

            if (frame.isEmpty() || maxLayer == 0) {
                return null;
            }

            // The screenshot API does not apply the current screen rotation.
            rot = getDefaultDisplayContentLocked().getDisplay().getRotation();
            int fw = frame.width();
            int fh = frame.height();

            // Constrain thumbnail to smaller of screen width or height. Assumes aspect
            // of thumbnail is the same as the screen (in landscape) or square.
            float targetWidthScale = width / (float) fw;
            float targetHeightScale = height / (float) fh;
            if (dw <= dh) {
                scale = targetWidthScale;
                // If aspect of thumbnail is the same as the screen (in landscape),
                // select the slightly larger value so we fill the entire bitmap
                if (targetHeightScale > scale && (int) (targetHeightScale * fw) == width) {
                    scale = targetHeightScale;
                }
            } else {
                scale = targetHeightScale;
                // If aspect of thumbnail is the same as the screen (in landscape),
                // select the slightly larger value so we fill the entire bitmap
                if (targetWidthScale > scale && (int) (targetWidthScale * fh) == height) {
                    scale = targetWidthScale;
                }
            }

            // The screen shot will contain the entire screen.
            dw = (int)(dw*scale);
            dh = (int)(dh*scale);
            if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
                int tmp = dw;
                dw = dh;
                dh = tmp;
                rot = (rot == Surface.ROTATION_90) ? Surface.ROTATION_270 : Surface.ROTATION_90;
            }
            if (DEBUG_SCREENSHOT) {
                Slog.i(TAG, "Screenshot: " + dw + "x" + dh + " from 0 to " + maxLayer);
                for (int i = 0; i < windows.size(); i++) {
                    WindowState win = windows.get(i);
                    Slog.i(TAG, win + ": " + win.mLayer
                            + " animLayer=" + win.mWinAnimator.mAnimLayer
                            + " surfaceLayer=" + win.mWinAnimator.mSurfaceLayer);
                }
            }
            rawss = Surface.screenshot(dw, dh, 0, maxLayer);
        }

        if (rawss == null) {
            Slog.w(TAG, "Failure taking screenshot for (" + dw + "x" + dh
                    + ") to layer " + maxLayer);
            return null;
        }

        Bitmap bm = Bitmap.createBitmap(width, height, rawss.getConfig());
        Matrix matrix = new Matrix();
        ScreenRotationAnimation.createRotationMatrix(rot, dw, dh, matrix);
        matrix.postTranslate(-FloatMath.ceil(frame.left*scale), -FloatMath.ceil(frame.top*scale));
        Canvas canvas = new Canvas(bm);
        canvas.drawBitmap(rawss, matrix, null);
        canvas.setBitmap(null);

        rawss.recycle();
        return bm;
    }

    /**
     * Freeze rotation changes.  (Enable "rotation lock".)
     * Persists across reboots.
     * @param rotation The desired rotation to freeze to, or -1 to use the
     * current rotation.
     */
    public void freezeRotation(int rotation) {
        if (!checkCallingPermission(android.Manifest.permission.SET_ORIENTATION,
                "freezeRotation()")) {
            throw new SecurityException("Requires SET_ORIENTATION permission");
        }
        if (rotation < -1 || rotation > Surface.ROTATION_270) {
            throw new IllegalArgumentException("Rotation argument must be -1 or a valid "
                    + "rotation constant.");
        }

        if (DEBUG_ORIENTATION) Slog.v(TAG, "freezeRotation: mRotation=" + mRotation);

        mPolicy.setUserRotationMode(WindowManagerPolicy.USER_ROTATION_LOCKED,
                rotation == -1 ? mRotation : rotation);
        updateRotationUnchecked(false, false);
    }

    /**
     * Thaw rotation changes.  (Disable "rotation lock".)
     * Persists across reboots.
     */
    public void thawRotation() {
        if (!checkCallingPermission(android.Manifest.permission.SET_ORIENTATION,
                "thawRotation()")) {
            throw new SecurityException("Requires SET_ORIENTATION permission");
        }

        if (DEBUG_ORIENTATION) Slog.v(TAG, "thawRotation: mRotation=" + mRotation);

        mPolicy.setUserRotationMode(WindowManagerPolicy.USER_ROTATION_FREE, 777); // rot not used
        updateRotationUnchecked(false, false);
    }

    /**
     * Recalculate the current rotation.
     *
     * Called by the window manager policy whenever the state of the system changes
     * such that the current rotation might need to be updated, such as when the
     * device is docked or rotated into a new posture.
     */
    public void updateRotation(boolean alwaysSendConfiguration, boolean forceRelayout) {
        updateRotationUnchecked(alwaysSendConfiguration, forceRelayout);
    }

    /**
     * Temporarily pauses rotation changes until resumed.
     *
     * This can be used to prevent rotation changes from occurring while the user is
     * performing certain operations, such as drag and drop.
     *
     * This call nests and must be matched by an equal number of calls to {@link #resumeRotation}.
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
        if(DEBUG_ORIENTATION) Slog.v(TAG, "updateRotationUnchecked("
                   + "alwaysSendConfiguration=" + alwaysSendConfiguration + ")");

        long origId = Binder.clearCallingIdentity();
        boolean changed;
        synchronized(mWindowMap) {
            changed = updateRotationUncheckedLocked(false);
            if (!changed || forceRelayout) {
                getDefaultDisplayContentLocked().layoutNeeded = true;
                performLayoutAndPlaceSurfacesLocked();
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
            if (DEBUG_ORIENTATION) Slog.v(TAG, "Deferring rotation, rotation is paused.");
            return false;
        }

        ScreenRotationAnimation screenRotationAnimation =
                mAnimator.getScreenRotationAnimationLocked(Display.DEFAULT_DISPLAY);
        if (screenRotationAnimation != null && screenRotationAnimation.isAnimating()) {
            // Rotation updates cannot be performed while the previous rotation change
            // animation is still in progress.  Skip this update.  We will try updating
            // again after the animation is finished and the display is unfrozen.
            if (DEBUG_ORIENTATION) Slog.v(TAG, "Deferring rotation, animation in progress.");
            return false;
        }

        if (!mDisplayEnabled) {
            // No point choosing a rotation if the display is not enabled.
            if (DEBUG_ORIENTATION) Slog.v(TAG, "Deferring rotation, display is not enabled.");
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
            Slog.v(TAG, "Application requested orientation "
                    + mForcedAppOrientation + ", got rotation " + rotation
                    + " which has " + (altOrientation ? "incompatible" : "compatible")
                    + " metrics");
        }

        if (mRotation == rotation && mAltOrientation == altOrientation) {
            // No change.
            return false;
        }

        if (DEBUG_ORIENTATION) {
            Slog.v(TAG,
                "Rotation changed to " + rotation + (altOrientation ? " (alt)" : "")
                + " from " + mRotation + (mAltOrientation ? " (alt)" : "")
                + ", forceApp=" + mForcedAppOrientation);
        }

        mRotation = rotation;
        mAltOrientation = altOrientation;
        mPolicy.setRotationLw(mRotation);

        mWindowsFreezingScreen = true;
        mH.removeMessages(H.WINDOW_FREEZE_TIMEOUT);
        mH.sendMessageDelayed(mH.obtainMessage(H.WINDOW_FREEZE_TIMEOUT),
                WINDOW_FREEZE_TIMEOUT_DURATION);
        mWaitingForConfig = true;
        getDefaultDisplayContentLocked().layoutNeeded = true;
        startFreezingDisplayLocked(inTransaction, 0, 0);
        // startFreezingDisplayLocked can reset the ScreenRotationAnimation.
        screenRotationAnimation =
                mAnimator.getScreenRotationAnimationLocked(Display.DEFAULT_DISPLAY);

        // We need to update our screen size information to match the new
        // rotation.  Note that this is redundant with the later call to
        // sendNewConfiguration() that must be called after this function
        // returns...  however we need to do the screen size part of that
        // before then so we have the correct size to use when initializing
        // the rotation animation for the new rotation.
        computeScreenConfigurationLocked(null);

        final DisplayContent displayContent = getDefaultDisplayContentLocked();
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        if (!inTransaction) {
            if (SHOW_TRANSACTIONS) {
                Slog.i(TAG, ">>> OPEN TRANSACTION setRotationUnchecked");
            }
            Surface.openTransaction();
        }
        try {
            // NOTE: We disable the rotation in the emulator because
            //       it doesn't support hardware OpenGL emulation yet.
            if (CUSTOM_SCREEN_ROTATION && screenRotationAnimation != null
                    && screenRotationAnimation.hasScreenshot()) {
                if (screenRotationAnimation.setRotationInTransaction(
                        rotation, mFxSession,
                        MAX_ANIMATION_DURATION, mTransitionAnimationScale,
                        displayInfo.logicalWidth, displayInfo.logicalHeight)) {
                    updateLayoutToAnimationLocked();
                }
            }

            mDisplayManagerService.performTraversalInTransactionFromWindowManager();
        } finally {
            if (!inTransaction) {
                Surface.closeTransaction();
                if (SHOW_LIGHT_TRANSACTIONS) {
                    Slog.i(TAG, "<<< CLOSE TRANSACTION setRotationUnchecked");
                }
            }
        }

        final WindowList windows = displayContent.getWindowList();
        for (int i = windows.size() - 1; i >= 0; i--) {
            WindowState w = windows.get(i);
            if (w.mHasSurface) {
                if (DEBUG_ORIENTATION) Slog.v(TAG, "Set mOrientationChanging of " + w);
                w.mOrientationChanging = true;
                mInnerFields.mOrientationChangeComplete = false;
            }
        }

        for (int i=mRotationWatchers.size()-1; i>=0; i--) {
            try {
                mRotationWatchers.get(i).onRotationChanged(rotation);
            } catch (RemoteException e) {
            }
        }

        scheduleNotifyRotationChangedIfNeededLocked(displayContent, rotation);

        return true;
    }

    public int getRotation() {
        return mRotation;
    }

    public int watchRotation(IRotationWatcher watcher) {
        final IBinder watcherBinder = watcher.asBinder();
        IBinder.DeathRecipient dr = new IBinder.DeathRecipient() {
            public void binderDied() {
                synchronized (mWindowMap) {
                    for (int i=0; i<mRotationWatchers.size(); i++) {
                        if (watcherBinder == mRotationWatchers.get(i).asBinder()) {
                            IRotationWatcher removed = mRotationWatchers.remove(i);
                            if (removed != null) {
                                removed.asBinder().unlinkToDeath(this, 0);
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
                mRotationWatchers.add(watcher);
            } catch (RemoteException e) {
                // Client died, no cleanup needed.
            }

            return mRotation;
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
            } else {
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
                    Slog.w(TAG, "View server did not start");
                }
            }
            return false;
        }

        try {
            mViewServer = new ViewServer(this, port);
            return mViewServer.start();
        } catch (IOException e) {
            Slog.w(TAG, "View server did not start");
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
            DisplayContentsIterator iterator = new DisplayContentsIterator();
            while(iterator.hasNext()) {
                windows.addAll(iterator.next().getWindowList());
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
            Slog.w(TAG, "Could not send command " + command + " with parameters " + parameters, e);
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

    @Override
    public void addDisplayContentChangeListener(int displayId,
            IDisplayContentChangeListener listener) {
        if (!checkCallingPermission(android.Manifest.permission.RETRIEVE_WINDOW_INFO,
                "addDisplayContentChangeListener()")) {
            throw new SecurityException("Requires RETRIEVE_WINDOW_INFO permission");
        }
        synchronized(mWindowMap) {
            DisplayContent displayContent = getDisplayContentLocked(displayId);
            if (displayContent != null) {
                if (displayContent.mDisplayContentChangeListeners == null) {
                    displayContent.mDisplayContentChangeListeners =
                            new RemoteCallbackList<IDisplayContentChangeListener>();
                    displayContent.mDisplayContentChangeListeners.register(listener);
                }
            }
        }
    }

    @Override
    public void removeDisplayContentChangeListener(int displayId,
            IDisplayContentChangeListener listener) {
        if (!checkCallingPermission(android.Manifest.permission.RETRIEVE_WINDOW_INFO,
                "removeDisplayContentChangeListener()")) {
            throw new SecurityException("Requires RETRIEVE_WINDOW_INFO permission");
        }
        synchronized(mWindowMap) {
            DisplayContent displayContent = getDisplayContentLocked(displayId);
            if (displayContent != null) {
                if (displayContent.mDisplayContentChangeListeners != null) {
                    displayContent.mDisplayContentChangeListeners.unregister(listener);
                    if (displayContent.mDisplayContentChangeListeners
                            .getRegisteredCallbackCount() == 0) {
                        displayContent.mDisplayContentChangeListeners = null;
                    }
                }
            }
        }
    }

    void scheduleNotifyWindowTranstionIfNeededLocked(WindowState window, int transition) {
        DisplayContent displayContent = window.mDisplayContent;
        if (displayContent.mDisplayContentChangeListeners != null) {
            WindowInfo info = getWindowInfoForWindowStateLocked(window);
            mH.obtainMessage(H.NOTIFY_WINDOW_TRANSITION, transition, 0, info).sendToTarget();
        }
    }

    private void handleNotifyWindowTranstion(int transition, WindowInfo info) {
        RemoteCallbackList<IDisplayContentChangeListener> callbacks = null;
        synchronized (mWindowMap) {
            DisplayContent displayContent = getDisplayContentLocked(info.displayId);
            if (displayContent == null) {
                return;
            }
            callbacks = displayContent.mDisplayContentChangeListeners;
            if (callbacks == null) {
                return;
            }
        }
        final int callbackCount = callbacks.beginBroadcast();
        try {
            for (int i = 0; i < callbackCount; i++) {
                try {
                    callbacks.getBroadcastItem(i).onWindowTransition(info.displayId,
                            transition, info);
                } catch (RemoteException re) {
                    /* ignore */
                }
            }
        } finally {
            callbacks.finishBroadcast();
        }
    }

    private void scheduleNotifyRotationChangedIfNeededLocked(DisplayContent displayContent,
            int rotation) {
        if (displayContent.mDisplayContentChangeListeners != null
                && displayContent.mDisplayContentChangeListeners.getRegisteredCallbackCount() > 0) {
            mH.obtainMessage(H.NOTIFY_ROTATION_CHANGED, displayContent.getDisplayId(),
                    rotation).sendToTarget();
        }
    }

    private void handleNotifyRotationChanged(int displayId, int rotation) {
        RemoteCallbackList<IDisplayContentChangeListener> callbacks = null;
        synchronized (mWindowMap) {
            DisplayContent displayContent = getDisplayContentLocked(displayId);
            if (displayContent == null) {
                return;
            }
            callbacks = displayContent.mDisplayContentChangeListeners;
            if (callbacks == null) {
                return;
            }
        }
        try {
            final int watcherCount = callbacks.beginBroadcast();
            for (int i = 0; i < watcherCount; i++) {
                try {
                    callbacks.getBroadcastItem(i).onRotationChanged(rotation);
                } catch (RemoteException re) {
                    /* ignore */
                }
            }
        } finally {
            callbacks.finishBroadcast();
        }
    }

    private void scheduleNotifyWindowLayersChangedIfNeededLocked(DisplayContent displayContent) {
        if (displayContent.mDisplayContentChangeListeners != null
                && displayContent.mDisplayContentChangeListeners.getRegisteredCallbackCount() > 0) {
            mH.obtainMessage(H.NOTIFY_WINDOW_LAYERS_CHANGED, displayContent) .sendToTarget();
        }
    }

    private void handleNotifyWindowLayersChanged(DisplayContent displayContent) {
        RemoteCallbackList<IDisplayContentChangeListener> callbacks = null;
        synchronized (mWindowMap) {
            callbacks = displayContent.mDisplayContentChangeListeners;
            if (callbacks == null) {
                return;
            }
        }
        try {
            final int watcherCount = callbacks.beginBroadcast();
            for (int i = 0; i < watcherCount; i++) {
                try {
                    callbacks.getBroadcastItem(i).onWindowLayersChanged(
                            displayContent.getDisplayId());
                } catch (RemoteException re) {
                    /* ignore */
                }
            }
        } finally {
            callbacks.finishBroadcast();
        }
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
            final AllWindowsIterator iterator = new AllWindowsIterator();
            while (iterator.hasNext()) {
                final WindowState w = iterator.next();
                if (System.identityHashCode(w) == hashCode) {
                    return w;
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
            Configuration config = computeNewConfigurationLocked();
            if (config == null && mWaitingForConfig) {
                // Nothing changed but we are waiting for something... stop that!
                mWaitingForConfig = false;
                performLayoutAndPlaceSurfacesLocked();
            }
            return config;
        }
    }

    Configuration computeNewConfigurationLocked() {
        Configuration config = new Configuration();
        config.fontScale = 0;
        if (!computeScreenConfigurationLocked(config)) {
            return null;
        }
        return config;
    }

    private void adjustDisplaySizeRanges(DisplayInfo displayInfo, int rotation, int dw, int dh) {
        // TODO: Multidisplay: for now only use with default display.
        final int width = mPolicy.getConfigDisplayWidth(dw, dh, rotation);
        if (width < displayInfo.smallestNominalAppWidth) {
            displayInfo.smallestNominalAppWidth = width;
        }
        if (width > displayInfo.largestNominalAppWidth) {
            displayInfo.largestNominalAppWidth = width;
        }
        final int height = mPolicy.getConfigDisplayHeight(dw, dh, rotation);
        if (height < displayInfo.smallestNominalAppHeight) {
            displayInfo.smallestNominalAppHeight = height;
        }
        if (height > displayInfo.largestNominalAppHeight) {
            displayInfo.largestNominalAppHeight = height;
        }
    }

    private int reduceConfigLayout(int curLayout, int rotation, float density,
            int dw, int dh) {
        // TODO: Multidisplay: for now only use with default display.
        // Get the app screen size at this rotation.
        int w = mPolicy.getNonDecorDisplayWidth(dw, dh, rotation);
        int h = mPolicy.getNonDecorDisplayHeight(dw, dh, rotation);

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
                  int dw, int dh, float density, Configuration outConfig) {
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
        adjustDisplaySizeRanges(displayInfo, Surface.ROTATION_0, unrotDw, unrotDh);
        adjustDisplaySizeRanges(displayInfo, Surface.ROTATION_90, unrotDh, unrotDw);
        adjustDisplaySizeRanges(displayInfo, Surface.ROTATION_180, unrotDw, unrotDh);
        adjustDisplaySizeRanges(displayInfo, Surface.ROTATION_270, unrotDh, unrotDw);
        int sl = Configuration.resetScreenLayout(outConfig.screenLayout);
        sl = reduceConfigLayout(sl, Surface.ROTATION_0, density, unrotDw, unrotDh);
        sl = reduceConfigLayout(sl, Surface.ROTATION_90, density, unrotDh, unrotDw);
        sl = reduceConfigLayout(sl, Surface.ROTATION_180, density, unrotDw, unrotDh);
        sl = reduceConfigLayout(sl, Surface.ROTATION_270, density, unrotDh, unrotDw);
        outConfig.smallestScreenWidthDp = (int)(displayInfo.smallestNominalAppWidth / density);
        outConfig.screenLayout = sl;
    }

    private int reduceCompatConfigWidthSize(int curSize, int rotation, DisplayMetrics dm,
            int dw, int dh) {
        // TODO: Multidisplay: for now only use with default display.
        dm.noncompatWidthPixels = mPolicy.getNonDecorDisplayWidth(dw, dh, rotation);
        dm.noncompatHeightPixels = mPolicy.getNonDecorDisplayHeight(dw, dh, rotation);
        float scale = CompatibilityInfo.computeCompatibleScaling(dm, null);
        int size = (int)(((dm.noncompatWidthPixels / scale) / dm.density) + .5f);
        if (curSize == 0 || size < curSize) {
            curSize = size;
        }
        return curSize;
    }

    private int computeCompatSmallestWidth(boolean rotated, DisplayMetrics dm, int dw, int dh) {
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
        int sw = reduceCompatConfigWidthSize(0, Surface.ROTATION_0, tmpDm, unrotDw, unrotDh);
        sw = reduceCompatConfigWidthSize(sw, Surface.ROTATION_90, tmpDm, unrotDh, unrotDw);
        sw = reduceCompatConfigWidthSize(sw, Surface.ROTATION_180, tmpDm, unrotDw, unrotDh);
        sw = reduceCompatConfigWidthSize(sw, Surface.ROTATION_270, tmpDm, unrotDh, unrotDw);
        return sw;
    }

    boolean computeScreenConfigurationLocked(Configuration config) {
        if (!mDisplayReady) {
            return false;
        }

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

        if (config != null) {
            config.orientation = (dw <= dh) ? Configuration.ORIENTATION_PORTRAIT :
                    Configuration.ORIENTATION_LANDSCAPE;
        }

        // Update application display metrics.
        final int appWidth = mPolicy.getNonDecorDisplayWidth(dw, dh, mRotation);
        final int appHeight = mPolicy.getNonDecorDisplayHeight(dw, dh, mRotation);
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        synchronized(displayContent.mDisplaySizeLock) {
            displayInfo.rotation = mRotation;
            displayInfo.logicalWidth = dw;
            displayInfo.logicalHeight = dh;
            displayInfo.logicalDensityDpi = displayContent.mBaseDisplayDensity;
            displayInfo.appWidth = appWidth;
            displayInfo.appHeight = appHeight;
            displayInfo.getLogicalMetrics(mRealDisplayMetrics, null);
            displayInfo.getAppMetrics(mDisplayMetrics, null);
            mDisplayManagerService.setDisplayInfoOverrideFromWindowManager(
                    displayContent.getDisplayId(), displayInfo);

            mAnimator.setDisplayDimensions(dw, dh, appWidth, appHeight);
        }
        if (false) {
            Slog.i(TAG, "Set app display size: " + appWidth + " x " + appHeight);
        }

        final DisplayMetrics dm = mDisplayMetrics;
        mCompatibleScreenScale = CompatibilityInfo.computeCompatibleScaling(dm,
                mCompatDisplayMetrics);

        if (config != null) {
            config.screenWidthDp = (int)(mPolicy.getConfigDisplayWidth(dw, dh, mRotation)
                    / dm.density);
            config.screenHeightDp = (int)(mPolicy.getConfigDisplayHeight(dw, dh, mRotation)
                    / dm.density);
            computeSizeRangesAndScreenLayout(displayInfo, rotated, dw, dh, dm.density, config);

            config.compatScreenWidthDp = (int)(config.screenWidthDp / mCompatibleScreenScale);
            config.compatScreenHeightDp = (int)(config.screenHeightDp / mCompatibleScreenScale);
            config.compatSmallestScreenWidthDp = computeCompatSmallestWidth(rotated, dm, dw, dh);
            config.densityDpi = displayContent.mBaseDisplayDensity;

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

            // Determine whether a hard keyboard is available and enabled.
            boolean hardKeyboardAvailable = config.keyboard != Configuration.KEYBOARD_NOKEYS;
            if (hardKeyboardAvailable != mHardKeyboardAvailable) {
                mHardKeyboardAvailable = hardKeyboardAvailable;
                mHardKeyboardEnabled = hardKeyboardAvailable;
                mH.removeMessages(H.REPORT_HARD_KEYBOARD_STATUS_CHANGE);
                mH.sendEmptyMessage(H.REPORT_HARD_KEYBOARD_STATUS_CHANGE);
            }
            if (!mHardKeyboardEnabled) {
                config.keyboard = Configuration.KEYBOARD_NOKEYS;
            }

            // Let the policy update hidden states.
            config.keyboardHidden = Configuration.KEYBOARDHIDDEN_NO;
            config.hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO;
            config.navigationHidden = Configuration.NAVIGATIONHIDDEN_NO;
            mPolicy.adjustConfigurationLw(config, keyboardPresence, navigationPresence);
        }

        return true;
    }

    public boolean isHardKeyboardAvailable() {
        synchronized (mWindowMap) {
            return mHardKeyboardAvailable;
        }
    }

    public boolean isHardKeyboardEnabled() {
        synchronized (mWindowMap) {
            return mHardKeyboardEnabled;
        }
    }

    public void setHardKeyboardEnabled(boolean enabled) {
        synchronized (mWindowMap) {
            if (mHardKeyboardEnabled != enabled) {
                mHardKeyboardEnabled = enabled;
                mH.sendEmptyMessage(H.SEND_NEW_CONFIGURATION);
            }
        }
    }

    public void setOnHardKeyboardStatusChangeListener(
            OnHardKeyboardStatusChangeListener listener) {
        synchronized (mWindowMap) {
            mHardKeyboardStatusChangeListener = listener;
        }
    }

    void notifyHardKeyboardStatusChange() {
        final boolean available, enabled;
        final OnHardKeyboardStatusChangeListener listener;
        synchronized (mWindowMap) {
            listener = mHardKeyboardStatusChangeListener;
            available = mHardKeyboardAvailable;
            enabled = mHardKeyboardEnabled;
        }
        if (listener != null) {
            listener.onHardKeyboardStatusChange(available, enabled);
        }
    }

    // -------------------------------------------------------------
    // Drag and drop
    // -------------------------------------------------------------

    IBinder prepareDragSurface(IWindow window, SurfaceSession session,
            int flags, int width, int height, Surface outSurface) {
        if (DEBUG_DRAG) {
            Slog.d(TAG, "prepare drag surface: w=" + width + " h=" + height
                    + " flags=" + Integer.toHexString(flags) + " win=" + window
                    + " asbinder=" + window.asBinder());
        }

        final int callerPid = Binder.getCallingPid();
        final long origId = Binder.clearCallingIdentity();
        IBinder token = null;

        try {
            synchronized (mWindowMap) {
                try {
                    if (mDragState == null) {
                        // TODO(multi-display): support other displays
                        final DisplayContent displayContent = getDefaultDisplayContentLocked();
                        final Display display = displayContent.getDisplay();
                        Surface surface = new Surface(session, "drag surface",
                                width, height, PixelFormat.TRANSLUCENT, Surface.HIDDEN);
                        surface.setLayerStack(display.getLayerStack());
                        if (SHOW_TRANSACTIONS) Slog.i(TAG, "  DRAG "
                                + surface + ": CREATE");
                        outSurface.copyFrom(surface);
                        final IBinder winBinder = window.asBinder();
                        token = new Binder();
                        mDragState = new DragState(this, token, surface, /*flags*/ 0, winBinder);
                        token = mDragState.mToken = new Binder();

                        // 5 second timeout for this window to actually begin the drag
                        mH.removeMessages(H.DRAG_START_TIMEOUT, winBinder);
                        Message msg = mH.obtainMessage(H.DRAG_START_TIMEOUT, winBinder);
                        mH.sendMessageDelayed(msg, 5000);
                    } else {
                        Slog.w(TAG, "Drag already in progress");
                    }
                } catch (Surface.OutOfResourcesException e) {
                    Slog.e(TAG, "Can't allocate drag surface w=" + width + " h=" + height, e);
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
            sendScreenStatusToClientsLocked();
        }
    }

    public IBinder getFocusedWindowToken() {
        if (!checkCallingPermission(android.Manifest.permission.RETRIEVE_WINDOW_INFO,
                "getFocusedWindowToken()")) {
            throw new SecurityException("Requires RETRIEVE_WINDOW_INFO permission.");
        }
        synchronized (mWindowMap) {
            WindowState windowState = getFocusedWindowLocked();
            if (windowState != null) {
                return windowState.mClient.asBinder();
            }
            return null;
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

    public boolean detectSafeMode() {
        if (!mInputMonitor.waitForInputDevicesReady(
                INPUT_DEVICES_READY_FOR_SAFE_MODE_DETECTION_TIMEOUT_MILLIS)) {
            Slog.w(TAG, "Devices still not ready after waiting "
                   + INPUT_DEVICES_READY_FOR_SAFE_MODE_DETECTION_TIMEOUT_MILLIS
                   + " milliseconds before attempting to detect safe mode.");
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
            if (SystemProperties.getInt(ShutdownThread.REBOOT_SAFEMODE_PROPERTY, 0) != 0) {
                mSafeMode = true;
                SystemProperties.set(ShutdownThread.REBOOT_SAFEMODE_PROPERTY, "");
            }
        } catch (IllegalArgumentException e) {
        }
        if (mSafeMode) {
            Log.i(TAG, "SAFE MODE ENABLED (menu=" + menuState + " s=" + sState
                    + " dpad=" + dpadState + " trackball=" + trackballState + ")");
        } else {
            Log.i(TAG, "SAFE MODE not enabled");
        }
        mPolicy.setSafeMode(mSafeMode);
        return mSafeMode;
    }

    public void displayReady() {
        displayReady(Display.DEFAULT_DISPLAY);

        synchronized(mWindowMap) {
            final DisplayContent displayContent = getDefaultDisplayContentLocked();
            readForcedDisplaySizeAndDensityLocked(displayContent);

            mDisplayReady = true;
            mIsTouchDevice = mContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_TOUCHSCREEN);

            final DisplayInfo displayInfo = getDefaultDisplayInfoLocked();
            mAnimator.setDisplayDimensions(
                    displayInfo.logicalWidth, displayInfo.logicalHeight,
                    displayInfo.appWidth, displayInfo.appHeight);

            mPolicy.setInitialDisplaySize(displayContent.getDisplay(),
                    displayContent.mInitialDisplayWidth,
                    displayContent.mInitialDisplayHeight,
                    displayContent.mInitialDisplayDensity);
        }

        try {
            mActivityManager.updateConfiguration(null);
        } catch (RemoteException e) {
        }
    }

    private void displayReady(int displayId) {
        synchronized(mWindowMap) {
            final DisplayContent displayContent = getDisplayContentLocked(displayId);
            if (displayContent != null) {
                mAnimator.addDisplayLocked(displayId);
                synchronized(displayContent.mDisplaySizeLock) {
                    // Bootstrap the default logical display from the display manager.
                    final DisplayInfo displayInfo = displayContent.getDisplayInfo();
                    DisplayInfo newDisplayInfo = mDisplayManagerService.getDisplayInfo(displayId);
                    if (newDisplayInfo != null) {
                        displayInfo.copyFrom(newDisplayInfo);
                    }
                    displayContent.mInitialDisplayWidth = displayInfo.logicalWidth;
                    displayContent.mInitialDisplayHeight = displayInfo.logicalHeight;
                    displayContent.mInitialDisplayDensity = displayInfo.logicalDensityDpi;
                    displayContent.mBaseDisplayWidth = displayContent.mInitialDisplayWidth;
                    displayContent.mBaseDisplayHeight = displayContent.mInitialDisplayHeight;
                    displayContent.mBaseDisplayDensity = displayContent.mInitialDisplayDensity;
                }
            }
        }
    }

    public void systemReady() {
        mPolicy.systemReady();
    }

    // TODO(multidisplay): Call isScreenOn for each display.
    private void sendScreenStatusToClientsLocked() {
        final boolean on = mPowerManager.isScreenOn();
        final AllWindowsIterator iterator = new AllWindowsIterator();
        while (iterator.hasNext()) {
            try {
                iterator.next().mClient.dispatchScreenState(on);
            } catch (RemoteException e) {
                // Ignored
            }
        }
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
        public static final int UPDATE_ANIM_PARAMETERS = 25;
        public static final int SHOW_STRICT_MODE_VIOLATION = 26;
        public static final int DO_ANIMATION_CALLBACK = 27;
        public static final int NOTIFY_ROTATION_CHANGED = 28;
        public static final int NOTIFY_WINDOW_TRANSITION = 29;
        public static final int NOTIFY_RECTANGLE_ON_SCREEN_REQUESTED = 30;
        public static final int NOTIFY_WINDOW_LAYERS_CHANGED = 31;

        public static final int DO_DISPLAY_ADDED = 32;
        public static final int DO_DISPLAY_REMOVED = 33;
        public static final int DO_DISPLAY_CHANGED = 34;

        public static final int CLIENT_FREEZE_TIMEOUT = 35;

        public static final int ANIMATOR_WHAT_OFFSET = 100000;
        public static final int SET_TRANSPARENT_REGION = ANIMATOR_WHAT_OFFSET + 1;
        public static final int CLEAR_PENDING_ACTIONS = ANIMATOR_WHAT_OFFSET + 2;

        public H() {
        }

        @Override
        public void handleMessage(Message msg) {
            if (DEBUG_WINDOW_TRACE) {
                Slog.v(TAG, "handleMessage: entry what=" + msg.what);
            }
            switch (msg.what) {
                case REPORT_FOCUS_CHANGE: {
                    WindowState lastFocus;
                    WindowState newFocus;

                    synchronized(mWindowMap) {
                        lastFocus = mLastFocus;
                        newFocus = mCurrentFocus;
                        if (lastFocus == newFocus) {
                            // Focus is not changing, so nothing to do.
                            return;
                        }
                        mLastFocus = newFocus;
                        //Slog.i(TAG, "Focus moving from " + lastFocus
                        //        + " to " + newFocus);
                        if (newFocus != null && lastFocus != null
                                && !newFocus.isDisplayedLw()) {
                            //Slog.i(TAG, "Delaying loss of focus...");
                            mLosingFocus.add(lastFocus);
                            lastFocus = null;
                        }
                    }

                    if (lastFocus != newFocus) {
                        //System.out.println("Changing focus from " + lastFocus
                        //                   + " to " + newFocus);
                        if (newFocus != null) {
                            try {
                                //Slog.i(TAG, "Gaining focus: " + newFocus);
                                newFocus.mClient.windowFocusChanged(true, mInTouchMode);
                            } catch (RemoteException e) {
                                // Ignore if process has died.
                            }
                            notifyFocusChanged();
                        }

                        if (lastFocus != null) {
                            try {
                                //Slog.i(TAG, "Losing focus: " + lastFocus);
                                lastFocus.mClient.windowFocusChanged(false, mInTouchMode);
                            } catch (RemoteException e) {
                                // Ignore if process has died.
                            }
                        }
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
                        try {
                            //Slog.i(TAG, "Losing delayed focus: " + losers.get(i));
                            losers.get(i).mClient.windowFocusChanged(false, mInTouchMode);
                        } catch (RemoteException e) {
                             // Ignore if process has died.
                        }
                    }
                } break;

                case DO_TRAVERSAL: {
                    synchronized(mWindowMap) {
                        mTraversalScheduled = false;
                        performLayoutAndPlaceSurfacesLocked();
                    }
                } break;

                case ADD_STARTING: {
                    final AppWindowToken wtoken = (AppWindowToken)msg.obj;
                    final StartingData sd = wtoken.startingData;

                    if (sd == null) {
                        // Animation has been canceled... do nothing.
                        return;
                    }

                    if (DEBUG_STARTING_WINDOW) Slog.v(TAG, "Add starting "
                            + wtoken + ": pkg=" + sd.pkg);

                    View view = null;
                    try {
                        view = mPolicy.addStartingWindow(
                            wtoken.token, sd.pkg, sd.theme, sd.compatInfo,
                            sd.nonLocalizedLabel, sd.labelRes, sd.icon, sd.windowFlags);
                    } catch (Exception e) {
                        Slog.w(TAG, "Exception when adding starting window", e);
                    }

                    if (view != null) {
                        boolean abort = false;

                        synchronized(mWindowMap) {
                            if (wtoken.removed || wtoken.startingData == null) {
                                // If the window was successfully added, then
                                // we need to remove it.
                                if (wtoken.startingWindow != null) {
                                    if (DEBUG_STARTING_WINDOW) Slog.v(TAG,
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
                            if (DEBUG_STARTING_WINDOW && !abort) Slog.v(TAG,
                                    "Added starting " + wtoken
                                    + ": startingWindow="
                                    + wtoken.startingWindow + " startingView="
                                    + wtoken.startingView);
                        }

                        if (abort) {
                            try {
                                mPolicy.removeStartingWindow(wtoken.token, view);
                            } catch (Exception e) {
                                Slog.w(TAG, "Exception when removing starting window", e);
                            }
                        }
                    }
                } break;

                case REMOVE_STARTING: {
                    final AppWindowToken wtoken = (AppWindowToken)msg.obj;
                    IBinder token = null;
                    View view = null;
                    synchronized (mWindowMap) {
                        if (DEBUG_STARTING_WINDOW) Slog.v(TAG, "Remove starting "
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
                            Slog.w(TAG, "Exception when removing starting window", e);
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

                            if (DEBUG_STARTING_WINDOW) Slog.v(TAG,
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
                            Slog.w(TAG, "Exception when removing starting window", e);
                        }
                    }
                } break;

                case REPORT_APPLICATION_TOKEN_DRAWN: {
                    final AppWindowToken wtoken = (AppWindowToken)msg.obj;

                    try {
                        if (DEBUG_VISIBILITY) Slog.v(
                                TAG, "Reporting drawn in " + wtoken);
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
                                TAG, "Reporting visible in " + wtoken
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
                        Slog.w(TAG, "Window freeze timeout expired.");
                        final WindowList windows = getDefaultWindowListLocked();
                        int i = windows.size();
                        while (i > 0) {
                            i--;
                            WindowState w = windows.get(i);
                            if (w.mOrientationChanging) {
                                w.mOrientationChanging = false;
                                Slog.w(TAG, "Force clearing orientation change: " + w);
                            }
                        }
                        performLayoutAndPlaceSurfacesLocked();
                    }
                    break;
                }

                case APP_TRANSITION_TIMEOUT: {
                    synchronized (mWindowMap) {
                        if (mNextAppTransition != WindowManagerPolicy.TRANSIT_UNSET) {
                            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                                    "*** APP TRANSITION TIMEOUT");
                            mAppTransitionReady = true;
                            mAppTransitionTimeout = true;
                            mAnimatingAppTokens.clear();
                            mAnimatingAppTokens.addAll(mAppTokens);
                            performLayoutAndPlaceSurfacesLocked();
                        }
                    }
                    break;
                }

                case PERSIST_ANIMATION_SCALE: {
                    Settings.Global.putFloat(mContext.getContentResolver(),
                            Settings.Global.WINDOW_ANIMATION_SCALE, mWindowAnimationScale);
                    Settings.Global.putFloat(mContext.getContentResolver(),
                            Settings.Global.TRANSITION_ANIMATION_SCALE, mTransitionAnimationScale);
                    Settings.Global.putFloat(mContext.getContentResolver(),
                            Settings.Global.ANIMATOR_DURATION_SCALE, mAnimatorDurationScale);
                    break;
                }

                case FORCE_GC: {
                    synchronized (mWindowMap) {
                        synchronized (mAnimator) {
                            // Since we're holding both mWindowMap and mAnimator we don't need to
                            // hold mAnimator.mLayoutToAnim.
                            if (mAnimator.mAnimating || mLayoutToAnim.mAnimationScheduled) {
                                // If we are animating, don't do the gc now but
                                // delay a bit so we don't interrupt the animation.
                                mH.sendMessageDelayed(mH.obtainMessage(H.FORCE_GC),
                                        2000);
                                return;
                            }
                            // If we are currently rotating the display, it will
                            // schedule a new message when done.
                            if (mDisplayFrozen) {
                                return;
                            }
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
                        synchronized (mAnimator) {
                            Slog.w(TAG, "App freeze timeout expired.");
                            int i = mAppTokens.size();
                            while (i > 0) {
                                i--;
                                AppWindowToken tok = mAppTokens.get(i);
                                if (tok.mAppAnimator.freezingScreen) {
                                    Slog.w(TAG, "Force clearing freeze: " + tok);
                                    unsetAppFreezingScreenLocked(tok, true, true);
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
                        Slog.w(TAG, "Timeout starting drag by win " + win);
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
                        Slog.w(TAG, "Timeout ending drag to win " + win);
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
                    Pair<WindowState, IRemoteCallback> pair;
                    synchronized (mWindowMap) {
                        pair = (Pair<WindowState, IRemoteCallback>)msg.obj;
                        Slog.w(TAG, "Timeout waiting for drawn: " + pair.first);
                        if (!mWaitingForDrawn.remove(pair)) {
                            return;
                        }
                    }
                    try {
                        pair.second.sendResult(null);
                    } catch (RemoteException e) {
                    }
                    break;
                }

                case UPDATE_ANIM_PARAMETERS: {
                    // Used to send multiple changes from the animation side to the layout side.
                    synchronized (mWindowMap) {
                        if (copyAnimToLayoutParamsLocked()) {
                            mH.sendEmptyMessage(CLEAR_PENDING_ACTIONS);
                            performLayoutAndPlaceSurfacesLocked();
                        }
                    }
                    break;
                }

                case SHOW_STRICT_MODE_VIOLATION: {
                    showStrictModeViolation(msg.arg1, msg.arg2);
                    break;
                }

                // Animation messages. Move to Window{State}Animator
                case SET_TRANSPARENT_REGION: {
                    Pair<WindowStateAnimator, Region> pair =
                                (Pair<WindowStateAnimator, Region>) msg.obj;
                    final WindowStateAnimator winAnimator = pair.first;
                    winAnimator.setTransparentRegionHint(pair.second);
                    break;
                }

                case CLEAR_PENDING_ACTIONS: {
                    mAnimator.clearPendingActions();
                    break;
                }

                case DO_ANIMATION_CALLBACK: {
                    try {
                        ((IRemoteCallback)msg.obj).sendResult(null);
                    } catch (RemoteException e) {
                    }
                    break;
                }

                case NOTIFY_ROTATION_CHANGED: {
                    final int displayId = msg.arg1;
                    final int rotation = msg.arg2;
                    handleNotifyRotationChanged(displayId, rotation);
                    break;
                }

                case NOTIFY_WINDOW_TRANSITION: {
                    final int transition = msg.arg1;
                    WindowInfo info = (WindowInfo) msg.obj;
                    handleNotifyWindowTranstion(transition, info);
                    break;
                }

                case NOTIFY_RECTANGLE_ON_SCREEN_REQUESTED: {
                    final int displayId = msg.arg1;
                    final boolean immediate = (msg.arg2 == 1);
                    Rect rectangle = (Rect) msg.obj;
                    handleNotifyRectangleOnScreenRequested(displayId, rectangle, immediate);
                    break;
                }

                case NOTIFY_WINDOW_LAYERS_CHANGED: {
                    DisplayContent displayContent = (DisplayContent) msg.obj;
                    handleNotifyWindowLayersChanged(displayContent);
                    break;
                }

                case DO_DISPLAY_ADDED:
                    synchronized (mWindowMap) {
                        handleDisplayAddedLocked(msg.arg1);
                    }
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
            }
            if (DEBUG_WINDOW_TRACE) {
                Slog.v(TAG, "handleMessage: exit");
            }
        }
    }

    // -------------------------------------------------------------
    // IWindowManager API
    // -------------------------------------------------------------

    @Override
    public IWindowSession openSession(IInputMethodClient client,
            IInputContext inputContext) {
        if (client == null) throw new IllegalArgumentException("null client");
        if (inputContext == null) throw new IllegalArgumentException("null inputContext");
        Session session = new Session(this, client, inputContext);
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
                    Slog.i(TAG, "Desired input method target: " + imFocus);
                    Slog.i(TAG, "Current focus: " + mCurrentFocus);
                    Slog.i(TAG, "Last focus: " + mLastFocus);
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
                                Log.i(TAG, "Switching to real app window: " + w);
                                imFocus = w;
                                break;
                            }
                        }
                    }
                    if (DEBUG_INPUT_METHOD) {
                        Slog.i(TAG, "IM target client: " + imFocus.mSession.mClient);
                        if (imFocus.mSession.mClient != null) {
                            Slog.i(TAG, "IM target client binder: "
                                    + imFocus.mSession.mClient.asBinder());
                            Slog.i(TAG, "Requesting client binder: " + client.asBinder());
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

    public void getInitialDisplaySize(int displayId, Point size) {
        // TODO(cmautner): Access to DisplayContent should be locked on mWindowMap. Doing that
        //  could lead to deadlock since this is called from ActivityManager.
        final DisplayContent displayContent = getDisplayContentLocked(displayId);
        if (displayContent != null) {
            synchronized(displayContent.mDisplaySizeLock) {
                size.x = displayContent.mInitialDisplayWidth;
                size.y = displayContent.mInitialDisplayHeight;
            }
        }
    }

    @Override
    public void setForcedDisplaySize(int displayId, int width, int height) {
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
    }

    private void readForcedDisplaySizeAndDensityLocked(final DisplayContent displayContent) {
        final String sizeStr = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.DISPLAY_SIZE_FORCED);
        if (sizeStr != null && sizeStr.length() > 0) {
            final int pos = sizeStr.indexOf(',');
            if (pos > 0 && sizeStr.lastIndexOf(',') == pos) {
                int width, height;
                try {
                    width = Integer.parseInt(sizeStr.substring(0, pos));
                    height = Integer.parseInt(sizeStr.substring(pos+1));
                    synchronized(displayContent.mDisplaySizeLock) {
                        if (displayContent.mBaseDisplayWidth != width
                                || displayContent.mBaseDisplayHeight != height) {
                            Slog.i(TAG, "FORCED DISPLAY SIZE: " + width + "x" + height);
                            displayContent.mBaseDisplayWidth = width;
                            displayContent.mBaseDisplayHeight = height;
                        }
                    }
                } catch (NumberFormatException ex) {
                }
            }
        }
        final String densityStr = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.DISPLAY_DENSITY_FORCED);
        if (densityStr != null && densityStr.length() > 0) {
            int density;
            try {
                density = Integer.parseInt(densityStr);
                synchronized(displayContent.mDisplaySizeLock) {
                    if (displayContent.mBaseDisplayDensity != density) {
                        Slog.i(TAG, "FORCED DISPLAY DENSITY: " + density);
                        displayContent.mBaseDisplayDensity = density;
                    }
                }
            } catch (NumberFormatException ex) {
            }
        }
    }

    // displayContent must not be null
    private void setForcedDisplaySizeLocked(DisplayContent displayContent, int width, int height) {
        Slog.i(TAG, "Using new display size: " + width + "x" + height);

        synchronized(displayContent.mDisplaySizeLock) {
            displayContent.mBaseDisplayWidth = width;
            displayContent.mBaseDisplayHeight = height;
        }
        reconfigureDisplayLocked(displayContent);
    }

    @Override
    public void clearForcedDisplaySize(int displayId) {
        synchronized(mWindowMap) {
            final DisplayContent displayContent = getDisplayContentLocked(displayId);
            if (displayContent != null) {
                setForcedDisplaySizeLocked(displayContent, displayContent.mInitialDisplayWidth,
                        displayContent.mInitialDisplayHeight);
                Settings.Global.putString(mContext.getContentResolver(),
                        Settings.Global.DISPLAY_SIZE_FORCED, "");
            }
        }
    }

    @Override
    public void setForcedDisplayDensity(int displayId, int density) {
        synchronized(mWindowMap) {
            final DisplayContent displayContent = getDisplayContentLocked(displayId);
            if (displayContent != null) {
                setForcedDisplayDensityLocked(displayContent, density);
                Settings.Global.putString(mContext.getContentResolver(),
                        Settings.Global.DISPLAY_DENSITY_FORCED, Integer.toString(density));
            }
        }
    }

    // displayContent must not be null
    private void setForcedDisplayDensityLocked(DisplayContent displayContent, int density) {
        Slog.i(TAG, "Using new display density: " + density);

        synchronized(displayContent.mDisplaySizeLock) {
            displayContent.mBaseDisplayDensity = density;
        }
        reconfigureDisplayLocked(displayContent);
    }

    @Override
    public void clearForcedDisplayDensity(int displayId) {
        synchronized(mWindowMap) {
            final DisplayContent displayContent = getDisplayContentLocked(displayId);
            if (displayContent != null) {
                setForcedDisplayDensityLocked(displayContent, displayContent.mInitialDisplayDensity);
                Settings.Global.putString(mContext.getContentResolver(),
                        Settings.Global.DISPLAY_DENSITY_FORCED, "");
            }
        }
    }

    // displayContent must not be null
    private void reconfigureDisplayLocked(DisplayContent displayContent) {
        // TODO: Multidisplay: for now only use with default display.
        mPolicy.setInitialDisplaySize(displayContent.getDisplay(),
                displayContent.mBaseDisplayWidth,
                displayContent.mBaseDisplayHeight,
                displayContent.mBaseDisplayDensity);

        displayContent.layoutNeeded = true;

        boolean configChanged = updateOrientationFromAppTokensLocked(false);
        mTempConfiguration.setToDefaults();
        mTempConfiguration.fontScale = mCurConfiguration.fontScale;
        if (computeScreenConfigurationLocked(mTempConfiguration)) {
            if (mCurConfiguration.diff(mTempConfiguration) != 0) {
                configChanged = true;
            }
        }

        if (configChanged) {
            mWaitingForConfig = true;
            startFreezingDisplayLocked(false, 0, 0);
            mH.sendEmptyMessage(H.SEND_NEW_CONFIGURATION);
        }

        performLayoutAndPlaceSurfacesLocked();
    }

    public boolean hasSystemNavBar() {
        return mPolicy.hasSystemNavBar();
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
            TAG, "Looking up client " + client + ": " + win);
        if (win == null) {
            RuntimeException ex = new IllegalArgumentException(
                    "Requested window " + client + " does not exist");
            if (throwOnError) {
                throw ex;
            }
            Slog.w(TAG, "Failed looking up window", ex);
            return null;
        }
        if (session != null && win.mSession != session) {
            RuntimeException ex = new IllegalArgumentException(
                    "Requested window " + client + " is in session " +
                    win.mSession + ", not " + session);
            if (throwOnError) {
                throw ex;
            }
            Slog.w(TAG, "Failed looking up window", ex);
            return null;
        }

        return win;
    }

    final void rebuildAppWindowListLocked() {
        DisplayContentsIterator iterator = new DisplayContentsIterator();
        while (iterator.hasNext()) {
            rebuildAppWindowListLocked(iterator.next());
        }
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
                if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG,
                        "Rebuild removing window: " + win);
                NW--;
                numRemoved++;
                continue;
            } else if (lastBelow == i-1) {
                if (w.mAttrs.type == TYPE_WALLPAPER || w.mAttrs.type == TYPE_UNIVERSE_BACKGROUND) {
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
        int NT = mExitingAppTokens.size();
        for (int j=0; j<NT; j++) {
            i = reAddAppWindowsLocked(displayContent, i, mExitingAppTokens.get(j));
        }

        // And add in the still active app tokens in Z order.
        NT = mAnimatingAppTokens.size();
        for (int j=0; j<NT; j++) {
            i = reAddAppWindowsLocked(displayContent, i, mAnimatingAppTokens.get(j));
        }

        i -= lastBelow;
        if (i != numRemoved) {
            Slog.w(TAG, "Rebuild removed " + numRemoved
                    + " windows but added " + i);
            for (i=0; i<numRemoved; i++) {
                WindowState ws = mRebuildTmp[i];
                if (ws.mRebuilding) {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    ws.dump(pw, "", true);
                    pw.flush();
                    Slog.w(TAG, "This window was lost: " + ws);
                    Slog.w(TAG, sw.toString());
                    ws.mWinAnimator.destroySurfaceLocked(false);
                }
            }
            Slog.w(TAG, "Current app token list:");
            dumpAnimatingAppTokensLocked();
            Slog.w(TAG, "Final window list:");
            dumpWindowsLocked();
        }
    }

    private final void assignLayersLocked(WindowList windows) {
        int N = windows.size();
        int curBaseLayer = 0;
        int curLayer = 0;
        int i;

        if (DEBUG_LAYERS) {
            RuntimeException here = new RuntimeException("here");
            here.fillInStackTrace();
            Slog.v(TAG, "Assigning layers", here);
        }

        boolean anyLayerChanged = false;

        for (i=0; i<N; i++) {
            final WindowState w = windows.get(i);
            final WindowStateAnimator winAnimator = w.mWinAnimator;
            boolean layerChanged = false;
            int oldLayer = w.mLayer;
            if (w.mBaseLayer == curBaseLayer || w.mIsImWindow
                    || (i > 0 && w.mIsWallpaper)) {
                curLayer += WINDOW_LAYER_MULTIPLIER;
                w.mLayer = curLayer;
            } else {
                curBaseLayer = curLayer = w.mBaseLayer;
                w.mLayer = curLayer;
            }
            if (w.mLayer != oldLayer) {
                layerChanged = true;
                anyLayerChanged = true;
            }
            oldLayer = winAnimator.mAnimLayer;
            if (w.mTargetAppToken != null) {
                winAnimator.mAnimLayer =
                        w.mLayer + w.mTargetAppToken.mAppAnimator.animLayerAdjustment;
            } else if (w.mAppToken != null) {
                winAnimator.mAnimLayer =
                        w.mLayer + w.mAppToken.mAppAnimator.animLayerAdjustment;
            } else {
                winAnimator.mAnimLayer = w.mLayer;
            }
            if (w.mIsImWindow) {
                winAnimator.mAnimLayer += mInputMethodAnimLayerAdjustment;
            } else if (w.mIsWallpaper) {
                winAnimator.mAnimLayer += mWallpaperAnimLayerAdjustment;
            }
            if (winAnimator.mAnimLayer != oldLayer) {
                layerChanged = true;
                anyLayerChanged = true;
            }
            if (layerChanged && mAnimator.isDimmingLocked(winAnimator)) {
                // Force an animation pass just to update the mDimAnimator layer.
                updateLayoutToAnimationLocked();
            }
            if (DEBUG_LAYERS) Slog.v(TAG, "Assign layer " + w + ": "
                    + "mBase=" + w.mBaseLayer
                    + " mLayer=" + w.mLayer
                    + (w.mAppToken == null ?
                            "" : " mAppLayer=" + w.mAppToken.mAppAnimator.animLayerAdjustment)
                    + " =mAnimLayer=" + winAnimator.mAnimLayer);
            //System.out.println(
            //    "Assigned layer " + curLayer + " to " + w.mClient.asBinder());
        }

        if (anyLayerChanged) {
            scheduleNotifyWindowLayersChangedIfNeededLocked(getDefaultDisplayContentLocked());
        }
    }

    private final void performLayoutAndPlaceSurfacesLocked() {
        int loopCount = 6;
        do {
            mTraversalScheduled = false;
            performLayoutAndPlaceSurfacesLockedLoop();
            mH.removeMessages(H.DO_TRAVERSAL);
            loopCount--;
        } while (mTraversalScheduled && loopCount > 0);
    }

    private boolean mInLayout = false;
    private final void performLayoutAndPlaceSurfacesLockedLoop() {
        if (mInLayout) {
            if (DEBUG) {
                throw new RuntimeException("Recursive call!");
            }
            Slog.w(TAG, "performLayoutAndPlaceSurfacesLocked called while in layout. Callers="
                    + Debug.getCallers(3));
            return;
        }

        if (mWaitingForConfig) {
            // Our configuration has changed (most likely rotation), but we
            // don't yet have the complete configuration to report to
            // applications.  Don't do any window layout until we have it.
            return;
        }
        
        if (!mDisplayReady) {
            // Not yet initialized, nothing to do.
            return;
        }

        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "wmLayout");
        mInLayout = true;
        boolean recoveringMemory = false;
        
        try {
            if (mForceRemoves != null) {
                recoveringMemory = true;
                // Wait a little bit for things to settle down, and off we go.
                for (int i=0; i<mForceRemoves.size(); i++) {
                    WindowState ws = mForceRemoves.get(i);
                    Slog.i(TAG, "Force removing: " + ws);
                    removeWindowInnerLocked(ws.mSession, ws);
                }
                mForceRemoves = null;
                Slog.w(TAG, "Due to memory failure, waiting a bit for next layout");
                Object tmp = new Object();
                synchronized (tmp) {
                    try {
                        tmp.wait(250);
                    } catch (InterruptedException e) {
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.wtf(TAG, "Unhandled exception while force removing for memory", e);
        }

        try {
            performLayoutAndPlaceSurfacesLockedInner(recoveringMemory);

            mInLayout = false;

            if (needsLayout()) {
                if (++mLayoutRepeatCount < 6) {
                    requestTraversalLocked();
                } else {
                    Slog.e(TAG, "Performed 6 layouts in a row. Skipping");
                    mLayoutRepeatCount = 0;
                }
            } else {
                mLayoutRepeatCount = 0;
            }

            if (mWindowsChanged && !mWindowChangeListeners.isEmpty()) {
                mH.removeMessages(H.REPORT_WINDOWS_CHANGE);
                mH.sendMessage(mH.obtainMessage(H.REPORT_WINDOWS_CHANGE));
            }
        } catch (RuntimeException e) {
            mInLayout = false;
            Log.wtf(TAG, "Unhandled exception while laying out windows", e);
        }

        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
    }

    private final void performLayoutLockedInner(final DisplayContent displayContent,
                                    boolean initial, boolean updateInputWindows) {
        if (!displayContent.layoutNeeded) {
            return;
        }
        displayContent.layoutNeeded = false;
        WindowList windows = displayContent.getWindowList();
        boolean isDefaultDisplay = displayContent.isDefaultDisplay;

        DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final int dw = displayInfo.logicalWidth;
        final int dh = displayInfo.logicalHeight;

        final int NFW = mFakeWindows.size();
        for (int i=0; i<NFW; i++) {
            mFakeWindows.get(i).layout(dw, dh);
        }

        final int N = windows.size();
        int i;

        if (DEBUG_LAYOUT) {
            Slog.v(TAG, "-------------------------------------");
            Slog.v(TAG, "performLayout: needed="
                    + displayContent.layoutNeeded + " dw=" + dw + " dh=" + dh);
        }

        WindowStateAnimator universeBackground = null;

        mPolicy.beginLayoutLw(isDefaultDisplay, dw, dh, mRotation);
        if (isDefaultDisplay) {
            // Not needed on non-default displays.
            mSystemDecorLayer = mPolicy.getSystemDecorRectLw(mSystemDecorRect);
            mScreenRect.set(0, 0, dw, dh);
        }

        int seq = mLayoutSeq+1;
        if (seq < 0) seq = 0;
        mLayoutSeq = seq;

        boolean behindDream = false;

        // First perform layout of any root windows (not attached
        // to another window).
        int topAttached = -1;
        for (i = N-1; i >= 0; i--) {
            final WindowState win = windows.get(i);

            // Don't do layout of a window if it is not visible, or
            // soon won't be visible, to avoid wasting time and funky
            // changes while a window is animating away.
            final boolean gone = (behindDream && mPolicy.canBeForceHidden(win, win.mAttrs))
                    || win.isGoneForLayoutLw();

            if (DEBUG_LAYOUT && !win.mLayoutAttached) {
                Slog.v(TAG, "1ST PASS " + win
                        + ": gone=" + gone + " mHaveFrame=" + win.mHaveFrame
                        + " mLayoutAttached=" + win.mLayoutAttached
                        + " screen changed=" + win.isConfigChanged());
                final AppWindowToken atoken = win.mAppToken;
                if (gone) Slog.v(TAG, "  GONE: mViewVisibility="
                        + win.mViewVisibility + " mRelayoutCalled="
                        + win.mRelayoutCalled + " hidden="
                        + win.mRootToken.hidden + " hiddenRequested="
                        + (atoken != null && atoken.hiddenRequested)
                        + " mAttachedHidden=" + win.mAttachedHidden);
                else Slog.v(TAG, "  VIS: mViewVisibility="
                        + win.mViewVisibility + " mRelayoutCalled="
                        + win.mRelayoutCalled + " hidden="
                        + win.mRootToken.hidden + " hiddenRequested="
                        + (atoken != null && atoken.hiddenRequested)
                        + " mAttachedHidden=" + win.mAttachedHidden);
            }

            // If this view is GONE, then skip it -- keep the current
            // frame, and let the caller know so they can ignore it
            // if they want.  (We do the normal layout for INVISIBLE
            // windows, since that means "perform layout as normal,
            // just don't display").
            if (!gone || !win.mHaveFrame || win.mLayoutNeeded
                    || ((win.mAttrs.type == TYPE_KEYGUARD || win.mAttrs.type == TYPE_WALLPAPER) &&
                        win.isConfigChanged())
                    || win.mAttrs.type == TYPE_UNIVERSE_BACKGROUND) {
                if (!win.mLayoutAttached) {
                    if (initial) {
                        //Slog.i(TAG, "Window " + this + " clearing mContentChanged - initial");
                        win.mContentChanged = false;
                    }
                    if (win.mAttrs.type == TYPE_DREAM) {
                        // Don't layout windows behind a dream, so that if it
                        // does stuff like hide the status bar we won't get a
                        // bad transition when it goes away.
                        behindDream = true;
                    }
                    win.mLayoutNeeded = false;
                    win.prelayout();
                    mPolicy.layoutWindowLw(win, win.mAttrs, null);
                    win.mLayoutSeq = seq;
                    if (DEBUG_LAYOUT) Slog.v(TAG, "  LAYOUT: mFrame="
                            + win.mFrame + " mContainingFrame="
                            + win.mContainingFrame + " mDisplayFrame="
                            + win.mDisplayFrame);
                } else {
                    if (topAttached < 0) topAttached = i;
                }
            }
            if (win.mViewVisibility == View.VISIBLE
                    && win.mAttrs.type == TYPE_UNIVERSE_BACKGROUND
                    && universeBackground == null) {
                universeBackground = win.mWinAnimator;
            }
        }

        if (mAnimator.mUniverseBackground  != universeBackground) {
            mFocusMayChange = true;
            mAnimator.mUniverseBackground = universeBackground;
        }

        boolean attachedBehindDream = false;

        // Now perform layout of attached windows, which usually
        // depend on the position of the window they are attached to.
        // XXX does not deal with windows that are attached to windows
        // that are themselves attached.
        for (i = topAttached; i >= 0; i--) {
            final WindowState win = windows.get(i);

            if (win.mLayoutAttached) {
                if (DEBUG_LAYOUT) Slog.v(TAG, "2ND PASS " + win
                        + " mHaveFrame=" + win.mHaveFrame
                        + " mViewVisibility=" + win.mViewVisibility
                        + " mRelayoutCalled=" + win.mRelayoutCalled);
                // If this view is GONE, then skip it -- keep the current
                // frame, and let the caller know so they can ignore it
                // if they want.  (We do the normal layout for INVISIBLE
                // windows, since that means "perform layout as normal,
                // just don't display").
                if (attachedBehindDream && mPolicy.canBeForceHidden(win, win.mAttrs)) {
                    continue;
                }
                if ((win.mViewVisibility != View.GONE && win.mRelayoutCalled)
                        || !win.mHaveFrame || win.mLayoutNeeded) {
                    if (initial) {
                        //Slog.i(TAG, "Window " + this + " clearing mContentChanged - initial");
                        win.mContentChanged = false;
                    }
                    win.mLayoutNeeded = false;
                    win.prelayout();
                    mPolicy.layoutWindowLw(win, win.mAttrs, win.mAttachedWindow);
                    win.mLayoutSeq = seq;
                    if (DEBUG_LAYOUT) Slog.v(TAG, "  LAYOUT: mFrame="
                            + win.mFrame + " mContainingFrame="
                            + win.mContainingFrame + " mDisplayFrame="
                            + win.mDisplayFrame);
                }
            } else if (win.mAttrs.type == TYPE_DREAM) {
                // Don't layout windows behind a dream, so that if it
                // does stuff like hide the status bar we won't get a
                // bad transition when it goes away.
                attachedBehindDream = behindDream;
            }
        }
        
        // Window frames may have changed.  Tell the input dispatcher about it.
        mInputMonitor.setUpdateInputWindowsNeededLw();
        if (updateInputWindows) {
            mInputMonitor.updateInputWindowsLw(false /*force*/);
        }

        mPolicy.finishLayoutLw();
    }

    void makeWindowFreezingScreenIfNeededLocked(WindowState w) {
        // If the screen is currently frozen or off, then keep
        // it frozen/off until this window draws at its new
        // orientation.
        if (!okToDisplay()) {
            if (DEBUG_ORIENTATION) Slog.v(TAG,
                    "Changing surface while display frozen: " + w);
            w.mOrientationChanging = true;
            mInnerFields.mOrientationChangeComplete = false;
            if (!mWindowsFreezingScreen) {
                mWindowsFreezingScreen = true;
                // XXX should probably keep timeout from
                // when we first froze the display.
                mH.removeMessages(H.WINDOW_FREEZE_TIMEOUT);
                mH.sendMessageDelayed(mH.obtainMessage(
                        H.WINDOW_FREEZE_TIMEOUT), WINDOW_FREEZE_TIMEOUT_DURATION);
            }
        }
    }

    /**
     * Extracted from {@link #performLayoutAndPlaceSurfacesLockedInner} to reduce size of method.
     * @param windows List of windows on default display.
     * @return bitmap indicating if another pass through layout must be made.
     */
    public int handleAppTransitionReadyLocked(WindowList windows) {
        int changes = 0;
        int i;
        int NN = mOpeningApps.size();
        boolean goodToGo = true;
        if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                "Checking " + NN + " opening apps (frozen="
                + mDisplayFrozen + " timeout="
                + mAppTransitionTimeout + ")...");
        if (!mDisplayFrozen && !mAppTransitionTimeout) {
            // If the display isn't frozen, wait to do anything until
            // all of the apps are ready.  Otherwise just go because
            // we'll unfreeze the display when everyone is ready.
            for (i=0; i<NN && goodToGo; i++) {
                AppWindowToken wtoken = mOpeningApps.get(i);
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                        "Check opening app=" + wtoken + ": allDrawn="
                        + wtoken.allDrawn + " startingDisplayed="
                        + wtoken.startingDisplayed + " startingMoved="
                        + wtoken.startingMoved);
                if (!wtoken.allDrawn && !wtoken.startingDisplayed
                        && !wtoken.startingMoved) {
                    goodToGo = false;
                }
            }
        }
        if (goodToGo) {
            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "**** GOOD TO GO");
            int transit = mNextAppTransition;
            if (mSkipAppTransitionAnimation) {
                transit = WindowManagerPolicy.TRANSIT_UNSET;
            }
            mNextAppTransition = WindowManagerPolicy.TRANSIT_UNSET;
            mAppTransitionReady = false;
            mAppTransitionRunning = true;
            mAppTransitionTimeout = false;
            mStartingIconInTransition = false;
            mSkipAppTransitionAnimation = false;

            mH.removeMessages(H.APP_TRANSITION_TIMEOUT);

            rebuildAppWindowListLocked();

            // if wallpaper is animating in or out set oldWallpaper to null else to wallpaper
            WindowState oldWallpaper =
                    mWallpaperTarget != null && mWallpaperTarget.mWinAnimator.isAnimating()
                        && !mWallpaperTarget.mWinAnimator.isDummyAnimation()
                    ? null : mWallpaperTarget;

            adjustWallpaperWindowsLocked();
            mInnerFields.mWallpaperMayChange = false;

            // The top-most window will supply the layout params,
            // and we will determine it below.
            LayoutParams animLp = null;
            int bestAnimLayer = -1;
            boolean fullscreenAnim = false;

            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                    "New wallpaper target=" + mWallpaperTarget
                    + ", oldWallpaper=" + oldWallpaper
                    + ", lower target=" + mLowerWallpaperTarget
                    + ", upper target=" + mUpperWallpaperTarget);
            int foundWallpapers = 0;
            // Do a first pass through the tokens for two
            // things:
            // (1) Determine if both the closing and opening
            // app token sets are wallpaper targets, in which
            // case special animations are needed
            // (since the wallpaper needs to stay static
            // behind them).
            // (2) Find the layout params of the top-most
            // application window in the tokens, which is
            // what will control the animation theme.
            final int NC = mClosingApps.size();
            NN = NC + mOpeningApps.size();
            for (i=0; i<NN; i++) {
                AppWindowToken wtoken;
                int mode;
                if (i < NC) {
                    wtoken = mClosingApps.get(i);
                    mode = 1;
                } else {
                    wtoken = mOpeningApps.get(i-NC);
                    mode = 2;
                }
                if (mLowerWallpaperTarget != null) {
                    if (mLowerWallpaperTarget.mAppToken == wtoken
                            || mUpperWallpaperTarget.mAppToken == wtoken) {
                        foundWallpapers |= mode;
                    }
                }
                if (wtoken.appFullscreen) {
                    WindowState ws = wtoken.findMainWindow();
                    if (ws != null) {
                        animLp = ws.mAttrs;
                        bestAnimLayer = ws.mLayer;
                        fullscreenAnim = true;
                    }
                } else if (!fullscreenAnim) {
                    WindowState ws = wtoken.findMainWindow();
                    if (ws != null) {
                        if (ws.mLayer > bestAnimLayer) {
                            animLp = ws.mAttrs;
                            bestAnimLayer = ws.mLayer;
                        }
                    }
                }
            }

            if (foundWallpapers == 3) {
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                        "Wallpaper animation!");
                switch (transit) {
                    case WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN:
                    case WindowManagerPolicy.TRANSIT_TASK_OPEN:
                    case WindowManagerPolicy.TRANSIT_TASK_TO_FRONT:
                        transit = WindowManagerPolicy.TRANSIT_WALLPAPER_INTRA_OPEN;
                        break;
                    case WindowManagerPolicy.TRANSIT_ACTIVITY_CLOSE:
                    case WindowManagerPolicy.TRANSIT_TASK_CLOSE:
                    case WindowManagerPolicy.TRANSIT_TASK_TO_BACK:
                        transit = WindowManagerPolicy.TRANSIT_WALLPAPER_INTRA_CLOSE;
                        break;
                }
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                        "New transit: " + transit);
            } else if ((oldWallpaper != null) && !mOpeningApps.contains(oldWallpaper.mAppToken)) {
                // We are transitioning from an activity with
                // a wallpaper to one without.
                transit = WindowManagerPolicy.TRANSIT_WALLPAPER_CLOSE;
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                        "New transit away from wallpaper: " + transit);
            } else if (mWallpaperTarget != null && mWallpaperTarget.isVisibleLw()) {
                // We are transitioning from an activity without
                // a wallpaper to now showing the wallpaper
                transit = WindowManagerPolicy.TRANSIT_WALLPAPER_OPEN;
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                        "New transit into wallpaper: " + transit);
            }

            // If all closing windows are obscured, then there is
            // no need to do an animation.  This is the case, for
            // example, when this transition is being done behind
            // the lock screen.
            if (!mPolicy.allowAppAnimationsLw()) {
                animLp = null;
            }

            AppWindowToken topOpeningApp = null;
            int topOpeningLayer = 0;

            NN = mOpeningApps.size();
            for (i=0; i<NN; i++) {
                AppWindowToken wtoken = mOpeningApps.get(i);
                final AppWindowAnimator appAnimator = wtoken.mAppAnimator;
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "Now opening app" + wtoken);
                appAnimator.clearThumbnail();
                wtoken.reportedVisible = false;
                wtoken.inPendingTransaction = false;
                appAnimator.animation = null;
                setTokenVisibilityLocked(wtoken, animLp, true, transit, false);
                wtoken.updateReportedVisibilityLocked();
                wtoken.waitingToShow = false;

                appAnimator.mAllAppWinAnimators.clear();
                final int N = wtoken.allAppWindows.size();
                for (int j = 0; j < N; j++) {
                    appAnimator.mAllAppWinAnimators.add(wtoken.allAppWindows.get(j).mWinAnimator);
                }
                mAnimator.mAnimating |= appAnimator.showAllWindowsLocked();

                if (animLp != null) {
                    int layer = -1;
                    for (int j=0; j<wtoken.windows.size(); j++) {
                        WindowState win = wtoken.windows.get(j);
                        if (win.mWinAnimator.mAnimLayer > layer) {
                            layer = win.mWinAnimator.mAnimLayer;
                        }
                    }
                    if (topOpeningApp == null || layer > topOpeningLayer) {
                        topOpeningApp = wtoken;
                        topOpeningLayer = layer;
                    }
                }
            }
            NN = mClosingApps.size();
            for (i=0; i<NN; i++) {
                AppWindowToken wtoken = mClosingApps.get(i);
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                        "Now closing app " + wtoken);
                wtoken.mAppAnimator.clearThumbnail();
                wtoken.inPendingTransaction = false;
                wtoken.mAppAnimator.animation = null;
                setTokenVisibilityLocked(wtoken, animLp, false,
                        transit, false);
                wtoken.updateReportedVisibilityLocked();
                wtoken.waitingToHide = false;
                // Force the allDrawn flag, because we want to start
                // this guy's animations regardless of whether it's
                // gotten drawn.
                wtoken.allDrawn = true;
            }

            if (mNextAppTransitionThumbnail != null && topOpeningApp != null
                    && topOpeningApp.mAppAnimator.animation != null) {
                // This thumbnail animation is very special, we need to have
                // an extra surface with the thumbnail included with the animation.
                Rect dirty = new Rect(0, 0, mNextAppTransitionThumbnail.getWidth(),
                        mNextAppTransitionThumbnail.getHeight());
                try {
                    // TODO(multi-display): support other displays
                    final DisplayContent displayContent = getDefaultDisplayContentLocked();
                    final Display display = displayContent.getDisplay();
                    Surface surface = new Surface(mFxSession,
                            "thumbnail anim",
                            dirty.width(), dirty.height(),
                            PixelFormat.TRANSLUCENT, Surface.HIDDEN);
                    surface.setLayerStack(display.getLayerStack());
                    topOpeningApp.mAppAnimator.thumbnail = surface;
                    if (SHOW_TRANSACTIONS) Slog.i(TAG, "  THUMBNAIL "
                            + surface + ": CREATE");
                    Surface drawSurface = new Surface();
                    drawSurface.copyFrom(surface);
                    Canvas c = drawSurface.lockCanvas(dirty);
                    c.drawBitmap(mNextAppTransitionThumbnail, 0, 0, null);
                    drawSurface.unlockCanvasAndPost(c);
                    drawSurface.release();
                    topOpeningApp.mAppAnimator.thumbnailLayer = topOpeningLayer;
                    Animation anim = createThumbnailAnimationLocked(
                            transit, true, true, mNextAppTransitionScaleUp);
                    topOpeningApp.mAppAnimator.thumbnailAnimation = anim;
                    anim.restrictDuration(MAX_ANIMATION_DURATION);
                    anim.scaleCurrentDuration(mTransitionAnimationScale);
                    topOpeningApp.mAppAnimator.thumbnailX = mNextAppTransitionStartX;
                    topOpeningApp.mAppAnimator.thumbnailY = mNextAppTransitionStartY;
                } catch (Surface.OutOfResourcesException e) {
                    Slog.e(TAG, "Can't allocate thumbnail surface w=" + dirty.width()
                            + " h=" + dirty.height(), e);
                    topOpeningApp.mAppAnimator.clearThumbnail();
                }
            }

            mNextAppTransitionType = ActivityOptions.ANIM_NONE;
            mNextAppTransitionPackage = null;
            mNextAppTransitionThumbnail = null;
            scheduleAnimationCallback(mNextAppTransitionCallback);
            mNextAppTransitionCallback = null;

            mOpeningApps.clear();
            mClosingApps.clear();

            // This has changed the visibility of windows, so perform
            // a new layout to get them all up-to-date.
            changes |= WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT
                    | WindowManagerPolicy.FINISH_LAYOUT_REDO_CONFIG;
            getDefaultDisplayContentLocked().layoutNeeded = true;

            // TODO(multidisplay): IMEs are only supported on the default display.
            if (windows == getDefaultWindowListLocked()
                    && !moveInputMethodWindowsIfNeededLocked(true)) {
                assignLayersLocked(windows);
            }
            updateFocusedWindowLocked(UPDATE_FOCUS_PLACING_SURFACES, false /*updateInputWindows*/);
            mFocusMayChange = false;
        }

        return changes;
    }

    /**
     * Extracted from {@link #performLayoutAndPlaceSurfacesLockedInner} to reduce size of method.
     * @return bitmap indicating if another pass through layout must be made.
     */
    private int handleAnimatingStoppedAndTransitionLocked() {
        int changes = 0;

        mAppTransitionRunning = false;
        // Restore window app tokens to the ActivityManager views
        for (int i = mAnimatingAppTokens.size() - 1; i >= 0; i--) {
            mAnimatingAppTokens.get(i).sendingToBottom = false;
        }
        mAnimatingAppTokens.clear();
        mAnimatingAppTokens.addAll(mAppTokens);
        rebuildAppWindowListLocked();

        changes |= PhoneWindowManager.FINISH_LAYOUT_REDO_LAYOUT;
        mInnerFields.mAdjResult |= ADJUST_WALLPAPER_LAYERS_CHANGED;
        moveInputMethodWindowsIfNeededLocked(true);
        mInnerFields.mWallpaperMayChange = true;
        // Since the window list has been rebuilt, focus might
        // have to be recomputed since the actual order of windows
        // might have changed again.
        mFocusMayChange = true;

        return changes;
    }

    /**
     * Extracted from {@link #performLayoutAndPlaceSurfacesLockedInner} to reduce size of method.
     *
     * @return bitmap indicating if another pass through layout must be made.
     */
    private int animateAwayWallpaperLocked() {
        int changes = 0;
        WindowState oldWallpaper = mWallpaperTarget;
        if (mLowerWallpaperTarget != null
                && mLowerWallpaperTarget.mAppToken != null) {
            if (DEBUG_WALLPAPER_LIGHT) Slog.v(TAG,
                    "wallpaperForceHiding changed with lower="
                    + mLowerWallpaperTarget);
            if (DEBUG_WALLPAPER_LIGHT) Slog.v(TAG,
                    "hidden=" + mLowerWallpaperTarget.mAppToken.hidden +
                    " hiddenRequested=" + mLowerWallpaperTarget.mAppToken.hiddenRequested);
            if (mLowerWallpaperTarget.mAppToken.hidden) {
                // The lower target has become hidden before we
                // actually started the animation...  let's completely
                // re-evaluate everything.
                mLowerWallpaperTarget = mUpperWallpaperTarget = null;
                changes |= PhoneWindowManager.FINISH_LAYOUT_REDO_ANIM;
            }
        }
        mInnerFields.mAdjResult |= adjustWallpaperWindowsLocked();
        if (DEBUG_WALLPAPER_LIGHT) Slog.v(TAG, "****** OLD: " + oldWallpaper
                + " NEW: " + mWallpaperTarget
                + " LOWER: " + mLowerWallpaperTarget);
        return changes;
    }

    private void updateResizingWindows(final WindowState w) {
        final WindowStateAnimator winAnimator = w.mWinAnimator;
        if (w.mHasSurface && w.mLayoutSeq == mLayoutSeq) {
            w.mContentInsetsChanged |=
                    !w.mLastContentInsets.equals(w.mContentInsets);
            w.mVisibleInsetsChanged |=
                    !w.mLastVisibleInsets.equals(w.mVisibleInsets);
            boolean configChanged = w.isConfigChanged();
            if (DEBUG_CONFIGURATION && configChanged) {
                Slog.v(TAG, "Win " + w + " config changed: "
                        + mCurConfiguration);
            }
            if (localLOGV) Slog.v(TAG, "Resizing " + w
                    + ": configChanged=" + configChanged
                    + " last=" + w.mLastFrame + " frame=" + w.mFrame);
            w.mLastFrame.set(w.mFrame);
            if (w.mContentInsetsChanged
                    || w.mVisibleInsetsChanged
                    || winAnimator.mSurfaceResized
                    || configChanged) {
                if (DEBUG_RESIZE || DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Resize reasons: "
                            + " contentInsetsChanged=" + w.mContentInsetsChanged
                            + " visibleInsetsChanged=" + w.mVisibleInsetsChanged
                            + " surfaceResized=" + winAnimator.mSurfaceResized
                            + " configChanged=" + configChanged);
                }

                w.mLastContentInsets.set(w.mContentInsets);
                w.mLastVisibleInsets.set(w.mVisibleInsets);
                makeWindowFreezingScreenIfNeededLocked(w);
                // If the orientation is changing, then we need to
                // hold off on unfreezing the display until this
                // window has been redrawn; to do that, we need
                // to go through the process of getting informed
                // by the application when it has finished drawing.
                if (w.mOrientationChanging) {
                    if (DEBUG_SURFACE_TRACE || DEBUG_ANIM || DEBUG_ORIENTATION) Slog.v(TAG,
                            "Orientation start waiting for draw mDrawState=DRAW_PENDING in "
                            + w + ", surface " + winAnimator.mSurface);
                    winAnimator.mDrawState = WindowStateAnimator.DRAW_PENDING;
                    if (w.mAppToken != null) {
                        w.mAppToken.allDrawn = false;
                    }
                }
                if (!mResizingWindows.contains(w)) {
                    if (DEBUG_RESIZE || DEBUG_ORIENTATION) Slog.v(TAG,
                            "Resizing window " + w + " to " + winAnimator.mSurfaceW
                            + "x" + winAnimator.mSurfaceH);
                    mResizingWindows.add(w);
                }
            } else if (w.mOrientationChanging) {
                if (w.isDrawnLw()) {
                    if (DEBUG_ORIENTATION) Slog.v(TAG,
                            "Orientation not waiting for draw in "
                            + w + ", surface " + winAnimator.mSurface);
                    w.mOrientationChanging = false;
                }
            }
        }
    }

    /**
     * Extracted from {@link #performLayoutAndPlaceSurfacesLockedInner} to reduce size of method.
     *
     * @param w WindowState this method is applied to.
     * @param currentTime The time which animations use for calculating transitions.
     * @param innerDw Width of app window.
     * @param innerDh Height of app window.
     */
    private void handleNotObscuredLocked(final WindowState w, final long currentTime,
                                         final int innerDw, final int innerDh) {
        final WindowManager.LayoutParams attrs = w.mAttrs;
        final int attrFlags = attrs.flags;
        final boolean canBeSeen = w.isDisplayedLw();

        if (w.mHasSurface) {
            if ((attrFlags&FLAG_KEEP_SCREEN_ON) != 0) {
                mInnerFields.mHoldScreen = w.mSession;
            }
            if (!mInnerFields.mSyswin && w.mAttrs.screenBrightness >= 0
                    && mInnerFields.mScreenBrightness < 0) {
                mInnerFields.mScreenBrightness = w.mAttrs.screenBrightness;
            }
            if (!mInnerFields.mSyswin && w.mAttrs.buttonBrightness >= 0
                    && mInnerFields.mButtonBrightness < 0) {
                mInnerFields.mButtonBrightness = w.mAttrs.buttonBrightness;
            }
            if (!mInnerFields.mSyswin && w.mAttrs.userActivityTimeout >= 0
                    && mInnerFields.mUserActivityTimeout < 0) {
                mInnerFields.mUserActivityTimeout = w.mAttrs.userActivityTimeout;
            }

            final int type = attrs.type;
            if (canBeSeen
                    && (type == TYPE_SYSTEM_DIALOG
                     || type == TYPE_RECENTS_OVERLAY
                     || type == TYPE_KEYGUARD
                     || type == TYPE_SYSTEM_ERROR)) {
                mInnerFields.mSyswin = true;
            }

            if (canBeSeen) {
                if (type == TYPE_DREAM || type == TYPE_KEYGUARD) {
                    mInnerFields.mDisplayHasContent = LayoutFields.DISPLAY_CONTENT_MIRROR;
                } else if (mInnerFields.mDisplayHasContent
                        == LayoutFields.DISPLAY_CONTENT_UNKNOWN) {
                    mInnerFields.mDisplayHasContent = LayoutFields.DISPLAY_CONTENT_UNIQUE;
                }
            }
        }

        boolean opaqueDrawn = canBeSeen && w.isOpaqueDrawn();
        if (opaqueDrawn && w.isFullscreen(innerDw, innerDh)) {
            // This window completely covers everything behind it,
            // so we want to leave all of them as undimmed (for
            // performance reasons).
            mInnerFields.mObscured = true;
        } else if (canBeSeen && (attrFlags & FLAG_DIM_BEHIND) != 0
                && !(w.mAppToken != null && w.mAppToken.hiddenRequested)
                && !w.mExiting) {
            if (localLOGV) Slog.v(TAG, "Win " + w + " obscured=" + mInnerFields.mObscured);
            if (!mInnerFields.mDimming) {
                //Slog.i(TAG, "DIM BEHIND: " + w);
                mInnerFields.mDimming = true;
                final WindowStateAnimator winAnimator = w.mWinAnimator;
                if (!mAnimator.isDimmingLocked(winAnimator)) {
                    final int width, height;
                    if (attrs.type == TYPE_BOOT_PROGRESS) {
                        final DisplayInfo displayInfo = w.mDisplayContent.getDisplayInfo();
                        width = displayInfo.logicalWidth;
                        height = displayInfo.logicalHeight;
                    } else {
                        width = innerDw;
                        height = innerDh;
                    }
                    startDimmingLocked(
                        winAnimator, w.mExiting ? 0 : w.mAttrs.dimAmount, width, height);
                }
            }
        }
    }

    private void updateAllDrawnLocked() {
        // See if any windows have been drawn, so they (and others
        // associated with them) can now be shown.
        final ArrayList<AppWindowToken> appTokens = mAnimatingAppTokens;
        final int NT = appTokens.size();
        for (int i=0; i<NT; i++) {
            AppWindowToken wtoken = appTokens.get(i);
            if (!wtoken.allDrawn) {
                int numInteresting = wtoken.numInterestingWindows;
                if (numInteresting > 0 && wtoken.numDrawnWindows >= numInteresting) {
                    if (WindowManagerService.DEBUG_VISIBILITY) Slog.v(TAG,
                            "allDrawn: " + wtoken
                            + " interesting=" + numInteresting
                            + " drawn=" + wtoken.numDrawnWindows);
                    wtoken.allDrawn = true;
                }
            }
        }
    }

    // "Something has changed!  Let's make it correct now."
    private final void performLayoutAndPlaceSurfacesLockedInner(boolean recoveringMemory) {
        if (DEBUG_WINDOW_TRACE) {
            Slog.v(TAG, "performLayoutAndPlaceSurfacesLockedInner: entry. Called by "
                    + Debug.getCallers(3));
        }

        final long currentTime = SystemClock.uptimeMillis();

        int i;

        if (mFocusMayChange) {
            mFocusMayChange = false;
            updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                    false /*updateInputWindows*/);
        }

        // Initialize state of exiting tokens.
        for (i=mExitingTokens.size()-1; i>=0; i--) {
            mExitingTokens.get(i).hasVisible = false;
        }

        // Initialize state of exiting applications.
        for (i=mExitingAppTokens.size()-1; i>=0; i--) {
            mExitingAppTokens.get(i).hasVisible = false;
        }

        mInnerFields.mHoldScreen = null;
        mInnerFields.mScreenBrightness = -1;
        mInnerFields.mButtonBrightness = -1;
        mInnerFields.mUserActivityTimeout = -1;
        mInnerFields.mDisplayHasContent = LayoutFields.DISPLAY_CONTENT_UNKNOWN;

        mTransactionSequence++;

        final DisplayContent defaultDisplay = getDefaultDisplayContentLocked();
        final DisplayInfo defaultInfo = defaultDisplay.getDisplayInfo();
        final int defaultDw = defaultInfo.logicalWidth;
        final int defaultDh = defaultInfo.logicalHeight;

        if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                ">>> OPEN TRANSACTION performLayoutAndPlaceSurfaces");
        Surface.openTransaction();
        try {

            if (mWatermark != null) {
                mWatermark.positionSurface(defaultDw, defaultDh);
            }
            if (mStrictModeFlash != null) {
                mStrictModeFlash.positionSurface(defaultDw, defaultDh);
            }

            boolean focusDisplayed = false;
            boolean updateAllDrawn = false;

            DisplayContentsIterator iterator = new DisplayContentsIterator();
            while (iterator.hasNext()) {
                final DisplayContent displayContent = iterator.next();
                WindowList windows = displayContent.getWindowList();
                DisplayInfo displayInfo = displayContent.getDisplayInfo();
                final int displayId = displayContent.getDisplayId();
                final int dw = displayInfo.logicalWidth;
                final int dh = displayInfo.logicalHeight;
                final int innerDw = displayInfo.appWidth;
                final int innerDh = displayInfo.appHeight;
                final boolean isDefaultDisplay = (displayId == Display.DEFAULT_DISPLAY);

                // Reset for each display unless we are forcing mirroring.
                if (mInnerFields.mDisplayHasContent != LayoutFields.DISPLAY_CONTENT_MIRROR) {
                    mInnerFields.mDisplayHasContent = LayoutFields.DISPLAY_CONTENT_UNKNOWN;
                }

                int repeats = 0;
                do {
                    repeats++;
                    if (repeats > 6) {
                        Slog.w(TAG, "Animation repeat aborted after too many iterations");
                        displayContent.layoutNeeded = false;
                        break;
                    }

                    if (DEBUG_LAYOUT_REPEATS) debugLayoutRepeats("On entry to LockedInner",
                        displayContent.pendingLayoutChanges);

                    if (isDefaultDisplay && ((displayContent.pendingLayoutChanges
                            & WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER) != 0)
                            && ((adjustWallpaperWindowsLocked()
                                    & ADJUST_WALLPAPER_LAYERS_CHANGED) != 0)) {
                        assignLayersLocked(windows);
                        displayContent.layoutNeeded = true;
                    }

                    if (isDefaultDisplay && (displayContent.pendingLayoutChanges
                            & WindowManagerPolicy.FINISH_LAYOUT_REDO_CONFIG) != 0) {
                        if (DEBUG_LAYOUT) Slog.v(TAG, "Computing new config from layout");
                        if (updateOrientationFromAppTokensLocked(true)) {
                            displayContent.layoutNeeded = true;
                            mH.sendEmptyMessage(H.SEND_NEW_CONFIGURATION);
                        }
                    }

                    if ((displayContent.pendingLayoutChanges
                            & WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT) != 0) {
                        displayContent.layoutNeeded = true;
                    }

                    // FIRST LOOP: Perform a layout, if needed.
                    if (repeats < 4) {
                        performLayoutLockedInner(displayContent, repeats == 1,
                                false /*updateInputWindows*/);
                    } else {
                        Slog.w(TAG, "Layout repeat skipped after too many iterations");
                    }

                    // FIRST AND ONE HALF LOOP: Make WindowManagerPolicy think
                    // it is animating.
                    displayContent.pendingLayoutChanges = 0;

                    if (DEBUG_LAYOUT_REPEATS) debugLayoutRepeats("loop number "
                            + mLayoutRepeatCount, displayContent.pendingLayoutChanges);

                    if (isDefaultDisplay) {
                        mPolicy.beginPostLayoutPolicyLw(dw, dh);
                        for (i = windows.size() - 1; i >= 0; i--) {
                            WindowState w = windows.get(i);
                            if (w.mHasSurface) {
                                mPolicy.applyPostLayoutPolicyLw(w, w.mAttrs);
                            }
                        }
                        displayContent.pendingLayoutChanges |= mPolicy.finishPostLayoutPolicyLw();
                        if (DEBUG_LAYOUT_REPEATS) debugLayoutRepeats(
                            "after finishPostLayoutPolicyLw", displayContent.pendingLayoutChanges);
                    }
                } while (displayContent.pendingLayoutChanges != 0);

                mInnerFields.mObscured = false;
                mInnerFields.mDimming = false;
                mInnerFields.mSyswin = false;

                // Only used if default window
                final boolean someoneLosingFocus = !mLosingFocus.isEmpty();

                final int N = windows.size();
                for (i=N-1; i>=0; i--) {
                    WindowState w = windows.get(i);

                    final boolean obscuredChanged = w.mObscured != mInnerFields.mObscured;

                    // Update effect.
                    w.mObscured = mInnerFields.mObscured;
                    if (!mInnerFields.mObscured) {
                        handleNotObscuredLocked(w, currentTime, innerDw, innerDh);
                    }

                    if (isDefaultDisplay && obscuredChanged && (mWallpaperTarget == w)
                            && w.isVisibleLw()) {
                        // This is the wallpaper target and its obscured state
                        // changed... make sure the current wallaper's visibility
                        // has been updated accordingly.
                        updateWallpaperVisibilityLocked();
                    }

                    final WindowStateAnimator winAnimator = w.mWinAnimator;

                    // If the window has moved due to its containing
                    // content frame changing, then we'd like to animate
                    // it.
                    if (w.mHasSurface && w.shouldAnimateMove()) {
                        // Frame has moved, containing content frame
                        // has also moved, and we're not currently animating...
                        // let's do something.
                        Animation a = AnimationUtils.loadAnimation(mContext,
                                com.android.internal.R.anim.window_move_from_decor);
                        winAnimator.setAnimation(a);
                        winAnimator.mAnimDw = w.mLastFrame.left - w.mFrame.left;
                        winAnimator.mAnimDh = w.mLastFrame.top - w.mFrame.top;
                        try {
                            w.mClient.moved(w.mFrame.left, w.mFrame.top);
                        } catch (RemoteException e) {
                        }
                    }

                    //Slog.i(TAG, "Window " + this + " clearing mContentChanged - done placing");
                    w.mContentChanged = false;

                    // Moved from updateWindowsAndWallpaperLocked().
                    if (w.mHasSurface) {
                        // Take care of the window being ready to display.
                        final boolean committed =
                                winAnimator.commitFinishDrawingLocked(currentTime);
                        if (isDefaultDisplay && committed) {
                            if (w.mAttrs.type == TYPE_DREAM) {
                                // HACK: When a dream is shown, it may at that
                                // point hide the lock screen.  So we need to
                                // redo the layout to let the phone window manager
                                // make this happen.
                                displayContent.pendingLayoutChanges |=
                                        WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
                                if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                                    debugLayoutRepeats(
                                        "dream and commitFinishDrawingLocked true",
                                        displayContent.pendingLayoutChanges);
                                }
                            }
                            if ((w.mAttrs.flags & FLAG_SHOW_WALLPAPER) != 0) {
                                if (DEBUG_WALLPAPER_LIGHT) Slog.v(TAG,
                                        "First draw done in potential wallpaper target " + w);
                                mInnerFields.mWallpaperMayChange = true;
                                displayContent.pendingLayoutChanges |=
                                        WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
                                if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                                    debugLayoutRepeats(
                                        "wallpaper and commitFinishDrawingLocked true",
                                        displayContent.pendingLayoutChanges);
                                }
                            }
                        }

                        winAnimator.setSurfaceBoundariesLocked(recoveringMemory);

                        final AppWindowToken atoken = w.mAppToken;
                        if (DEBUG_STARTING_WINDOW && atoken != null
                                && w == atoken.startingWindow) {
                            Slog.d(TAG, "updateWindows: starting " + w + " isOnScreen="
                                + w.isOnScreen() + " allDrawn=" + atoken.allDrawn
                                + " freezingScreen=" + atoken.mAppAnimator.freezingScreen);
                        }
                        if (atoken != null
                                && (!atoken.allDrawn || atoken.mAppAnimator.freezingScreen)) {
                            if (atoken.lastTransactionSequence != mTransactionSequence) {
                                atoken.lastTransactionSequence = mTransactionSequence;
                                atoken.numInterestingWindows = atoken.numDrawnWindows = 0;
                                atoken.startingDisplayed = false;
                            }
                            if ((w.isOnScreen() || winAnimator.mAttrType == TYPE_BASE_APPLICATION)
                                    && !w.mExiting && !w.mDestroying) {
                                if (WindowManagerService.DEBUG_VISIBILITY ||
                                        WindowManagerService.DEBUG_ORIENTATION) {
                                    Slog.v(TAG, "Eval win " + w + ": isDrawn=" + w.isDrawnLw()
                                            + ", isAnimating=" + winAnimator.isAnimating());
                                    if (!w.isDrawnLw()) {
                                        Slog.v(TAG, "Not displayed: s=" + winAnimator.mSurface
                                                + " pv=" + w.mPolicyVisibility
                                                + " mDrawState=" + winAnimator.mDrawState
                                                + " ah=" + w.mAttachedHidden
                                                + " th=" + atoken.hiddenRequested
                                                + " a=" + winAnimator.mAnimating);
                                    }
                                }
                                if (w != atoken.startingWindow) {
                                    if (!atoken.mAppAnimator.freezingScreen || !w.mAppFreezing) {
                                        atoken.numInterestingWindows++;
                                        if (w.isDrawnLw()) {
                                            atoken.numDrawnWindows++;
                                            if (WindowManagerService.DEBUG_VISIBILITY ||
                                                    WindowManagerService.DEBUG_ORIENTATION) Slog.v(TAG,
                                                    "tokenMayBeDrawn: " + atoken
                                                    + " freezingScreen=" + atoken.mAppAnimator.freezingScreen
                                                    + " mAppFreezing=" + w.mAppFreezing);
                                            updateAllDrawn = true;
                                        }
                                    }
                                } else if (w.isDrawnLw()) {
                                    atoken.startingDisplayed = true;
                                }
                            }
                        }
                    }

                    if (isDefaultDisplay && someoneLosingFocus && (w == mCurrentFocus)
                            && w.isDisplayedLw()) {
                        focusDisplayed = true;
                    }

                    updateResizingWindows(w);
                }

                final boolean hasUniqueContent;
                switch (mInnerFields.mDisplayHasContent) {
                    case LayoutFields.DISPLAY_CONTENT_MIRROR:
                        hasUniqueContent = isDefaultDisplay;
                        break;
                    case LayoutFields.DISPLAY_CONTENT_UNIQUE:
                        hasUniqueContent = true;
                        break;
                    case LayoutFields.DISPLAY_CONTENT_UNKNOWN:
                    default:
                        hasUniqueContent = false;
                        break;
                }
                mDisplayManagerService.setDisplayHasContent(displayId, hasUniqueContent,
                        true /* inTraversal, must call performTraversalInTrans... below */);

                if (!mInnerFields.mDimming && mAnimator.isDimmingLocked(displayId)) {
                    stopDimmingLocked(displayId);
                }
            }

            if (updateAllDrawn) {
                updateAllDrawnLocked();
            }

            if (focusDisplayed) {
                mH.sendEmptyMessage(H.REPORT_LOSING_FOCUS);
            }

            // Give the display manager a chance to adjust properties
            // like display rotation if it needs to.
            mDisplayManagerService.performTraversalInTransactionFromWindowManager();

        } catch (RuntimeException e) {
            Log.wtf(TAG, "Unhandled exception in Window Manager", e);
        } finally {
            Surface.closeTransaction();
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                    "<<< CLOSE TRANSACTION performLayoutAndPlaceSurfaces");
        }

        final WindowList defaultWindows = defaultDisplay.getWindowList();

        // If we are ready to perform an app transition, check through
        // all of the app tokens to be shown and see if they are ready
        // to go.
        if (mAppTransitionReady) {
            defaultDisplay.pendingLayoutChanges |= handleAppTransitionReadyLocked(defaultWindows);
            if (DEBUG_LAYOUT_REPEATS) debugLayoutRepeats("after handleAppTransitionReadyLocked",
                defaultDisplay.pendingLayoutChanges);
        }

        mInnerFields.mAdjResult = 0;

        if (!mAnimator.mAnimating && mAppTransitionRunning) {
            // We have finished the animation of an app transition.  To do
            // this, we have delayed a lot of operations like showing and
            // hiding apps, moving apps in Z-order, etc.  The app token list
            // reflects the correct Z-order, but the window list may now
            // be out of sync with it.  So here we will just rebuild the
            // entire app window list.  Fun!
            defaultDisplay.pendingLayoutChanges |= handleAnimatingStoppedAndTransitionLocked();
            if (DEBUG_LAYOUT_REPEATS) debugLayoutRepeats("after handleAnimStopAndXitionLock",
                defaultDisplay.pendingLayoutChanges);
        }

        if (mInnerFields.mWallpaperForceHidingChanged && defaultDisplay.pendingLayoutChanges == 0
                && !mAppTransitionReady) {
            // At this point, there was a window with a wallpaper that
            // was force hiding other windows behind it, but now it
            // is going away.  This may be simple -- just animate
            // away the wallpaper and its window -- or it may be
            // hard -- the wallpaper now needs to be shown behind
            // something that was hidden.
            defaultDisplay.pendingLayoutChanges |= animateAwayWallpaperLocked();
            if (DEBUG_LAYOUT_REPEATS) debugLayoutRepeats("after animateAwayWallpaperLocked",
                defaultDisplay.pendingLayoutChanges);
        }
        mInnerFields.mWallpaperForceHidingChanged = false;

        if (mInnerFields.mWallpaperMayChange) {
            if (WindowManagerService.DEBUG_WALLPAPER_LIGHT) Slog.v(TAG,
                    "Wallpaper may change!  Adjusting");
            mInnerFields.mAdjResult |= adjustWallpaperWindowsLocked();
        }

        if ((mInnerFields.mAdjResult&ADJUST_WALLPAPER_LAYERS_CHANGED) != 0) {
            if (DEBUG_WALLPAPER_LIGHT) Slog.v(TAG,
                    "Wallpaper layer changed: assigning layers + relayout");
            defaultDisplay.pendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
            assignLayersLocked(defaultWindows);
        } else if ((mInnerFields.mAdjResult&ADJUST_WALLPAPER_VISIBILITY_CHANGED) != 0) {
            if (DEBUG_WALLPAPER_LIGHT) Slog.v(TAG,
                    "Wallpaper visibility changed: relayout");
            defaultDisplay.pendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
        }

        if (mFocusMayChange) {
            mFocusMayChange = false;
            if (updateFocusedWindowLocked(UPDATE_FOCUS_PLACING_SURFACES,
                    false /*updateInputWindows*/)) {
                defaultDisplay.pendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
                mInnerFields.mAdjResult = 0;
            }
        }

        if (needsLayout()) {
            defaultDisplay.pendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
            if (DEBUG_LAYOUT_REPEATS) debugLayoutRepeats("mLayoutNeeded",
                    defaultDisplay.pendingLayoutChanges);
        }

        for (i = mResizingWindows.size() - 1; i >= 0; i--) {
            WindowState win = mResizingWindows.get(i);
            if (win.mAppFreezing) {
                // Don't remove this window until rotation has completed.
                continue;
            }
            final WindowStateAnimator winAnimator = win.mWinAnimator;
            try {
                if (DEBUG_RESIZE || DEBUG_ORIENTATION) Slog.v(TAG,
                        "Reporting new frame to " + win + ": " + win.mCompatFrame);
                int diff = 0;
                boolean configChanged = win.isConfigChanged();
                if ((DEBUG_RESIZE || DEBUG_ORIENTATION || DEBUG_CONFIGURATION)
                        && configChanged) {
                    Slog.i(TAG, "Sending new config to window " + win + ": "
                            + winAnimator.mSurfaceW + "x" + winAnimator.mSurfaceH
                            + " / " + mCurConfiguration + " / 0x"
                            + Integer.toHexString(diff));
                }
                win.mConfiguration = mCurConfiguration;
                if (DEBUG_ORIENTATION &&
                        winAnimator.mDrawState == WindowStateAnimator.DRAW_PENDING) Slog.i(
                        TAG, "Resizing " + win + " WITH DRAW PENDING");
                win.mClient.resized(win.mFrame, win.mLastContentInsets, win.mLastVisibleInsets,
                        winAnimator.mDrawState == WindowStateAnimator.DRAW_PENDING,
                        configChanged ? win.mConfiguration : null);
                win.mContentInsetsChanged = false;
                win.mVisibleInsetsChanged = false;
                winAnimator.mSurfaceResized = false;
            } catch (RemoteException e) {
                win.mOrientationChanging = false;
            }
            mResizingWindows.remove(i);
        }

        if (DEBUG_ORIENTATION && mDisplayFrozen) Slog.v(TAG,
                "With display frozen, orientationChangeComplete="
                + mInnerFields.mOrientationChangeComplete);
        if (mInnerFields.mOrientationChangeComplete) {
            if (mWindowsFreezingScreen) {
                mWindowsFreezingScreen = false;
                mH.removeMessages(H.WINDOW_FREEZE_TIMEOUT);
            }
            stopFreezingDisplayLocked();
        }

        // Destroy the surface of any windows that are no longer visible.
        boolean wallpaperDestroyed = false;
        i = mDestroySurface.size();
        if (i > 0) {
            do {
                i--;
                WindowState win = mDestroySurface.get(i);
                win.mDestroying = false;
                if (mInputMethodWindow == win) {
                    mInputMethodWindow = null;
                }
                if (win == mWallpaperTarget) {
                    wallpaperDestroyed = true;
                }
                win.mWinAnimator.destroySurfaceLocked(false);
            } while (i > 0);
            mDestroySurface.clear();
        }

        // Time to remove any exiting tokens?
        for (i=mExitingTokens.size()-1; i>=0; i--) {
            WindowToken token = mExitingTokens.get(i);
            if (!token.hasVisible) {
                mExitingTokens.remove(i);
                if (token.windowType == TYPE_WALLPAPER) {
                    mWallpaperTokens.remove(token);
                    updateLayoutToAnimWallpaperTokens();
                }
            }
        }

        // Time to remove any exiting applications?
        for (i=mExitingAppTokens.size()-1; i>=0; i--) {
            AppWindowToken token = mExitingAppTokens.get(i);
            if (!token.hasVisible && !mClosingApps.contains(token)) {
                // Make sure there is no animation running on this token,
                // so any windows associated with it will be removed as
                // soon as their animations are complete
                token.mAppAnimator.clearAnimation();
                token.mAppAnimator.animating = false;
                if (DEBUG_ADD_REMOVE || DEBUG_TOKEN_MOVEMENT) Slog.v(TAG,
                        "performLayout: App token exiting now removed" + token);
                mAppTokens.remove(token);
                mAnimatingAppTokens.remove(token);
                mExitingAppTokens.remove(i);
            }
        }

        if (!mAnimator.mAnimating && mRelayoutWhileAnimating.size() > 0) {
            for (int j=mRelayoutWhileAnimating.size()-1; j>=0; j--) {
                try {
                    mRelayoutWhileAnimating.get(j).mClient.doneAnimating();
                } catch (RemoteException e) {
                }
            }
            mRelayoutWhileAnimating.clear();
        }

        if (wallpaperDestroyed && (adjustWallpaperWindowsLocked() != 0)) {
            getDefaultDisplayContentLocked().layoutNeeded = true;
        }

        DisplayContentsIterator iterator = new DisplayContentsIterator();
        while (iterator.hasNext()) {
            DisplayContent displayContent = iterator.next();
            if (displayContent.pendingLayoutChanges != 0) {
                displayContent.layoutNeeded = true;
            }
        }

        // Finally update all input windows now that the window changes have stabilized.
        mInputMonitor.updateInputWindowsLw(true /*force*/);

        setHoldScreenLocked(mInnerFields.mHoldScreen);
        if (!mDisplayFrozen) {
            if (mInnerFields.mScreenBrightness < 0 || mInnerFields.mScreenBrightness > 1.0f) {
                mPowerManager.setScreenBrightnessOverrideFromWindowManager(-1);
            } else {
                mPowerManager.setScreenBrightnessOverrideFromWindowManager(
                        toBrightnessOverride(mInnerFields.mScreenBrightness));
            }
            if (mInnerFields.mButtonBrightness < 0 || mInnerFields.mButtonBrightness > 1.0f) {
                mPowerManager.setButtonBrightnessOverrideFromWindowManager(-1);
            } else {
                mPowerManager.setButtonBrightnessOverrideFromWindowManager(
                        toBrightnessOverride(mInnerFields.mButtonBrightness));
            }
            mPowerManager.setUserActivityTimeoutOverrideFromWindowManager(
                    mInnerFields.mUserActivityTimeout);
        }

        if (mTurnOnScreen) {
            if (DEBUG_VISIBILITY) Slog.v(TAG, "Turning screen on after layout!");
            mPowerManager.wakeUp(SystemClock.uptimeMillis());
            mTurnOnScreen = false;
        }

        if (mInnerFields.mUpdateRotation) {
            if (DEBUG_ORIENTATION) Slog.d(TAG, "Performing post-rotate rotation");
            if (updateRotationUncheckedLocked(false)) {
                mH.sendEmptyMessage(H.SEND_NEW_CONFIGURATION);
            } else {
                mInnerFields.mUpdateRotation = false;
            }
        }

        if (mInnerFields.mOrientationChangeComplete && !defaultDisplay.layoutNeeded
                && !mInnerFields.mUpdateRotation) {
            checkDrawnWindowsLocked();
        }

        final int N = mPendingRemove.size();
        if (N > 0) {
            if (mPendingRemoveTmp.length < N) {
                mPendingRemoveTmp = new WindowState[N+10];
            }
            mPendingRemove.toArray(mPendingRemoveTmp);
            mPendingRemove.clear();
            DisplayContentList displayList = new DisplayContentList();
            for (i = 0; i < N; i++) {
                WindowState w = mPendingRemoveTmp[i];
                removeWindowInnerLocked(w.mSession, w);
                if (!displayList.contains(w.mDisplayContent)) {
                    displayList.add(w.mDisplayContent);
                }
            }

            for (DisplayContent displayContent : displayList) {
                assignLayersLocked(displayContent.getWindowList());
                displayContent.layoutNeeded = true;
            }
        }

        // Check to see if we are now in a state where the screen should
        // be enabled, because the window obscured flags have changed.
        enableScreenIfNeededLocked();

        updateLayoutToAnimationLocked();

        if (DEBUG_WINDOW_TRACE) {
            Slog.e(TAG, "performLayoutAndPlaceSurfacesLockedInner exit: animating="
                    + mAnimator.mAnimating);
        }
    }

    private int toBrightnessOverride(float value) {
        return (int)(value * PowerManager.BRIGHTNESS_ON);
    }

    void checkDrawnWindowsLocked() {
        if (mWaitingForDrawn.size() > 0) {
            for (int j=mWaitingForDrawn.size()-1; j>=0; j--) {
                Pair<WindowState, IRemoteCallback> pair = mWaitingForDrawn.get(j);
                WindowState win = pair.first;
                //Slog.i(TAG, "Waiting for drawn " + win + ": removed="
                //        + win.mRemoved + " visible=" + win.isVisibleLw()
                //        + " shown=" + win.mSurfaceShown);
                if (win.mRemoved || !win.isVisibleLw()) {
                    // Window has been removed or made invisible; no draw
                    // will now happen, so stop waiting.
                    Slog.w(TAG, "Aborted waiting for drawn: " + pair.first);
                    try {
                        pair.second.sendResult(null);
                    } catch (RemoteException e) {
                    }
                    mWaitingForDrawn.remove(pair);
                    mH.removeMessages(H.WAITING_FOR_DRAWN_TIMEOUT, pair);
                } else if (win.mWinAnimator.mSurfaceShown) {
                    // Window is now drawn (and shown).
                    try {
                        pair.second.sendResult(null);
                    } catch (RemoteException e) {
                    }
                    mWaitingForDrawn.remove(pair);
                    mH.removeMessages(H.WAITING_FOR_DRAWN_TIMEOUT, pair);
                }
            }
        }
    }

    public boolean waitForWindowDrawn(IBinder token, IRemoteCallback callback) {
        if (token != null && callback != null) {
            synchronized (mWindowMap) {
                WindowState win = windowForClientLocked(null, token, true);
                if (win != null) {
                    Pair<WindowState, IRemoteCallback> pair =
                            new Pair<WindowState, IRemoteCallback>(win, callback);
                    Message m = mH.obtainMessage(H.WAITING_FOR_DRAWN_TIMEOUT, pair);
                    mH.sendMessageDelayed(m, 2000);
                    mWaitingForDrawn.add(pair);
                    checkDrawnWindowsLocked();
                    return true;
                }
            }
        }
        return false;
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
                mHoldingScreenWakeLock.acquire();
                mPolicy.keepScreenOnStartedLw();
            } else {
                mPolicy.keepScreenOnStoppedLw();
                mHoldingScreenWakeLock.release();
            }
        }
    }

    @Override
    public void requestTraversal() {
        synchronized (mWindowMap) {
            requestTraversalLocked();
        }
    }

    void requestTraversalLocked() {
        if (!mTraversalScheduled) {
            mTraversalScheduled = true;
            mH.sendEmptyMessage(H.DO_TRAVERSAL);
        }
    }

    /** Note that Locked in this case is on mLayoutToAnim */
    void scheduleAnimationLocked() {
        final LayoutToAnimatorParams layoutToAnim = mLayoutToAnim;
        if (!layoutToAnim.mAnimationScheduled) {
            layoutToAnim.mAnimationScheduled = true;
            mChoreographer.postCallback(
                    Choreographer.CALLBACK_ANIMATION, mAnimator.mAnimationRunnable, null);
        }
    }

    void updateLayoutToAnimationLocked() {
        final LayoutToAnimatorParams layoutToAnim = mLayoutToAnim;
        synchronized (layoutToAnim) {
            // Copy local params to transfer params.
            SparseArray<WinAnimatorList> allWinAnimatorLists = layoutToAnim.mWinAnimatorLists;
            allWinAnimatorLists.clear();
            DisplayContentsIterator iterator = new DisplayContentsIterator();
            while (iterator.hasNext()) {
                final DisplayContent displayContent = iterator.next();
                WinAnimatorList winAnimatorList = new WinAnimatorList();
                final WindowList windows = displayContent.getWindowList();
                int N = windows.size();
                for (int i = 0; i < N; i++) {
                    final WindowStateAnimator winAnimator = windows.get(i).mWinAnimator;
                    if (winAnimator.mSurface != null) {
                        winAnimatorList.add(winAnimator);
                    }
                }
                allWinAnimatorLists.put(displayContent.getDisplayId(), winAnimatorList);
            }

            if (WindowManagerService.DEBUG_WALLPAPER_LIGHT) {
                if (mWallpaperTarget != layoutToAnim.mWallpaperTarget
                        || mLowerWallpaperTarget != layoutToAnim.mLowerWallpaperTarget
                        || mUpperWallpaperTarget != layoutToAnim.mUpperWallpaperTarget) {
                    Slog.d(TAG, "Pushing anim wallpaper: target=" + mWallpaperTarget
                            + " lower=" + mLowerWallpaperTarget + " upper="
                            + mUpperWallpaperTarget + "\n" + Debug.getCallers(5, "  "));
                }
            }
            layoutToAnim.mWallpaperTarget = mWallpaperTarget;
            layoutToAnim.mLowerWallpaperTarget = mLowerWallpaperTarget;
            layoutToAnim.mUpperWallpaperTarget = mUpperWallpaperTarget;

            final ArrayList<AppWindowAnimParams> paramList = layoutToAnim.mAppWindowAnimParams;
            paramList.clear();
            int N = mAnimatingAppTokens.size();
            for (int i = 0; i < N; i++) {
                paramList.add(new AppWindowAnimParams(mAnimatingAppTokens.get(i).mAppAnimator));
            }

            layoutToAnim.mParamsModified = true;
            scheduleAnimationLocked();
        }
    }

    void updateLayoutToAnimWallpaperTokens() {
        synchronized(mLayoutToAnim) {
            mLayoutToAnim.mWallpaperTokens = new ArrayList<WindowToken>(mWallpaperTokens);
            mLayoutToAnim.mChanges |= LayoutToAnimatorParams.WALLPAPER_TOKENS_CHANGED;
        }
    }

    void setAnimDimParams(int displayId, DimAnimator.Parameters params) {
        synchronized (mLayoutToAnim) {
            mLayoutToAnim.mDimParams.put(displayId, params);
            scheduleAnimationLocked();
        }
    }

    void startDimmingLocked(final WindowStateAnimator winAnimator, final float target,
                      final int width, final int height) {
        setAnimDimParams(winAnimator.mWin.getDisplayId(),
                new DimAnimator.Parameters(winAnimator, width, height, target));
    }

    void stopDimmingLocked(int displayId) {
        setAnimDimParams(displayId, null);
    }

    private boolean needsLayout() {
        DisplayContentsIterator iterator = new DisplayContentsIterator();
        while (iterator.hasNext()) {
            if (iterator.next().layoutNeeded) {
                return true;
            }
        }
        return false;
    }

    private boolean copyAnimToLayoutParamsLocked() {
        boolean doRequest = false;
        final WindowAnimator.AnimatorToLayoutParams animToLayout = mAnimator.mAnimToLayout;
        synchronized (animToLayout) {
            animToLayout.mUpdateQueued = false;
            final int bulkUpdateParams = animToLayout.mBulkUpdateParams;
            // TODO(cmautner): As the number of bits grows, use masks of bit groups to
            //  eliminate unnecessary tests.
            if ((bulkUpdateParams & LayoutFields.SET_UPDATE_ROTATION) != 0) {
                mInnerFields.mUpdateRotation = true;
                doRequest = true;
            }
            if ((bulkUpdateParams & LayoutFields.SET_WALLPAPER_MAY_CHANGE) != 0) {
                mInnerFields.mWallpaperMayChange = true;
                doRequest = true;
            }
            if ((bulkUpdateParams & LayoutFields.SET_FORCE_HIDING_CHANGED) != 0) {
                mInnerFields.mWallpaperForceHidingChanged = true;
                doRequest = true;
            }
            if ((bulkUpdateParams & LayoutFields.SET_ORIENTATION_CHANGE_COMPLETE) == 0) {
                mInnerFields.mOrientationChangeComplete = false;
            } else {
                mInnerFields.mOrientationChangeComplete = true;
                if (mWindowsFreezingScreen) {
                    doRequest = true;
                }
            }
            if ((bulkUpdateParams & LayoutFields.SET_TURN_ON_SCREEN) != 0) {
                mTurnOnScreen = true;
            }

            SparseIntArray pendingLayouts = animToLayout.mPendingLayoutChanges;
            final int count = pendingLayouts.size();
            if (count > 0) {
                doRequest = true;
            }
            for (int i = 0; i < count; ++i) {
                final DisplayContent displayContent =
                        getDisplayContentLocked(pendingLayouts.keyAt(i));
                if (displayContent != null) {
                    displayContent.pendingLayoutChanges |= pendingLayouts.valueAt(i);
                }
            }

            mWindowDetachedWallpaper = animToLayout.mWindowDetachedWallpaper;
        }
        return doRequest;
    }

    boolean reclaimSomeSurfaceMemoryLocked(WindowStateAnimator winAnimator, String operation,
                                           boolean secure) {
        final Surface surface = winAnimator.mSurface;
        boolean leakedSurface = false;
        boolean killedApps = false;

        EventLog.writeEvent(EventLogTags.WM_NO_SURFACE_MEMORY, winAnimator.mWin.toString(),
                winAnimator.mSession.mPid, operation);

        if (mForceRemoves == null) {
            mForceRemoves = new ArrayList<WindowState>();
        }

        long callingIdentity = Binder.clearCallingIdentity();
        try {
            // There was some problem...   first, do a sanity check of the
            // window list to make sure we haven't left any dangling surfaces
            // around.

            AllWindowsIterator iterator = new AllWindowsIterator();
            Slog.i(TAG, "Out of memory for surface!  Looking for leaks...");
            while (iterator.hasNext()) {
                WindowState ws = iterator.next();
                WindowStateAnimator wsa = ws.mWinAnimator;
                if (wsa.mSurface != null) {
                    if (!mSessions.contains(wsa.mSession)) {
                        Slog.w(TAG, "LEAKED SURFACE (session doesn't exist): "
                                + ws + " surface=" + wsa.mSurface
                                + " token=" + ws.mToken
                                + " pid=" + ws.mSession.mPid
                                + " uid=" + ws.mSession.mUid);
                        if (SHOW_TRANSACTIONS) logSurface(ws, "LEAK DESTROY", null);
                        wsa.mSurface.destroy();
                        wsa.mSurfaceShown = false;
                        wsa.mSurface = null;
                        ws.mHasSurface = false;
                        mForceRemoves.add(ws);
                        leakedSurface = true;
                    } else if (ws.mAppToken != null && ws.mAppToken.clientHidden) {
                        Slog.w(TAG, "LEAKED SURFACE (app token hidden): "
                                + ws + " surface=" + wsa.mSurface
                                + " token=" + ws.mAppToken);
                        if (SHOW_TRANSACTIONS) logSurface(ws, "LEAK DESTROY", null);
                        wsa.mSurface.destroy();
                        wsa.mSurfaceShown = false;
                        wsa.mSurface = null;
                        ws.mHasSurface = false;
                        leakedSurface = true;
                    }
                }
            }

            if (!leakedSurface) {
                Slog.w(TAG, "No leaked surfaces; killing applicatons!");
                SparseIntArray pidCandidates = new SparseIntArray();
                iterator = new AllWindowsIterator();
                while (iterator.hasNext()) {
                    WindowState ws = iterator.next();
                    if (mForceRemoves.contains(ws)) {
                        continue;
                    }
                    WindowStateAnimator wsa = ws.mWinAnimator;
                    if (wsa.mSurface != null) {
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

            if (leakedSurface || killedApps) {
                // We managed to reclaim some memory, so get rid of the trouble
                // surface and ask the app to request another one.
                Slog.w(TAG, "Looks like we have reclaimed some memory, clearing surface for retry.");
                if (surface != null) {
                    if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) logSurface(winAnimator.mWin,
                            "RECOVER DESTROY", null);
                    surface.destroy();
                    winAnimator.mSurfaceShown = false;
                    winAnimator.mSurface = null;
                    winAnimator.mWin.mHasSurface = false;
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

    private boolean updateFocusedWindowLocked(int mode, boolean updateInputWindows) {
        WindowState newFocus = computeFocusedWindowLocked();
        if (mCurrentFocus != newFocus) {
            Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "wmUpdateFocus");
            // This check makes sure that we don't already have the focus
            // change message pending.
            mH.removeMessages(H.REPORT_FOCUS_CHANGE);
            mH.sendEmptyMessage(H.REPORT_FOCUS_CHANGE);
            if (localLOGV) Slog.v(
                TAG, "Changing focus from " + mCurrentFocus + " to " + newFocus);
            final WindowState oldFocus = mCurrentFocus;
            mCurrentFocus = newFocus;
            mAnimator.setCurrentFocus(newFocus);
            mLosingFocus.remove(newFocus);
            int focusChanged = mPolicy.focusChangedLw(oldFocus, newFocus);

            // TODO(multidisplay): Focused windows on default display only.
            final DisplayContent displayContent = getDefaultDisplayContentLocked();

            final WindowState imWindow = mInputMethodWindow;
            if (newFocus != imWindow && oldFocus != imWindow) {
                if (moveInputMethodWindowsIfNeededLocked(
                        mode != UPDATE_FOCUS_WILL_ASSIGN_LAYERS &&
                        mode != UPDATE_FOCUS_WILL_PLACE_SURFACES)) {
                    displayContent.layoutNeeded = true;
                }
                if (mode == UPDATE_FOCUS_PLACING_SURFACES) {
                    performLayoutLockedInner(displayContent, true /*initial*/, updateInputWindows);
                    focusChanged &= ~WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
                } else if (mode == UPDATE_FOCUS_WILL_PLACE_SURFACES) {
                    // Client will do the layout, but we need to assign layers
                    // for handleNewWindowLocked() below.
                    assignLayersLocked(displayContent.getWindowList());
                }
            }

            if ((focusChanged & WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT) != 0) {
                // The change in focus caused us to need to do a layout.  Okay.
                displayContent.layoutNeeded = true;
                if (mode == UPDATE_FOCUS_PLACING_SURFACES) {
                    performLayoutLockedInner(displayContent, true /*initial*/, updateInputWindows);
                }
            }

            if (mode != UPDATE_FOCUS_WILL_ASSIGN_LAYERS) {
                // If we defer assigning layers, then the caller is responsible for
                // doing this part.
                finishUpdateFocusedWindowAfterAssignLayersLocked(updateInputWindows);
            }

            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
            return true;
        }
        return false;
    }
    
    private void finishUpdateFocusedWindowAfterAssignLayersLocked(boolean updateInputWindows) {
        mInputMonitor.setInputFocusLw(mCurrentFocus, updateInputWindows);
    }

    private WindowState computeFocusedWindowLocked() {
        if (mAnimator.mUniverseBackground != null
                && mAnimator.mUniverseBackground.mWin.canReceiveKeys()) {
            return mAnimator.mUniverseBackground.mWin;
        }

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

    private WindowState findFocusedWindowLocked(DisplayContent displayContent) {
        int nextAppIndex = mAppTokens.size()-1;
        WindowToken nextApp = nextAppIndex >= 0 ? mAppTokens.get(nextAppIndex) : null;

        final WindowList windows = displayContent.getWindowList();
        for (int i = windows.size() - 1; i >= 0; i--) {
            final WindowState win = windows.get(i);

            if (localLOGV || DEBUG_FOCUS) Slog.v(
                TAG, "Looking for focus: " + i
                + " = " + win
                + ", flags=" + win.mAttrs.flags
                + ", canReceive=" + win.canReceiveKeys());

            AppWindowToken thisApp = win.mAppToken;

            // If this window's application has been removed, just skip it.
            if (thisApp != null && (thisApp.removed || thisApp.sendingToBottom)) {
                if (DEBUG_FOCUS) Slog.v(TAG, "Skipping app because " + (thisApp.removed
                        ? "removed" : "sendingToBottom"));
                continue;
            }

            // If there is a focused app, don't allow focus to go to any
            // windows below it.  If this is an application window, step
            // through the app tokens until we find its app.
            if (thisApp != null && nextApp != null && thisApp != nextApp
                    && win.mAttrs.type != TYPE_APPLICATION_STARTING) {
                int origAppIndex = nextAppIndex;
                while (nextAppIndex > 0) {
                    if (nextApp == mFocusedApp) {
                        // Whoops, we are below the focused app...  no focus
                        // for you!
                        if (localLOGV || DEBUG_FOCUS) Slog.v(
                            TAG, "Reached focused app: " + mFocusedApp);
                        return null;
                    }
                    nextAppIndex--;
                    nextApp = mAppTokens.get(nextAppIndex);
                    if (nextApp == thisApp) {
                        break;
                    }
                }
                if (thisApp != nextApp) {
                    // Uh oh, the app token doesn't exist!  This shouldn't
                    // happen, but if it does we can get totally hosed...
                    // so restart at the original app.
                    nextAppIndex = origAppIndex;
                    nextApp = mAppTokens.get(nextAppIndex);
                }
            }

            // Dispatch to this window if it is wants key events.
            if (win.canReceiveKeys()) {
                if (DEBUG_FOCUS) Slog.v(
                        TAG, "Found focus @ " + i + " = " + win);
                return win;
            }
        }
        return null;
    }

    private void startFreezingDisplayLocked(boolean inTransaction,
            int exitAnim, int enterAnim) {
        if (mDisplayFrozen) {
            return;
        }

        if (!mDisplayReady || !mPolicy.isScreenOnFully()) {
            // No need to freeze the screen before the system is ready or if
            // the screen is off.
            return;
        }

        mScreenFrozenLock.acquire();

        mDisplayFrozen = true;

        mInputMonitor.freezeInputDispatchingLw();

        // Clear the last input window -- that is just used for
        // clean transitions between IMEs, and if we are freezing
        // the screen then the whole world is changing behind the scenes.
        mPolicy.setLastInputMethodWindowLw(null, null);

        if (mNextAppTransition != WindowManagerPolicy.TRANSIT_UNSET) {
            mNextAppTransition = WindowManagerPolicy.TRANSIT_UNSET;
            mNextAppTransitionType = ActivityOptions.ANIM_NONE;
            mNextAppTransitionPackage = null;
            mNextAppTransitionThumbnail = null;
            mAppTransitionReady = true;
        }

        if (PROFILE_ORIENTATION) {
            File file = new File("/data/system/frozen");
            Debug.startMethodTracing(file.toString(), 8 * 1024 * 1024);
        }

        if (CUSTOM_SCREEN_ROTATION) {
            final DisplayContent displayContent = getDefaultDisplayContentLocked();
            final int displayId = displayContent.getDisplayId();
            ScreenRotationAnimation screenRotationAnimation =
                    mAnimator.getScreenRotationAnimationLocked(displayId);
            if (screenRotationAnimation != null) {
                screenRotationAnimation.kill();
            }

            // TODO(multidisplay): rotation on main screen only.
            final Display display = displayContent.getDisplay();
            final DisplayInfo displayInfo = displayContent.getDisplayInfo();
            screenRotationAnimation = new ScreenRotationAnimation(mContext,
                    display, mFxSession, inTransaction, displayInfo.logicalWidth,
                    displayInfo.logicalHeight, display.getRotation(),
                    exitAnim, enterAnim);
            mAnimator.setScreenRotationAnimationLocked(displayId, screenRotationAnimation);
        }
    }

    private void stopFreezingDisplayLocked() {
        if (!mDisplayFrozen) {
            return;
        }

        if (mWaitingForConfig || mAppsFreezingScreen > 0 || mWindowsFreezingScreen
                || mClientFreezingScreen) {
            if (DEBUG_ORIENTATION) Slog.d(TAG,
                "stopFreezingDisplayLocked: Returning mWaitingForConfig=" + mWaitingForConfig
                + ", mAppsFreezingScreen=" + mAppsFreezingScreen
                + ", mWindowsFreezingScreen=" + mWindowsFreezingScreen
                + ", mClientFreezingScreen=" + mClientFreezingScreen);
            return;
        }
        
        mDisplayFrozen = false;
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
            if (DEBUG_ORIENTATION) Slog.i(TAG, "**** Dismissing screen rotation animation");
            // TODO(multidisplay): rotation on main screen only.
            DisplayInfo displayInfo = displayContent.getDisplayInfo();
            if (screenRotationAnimation.dismiss(mFxSession, MAX_ANIMATION_DURATION,
                    mTransitionAnimationScale, displayInfo.logicalWidth,
                        displayInfo.logicalHeight)) {
                updateLayoutToAnimationLocked();
            } else {
                screenRotationAnimation.kill();
                screenRotationAnimation = null;
                mAnimator.setScreenRotationAnimationLocked(displayId, screenRotationAnimation);
                updateRotation = true;
            }
        } else {
            if (screenRotationAnimation != null) {
                screenRotationAnimation.kill();
                screenRotationAnimation = null;
                mAnimator.setScreenRotationAnimationLocked(displayId, screenRotationAnimation);
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
        mH.sendMessageDelayed(mH.obtainMessage(H.FORCE_GC),
                2000);

        mScreenFrozenLock.release();
        
        if (updateRotation) {
            if (DEBUG_ORIENTATION) Slog.d(TAG, "Performing post-rotate rotation");
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
        try {
            in = new FileInputStream(file);
            DataInputStream ind = new DataInputStream(in);
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
            if (in != null) {
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
    void updateStatusBarVisibilityLocked(int visibility) {
        mInputManager.setSystemUiVisibility(visibility);
        final WindowList windows = getDefaultWindowListLocked();
        final int N = windows.size();
        for (int i = 0; i < N; i++) {
            WindowState ws = windows.get(i);
            try {
                int curValue = ws.mSystemUiVisibility;
                int diff = curValue ^ visibility;
                // We are only interested in differences of one of the
                // clearable flags...
                diff &= View.SYSTEM_UI_CLEARABLE_FLAGS;
                // ...if it has actually been cleared.
                diff &= ~visibility;
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
    }
 
    @Override
    public void reevaluateStatusBarVisibility() {
        synchronized (mWindowMap) {
            int visibility = mPolicy.adjustSystemUiVisibilityLw(mLastStatusBarVisibility);
            updateStatusBarVisibilityLocked(visibility);
            performLayoutAndPlaceSurfacesLocked();
        }
    }

    @Override
    public FakeWindow addFakeWindow(Looper looper,
            InputEventReceiver.Factory inputEventReceiverFactory,
            String name, int windowType, int layoutParamsFlags, boolean canReceiveKeys,
            boolean hasFocus, boolean touchFullscreen) {
        synchronized (mWindowMap) {
            FakeWindowImpl fw = new FakeWindowImpl(this, looper, inputEventReceiverFactory,
                    name, windowType,
                    layoutParamsFlags, canReceiveKeys, hasFocus, touchFullscreen);
            int i=0;
            while (i<mFakeWindows.size()) {
                if (mFakeWindows.get(i).mWindowLayer <= fw.mWindowLayer) {
                    break;
                }
            }
            mFakeWindows.add(i, fw);
            mInputMonitor.updateInputWindowsLw(true);
            return fw;
        }
    }

    boolean removeFakeWindowLocked(FakeWindow window) {
        synchronized (mWindowMap) {
            if (mFakeWindows.remove(window)) {
                mInputMonitor.updateInputWindowsLw(true);
                return true;
            }
            return false;
        }
    }

    // It is assumed that this method is called only by InputMethodManagerService.
    public void saveLastInputMethodWindowForTransition() {
        synchronized (mWindowMap) {
            // TODO(multidisplay): Pass in the displayID.
            DisplayContent displayContent = getDefaultDisplayContentLocked();
            if (mInputMethodWindow != null) {
                mPolicy.setLastInputMethodWindowLw(mInputMethodWindow, mInputMethodTarget);
            }
        }
    }

    @Override
    public boolean hasNavigationBar() {
        return mPolicy.hasNavigationBar();
    }

    public void lockNow(Bundle options) {
        mPolicy.lockNow(options);
    }
    
    public boolean isSafeModeEnabled() {
        return mSafeMode;
    }

    public void showAssistant() {
        // TODO: What permission?
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        mPolicy.showAssistant();
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
        if (mTokenMap.size() > 0) {
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
        if (mWallpaperTokens.size() > 0) {
            pw.println();
            pw.println("  Wallpaper tokens:");
            for (int i=mWallpaperTokens.size()-1; i>=0; i--) {
                WindowToken token = mWallpaperTokens.get(i);
                pw.print("  Wallpaper #"); pw.print(i);
                        pw.print(' '); pw.print(token);
                if (dumpAll) {
                    pw.println(':');
                    token.dump(pw, "    ");
                } else {
                    pw.println();
                }
            }
        }
        if (mAppTokens.size() > 0) {
            pw.println();
            pw.println("  Application tokens in Z order:");
            for (int i=mAppTokens.size()-1; i>=0; i--) {
                pw.print("  App #"); pw.print(i);
                        pw.print(' '); pw.print(mAppTokens.get(i)); pw.println(":");
                mAppTokens.get(i).dump(pw, "    ");
            }
        }
        if (mFinishedStarting.size() > 0) {
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
        if (mExitingTokens.size() > 0) {
            pw.println();
            pw.println("  Exiting tokens:");
            for (int i=mExitingTokens.size()-1; i>=0; i--) {
                WindowToken token = mExitingTokens.get(i);
                pw.print("  Exiting #"); pw.print(i);
                        pw.print(' '); pw.print(token);
                if (dumpAll) {
                    pw.println(':');
                    token.dump(pw, "    ");
                } else {
                    pw.println();
                }
            }
        }
        if (mExitingAppTokens.size() > 0) {
            pw.println();
            pw.println("  Exiting application tokens:");
            for (int i=mExitingAppTokens.size()-1; i>=0; i--) {
                WindowToken token = mExitingAppTokens.get(i);
                pw.print("  Exiting App #"); pw.print(i);
                        pw.print(' '); pw.print(token);
                if (dumpAll) {
                    pw.println(':');
                    token.dump(pw, "    ");
                } else {
                    pw.println();
                }
            }
        }
        if (mAppTransitionRunning && mAnimatingAppTokens.size() > 0) {
            pw.println();
            pw.println("  Application tokens during animation:");
            for (int i=mAnimatingAppTokens.size()-1; i>=0; i--) {
                WindowToken token = mAnimatingAppTokens.get(i);
                pw.print("  App moving to bottom #"); pw.print(i);
                        pw.print(' '); pw.print(token);
                if (dumpAll) {
                    pw.println(':');
                    token.dump(pw, "    ");
                } else {
                    pw.println();
                }
            }
        }
        if (mOpeningApps.size() > 0 || mClosingApps.size() > 0) {
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
        if (mSessions.size() > 0) {
            Iterator<Session> it = mSessions.iterator();
            while (it.hasNext()) {
                Session s = it.next();
                pw.print("  Session "); pw.print(s); pw.println(':');
                s.dump(pw, "    ");
            }
        }
    }

    void dumpWindowsLocked(PrintWriter pw, boolean dumpAll,
            ArrayList<WindowState> windows) {
        pw.println("WINDOW MANAGER WINDOWS (dumpsys window windows)");
        dumpWindowsNoHeaderLocked(pw, dumpAll, windows);
    }

    void dumpWindowsNoHeaderLocked(PrintWriter pw, boolean dumpAll,
            ArrayList<WindowState> windows) {
        int j = 0;
        final AllWindowsIterator iterator = new AllWindowsIterator(REVERSE_ITERATOR);
        while (iterator.hasNext()) {
            final WindowState w = iterator.next();
            if (windows == null || windows.contains(w)) {
                pw.print("  Window #"); pw.print(j++); pw.print(' ');
                        pw.print(w); pw.println(":");
                w.dump(pw, "    ", dumpAll || windows != null);
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
                Pair<WindowState, IRemoteCallback> pair = mWaitingForDrawn.get(i);
                pw.print("  Waiting #"); pw.print(i); pw.print(' '); pw.print(pair.first);
                        pw.print(": "); pw.println(pair.second);
            }
        }
        pw.println();
        pw.println("  DisplayContents:");
        if (mDisplayReady) {
            DisplayContentsIterator dCIterator = new DisplayContentsIterator();
            while (dCIterator.hasNext()) {
                dCIterator.next().dump("    ", pw);
            }
        } else {
            pw.println("  NO DISPLAY");
        }
        pw.print("  mCurConfiguration="); pw.println(this.mCurConfiguration);
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
        if (dumpAll) {
            pw.print("  mSystemDecorRect="); pw.print(mSystemDecorRect.toShortString());
                    pw.print(" mSystemDecorLayer="); pw.print(mSystemDecorLayer);
                    pw.print(" mScreenRecr="); pw.println(mScreenRect.toShortString());
            if (mLastStatusBarVisibility != 0) {
                pw.print("  mLastStatusBarVisibility=0x");
                        pw.println(Integer.toHexString(mLastStatusBarVisibility));
            }
            if (mInputMethodWindow != null) {
                pw.print("  mInputMethodWindow="); pw.println(mInputMethodWindow);
            }
            pw.print("  mWallpaperTarget="); pw.println(mWallpaperTarget);
            if (mLowerWallpaperTarget != null || mUpperWallpaperTarget != null) {
                pw.print("  mLowerWallpaperTarget="); pw.println(mLowerWallpaperTarget);
                pw.print("  mUpperWallpaperTarget="); pw.println(mUpperWallpaperTarget);
            }
            pw.print("  mLastWallpaperX="); pw.print(mLastWallpaperX);
                    pw.print(" mLastWallpaperY="); pw.println(mLastWallpaperY);
            if (mInputMethodAnimLayerAdjustment != 0 ||
                    mWallpaperAnimLayerAdjustment != 0) {
                pw.print("  mInputMethodAnimLayerAdjustment=");
                        pw.print(mInputMethodAnimLayerAdjustment);
                        pw.print("  mWallpaperAnimLayerAdjustment=");
                        pw.println(mWallpaperAnimLayerAdjustment);
            }
            pw.print("  mSystemBooted="); pw.print(mSystemBooted);
                    pw.print(" mDisplayEnabled="); pw.println(mDisplayEnabled);
            if (needsLayout()) {
                pw.print("  layoutNeeded on displays=");
                DisplayContentsIterator dcIterator = new DisplayContentsIterator();
                while (dcIterator.hasNext()) {
                    final DisplayContent displayContent = dcIterator.next();
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
            pw.print("  mWindowAnimationScale="); pw.print(mWindowAnimationScale);
                    pw.print(" mTransitionWindowAnimationScale="); pw.print(mTransitionAnimationScale);
                    pw.print(" mAnimatorDurationScale="); pw.println(mAnimatorDurationScale);
            pw.print("  mTraversalScheduled="); pw.print(mTraversalScheduled);
                    pw.print(" mNextAppTransition=0x");
                    pw.print(Integer.toHexString(mNextAppTransition));
                    pw.print(" mAppTransitionReady="); pw.println(mAppTransitionReady);
            pw.print("  mAppTransitionRunning="); pw.print(mAppTransitionRunning);
                    pw.print(" mAppTransitionTimeout="); pw.println(mAppTransitionTimeout);
            if (mNextAppTransitionType != ActivityOptions.ANIM_NONE) {
                pw.print("  mNextAppTransitionType="); pw.println(mNextAppTransitionType);
            }
            switch (mNextAppTransitionType) {
                case ActivityOptions.ANIM_CUSTOM:
                    pw.print("  mNextAppTransitionPackage=");
                            pw.println(mNextAppTransitionPackage);
                    pw.print("  mNextAppTransitionEnter=0x");
                            pw.print(Integer.toHexString(mNextAppTransitionEnter));
                            pw.print(" mNextAppTransitionExit=0x");
                            pw.println(Integer.toHexString(mNextAppTransitionExit));
                    break;
                case ActivityOptions.ANIM_SCALE_UP:
                    pw.print("  mNextAppTransitionStartX="); pw.print(mNextAppTransitionStartX);
                            pw.print(" mNextAppTransitionStartY=");
                            pw.println(mNextAppTransitionStartY);
                    pw.print("  mNextAppTransitionStartWidth=");
                            pw.print(mNextAppTransitionStartWidth);
                            pw.print(" mNextAppTransitionStartHeight=");
                            pw.println(mNextAppTransitionStartHeight);
                    break;
                case ActivityOptions.ANIM_THUMBNAIL_SCALE_UP:
                case ActivityOptions.ANIM_THUMBNAIL_SCALE_DOWN:
                    pw.print("  mNextAppTransitionThumbnail=");
                            pw.print(mNextAppTransitionThumbnail);
                            pw.print(" mNextAppTransitionStartX=");
                            pw.print(mNextAppTransitionStartX);
                            pw.print(" mNextAppTransitionStartY=");
                            pw.println(mNextAppTransitionStartY);
                    pw.print("  mNextAppTransitionScaleUp="); pw.println(mNextAppTransitionScaleUp);
                    break;
            }
            if (mNextAppTransitionCallback != null) {
                pw.print("  mNextAppTransitionCallback=");
                        pw.println(mNextAppTransitionCallback);
            }
            pw.print("  mStartingIconInTransition="); pw.print(mStartingIconInTransition);
                    pw.print(" mSkipAppTransitionAnimation="); pw.println(mSkipAppTransitionAnimation);
            pw.println("  mLayoutToAnim:");
            pw.print("    mParamsModified="); pw.print(mLayoutToAnim.mParamsModified);
                    pw.print(" mAnimationScheduled="); pw.print(mLayoutToAnim.mAnimationScheduled);
                    pw.print(" mChanges=0x");
                    pw.println(Long.toHexString(mLayoutToAnim.mChanges));
            pw.print("    mWallpaperTarget="); pw.println(mLayoutToAnim.mWallpaperTarget);
            if (mLayoutToAnim.mLowerWallpaperTarget != null
                    || mLayoutToAnim.mUpperWallpaperTarget != null) {
                pw.print("    mLowerWallpaperTarget=");
                        pw.println(mLayoutToAnim.mLowerWallpaperTarget);
                pw.print("    mUpperWallpaperTarget=");
                        pw.println(mLayoutToAnim.mUpperWallpaperTarget);
            }
            for (int i=0; i<mLayoutToAnim.mWinAnimatorLists.size(); i++) {
                pw.print("    Win Animator List #");
                        pw.print(mLayoutToAnim.mWinAnimatorLists.keyAt(i)); pw.println(":");
                WinAnimatorList wanim = mLayoutToAnim.mWinAnimatorLists.valueAt(i);
                for (int wi=0; wi<wanim.size(); wi++) {
                    pw.print("      "); pw.println(wanim.get(wi));
                }
            }
            for (int i=0; i<mLayoutToAnim.mWallpaperTokens.size(); i++) {
                pw.print("    Wallpaper Token #"); pw.print(i); pw.print(": ");
                        pw.println(mLayoutToAnim.mWallpaperTokens.get(i));
            }
            // XXX also need to print mDimParams and mAppWindowAnimParams.  I am lazy.
        }
    }

    boolean dumpWindows(PrintWriter pw, String name, String[] args,
            int opti, boolean dumpAll) {
        WindowList windows = new WindowList();
        if ("visible".equals(name)) {
            synchronized(mWindowMap) {
                final AllWindowsIterator iterator = new AllWindowsIterator(REVERSE_ITERATOR);
                while (iterator.hasNext()) {
                    final WindowState w = iterator.next();
                    if (w.mWinAnimator.mSurfaceShown) {
                        windows.add(w);
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
                final AllWindowsIterator iterator = new AllWindowsIterator(REVERSE_ITERATOR);
                while (iterator.hasNext()) {
                    final WindowState w = iterator.next();
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
     */
    public void saveANRStateLocked(AppWindowToken appWindowToken, WindowState windowState) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("  ANR time: " + DateFormat.getInstance().format(new Date()));
        if (appWindowToken != null) {
            pw.println("  Application at fault: " + appWindowToken.stringName);
        }
        if (windowState != null) {
            pw.println("  Window at fault: " + windowState.mAttrs.getTitle());
        }
        pw.println();
        dumpWindowsNoHeaderLocked(pw, true, null);
        pw.close();
        mLastANRState = sw.toString();
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
                pw.println("    t[okens]: token list");
                pw.println("    w[indows]: window list");
                pw.println("  cmd may also be a NAME to dump windows.  NAME may");
                pw.println("    be a partial substring in a window name, a");
                pw.println("    Window hex object identifier, or");
                pw.println("    \"all\" for all windows, or");
                pw.println("    \"visible\" for the visible windows.");
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
            dumpTokensLocked(pw, dumpAll);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpWindowsLocked(pw, dumpAll, null);
        }
    }

    // Called by the heartbeat to ensure locks are not held indefnitely (for deadlock detection).
    public void monitor() {
        synchronized (mWindowMap) { }
    }

    public interface OnHardKeyboardStatusChangeListener {
        public void onHardKeyboardStatusChange(boolean available, boolean enabled);
    }

    void debugLayoutRepeats(final String msg, int pendingLayoutChanges) {
        if (mLayoutRepeatCount >= LAYOUT_REPEAT_THRESHOLD) {
            Slog.v(TAG, "Layouts looping: " + msg + ", mPendingLayoutChanges = 0x" +
                    Integer.toHexString(pendingLayoutChanges));
        }
    }

    public void createDisplayContentLocked(final Display display) {
        if (display == null) {
            throw new IllegalArgumentException("getDisplayContent: display must not be null");
        }
        final DisplayContent displayContent = new DisplayContent(display);
        mDisplayContents.put(display.getDisplayId(), displayContent);
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
                displayContent = new DisplayContent(display);
                mDisplayContents.put(displayId, displayContent);
            }
        }
        return displayContent;
    }

    class DisplayContentsIterator implements Iterator<DisplayContent> {
        private int cur;

        @Override
        public boolean hasNext() {
            return cur < mDisplayContents.size();
        }

        @Override
        public DisplayContent next() {
            if (hasNext()) {
                return mDisplayContents.valueAt(cur++);
            }
            throw new NoSuchElementException();
        }

        @Override
        public void remove() {
            throw new IllegalArgumentException("AllDisplayContentIterator.remove not implemented");
        }
    }

    final static boolean REVERSE_ITERATOR = true;
    class AllWindowsIterator implements Iterator<WindowState> {
        private DisplayContent mDisplayContent;
        private DisplayContentsIterator mDisplayContentsIterator;
        private WindowList mWindowList;
        private int mWindowListIndex;
        private boolean mReverse;

        AllWindowsIterator() {
            mDisplayContentsIterator = new DisplayContentsIterator();
            mDisplayContent = mDisplayContentsIterator.next();
            mWindowList = mDisplayContent.getWindowList();
        }

        AllWindowsIterator(boolean reverse) {
            this();
            mReverse = reverse;
            mWindowListIndex = reverse ? mWindowList.size() - 1 : 0;
        }

        @Override
        public boolean hasNext() {
            if (mReverse) {
                return mWindowListIndex >= 0;
            }
            return mWindowListIndex < mWindowList.size();
        }

        @Override
        public WindowState next() {
            if (hasNext()) {
                WindowState win = mWindowList.get(mWindowListIndex);
                if (mReverse) {
                    mWindowListIndex--;
                    if (mWindowListIndex < 0 && mDisplayContentsIterator.hasNext()) {
                        mDisplayContent = mDisplayContentsIterator.next();
                        mWindowList = mDisplayContent.getWindowList();
                        mWindowListIndex = mWindowList.size() - 1;
                    }
                } else {
                    mWindowListIndex++;
                    if (mWindowListIndex >= mWindowList.size()
                            && mDisplayContentsIterator.hasNext()) {
                        mDisplayContent = mDisplayContentsIterator.next();
                        mWindowList = mDisplayContent.getWindowList();
                        mWindowListIndex = 0;
                    }
                }
                return win;
            }
            throw new NoSuchElementException();
        }

        @Override
        public void remove() {
            throw new IllegalArgumentException("AllWindowsIterator.remove not implemented");
        }
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
        final DisplayContent displayContent = getDisplayContentLocked(display.getDisplayId());
        return displayContent != null ? displayContent.getWindowList() : null;
    }

    @Override
    public void onDisplayAdded(int displayId) {
        mH.sendMessage(mH.obtainMessage(H.DO_DISPLAY_ADDED, displayId, 0));
    }

    private void handleDisplayAddedLocked(int displayId) {
        final Display display = mDisplayManager.getDisplay(displayId);
        if (display != null) {
            createDisplayContentLocked(display);
            displayReady(displayId);
        }
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        mH.sendMessage(mH.obtainMessage(H.DO_DISPLAY_REMOVED, displayId, 0));
    }

    private void handleDisplayRemovedLocked(int displayId) {
        final DisplayContent displayContent = getDisplayContentLocked(displayId);
        if (displayContent != null) {
            mDisplayContents.delete(displayId);
            WindowList windows = displayContent.getWindowList();
            while (!windows.isEmpty()) {
                final WindowState win = windows.get(windows.size() - 1);
                removeWindowLocked(win.mSession, win);
            }
        }
        mAnimator.removeDisplayLocked(displayId);
    }

    @Override
    public void onDisplayChanged(int displayId) {
        mH.sendMessage(mH.obtainMessage(H.DO_DISPLAY_CHANGED, displayId, 0));
    }

    private void handleDisplayChangedLocked(int displayId) {
        final DisplayContent displayContent = getDisplayContentLocked(displayId);
        if (displayContent != null) {
            displayContent.updateDisplayInfo();
        }
    }
}
