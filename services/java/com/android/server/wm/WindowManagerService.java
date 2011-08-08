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
import static android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_COMPATIBLE_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import com.android.internal.app.IBatteryStats;
import com.android.internal.policy.PolicyManager;
import com.android.internal.policy.impl.PhoneWindowManager;
import com.android.internal.view.BaseInputHandler;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.view.IInputMethodManager;
import com.android.internal.view.WindowManagerPolicyThread;
import com.android.server.AttributeCache;
import com.android.server.EventLogTags;
import com.android.server.PowerManagerService;
import com.android.server.Watchdog;
import com.android.server.am.BatteryStatsService;

import android.Manifest;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.StatusBarManager;
import android.app.admin.DevicePolicyManager;
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
import android.graphics.Region;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.LocalPowerManager;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Power;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.TokenWatcher;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.IApplicationToken;
import android.view.IOnKeyguardExitResult;
import android.view.IRotationWatcher;
import android.view.IWindow;
import android.view.IWindowManager;
import android.view.IWindowSession;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputHandler;
import android.view.InputQueue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceSession;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.view.WindowManagerPolicy;
import android.view.WindowManager.LayoutParams;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

/** {@hide} */
public class WindowManagerService extends IWindowManager.Stub
        implements Watchdog.Monitor {
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
    static final boolean DEBUG_DRAG = false;
    static final boolean SHOW_SURFACE_ALLOC = false;
    static final boolean SHOW_TRANSACTIONS = false;
    static final boolean HIDE_STACK_CRAWLS = true;

    static final boolean PROFILE_ORIENTATION = false;
    static final boolean BLUR = true;
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

    /**
     * If true, the window manager will do its own custom freezing and general
     * management of the screen during rotation.
     */
    static final boolean CUSTOM_SCREEN_ROTATION = true;

    // Maximum number of milliseconds to wait for input event injection.
    // FIXME is this value reasonable?
    private static final int INJECTION_TIMEOUT_MILLIS = 30 * 1000;

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

    /**
     * Condition waited on by {@link #reenableKeyguard} to know the call to
     * the window policy has finished.
     * This is set to true only if mKeyguardTokenWatcher.acquired() has
     * actually disabled the keyguard.
     */
    private boolean mKeyguardDisabled = false;

    private static final int ALLOW_DISABLE_YES = 1;
    private static final int ALLOW_DISABLE_NO = 0;
    private static final int ALLOW_DISABLE_UNKNOWN = -1; // check with DevicePolicyManager
    private int mAllowDisableKeyguard = ALLOW_DISABLE_UNKNOWN; // sync'd by mKeyguardTokenWatcher

    final TokenWatcher mKeyguardTokenWatcher = new TokenWatcher(
            new Handler(), "WindowManagerService.mKeyguardTokenWatcher") {
        public void acquired() {
            if (shouldAllowDisableKeyguard()) {
                mPolicy.enableKeyguard(false);
                mKeyguardDisabled = true;
            } else {
                Log.v(TAG, "Not disabling keyguard since device policy is enforced");
            }
        }
        public void released() {
            mPolicy.enableKeyguard(true);
            synchronized (mKeyguardTokenWatcher) {
                mKeyguardDisabled = false;
                mKeyguardTokenWatcher.notifyAll();
            }
        }
    };

    final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mPolicy.enableKeyguard(true);
            synchronized(mKeyguardTokenWatcher) {
                // lazily evaluate this next time we're asked to disable keyguard
                mAllowDisableKeyguard = ALLOW_DISABLE_UNKNOWN;
                mKeyguardDisabled = false;
            }
        }
    };

    final Context mContext;

    final boolean mHaveInputMethods;

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
     * Z-ordered (bottom-most first) list of all application tokens, for
     * controlling the ordering of windows in different applications.  This
     * contains AppWindowToken objects.
     */
    final ArrayList<AppWindowToken> mAppTokens = new ArrayList<AppWindowToken>();

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
     * Z-ordered (bottom-most first) list of all Window objects.
     */
    final ArrayList<WindowState> mWindows = new ArrayList<WindowState>();

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
     * Used when rebuilding window list to keep track of windows that have
     * been removed.
     */
    WindowState[] mRebuildTmp = new WindowState[20];

    IInputMethodManager mInputMethodManager;

    SurfaceSession mFxSession;
    private DimAnimator mDimAnimator = null;
    Surface mBlurSurface;
    boolean mBlurShown;
    Watermark mWatermark;
    StrictModeFlash mStrictModeFlash;
    ScreenRotationAnimation mScreenRotationAnimation;

    BlackFrame mBlackFrame;

    int mTransactionSequence = 0;

    final float[] mTmpFloats = new float[9];

    boolean mSafeMode;
    boolean mDisplayEnabled = false;
    boolean mSystemBooted = false;
    int mInitialDisplayWidth = 0;
    int mInitialDisplayHeight = 0;
    int mBaseDisplayWidth = 0;
    int mBaseDisplayHeight = 0;
    int mCurDisplayWidth = 0;
    int mCurDisplayHeight = 0;
    int mAppDisplayWidth = 0;
    int mAppDisplayHeight = 0;
    int mRotation = 0;
    int mRequestedRotation = 0;
    int mForcedAppOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    boolean mAltOrientation = false;
    int mLastRotationFlags;
    ArrayList<IRotationWatcher> mRotationWatchers
            = new ArrayList<IRotationWatcher>();
    int mDeferredRotation;
    int mDeferredRotationAnimFlags;

    boolean mLayoutNeeded = true;
    boolean mAnimationPending = false;
    boolean mDisplayFrozen = false;
    boolean mWaitingForConfig = false;
    boolean mWindowsFreezingScreen = false;
    int mAppsFreezingScreen = 0;
    int mLastWindowForcedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    int mLayoutSeq = 0;
    
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
    String mNextAppTransitionPackage;
    int mNextAppTransitionEnter;
    int mNextAppTransitionExit;
    boolean mAppTransitionReady = false;
    boolean mAppTransitionRunning = false;
    boolean mAppTransitionTimeout = false;
    boolean mStartingIconInTransition = false;
    boolean mSkipAppTransitionAnimation = false;
    final ArrayList<AppWindowToken> mOpeningApps = new ArrayList<AppWindowToken>();
    final ArrayList<AppWindowToken> mClosingApps = new ArrayList<AppWindowToken>();
    final ArrayList<AppWindowToken> mToTopApps = new ArrayList<AppWindowToken>();
    final ArrayList<AppWindowToken> mToBottomApps = new ArrayList<AppWindowToken>();

    Display mDisplay;

    final DisplayMetrics mDisplayMetrics = new DisplayMetrics();
    final DisplayMetrics mRealDisplayMetrics = new DisplayMetrics();
    final DisplayMetrics mTmpDisplayMetrics = new DisplayMetrics();
    final DisplayMetrics mCompatDisplayMetrics = new DisplayMetrics();

    H mH = new H();

    WindowState mCurrentFocus = null;
    WindowState mLastFocus = null;

    // This just indicates the window the input method is on top of, not
    // necessarily the window its input is going to.
    WindowState mInputMethodTarget = null;
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
    WindowState mUpperWallpaperTarget = null;
    // Window currently running an animation that has requested it be detached
    // from the wallpaper.  This means we need to ensure the wallpaper is
    // visible behind it in case it animates in a way that would allow it to be
    // seen.
    WindowState mWindowDetachedWallpaper = null;
    DimSurface mWindowAnimationBackgroundSurface = null;
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

    final InputManager mInputManager;

    // Who is holding the screen on.
    Session mHoldingScreenOn;
    PowerManager.WakeLock mHoldingScreenWakeLock;

    boolean mTurnOnScreen;

    DragState mDragState = null;
    final InputHandler mDragInputHandler = new BaseInputHandler() {
        @Override
        public void handleMotion(MotionEvent event, InputQueue.FinishedCallback finishedCallback) {
            boolean handled = false;
            try {
                if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0
                        && mDragState != null) {
                    boolean endDrag = false;
                    final float newX = event.getRawX();
                    final float newY = event.getRawY();

                    switch (event.getAction()) {
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
                finishedCallback.finished(handled);
            }
        }
    };

    /**
     * Whether the UI is currently running in touch mode (not showing
     * navigational focus because the user is directly pressing the screen).
     */
    boolean mInTouchMode = false;

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

    public static WindowManagerService main(Context context,
            PowerManagerService pm, boolean haveInputMethods) {
        WMThread thr = new WMThread(context, pm, haveInputMethods);
        thr.start();

        synchronized (thr) {
            while (thr.mService == null) {
                try {
                    thr.wait();
                } catch (InterruptedException e) {
                }
            }
            return thr.mService;
        }
    }

    static class WMThread extends Thread {
        WindowManagerService mService;

        private final Context mContext;
        private final PowerManagerService mPM;
        private final boolean mHaveInputMethods;

        public WMThread(Context context, PowerManagerService pm,
                boolean haveInputMethods) {
            super("WindowManager");
            mContext = context;
            mPM = pm;
            mHaveInputMethods = haveInputMethods;
        }

        public void run() {
            Looper.prepare();
            WindowManagerService s = new WindowManagerService(mContext, mPM,
                    mHaveInputMethods);
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_DISPLAY);
            android.os.Process.setCanSelfBackground(false);

            synchronized (this) {
                mService = s;
                notifyAll();
            }

            // For debug builds, log event loop stalls to dropbox for analysis.
            if (StrictMode.conditionallyEnableDebugLogging()) {
                Slog.i(TAG, "Enabled StrictMode logging for WMThread's Looper");
            }

            Looper.loop();
        }
    }

    static class PolicyThread extends Thread {
        private final WindowManagerPolicy mPolicy;
        private final WindowManagerService mService;
        private final Context mContext;
        private final PowerManagerService mPM;
        boolean mRunning = false;

        public PolicyThread(WindowManagerPolicy policy,
                WindowManagerService service, Context context,
                PowerManagerService pm) {
            super("WindowManagerPolicy");
            mPolicy = policy;
            mService = service;
            mContext = context;
            mPM = pm;
        }

        public void run() {
            Looper.prepare();
            WindowManagerPolicyThread.set(this, Looper.myLooper());
            
            //Looper.myLooper().setMessageLogging(new LogPrinter(
            //        Log.VERBOSE, "WindowManagerPolicy", Log.LOG_ID_SYSTEM));
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_FOREGROUND);
            android.os.Process.setCanSelfBackground(false);
            mPolicy.init(mContext, mService, mPM);

            synchronized (this) {
                mRunning = true;
                notifyAll();
            }

            // For debug builds, log event loop stalls to dropbox for analysis.
            if (StrictMode.conditionallyEnableDebugLogging()) {
                Slog.i(TAG, "Enabled StrictMode for PolicyThread's Looper");
            }

            Looper.loop();
        }
    }

    private WindowManagerService(Context context, PowerManagerService pm,
            boolean haveInputMethods) {
        mContext = context;
        mHaveInputMethods = haveInputMethods;
        mLimitedAlphaCompositing = context.getResources().getBoolean(
                com.android.internal.R.bool.config_sf_limitedAlpha);

        mPowerManager = pm;
        mPowerManager.setPolicy(mPolicy);
        PowerManager pmc = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mScreenFrozenLock = pmc.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "SCREEN_FROZEN");
        mScreenFrozenLock.setReferenceCounted(false);

        mActivityManager = ActivityManagerNative.getDefault();
        mBatteryStats = BatteryStatsService.getService();

        // Get persisted window scale setting
        mWindowAnimationScale = Settings.System.getFloat(context.getContentResolver(),
                Settings.System.WINDOW_ANIMATION_SCALE, mWindowAnimationScale);
        mTransitionAnimationScale = Settings.System.getFloat(context.getContentResolver(),
                Settings.System.TRANSITION_ANIMATION_SCALE, mTransitionAnimationScale);

        // Track changes to DevicePolicyManager state so we can enable/disable keyguard.
        IntentFilter filter = new IntentFilter();
        filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        mContext.registerReceiver(mBroadcastReceiver, filter);

        mHoldingScreenWakeLock = pmc.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "KEEP_SCREEN_ON_FLAG");
        mHoldingScreenWakeLock.setReferenceCounted(false);

        mInputManager = new InputManager(context, this);

        PolicyThread thr = new PolicyThread(mPolicy, this, context, pm);
        thr.start();

        synchronized (thr) {
            while (!thr.mRunning) {
                try {
                    thr.wait();
                } catch (InterruptedException e) {
                }
            }
        }

        mInputManager.start();

        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);
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
                Slog.e(TAG, "Window Manager Crash", e);
            }
            throw e;
        }
    }

    private void placeWindowAfter(WindowState pos, WindowState window) {
        final int i = mWindows.indexOf(pos);
        if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(
            TAG, "Adding window " + window + " at "
            + (i+1) + " of " + mWindows.size() + " (after " + pos + ")");
        mWindows.add(i+1, window);
        mWindowsChanged = true;
    }

    private void placeWindowBefore(WindowState pos, WindowState window) {
        final int i = mWindows.indexOf(pos);
        if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(
            TAG, "Adding window " + window + " at "
            + i + " of " + mWindows.size() + " (before " + pos + ")");
        mWindows.add(i, window);
        mWindowsChanged = true;
    }

    //This method finds out the index of a window that has the same app token as
    //win. used for z ordering the windows in mWindows
    private int findIdxBasedOnAppTokens(WindowState win) {
        //use a local variable to cache mWindows
        ArrayList<WindowState> localmWindows = mWindows;
        int jmax = localmWindows.size();
        if(jmax == 0) {
            return -1;
        }
        for(int j = (jmax-1); j >= 0; j--) {
            WindowState wentry = localmWindows.get(j);
            if(wentry.mAppToken == win.mAppToken) {
                return j;
            }
        }
        return -1;
    }

    private void addWindowToListInOrderLocked(WindowState win, boolean addToToken) {
        final IWindow client = win.mClient;
        final WindowToken token = win.mToken;
        final ArrayList<WindowState> localmWindows = mWindows;

        final int N = localmWindows.size();
        final WindowState attached = win.mAttachedWindow;
        int i;
        if (attached == null) {
            int tokenWindowsPos = token.windows.size();
            if (token.appWindowToken != null) {
                int index = tokenWindowsPos-1;
                if (index >= 0) {
                    // If this application has existing windows, we
                    // simply place the new window on top of them... but
                    // keep the starting window on top.
                    if (win.mAttrs.type == TYPE_BASE_APPLICATION) {
                        // Base windows go behind everything else.
                        placeWindowBefore(token.windows.get(0), win);
                        tokenWindowsPos = 0;
                    } else {
                        AppWindowToken atoken = win.mAppToken;
                        if (atoken != null &&
                                token.windows.get(index) == atoken.startingWindow) {
                            placeWindowBefore(token.windows.get(index), win);
                            tokenWindowsPos--;
                        } else {
                            int newIdx =  findIdxBasedOnAppTokens(win);
                            if(newIdx != -1) {
                                //there is a window above this one associated with the same
                                //apptoken note that the window could be a floating window
                                //that was created later or a window at the top of the list of
                                //windows associated with this token.
                                if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) {
                                    Slog.v(TAG, "Adding window " + win + " at "
                                            + (newIdx+1) + " of " + N);
                                }
                                localmWindows.add(newIdx+1, win);
                                mWindowsChanged = true;
                            }
                        }
                    }
                } else {
                    if (localLOGV) Slog.v(
                        TAG, "Figuring out where to add app window "
                        + client.asBinder() + " (token=" + token + ")");
                    // Figure out where the window should go, based on the
                    // order of applications.
                    final int NA = mAppTokens.size();
                    WindowState pos = null;
                    for (i=NA-1; i>=0; i--) {
                        AppWindowToken t = mAppTokens.get(i);
                        if (t == token) {
                            i--;
                            break;
                        }

                        // We haven't reached the token yet; if this token
                        // is not going to the bottom and has windows, we can
                        // use it as an anchor for when we do reach the token.
                        if (!t.sendingToBottom && t.windows.size() > 0) {
                            pos = t.windows.get(0);
                        }
                    }
                    // We now know the index into the apps.  If we found
                    // an app window above, that gives us the position; else
                    // we need to look some more.
                    if (pos != null) {
                        // Move behind any windows attached to this one.
                        WindowToken atoken = mTokenMap.get(pos.mClient.asBinder());
                        if (atoken != null) {
                            final int NC = atoken.windows.size();
                            if (NC > 0) {
                                WindowState bottom = atoken.windows.get(0);
                                if (bottom.mSubLayer < 0) {
                                    pos = bottom;
                                }
                            }
                        }
                        placeWindowBefore(pos, win);
                    } else {
                        // Continue looking down until we find the first
                        // token that has windows.
                        while (i >= 0) {
                            AppWindowToken t = mAppTokens.get(i);
                            final int NW = t.windows.size();
                            if (NW > 0) {
                                pos = t.windows.get(NW-1);
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
                                WindowState w = localmWindows.get(i);
                                if (w.mBaseLayer > myLayer) {
                                    break;
                                }
                            }
                            if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) {
                                Slog.v(TAG, "Adding window " + win + " at "
                                        + i + " of " + N);
                            }
                            localmWindows.add(i, win);
                            mWindowsChanged = true;
                        }
                    }
                }
            } else {
                // Figure out where window should go, based on layer.
                final int myLayer = win.mBaseLayer;
                for (i=N-1; i>=0; i--) {
                    if (localmWindows.get(i).mBaseLayer <= myLayer) {
                        i++;
                        break;
                    }
                }
                if (i < 0) i = 0;
                if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(
                        TAG, "Adding window " + win + " at "
                        + i + " of " + N);
                localmWindows.add(i, win);
                mWindowsChanged = true;
            }
            if (addToToken) {
                if (DEBUG_ADD_REMOVE) Slog.v(TAG, "Adding " + win + " to " + token);
                token.windows.add(tokenWindowsPos, win);
            }

        } else {
            // Figure out this window's ordering relative to the window
            // it is attached to.
            final int NA = token.windows.size();
            final int sublayer = win.mSubLayer;
            int largestSublayer = Integer.MIN_VALUE;
            WindowState windowWithLargestSublayer = null;
            for (i=0; i<NA; i++) {
                WindowState w = token.windows.get(i);
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
                        placeWindowBefore(
                            wSublayer >= 0 ? attached : w, win);
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

    static boolean canBeImeTarget(WindowState w) {
        final int fl = w.mAttrs.flags
                & (FLAG_NOT_FOCUSABLE|FLAG_ALT_FOCUSABLE_IM);
        if (fl == 0 || fl == (FLAG_NOT_FOCUSABLE|FLAG_ALT_FOCUSABLE_IM)
                || w.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING) {
            if (DEBUG_INPUT_METHOD) {
                Slog.i(TAG, "isVisibleOrAdding " + w + ": " + w.isVisibleOrAdding());
                if (!w.isVisibleOrAdding()) {
                    Slog.i(TAG, "  mSurface=" + w.mSurface + " reportDestroy=" + w.mReportDestroySurface
                            + " relayoutCalled=" + w.mRelayoutCalled + " viewVis=" + w.mViewVisibility
                            + " policyVis=" + w.mPolicyVisibility + " attachHid=" + w.mAttachedHidden
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

    int findDesiredInputMethodWindowIndexLocked(boolean willMove) {
        final ArrayList<WindowState> localmWindows = mWindows;
        final int N = localmWindows.size();
        WindowState w = null;
        int i = N;
        while (i > 0) {
            i--;
            w = localmWindows.get(i);

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
                        && w.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING
                        && i > 0) {
                    WindowState wb = localmWindows.get(i-1);
                    if (wb.mAppToken == w.mAppToken && canBeImeTarget(wb)) {
                        i--;
                        w = wb;
                    }
                }
                break;
            }
        }

        if (DEBUG_INPUT_METHOD && willMove) Slog.v(TAG, "Proposed new IME target: " + w);
        
        // Now, a special case -- if the last target's window is in the
        // process of exiting, and is above the new target, keep on the
        // last target to avoid flicker.  Consider for example a Dialog with
        // the IME shown: when the Dialog is dismissed, we want to keep
        // the IME above it until it is completely gone so it doesn't drop
        // behind the dialog or its full-screen scrim.
        if (mInputMethodTarget != null && w != null
                && mInputMethodTarget.isDisplayedLw()
                && mInputMethodTarget.mExiting) {
            if (mInputMethodTarget.mAnimLayer > w.mAnimLayer) {
                w = mInputMethodTarget;
                i = localmWindows.indexOf(w);
                if (DEBUG_INPUT_METHOD) Slog.v(TAG, "Current target higher, switching to: " + w);
            }
        }

        if (DEBUG_INPUT_METHOD) Slog.v(TAG, "Desired input method target="
                + w + " willMove=" + willMove);

        if (willMove && w != null) {
            final WindowState curTarget = mInputMethodTarget;
            if (curTarget != null && curTarget.mAppToken != null) {

                // Now some fun for dealing with window animations that
                // modify the Z order.  We need to look at all windows below
                // the current target that are in this app, finding the highest
                // visible one in layering.
                AppWindowToken token = curTarget.mAppToken;
                WindowState highestTarget = null;
                int highestPos = 0;
                if (token.animating || token.animation != null) {
                    int pos = 0;
                    pos = localmWindows.indexOf(curTarget);
                    while (pos >= 0) {
                        WindowState win = localmWindows.get(pos);
                        if (win.mAppToken != token) {
                            break;
                        }
                        if (!win.mRemoved) {
                            if (highestTarget == null || win.mAnimLayer >
                                    highestTarget.mAnimLayer) {
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
                            + " animating=" + highestTarget.isAnimating()
                            + " layer=" + highestTarget.mAnimLayer
                            + " new layer=" + w.mAnimLayer);

                    if (mNextAppTransition != WindowManagerPolicy.TRANSIT_UNSET) {
                        // If we are currently setting up for an animation,
                        // hold everything until we can find out what will happen.
                        mInputMethodTargetWaitingAnim = true;
                        mInputMethodTarget = highestTarget;
                        return highestPos + 1;
                    } else if (highestTarget.isAnimating() &&
                            highestTarget.mAnimLayer > w.mAnimLayer) {
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
                if (DEBUG_INPUT_METHOD) {
                    RuntimeException e = null;
                    if (!HIDE_STACK_CRAWLS) {
                        e = new RuntimeException();
                        e.fillInStackTrace();
                    }
                    Slog.w(TAG, "Moving IM target from "
                            + mInputMethodTarget + " to " + w, e);
                }
                mInputMethodTarget = w;
                mInputMethodTargetWaitingAnim = false;
                if (w.mAppToken != null) {
                    setInputMethodAnimLayerAdjustment(w.mAppToken.animLayerAdjustment);
                } else {
                    setInputMethodAnimLayerAdjustment(0);
                }
            }
            return i+1;
        }
        if (willMove) {
            if (DEBUG_INPUT_METHOD) {
                RuntimeException e = null;
                if (!HIDE_STACK_CRAWLS) {
                    e = new RuntimeException();
                    e.fillInStackTrace();
                }
                Slog.w(TAG, "Moving IM target from "
                        + mInputMethodTarget + " to null", e);
            }
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
            mWindows.add(pos, win);
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
            imw.mAnimLayer = imw.mLayer + adj;
            if (DEBUG_LAYERS) Slog.v(TAG, "IM win " + imw
                    + " anim layer: " + imw.mAnimLayer);
            int wi = imw.mChildWindows.size();
            while (wi > 0) {
                wi--;
                WindowState cw = imw.mChildWindows.get(wi);
                cw.mAnimLayer = cw.mLayer + adj;
                if (DEBUG_LAYERS) Slog.v(TAG, "IM win " + cw
                        + " anim layer: " + cw.mAnimLayer);
            }
        }
        int di = mInputMethodDialogs.size();
        while (di > 0) {
            di --;
            imw = mInputMethodDialogs.get(di);
            imw.mAnimLayer = imw.mLayer + adj;
            if (DEBUG_LAYERS) Slog.v(TAG, "IM win " + imw
                    + " anim layer: " + imw.mAnimLayer);
        }
    }

    private int tmpRemoveWindowLocked(int interestingPos, WindowState win) {
        int wpos = mWindows.indexOf(win);
        if (wpos >= 0) {
            if (wpos < interestingPos) interestingPos--;
            if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG, "Temp removing at " + wpos + ": " + win);
            mWindows.remove(wpos);
            mWindowsChanged = true;
            int NC = win.mChildWindows.size();
            while (NC > 0) {
                NC--;
                WindowState cw = win.mChildWindows.get(NC);
                int cpos = mWindows.indexOf(cw);
                if (cpos >= 0) {
                    if (cpos < interestingPos) interestingPos--;
                    if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG, "Temp removing child at "
                            + cpos + ": " + cw);
                    mWindows.remove(cpos);
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
        int wpos = mWindows.indexOf(win);
        if (wpos >= 0) {
            if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG, "ReAdd removing from " + wpos
                    + ": " + win);
            mWindows.remove(wpos);
            mWindowsChanged = true;
            reAddWindowLocked(wpos, win);
        }
    }

    void logWindowList(String prefix) {
        int N = mWindows.size();
        while (N > 0) {
            N--;
            Slog.v(TAG, prefix + "#" + N + ": " + mWindows.get(N));
        }
    }

    void moveInputMethodDialogsLocked(int pos) {
        ArrayList<WindowState> dialogs = mInputMethodDialogs;

        final int N = dialogs.size();
        if (DEBUG_INPUT_METHOD) Slog.v(TAG, "Removing " + N + " dialogs w/pos=" + pos);
        for (int i=0; i<N; i++) {
            pos = tmpRemoveWindowLocked(pos, dialogs.get(i));
        }
        if (DEBUG_INPUT_METHOD) {
            Slog.v(TAG, "Window list w/pos=" + pos);
            logWindowList("  ");
        }

        if (pos >= 0) {
            final AppWindowToken targetAppToken = mInputMethodTarget.mAppToken;
            if (pos < mWindows.size()) {
                WindowState wp = mWindows.get(pos);
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
                logWindowList("  ");
            }
            return;
        }
        for (int i=0; i<N; i++) {
            WindowState win = dialogs.get(i);
            win.mTargetAppToken = null;
            reAddWindowToListInOrderLocked(win);
            if (DEBUG_INPUT_METHOD) {
                Slog.v(TAG, "No IM target, final list:");
                logWindowList("  ");
            }
        }
    }

    boolean moveInputMethodWindowsIfNeededLocked(boolean needAssignLayers) {
        final WindowState imWin = mInputMethodWindow;
        final int DN = mInputMethodDialogs.size();
        if (imWin == null && DN == 0) {
            return false;
        }

        int imPos = findDesiredInputMethodWindowIndexLocked(true);
        if (imPos >= 0) {
            // In this case, the input method windows are to be placed
            // immediately above the window they are targeting.

            // First check to see if the input method windows are already
            // located here, and contiguous.
            final int N = mWindows.size();
            WindowState firstImWin = imPos < N
                    ? mWindows.get(imPos) : null;

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
                    if (!(mWindows.get(pos)).mIsImWindow) {
                        break;
                    }
                    pos++;
                }
                pos++;
                // Now there should be no more input method windows above.
                while (pos < N) {
                    if ((mWindows.get(pos)).mIsImWindow) {
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
                    logWindowList("  ");
                }
                imPos = tmpRemoveWindowLocked(imPos, imWin);
                if (DEBUG_INPUT_METHOD) {
                    Slog.v(TAG, "List after removing with new pos " + imPos + ":");
                    logWindowList("  ");
                }
                imWin.mTargetAppToken = mInputMethodTarget.mAppToken;
                reAddWindowLocked(imPos, imWin);
                if (DEBUG_INPUT_METHOD) {
                    Slog.v(TAG, "List after moving IM to " + imPos + ":");
                    logWindowList("  ");
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
                    logWindowList("  ");
                }
                if (DN > 0) moveInputMethodDialogsLocked(-1);;
            } else {
                moveInputMethodDialogsLocked(-1);;
            }

        }

        if (needAssignLayers) {
            assignLayersLocked();
        }

        return true;
    }

    void adjustInputMethodDialogsLocked() {
        moveInputMethodDialogsLocked(findDesiredInputMethodWindowIndexLocked(true));
    }

    final boolean isWallpaperVisible(WindowState wallpaperTarget) {
        if (DEBUG_WALLPAPER) Slog.v(TAG, "Wallpaper vis: target obscured="
                + (wallpaperTarget != null ? Boolean.toString(wallpaperTarget.mObscured) : "??")
                + " anim=" + ((wallpaperTarget != null && wallpaperTarget.mAppToken != null)
                        ? wallpaperTarget.mAppToken.animation : null)
                + " upper=" + mUpperWallpaperTarget
                + " lower=" + mLowerWallpaperTarget);
        return (wallpaperTarget != null
                        && (!wallpaperTarget.mObscured || (wallpaperTarget.mAppToken != null
                                && wallpaperTarget.mAppToken.animation != null)))
                || mUpperWallpaperTarget != null
                || mLowerWallpaperTarget != null;
    }

    static final int ADJUST_WALLPAPER_LAYERS_CHANGED = 1<<1;
    static final int ADJUST_WALLPAPER_VISIBILITY_CHANGED = 1<<2;

    int adjustWallpaperWindowsLocked() {
        int changed = 0;

        final int dw = mAppDisplayWidth;
        final int dh = mAppDisplayHeight;

        // First find top-most window that has asked to be on top of the
        // wallpaper; all wallpapers go behind it.
        final ArrayList<WindowState> localmWindows = mWindows;
        int N = localmWindows.size();
        WindowState w = null;
        WindowState foundW = null;
        int foundI = 0;
        WindowState topCurW = null;
        int topCurI = 0;
        int windowDetachedI = -1;
        int i = N;
        while (i > 0) {
            i--;
            w = localmWindows.get(i);
            if ((w.mAttrs.type == WindowManager.LayoutParams.TYPE_WALLPAPER)) {
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
                if (w.mAppToken.hidden && w.mAppToken.animation == null) {
                    if (DEBUG_WALLPAPER) Slog.v(TAG,
                            "Skipping not hidden or animating token: " + w);
                    continue;
                }
            }
            if (DEBUG_WALLPAPER) Slog.v(TAG, "Win " + w + ": readyfordisplay="
                    + w.isReadyForDisplay() + " drawpending=" + w.mDrawPending
                    + " commitdrawpending=" + w.mCommitDrawPending);
            if ((w.mAttrs.flags&FLAG_SHOW_WALLPAPER) != 0 && w.isReadyForDisplay()
                    && (mWallpaperTarget == w
                            || (!w.mDrawPending && !w.mCommitDrawPending))) {
                if (DEBUG_WALLPAPER) Slog.v(TAG,
                        "Found wallpaper activity: #" + i + "=" + w);
                foundW = w;
                foundI = i;
                if (w == mWallpaperTarget && ((w.mAppToken != null
                        && w.mAppToken.animation != null)
                        || w.mAnimation != null)) {
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
            if (DEBUG_WALLPAPER) Slog.v(TAG,
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
                if (DEBUG_WALLPAPER) Slog.v(TAG,
                        "Wallpaper not changing: waiting for app anim in current target");
                return 0;
            }
            if (foundW != null && foundW.mAppToken != null) {
                if (DEBUG_WALLPAPER) Slog.v(TAG,
                        "Wallpaper not changing: waiting for app anim in found target");
                return 0;
            }
        }

        if (mWallpaperTarget != foundW) {
            if (DEBUG_WALLPAPER) {
                Slog.v(TAG, "New wallpaper target: " + foundW
                        + " oldTarget: " + mWallpaperTarget);
            }

            mLowerWallpaperTarget = null;
            mUpperWallpaperTarget = null;

            WindowState oldW = mWallpaperTarget;
            mWallpaperTarget = foundW;

            // Now what is happening...  if the current and new targets are
            // animating, then we are in our super special mode!
            if (foundW != null && oldW != null) {
                boolean oldAnim = oldW.mAnimation != null
                        || (oldW.mAppToken != null && oldW.mAppToken.animation != null);
                boolean foundAnim = foundW.mAnimation != null
                        || (foundW.mAppToken != null && foundW.mAppToken.animation != null);
                if (DEBUG_WALLPAPER) {
                    Slog.v(TAG, "New animation: " + foundAnim
                            + " old animation: " + oldAnim);
                }
                if (foundAnim && oldAnim) {
                    int oldI = localmWindows.indexOf(oldW);
                    if (DEBUG_WALLPAPER) {
                        Slog.v(TAG, "New i: " + foundI + " old i: " + oldI);
                    }
                    if (oldI >= 0) {
                        if (DEBUG_WALLPAPER) {
                            Slog.v(TAG, "Animating wallpapers: old#" + oldI
                                    + "=" + oldW + "; new#" + foundI
                                    + "=" + foundW);
                        }

                        // Set the new target correctly.
                        if (foundW.mAppToken != null && foundW.mAppToken.hiddenRequested) {
                            if (DEBUG_WALLPAPER) {
                                Slog.v(TAG, "Old wallpaper still the target.");
                            }
                            mWallpaperTarget = oldW;
                        }

                        // Now set the upper and lower wallpaper targets
                        // correctly, and make sure that we are positioning
                        // the wallpaper below the lower.
                        if (foundI > oldI) {
                            // The new target is on top of the old one.
                            if (DEBUG_WALLPAPER) {
                                Slog.v(TAG, "Found target above old target.");
                            }
                            mUpperWallpaperTarget = foundW;
                            mLowerWallpaperTarget = oldW;
                            foundW = oldW;
                            foundI = oldI;
                        } else {
                            // The new target is below the old one.
                            if (DEBUG_WALLPAPER) {
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
            boolean lowerAnimating = mLowerWallpaperTarget.mAnimation != null
                    || (mLowerWallpaperTarget.mAppToken != null
                            && mLowerWallpaperTarget.mAppToken.animation != null);
            boolean upperAnimating = mUpperWallpaperTarget.mAnimation != null
                    || (mUpperWallpaperTarget.mAppToken != null
                            && mUpperWallpaperTarget.mAppToken.animation != null);
            if (!lowerAnimating || !upperAnimating) {
                if (DEBUG_WALLPAPER) {
                    Slog.v(TAG, "No longer animating wallpaper targets!");
                }
                mLowerWallpaperTarget = null;
                mUpperWallpaperTarget = null;
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
                    ? foundW.mAppToken.animLayerAdjustment : 0;

            final int maxLayer = mPolicy.getMaxWallpaperLayer()
                    * TYPE_LAYER_MULTIPLIER
                    + TYPE_LAYER_OFFSET;

            // Now w is the window we are supposed to be behind...  but we
            // need to be sure to also be behind any of its attached windows,
            // AND any starting window associated with it, AND below the
            // maximum layer the policy allows for wallpapers.
            while (foundI > 0) {
                WindowState wb = localmWindows.get(foundI-1);
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
            foundW = foundI > 0 ? localmWindows.get(foundI-1) : null;
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
                changed |= ADJUST_WALLPAPER_VISIBILITY_CHANGED;
                token.hidden = !visible;
                // Need to do a layout to ensure the wallpaper now has the
                // correct size.
                mLayoutNeeded = true;
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
                if (wallpaper.mWallpaperVisible != visible) {
                    wallpaper.mWallpaperVisible = visible;
                    try {
                        if (DEBUG_VISIBILITY || DEBUG_WALLPAPER) Slog.v(TAG,
                                "Setting visibility of wallpaper " + wallpaper
                                + ": " + visible);
                        wallpaper.mClient.dispatchAppVisibility(visible);
                    } catch (RemoteException e) {
                    }
                }

                wallpaper.mAnimLayer = wallpaper.mLayer + mWallpaperAnimLayerAdjustment;
                if (DEBUG_LAYERS || DEBUG_WALLPAPER) Slog.v(TAG, "Wallpaper win "
                        + wallpaper + " anim layer: " + wallpaper.mAnimLayer);

                // First, if this window is at the current index, then all
                // is well.
                if (wallpaper == foundW) {
                    foundI--;
                    foundW = foundI > 0
                            ? localmWindows.get(foundI-1) : null;
                    continue;
                }

                // The window didn't match...  the current wallpaper window,
                // wherever it is, is in the wrong place, so make sure it is
                // not in the list.
                int oldIndex = localmWindows.indexOf(wallpaper);
                if (oldIndex >= 0) {
                    if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG, "Wallpaper removing at "
                            + oldIndex + ": " + wallpaper);
                    localmWindows.remove(oldIndex);
                    mWindowsChanged = true;
                    if (oldIndex < foundI) {
                        foundI--;
                    }
                }

                // Now stick it in.
                if (DEBUG_WALLPAPER || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) {
                    Slog.v(TAG, "Moving wallpaper " + wallpaper
                            + " from " + oldIndex + " to " + foundI);
                }

                localmWindows.add(foundI, wallpaper);
                mWindowsChanged = true;
                changed |= ADJUST_WALLPAPER_LAYERS_CHANGED;
            }
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
                wallpaper.mAnimLayer = wallpaper.mLayer + adj;
                if (DEBUG_LAYERS || DEBUG_WALLPAPER) Slog.v(TAG, "Wallpaper win "
                        + wallpaper + " anim layer: " + wallpaper.mAnimLayer);
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

        if (rawChanged) {
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

    boolean updateWallpaperOffsetLocked(WindowState changingTarget, boolean sync) {
        final int dw = mAppDisplayWidth;
        final int dh = mAppDisplayHeight;

        boolean changed = false;

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
                    wallpaper.computeShownFrameLocked();
                    changed = true;
                    // We only want to be synchronous with one wallpaper.
                    sync = false;
                }
            }
        }

        return changed;
    }

    void updateWallpaperVisibilityLocked() {
        final boolean visible = isWallpaperVisible(mWallpaperTarget);
        final int dw = mAppDisplayWidth;
        final int dh = mAppDisplayHeight;

        int curTokenIndex = mWallpaperTokens.size();
        while (curTokenIndex > 0) {
            curTokenIndex--;
            WindowToken token = mWallpaperTokens.get(curTokenIndex);
            if (token.hidden == visible) {
                token.hidden = !visible;
                // Need to do a layout to ensure the wallpaper now has the
                // correct size.
                mLayoutNeeded = true;
            }

            int curWallpaperIndex = token.windows.size();
            while (curWallpaperIndex > 0) {
                curWallpaperIndex--;
                WindowState wallpaper = token.windows.get(curWallpaperIndex);
                if (visible) {
                    updateWallpaperOffsetLocked(wallpaper, dw, dh, false);
                }

                if (wallpaper.mWallpaperVisible != visible) {
                    wallpaper.mWallpaperVisible = visible;
                    try {
                        if (DEBUG_VISIBILITY || DEBUG_WALLPAPER) Slog.v(TAG,
                                "Updating visibility of wallpaper " + wallpaper
                                + ": " + visible);
                        wallpaper.mClient.dispatchAppVisibility(visible);
                    } catch (RemoteException e) {
                    }
                }
            }
        }
    }
    
    public int addWindow(Session session, IWindow client,
            WindowManager.LayoutParams attrs, int viewVisibility,
            Rect outContentInsets, InputChannel outInputChannel) {
        int res = mPolicy.checkAddPermission(attrs);
        if (res != WindowManagerImpl.ADD_OKAY) {
            return res;
        }

        boolean reportNewConfig = false;
        WindowState attachedWindow = null;
        WindowState win = null;
        long origId;

        synchronized(mWindowMap) {
            if (mDisplay == null) {
                throw new IllegalStateException("Display has not been initialialized");
            }

            if (mWindowMap.containsKey(client.asBinder())) {
                Slog.w(TAG, "Window " + client + " is already added");
                return WindowManagerImpl.ADD_DUPLICATE_ADD;
            }

            if (attrs.type >= FIRST_SUB_WINDOW && attrs.type <= LAST_SUB_WINDOW) {
                attachedWindow = windowForClientLocked(null, attrs.token, false);
                if (attachedWindow == null) {
                    Slog.w(TAG, "Attempted to add window with token that is not a window: "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerImpl.ADD_BAD_SUBWINDOW_TOKEN;
                }
                if (attachedWindow.mAttrs.type >= FIRST_SUB_WINDOW
                        && attachedWindow.mAttrs.type <= LAST_SUB_WINDOW) {
                    Slog.w(TAG, "Attempted to add window with token that is a sub-window: "
                            + attrs.token + ".  Aborting.");
                    return WindowManagerImpl.ADD_BAD_SUBWINDOW_TOKEN;
                }
            }

            boolean addToken = false;
            WindowToken token = mTokenMap.get(attrs.token);
            if (token == null) {
                if (attrs.type >= FIRST_APPLICATION_WINDOW
                        && attrs.type <= LAST_APPLICATION_WINDOW) {
                    Slog.w(TAG, "Attempted to add application window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerImpl.ADD_BAD_APP_TOKEN;
                }
                if (attrs.type == TYPE_INPUT_METHOD) {
                    Slog.w(TAG, "Attempted to add input method window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerImpl.ADD_BAD_APP_TOKEN;
                }
                if (attrs.type == TYPE_WALLPAPER) {
                    Slog.w(TAG, "Attempted to add wallpaper window with unknown token "
                          + attrs.token + ".  Aborting.");
                    return WindowManagerImpl.ADD_BAD_APP_TOKEN;
                }
                token = new WindowToken(this, attrs.token, -1, false);
                addToken = true;
            } else if (attrs.type >= FIRST_APPLICATION_WINDOW
                    && attrs.type <= LAST_APPLICATION_WINDOW) {
                AppWindowToken atoken = token.appWindowToken;
                if (atoken == null) {
                    Slog.w(TAG, "Attempted to add window with non-application token "
                          + token + ".  Aborting.");
                    return WindowManagerImpl.ADD_NOT_APP_TOKEN;
                } else if (atoken.removed) {
                    Slog.w(TAG, "Attempted to add window with exiting application token "
                          + token + ".  Aborting.");
                    return WindowManagerImpl.ADD_APP_EXITING;
                }
                if (attrs.type == TYPE_APPLICATION_STARTING && atoken.firstWindowDrawn) {
                    // No need for this guy!
                    if (localLOGV) Slog.v(
                            TAG, "**** NO NEED TO START: " + attrs.getTitle());
                    return WindowManagerImpl.ADD_STARTING_NOT_NEEDED;
                }
            } else if (attrs.type == TYPE_INPUT_METHOD) {
                if (token.windowType != TYPE_INPUT_METHOD) {
                    Slog.w(TAG, "Attempted to add input method window with bad token "
                            + attrs.token + ".  Aborting.");
                      return WindowManagerImpl.ADD_BAD_APP_TOKEN;
                }
            } else if (attrs.type == TYPE_WALLPAPER) {
                if (token.windowType != TYPE_WALLPAPER) {
                    Slog.w(TAG, "Attempted to add wallpaper window with bad token "
                            + attrs.token + ".  Aborting.");
                      return WindowManagerImpl.ADD_BAD_APP_TOKEN;
                }
            }

            win = new WindowState(this, session, client, token,
                    attachedWindow, attrs, viewVisibility);
            if (win.mDeathRecipient == null) {
                // Client has apparently died, so there is no reason to
                // continue.
                Slog.w(TAG, "Adding window client " + client.asBinder()
                        + " that is dead, aborting.");
                return WindowManagerImpl.ADD_APP_EXITING;
            }

            mPolicy.adjustWindowParamsLw(win.mAttrs);

            res = mPolicy.prepareAddWindowLw(win, attrs);
            if (res != WindowManagerImpl.ADD_OKAY) {
                return res;
            }
            
            if (outInputChannel != null) {
                String name = win.makeInputChannelName();
                InputChannel[] inputChannels = InputChannel.openInputChannelPair(name);
                win.mInputChannel = inputChannels[0];
                inputChannels[1].transferToBinderOutParameter(outInputChannel);
                
                mInputManager.registerInputChannel(win.mInputChannel, win.mInputWindowHandle);
            }

            // From now on, no exceptions or errors allowed!

            res = WindowManagerImpl.ADD_OKAY;

            origId = Binder.clearCallingIdentity();

            if (addToken) {
                mTokenMap.put(attrs.token, token);
            }
            win.attach();
            mWindowMap.put(client.asBinder(), win);

            if (attrs.type == TYPE_APPLICATION_STARTING &&
                    token.appWindowToken != null) {
                token.appWindowToken.startingWindow = win;
            }

            boolean imMayMove = true;

            if (attrs.type == TYPE_INPUT_METHOD) {
                mInputMethodWindow = win;
                addInputMethodWindowToListLocked(win);
                imMayMove = false;
            } else if (attrs.type == TYPE_INPUT_METHOD_DIALOG) {
                mInputMethodDialogs.add(win);
                addWindowToListInOrderLocked(win, true);
                adjustInputMethodDialogsLocked();
                imMayMove = false;
            } else {
                addWindowToListInOrderLocked(win, true);
                if (attrs.type == TYPE_WALLPAPER) {
                    mLastWallpaperTimeoutTime = 0;
                    adjustWallpaperWindowsLocked();
                } else if ((attrs.flags&FLAG_SHOW_WALLPAPER) != 0) {
                    adjustWallpaperWindowsLocked();
                }
            }

            win.mEnterAnimationPending = true;

            mPolicy.getContentInsetHintLw(attrs, outContentInsets);

            if (mInTouchMode) {
                res |= WindowManagerImpl.ADD_FLAG_IN_TOUCH_MODE;
            }
            if (win == null || win.mAppToken == null || !win.mAppToken.clientHidden) {
                res |= WindowManagerImpl.ADD_FLAG_APP_VISIBLE;
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

            assignLayersLocked();
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
            + ", surface=" + win.mSurface);

        final long origId = Binder.clearCallingIdentity();
        
        win.disposeInputChannel();

        if (DEBUG_APP_TRANSITIONS) Slog.v(
                TAG, "Remove " + win + ": mSurface=" + win.mSurface
                + " mExiting=" + win.mExiting
                + " isAnimating=" + win.isAnimating()
                + " app-animation="
                + (win.mAppToken != null ? win.mAppToken.animation : null)
                + " inPendingTransaction="
                + (win.mAppToken != null ? win.mAppToken.inPendingTransaction : false)
                + " mDisplayFrozen=" + mDisplayFrozen);
        // Visibility of the removed window. Will be used later to update orientation later on.
        boolean wasVisible = false;
        // First, see if we need to run an animation.  If we do, we have
        // to hold off on removing the window until the animation is done.
        // If the display is frozen, just remove immediately, since the
        // animation wouldn't be seen.
        if (win.mSurface != null && !mDisplayFrozen && mPolicy.isScreenOn()) {
            // If we are not currently running the exit animation, we
            // need to see about starting one.
            if (wasVisible=win.isWinVisibleLw()) {

                int transit = WindowManagerPolicy.TRANSIT_EXIT;
                if (win.getAttrs().type == TYPE_APPLICATION_STARTING) {
                    transit = WindowManagerPolicy.TRANSIT_PREVIEW_DONE;
                }
                // Try starting an animation.
                if (applyAnimationLocked(win, transit, false)) {
                    win.mExiting = true;
                }
            }
            if (win.mExiting || win.isAnimating()) {
                // The exit animation is running... wait for it!
                //Slog.i(TAG, "*** Running exit animation...");
                win.mExiting = true;
                win.mRemoveOnExit = true;
                mLayoutNeeded = true;
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
        mWindows.remove(win);
        mPendingRemove.remove(win);
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
                atoken.startingWindow = null;
            } else if (atoken.allAppWindows.size() == 0 && atoken.startingData != null) {
                // If this is the last window and we had requested a starting
                // transition window, well there is no point now.
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
            assignLayersLocked();
            mLayoutNeeded = true;
            performLayoutAndPlaceSurfacesLocked();
            if (win.mAppToken != null) {
                win.mAppToken.updateReportedVisibilityLocked();
            }
        }
        
        mInputMonitor.updateInputWindowsLw(true /*force*/);
    }

    static void logSurface(WindowState w, String msg, RuntimeException where) {
        String str = "  SURFACE " + Integer.toHexString(w.hashCode())
                + ": " + msg + " / " + w.mAttrs.getTitle();
        if (where != null) {
            Slog.i(TAG, str, where);
        } else {
            Slog.i(TAG, str);
        }
    }
    
    void setTransparentRegionWindow(Session session, IWindow client, Region region) {
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mWindowMap) {
                WindowState w = windowForClientLocked(session, client, false);
                if ((w != null) && (w.mSurface != null)) {
                    if (SHOW_TRANSACTIONS) Slog.i(TAG,
                            ">>> OPEN TRANSACTION setTransparentRegion");
                    Surface.openTransaction();
                    try {
                        if (SHOW_TRANSACTIONS) logSurface(w,
                                "transparentRegionHint=" + region, null);
                        w.mSurface.setTransparentRegionHint(region);
                    } finally {
                        Surface.closeTransaction();
                        if (SHOW_TRANSACTIONS) Slog.i(TAG,
                                "<<< CLOSE TRANSACTION setTransparentRegion");
                    }
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
                    mLayoutNeeded = true;
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
            if (updateWallpaperOffsetLocked(window, true)) {
                performLayoutAndPlaceSurfacesLocked();
            }
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

    public int relayoutWindow(Session session, IWindow client,
            WindowManager.LayoutParams attrs, int requestedWidth,
            int requestedHeight, int viewVisibility, boolean insetsPending,
            Rect outFrame, Rect outContentInsets, Rect outVisibleInsets,
            Configuration outConfig, Surface outSurface) {
        boolean displayed = false;
        boolean inTouchMode;
        boolean configChanged;

        // if they don't have this permission, mask out the status bar bits
        if (attrs != null) {
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.STATUS_BAR)
                    != PackageManager.PERMISSION_GRANTED) {
                attrs.systemUiVisibility &= ~StatusBarManager.DISABLE_MASK;
                attrs.subtreeSystemUiVisibility &= ~StatusBarManager.DISABLE_MASK;
            }
        }
        long origId = Binder.clearCallingIdentity();

        synchronized(mWindowMap) {
            WindowState win = windowForClientLocked(session, client, false);
            if (win == null) {
                return 0;
            }
            win.mRequestedWidth = requestedWidth;
            win.mRequestedHeight = requestedHeight;

            if (attrs != null) {
                mPolicy.adjustWindowParamsLw(attrs);
            }

            int attrChanges = 0;
            int flagChanges = 0;
            if (attrs != null) {
                flagChanges = win.mAttrs.flags ^= attrs.flags;
                attrChanges = win.mAttrs.copyFrom(attrs);
            }

            if (DEBUG_LAYOUT) Slog.v(TAG, "Relayout " + win + ": " + win.mAttrs);

            win.mEnforceSizeCompat = (win.mAttrs.flags & FLAG_COMPATIBLE_WINDOW) != 0;

            if ((attrChanges & WindowManager.LayoutParams.ALPHA_CHANGED) != 0) {
                win.mAlpha = attrs.alpha;
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

            boolean imMayMove = (flagChanges&(
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM |
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)) != 0;

            boolean focusMayChange = win.mViewVisibility != viewVisibility
                    || ((flagChanges&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
                    || (!win.mRelayoutCalled);

            boolean wallpaperMayMove = win.mViewVisibility != viewVisibility
                    && (win.mAttrs.flags & FLAG_SHOW_WALLPAPER) != 0;

            win.mRelayoutCalled = true;
            final int oldVisibility = win.mViewVisibility;
            win.mViewVisibility = viewVisibility;
            if (viewVisibility == View.VISIBLE &&
                    (win.mAppToken == null || !win.mAppToken.clientHidden)) {
                displayed = !win.isVisibleLw();
                if (win.mExiting) {
                    win.mExiting = false;
                    if (win.mAnimation != null) {
                        win.mAnimation.cancel();
                        win.mAnimation = null;
                    }
                }
                if (win.mDestroying) {
                    win.mDestroying = false;
                    mDestroySurface.remove(win);
                }
                if (oldVisibility == View.GONE) {
                    win.mEnterAnimationPending = true;
                }
                if (displayed) {
                    if (win.mSurface != null && !win.mDrawPending
                            && !win.mCommitDrawPending && !mDisplayFrozen
                            && mPolicy.isScreenOn()) {
                        applyEnterAnimationLocked(win);
                    }
                    if ((win.mAttrs.flags
                            & WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON) != 0) {
                        if (DEBUG_VISIBILITY) Slog.v(TAG,
                                "Relayout window turning screen on: " + win);
                        win.mTurnOnScreen = true;
                    }
                    int diff = 0;
                    if (win.mConfiguration != mCurConfiguration
                            && (win.mConfiguration == null
                                    || (diff=mCurConfiguration.diff(win.mConfiguration)) != 0)) {
                        win.mConfiguration = mCurConfiguration;
                        if (DEBUG_CONFIGURATION) {
                            Slog.i(TAG, "Window " + win + " visible with new config: "
                                    + win.mConfiguration + " / 0x"
                                    + Integer.toHexString(diff));
                        }
                        outConfig.setTo(mCurConfiguration);
                    }
                }
                if ((attrChanges&WindowManager.LayoutParams.FORMAT_CHANGED) != 0) {
                    // To change the format, we need to re-build the surface.
                    win.destroySurfaceLocked();
                    displayed = true;
                }
                try {
                    Surface surface = win.createSurfaceLocked();
                    if (surface != null) {
                        outSurface.copyFrom(surface);
                        win.mReportDestroySurface = false;
                        win.mSurfacePendingDestroy = false;
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
                if (displayed) {
                    focusMayChange = true;
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
                win.mEnterAnimationPending = false;
                if (win.mSurface != null) {
                    if (DEBUG_VISIBILITY) Slog.i(TAG, "Relayout invis " + win
                            + ": mExiting=" + win.mExiting
                            + " mSurfacePendingDestroy=" + win.mSurfacePendingDestroy);
                    // If we are not currently running the exit animation, we
                    // need to see about starting one.
                    if (!win.mExiting || win.mSurfacePendingDestroy) {
                        // Try starting an animation; if there isn't one, we
                        // can destroy the surface right away.
                        int transit = WindowManagerPolicy.TRANSIT_EXIT;
                        if (win.getAttrs().type == TYPE_APPLICATION_STARTING) {
                            transit = WindowManagerPolicy.TRANSIT_PREVIEW_DONE;
                        }
                        if (!win.mSurfacePendingDestroy && win.isWinVisibleLw() &&
                              applyAnimationLocked(win, transit, false)) {
                            focusMayChange = true;
                            win.mExiting = true;
                        } else if (win.isAnimating()) {
                            // Currently in a hide animation... turn this into
                            // an exit.
                            win.mExiting = true;
                        } else if (win == mWallpaperTarget) {
                            // If the wallpaper is currently behind this
                            // window, we need to change both of them inside
                            // of a transaction to avoid artifacts.
                            win.mExiting = true;
                            win.mAnimating = true;
                        } else {
                            if (mInputMethodWindow == win) {
                                mInputMethodWindow = null;
                            }
                            win.destroySurfaceLocked();
                        }
                    }
                }

                if (win.mSurface == null || (win.getAttrs().flags
                        & WindowManager.LayoutParams.FLAG_KEEP_SURFACE_WHILE_ANIMATING) == 0
                        || win.mSurfacePendingDestroy) {
                    // We are being called from a local process, which
                    // means outSurface holds its current surface.  Ensure the
                    // surface object is cleared, but we don't want it actually
                    // destroyed at this point.
                    win.mSurfacePendingDestroy = false;
                    outSurface.release();
                    if (DEBUG_VISIBILITY) Slog.i(TAG, "Releasing surface in: " + win);
                } else if (win.mSurface != null) {
                    if (DEBUG_VISIBILITY) Slog.i(TAG,
                            "Keeping surface, will report destroy: " + win);
                    win.mReportDestroySurface = true;
                    outSurface.copyFrom(win.mSurface);
                }
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
                if (moveInputMethodWindowsIfNeededLocked(false) || displayed) {
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

            mLayoutNeeded = true;
            win.mGivenInsetsPending = insetsPending;
            if (assignLayers) {
                assignLayersLocked();
            }
            configChanged = updateOrientationFromAppTokensLocked(false);
            performLayoutAndPlaceSurfacesLocked();
            if (displayed && win.mIsWallpaper) {
                updateWallpaperOffsetLocked(win, mAppDisplayWidth, mAppDisplayHeight, false);
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
            
            mInputMonitor.updateInputWindowsLw(true /*force*/);
        }

        if (configChanged) {
            sendNewConfiguration();
        }

        Binder.restoreCallingIdentity(origId);

        return (inTouchMode ? WindowManagerImpl.RELAYOUT_IN_TOUCH_MODE : 0)
                | (displayed ? WindowManagerImpl.RELAYOUT_FIRST_TIME : 0);
    }

    public boolean outOfMemoryWindow(Session session, IWindow client) {
        long origId = Binder.clearCallingIdentity();

        try {
            synchronized(mWindowMap) {
                WindowState win = windowForClientLocked(session, client, false);
                if (win == null) {
                    return false;
                }
                return reclaimSomeSurfaceMemoryLocked(win, "from-client", false);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void finishDrawingWindow(Session session, IWindow client) {
        final long origId = Binder.clearCallingIdentity();
        synchronized(mWindowMap) {
            WindowState win = windowForClientLocked(session, client, false);
            if (win != null && win.finishDrawingLocked()) {
                if ((win.mAttrs.flags&FLAG_SHOW_WALLPAPER) != 0) {
                    adjustWallpaperWindowsLocked();
                }
                mLayoutNeeded = true;
                performLayoutAndPlaceSurfacesLocked();
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    public float getWindowCompatibilityScale(IBinder windowToken) {
        synchronized (mWindowMap) {
            WindowState windowState = mWindowMap.get(windowToken);
            return (windowState != null) ? windowState.mGlobalScale : 1.0f;
        }
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

    void applyEnterAnimationLocked(WindowState win) {
        int transit = WindowManagerPolicy.TRANSIT_SHOW;
        if (win.mEnterAnimationPending) {
            win.mEnterAnimationPending = false;
            transit = WindowManagerPolicy.TRANSIT_ENTER;
        }

        applyAnimationLocked(win, transit, true);
    }

    boolean applyAnimationLocked(WindowState win,
            int transit, boolean isEntrance) {
        if (win.mLocalAnimating && win.mAnimationIsEntrance == isEntrance) {
            // If we are trying to apply an animation, but already running
            // an animation of the same type, then just leave that one alone.
            return true;
        }

        // Only apply an animation if the display isn't frozen.  If it is
        // frozen, there is no reason to animate and it can cause strange
        // artifacts when we unfreeze the display if some different animation
        // is running.
        if (!mDisplayFrozen && mPolicy.isScreenOn()) {
            int anim = mPolicy.selectAnimationLw(win, transit);
            int attr = -1;
            Animation a = null;
            if (anim != 0) {
                a = AnimationUtils.loadAnimation(mContext, anim);
            } else {
                switch (transit) {
                    case WindowManagerPolicy.TRANSIT_ENTER:
                        attr = com.android.internal.R.styleable.WindowAnimation_windowEnterAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_EXIT:
                        attr = com.android.internal.R.styleable.WindowAnimation_windowExitAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_SHOW:
                        attr = com.android.internal.R.styleable.WindowAnimation_windowShowAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_HIDE:
                        attr = com.android.internal.R.styleable.WindowAnimation_windowHideAnimation;
                        break;
                }
                if (attr >= 0) {
                    a = loadAnimation(win.mAttrs, attr);
                }
            }
            if (DEBUG_ANIM) Slog.v(TAG, "applyAnimation: win=" + win
                    + " anim=" + anim + " attr=0x" + Integer.toHexString(attr)
                    + " mAnimation=" + win.mAnimation
                    + " isEntrance=" + isEntrance);
            if (a != null) {
                if (DEBUG_ANIM) {
                    RuntimeException e = null;
                    if (!HIDE_STACK_CRAWLS) {
                        e = new RuntimeException();
                        e.fillInStackTrace();
                    }
                    Slog.v(TAG, "Loaded animation " + a + " for " + win, e);
                }
                win.setAnimation(a);
                win.mAnimationIsEntrance = isEntrance;
            }
        } else {
            win.clearAnimation();
        }

        return win.mAnimation != null;
    }

    private Animation loadAnimation(WindowManager.LayoutParams lp, int animAttr) {
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

    private boolean applyAnimationLocked(AppWindowToken wtoken,
            WindowManager.LayoutParams lp, int transit, boolean enter) {
        // Only apply an animation if the display isn't frozen.  If it is
        // frozen, there is no reason to animate and it can cause strange
        // artifacts when we unfreeze the display if some different animation
        // is running.
        if (!mDisplayFrozen && mPolicy.isScreenOn()) {
            Animation a;
            if (mNextAppTransitionPackage != null) {
                a = loadAnimation(mNextAppTransitionPackage, enter ?
                        mNextAppTransitionEnter : mNextAppTransitionExit);
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
                if (DEBUG_ANIM) Slog.v(TAG, "applyAnimation: wtoken=" + wtoken
                        + " anim=" + a
                        + " animAttr=0x" + Integer.toHexString(animAttr)
                        + " transit=" + transit);
            }
            if (a != null) {
                if (DEBUG_ANIM) {
                    RuntimeException e = null;
                    if (!HIDE_STACK_CRAWLS) {
                        e = new RuntimeException();
                        e.fillInStackTrace();
                    }
                    Slog.v(TAG, "Loaded animation " + a + " for " + wtoken, e);
                }
                wtoken.setAnimation(a);
            }
        } else {
            wtoken.clearAnimation();
        }

        return wtoken.animation != null;
    }

    // -------------------------------------------------------------
    // Application Window Tokens
    // -------------------------------------------------------------

    public void validateAppTokens(List tokens) {
        int v = tokens.size()-1;
        int m = mAppTokens.size()-1;
        while (v >= 0 && m >= 0) {
            AppWindowToken wtoken = mAppTokens.get(m);
            if (wtoken.removed) {
                m--;
                continue;
            }
            if (tokens.get(v) != wtoken.token) {
                Slog.w(TAG, "Tokens out of sync: external is " + tokens.get(v)
                      + " @ " + v + ", internal is " + wtoken.token + " @ " + m);
            }
            v--;
            m--;
        }
        while (v >= 0) {
            Slog.w(TAG, "External token not found: " + tokens.get(v) + " @ " + v);
            v--;
        }
        while (m >= 0) {
            AppWindowToken wtoken = mAppTokens.get(m);
            if (!wtoken.removed) {
                Slog.w(TAG, "Invalid internal token: " + wtoken.token + " @ " + m);
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

    AppWindowToken findAppWindowToken(IBinder token) {
        WindowToken wtoken = mTokenMap.get(token);
        if (wtoken == null) {
            return null;
        }
        return wtoken.appWindowToken;
    }

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
            }
        }
    }

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
                    wtoken.hidden = true;

                    final int N = wtoken.windows.size();
                    boolean changed = false;

                    for (int i=0; i<N; i++) {
                        WindowState win = wtoken.windows.get(i);

                        if (win.isAnimating()) {
                            delayed = true;
                        }

                        if (win.isVisibleNow()) {
                            applyAnimationLocked(win,
                                    WindowManagerPolicy.TRANSIT_EXIT, false);
                            changed = true;
                        }
                    }

                    if (changed) {
                        mLayoutNeeded = true;
                        performLayoutAndPlaceSurfacesLocked();
                        updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL,
                                false /*updateInputWindows*/);
                    }

                    if (delayed) {
                        mExitingTokens.add(wtoken);
                    } else if (wtoken.windowType == TYPE_WALLPAPER) {
                        mWallpaperTokens.remove(wtoken);
                    }
                }

                mInputMonitor.updateInputWindowsLw(true /*force*/);
            } else {
                Slog.w(TAG, "Attempted to remove non-existing token: " + token);
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    public void addAppToken(int addPos, IApplicationToken token,
            int groupId, int requestedOrientation, boolean fullscreen) {
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
            AppWindowToken wtoken = findAppWindowToken(token.asBinder());
            if (wtoken != null) {
                Slog.w(TAG, "Attempted to add existing app token: " + token);
                return;
            }
            wtoken = new AppWindowToken(this, token);
            wtoken.inputDispatchingTimeoutNanos = inputDispatchingTimeoutNanos;
            wtoken.groupId = groupId;
            wtoken.appFullscreen = fullscreen;
            wtoken.requestedOrientation = requestedOrientation;
            if (DEBUG_TOKEN_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(TAG, "addAppToken: " + wtoken);
            mAppTokens.add(addPos, wtoken);
            mTokenMap.put(token.asBinder(), wtoken);

            // Application tokens start out hidden.
            wtoken.hidden = true;
            wtoken.hiddenRequested = true;

            //dump();
        }
    }

    public void setAppGroupId(IBinder token, int groupId) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppStartingIcon()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            AppWindowToken wtoken = findAppWindowToken(token);
            if (wtoken == null) {
                Slog.w(TAG, "Attempted to set group id of non-existing app token: " + token);
                return;
            }
            wtoken.groupId = groupId;
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

        int pos = mWindows.size() - 1;
        while (pos >= 0) {
            WindowState wtoken = mWindows.get(pos);
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
            } else {
                return (mLastWindowForcedOrientation=req);
            }
        }
        return (mLastWindowForcedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    public int getOrientationFromAppTokensLocked() {
        int pos = mAppTokens.size() - 1;
        int curGroup = 0;
        int lastOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        boolean findingBehind = false;
        boolean haveGroup = false;
        boolean lastFullscreen = false;
        while (pos >= 0) {
            AppWindowToken wtoken = mAppTokens.get(pos);
            pos--;

            if (DEBUG_APP_ORIENTATION) Slog.v(TAG, "Checking app orientation: " + wtoken);

            // if we're about to tear down this window and not seek for
            // the behind activity, don't use it for orientation
            if (!findingBehind
                    && (!wtoken.hidden && wtoken.hiddenRequested)) {
                if (DEBUG_ORIENTATION) Slog.v(TAG, "Skipping " + wtoken
                        + " -- going to hide");
                continue;
            }

            if (!haveGroup) {
                // We ignore any hidden applications on the top.
                if (wtoken.hiddenRequested || wtoken.willBeHidden) {
                    if (DEBUG_ORIENTATION) Slog.v(TAG, "Skipping " + wtoken
                            + " -- hidden on top");
                    continue;
                }
                haveGroup = true;
                curGroup = wtoken.groupId;
                lastOrientation = wtoken.requestedOrientation;
            } else if (curGroup != wtoken.groupId) {
                // If we have hit a new application group, and the bottom
                // of the previous group didn't explicitly say to use
                // the orientation behind it, and the last app was
                // full screen, then we'll stick with the
                // user's orientation.
                if (lastOrientation != ActivityInfo.SCREEN_ORIENTATION_BEHIND
                        && lastFullscreen) {
                    if (DEBUG_ORIENTATION) Slog.v(TAG, "Done at " + wtoken
                            + " -- end of group, return " + lastOrientation);
                    return lastOrientation;
                }
            }
            int or = wtoken.requestedOrientation;
            // If this application is fullscreen, and didn't explicitly say
            // to use the orientation behind it, then just take whatever
            // orientation it has and ignores whatever is under it.
            lastFullscreen = wtoken.appFullscreen;
            if (lastFullscreen
                    && or != ActivityInfo.SCREEN_ORIENTATION_BEHIND) {
                if (DEBUG_ORIENTATION) Slog.v(TAG, "Done at " + wtoken
                        + " -- full screen, return " + or);
                return or;
            }
            // If this application has requested an explicit orientation,
            // then use it.
            if (or != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    && or != ActivityInfo.SCREEN_ORIENTATION_BEHIND) {
                if (DEBUG_ORIENTATION) Slog.v(TAG, "Done at " + wtoken
                        + " -- explicitly set, return " + or);
                return or;
            }
            findingBehind |= (or == ActivityInfo.SCREEN_ORIENTATION_BEHIND);
        }
        if (DEBUG_ORIENTATION) Slog.v(TAG, "No app is requesting an orientation");
        return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    }

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
                AppWindowToken wtoken = findAppWindowToken(
                        freezeThisOneIfNeeded);
                if (wtoken != null) {
                    startAppFreezingScreenLocked(wtoken,
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
            if (computeNewConfigurationLocked(mTempConfiguration)) {
                if (currentConfig.diff(mTempConfiguration) != 0) {
                    mWaitingForConfig = true;
                    mLayoutNeeded = true;
                    startFreezingDisplayLocked(false);
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
        boolean changed = false;
        long ident = Binder.clearCallingIdentity();
        try {
            int req = computeForcedAppOrientationLocked();

            if (req != mForcedAppOrientation) {
                mForcedAppOrientation = req;
                //send a message to Policy indicating orientation change to take
                //action like disabling/enabling sensors etc.,
                mPolicy.setCurrentOrientationLw(req);
                if (setRotationUncheckedLocked(WindowManagerPolicy.USE_LAST_ROTATION,
                        mLastRotationFlags | Surface.FLAGS_ORIENTATION_ANIMATION_DISABLE,
                        inTransaction)) {
                    changed = true;
                }
            }

            return changed;
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
    
    public void setAppOrientation(IApplicationToken token, int requestedOrientation) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppOrientation()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            AppWindowToken wtoken = findAppWindowToken(token.asBinder());
            if (wtoken == null) {
                Slog.w(TAG, "Attempted to set orientation of non-existing app token: " + token);
                return;
            }

            wtoken.requestedOrientation = requestedOrientation;
        }
    }

    public int getAppOrientation(IApplicationToken token) {
        synchronized(mWindowMap) {
            AppWindowToken wtoken = findAppWindowToken(token.asBinder());
            if (wtoken == null) {
                return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            }

            return wtoken.requestedOrientation;
        }
    }

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
                if (DEBUG_FOCUS) Slog.v(TAG, "Set focused app to: " + mFocusedApp);
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

    public void prepareAppTransition(int transit, boolean alwaysKeepCurrent) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "prepareAppTransition()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            if (DEBUG_APP_TRANSITIONS) Slog.v(
                    TAG, "Prepare app transition: transit=" + transit
                    + " mNextAppTransition=" + mNextAppTransition);
            if (!mDisplayFrozen && mPolicy.isScreenOn()) {
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

    public int getPendingAppTransition() {
        return mNextAppTransition;
    }

    public void overridePendingAppTransition(String packageName,
            int enterAnim, int exitAnim) {
        if (mNextAppTransition != WindowManagerPolicy.TRANSIT_UNSET) {
            mNextAppTransitionPackage = packageName;
            mNextAppTransitionEnter = enterAnim;
            mNextAppTransitionExit = exitAnim;
        }
    }

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

    public void setAppStartingWindow(IBinder token, String pkg,
            int theme, CompatibilityInfo compatInfo,
            CharSequence nonLocalizedLabel, int labelRes, int icon,
            int windowFlags, IBinder transferFrom, boolean createIfNeeded) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "setAppStartingIcon()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized(mWindowMap) {
            if (DEBUG_STARTING_WINDOW) Slog.v(
                    TAG, "setAppStartingIcon: token=" + token + " pkg=" + pkg
                    + " transferFrom=" + transferFrom);

            AppWindowToken wtoken = findAppWindowToken(token);
            if (wtoken == null) {
                Slog.w(TAG, "Attempted to set icon of non-existing app token: " + token);
                return;
            }

            // If the display is frozen, we won't do anything until the
            // actual window is displayed so there is no reason to put in
            // the starting window.
            if (mDisplayFrozen || !mPolicy.isScreenOn()) {
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
                                "Moving existing starting from " + ttoken
                                + " to " + wtoken);
                        final long origId = Binder.clearCallingIdentity();

                        // Transfer the starting window over to the new
                        // token.
                        wtoken.startingData = ttoken.startingData;
                        wtoken.startingView = ttoken.startingView;
                        wtoken.startingWindow = startingWindow;
                        ttoken.startingData = null;
                        ttoken.startingView = null;
                        ttoken.startingWindow = null;
                        ttoken.startingMoved = true;
                        startingWindow.mToken = wtoken;
                        startingWindow.mRootToken = wtoken;
                        startingWindow.mAppToken = wtoken;
                        if (DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(TAG,
                                "Removing starting window: " + startingWindow);
                        mWindows.remove(startingWindow);
                        mWindowsChanged = true;
                        if (DEBUG_ADD_REMOVE) Slog.v(TAG, "Removing starting " + startingWindow
                                + " from " + ttoken);
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
                        if (ttoken.animation != null) {
                            wtoken.animation = ttoken.animation;
                            wtoken.animating = ttoken.animating;
                            wtoken.animLayerAdjustment = ttoken.animLayerAdjustment;
                            ttoken.animation = null;
                            ttoken.animLayerAdjustment = 0;
                            wtoken.updateLayers();
                            ttoken.updateLayers();
                        }

                        updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                                true /*updateInputWindows*/);
                        mLayoutNeeded = true;
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
                }
            }

            // There is no existing starting window, and the caller doesn't
            // want us to create one, so that's it!
            if (!createIfNeeded) {
                return;
            }

            // If this is a translucent or wallpaper window, then don't
            // show a starting window -- the current effect (a full-screen
            // opaque starting window that fades away to the real contents
            // when it is ready) does not work for this.
            if (theme != 0) {
                AttributeCache.Entry ent = AttributeCache.instance().get(pkg, theme,
                        com.android.internal.R.styleable.Window);
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
                    return;
                }
            }

            mStartingIconInTransition = true;
            wtoken.startingData = new StartingData(pkg, theme, compatInfo, nonLocalizedLabel,
                    labelRes, icon, windowFlags);
            Message m = mH.obtainMessage(H.ADD_STARTING, wtoken);
            // Note: we really want to do sendMessageAtFrontOfQueue() because we
            // want to process the message ASAP, before any other queued
            // messages.
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
            final int N = wtoken.allAppWindows.size();
            boolean changed = false;
            if (DEBUG_APP_TRANSITIONS) Slog.v(
                TAG, "Changing app " + wtoken + " hidden=" + wtoken.hidden
                + " performLayout=" + performLayout);

            boolean runningAppAnimation = false;

            if (transit != WindowManagerPolicy.TRANSIT_UNSET) {
                if (wtoken.animation == sDummyAnimation) {
                    wtoken.animation = null;
                }
                applyAnimationLocked(wtoken, lp, transit, visible);
                changed = true;
                if (wtoken.animation != null) {
                    delayed = runningAppAnimation = true;
                }
            }

            for (int i=0; i<N; i++) {
                WindowState win = wtoken.allAppWindows.get(i);
                if (win == wtoken.startingWindow) {
                    continue;
                }

                if (win.isAnimating()) {
                    delayed = true;
                }

                //Slog.i(TAG, "Window " + win + ": vis=" + win.isVisible());
                //win.dump("  ");
                if (visible) {
                    if (!win.isVisibleNow()) {
                        if (!runningAppAnimation) {
                            applyAnimationLocked(win,
                                    WindowManagerPolicy.TRANSIT_ENTER, true);
                        }
                        changed = true;
                    }
                } else if (win.isVisibleNow()) {
                    if (!runningAppAnimation) {
                        applyAnimationLocked(win,
                                WindowManagerPolicy.TRANSIT_EXIT, false);
                    }
                    changed = true;
                }
            }

            wtoken.hidden = wtoken.hiddenRequested = !visible;
            if (!visible) {
                unsetAppFreezingScreenLocked(wtoken, true, true);
            } else {
                // If we are being set visible, and the starting window is
                // not yet displayed, then make sure it doesn't get displayed.
                WindowState swin = wtoken.startingWindow;
                if (swin != null && (swin.mDrawPending
                        || swin.mCommitDrawPending)) {
                    swin.mPolicyVisibility = false;
                    swin.mPolicyVisibilityAfterAnim = false;
                 }
            }

            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "setTokenVisibilityLocked: " + wtoken
                      + ": hidden=" + wtoken.hidden + " hiddenRequested="
                      + wtoken.hiddenRequested);

            if (changed) {
                mLayoutNeeded = true;
                mInputMonitor.setUpdateInputWindowsNeededLw();
                if (performLayout) {
                    updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                            false /*updateInputWindows*/);
                    performLayoutAndPlaceSurfacesLocked();
                }
                mInputMonitor.updateInputWindowsLw(false /*force*/);
            }
        }

        if (wtoken.animation != null) {
            delayed = true;
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
                Slog.v(TAG, "setAppVisibility(" + token + ", " + visible
                        + "): mNextAppTransition=" + mNextAppTransition
                        + " hidden=" + wtoken.hidden
                        + " hiddenRequested=" + wtoken.hiddenRequested, e);
            }

            // If we are preparing an app transition, then delay changing
            // the visibility of this token until we execute that transition.
            if (!mDisplayFrozen && mPolicy.isScreenOn()
                    && mNextAppTransition != WindowManagerPolicy.TRANSIT_UNSET) {
                // Already in requested state, don't do anything more.
                if (wtoken.hiddenRequested != visible) {
                    return;
                }
                wtoken.hiddenRequested = !visible;

                if (DEBUG_APP_TRANSITIONS) Slog.v(
                        TAG, "Setting dummy animation on: " + wtoken);
                wtoken.setDummyAnimation();
                mOpeningApps.remove(wtoken);
                mClosingApps.remove(wtoken);
                wtoken.waitingToShow = wtoken.waitingToHide = false;
                wtoken.inPendingTransaction = true;
                if (visible) {
                    mOpeningApps.add(wtoken);
                    wtoken.startingDisplayed = false;
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
        if (wtoken.freezingScreen) {
            if (DEBUG_ORIENTATION) Slog.v(TAG, "Clear freezing of " + wtoken
                    + " force=" + force);
            final int N = wtoken.allAppWindows.size();
            boolean unfrozeWindows = false;
            for (int i=0; i<N; i++) {
                WindowState w = wtoken.allAppWindows.get(i);
                if (w.mAppFreezing) {
                    w.mAppFreezing = false;
                    if (w.mSurface != null && !w.mOrientationChanging) {
                        w.mOrientationChanging = true;
                    }
                    unfrozeWindows = true;
                }
            }
            if (force || unfrozeWindows) {
                if (DEBUG_ORIENTATION) Slog.v(TAG, "No longer freezing: " + wtoken);
                wtoken.freezingScreen = false;
                mAppsFreezingScreen--;
            }
            if (unfreezeSurfaceNow) {
                if (unfrozeWindows) {
                    mLayoutNeeded = true;
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
                    + wtoken.freezingScreen, e);
        }
        if (!wtoken.hiddenRequested) {
            if (!wtoken.freezingScreen) {
                wtoken.freezingScreen = true;
                mAppsFreezingScreen++;
                if (mAppsFreezingScreen == 1) {
                    startFreezingDisplayLocked(false);
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
            if (configChanges == 0 && !mDisplayFrozen && mPolicy.isScreenOn()) {
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
                    + ": hidden=" + wtoken.hidden + " freezing=" + wtoken.freezingScreen);
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
                        + " animation=" + wtoken.animation
                        + " animating=" + wtoken.animating);
                if (delayed) {
                    // set the token aside because it has an active animation to be finished
                    if (DEBUG_ADD_REMOVE || DEBUG_TOKEN_MOVEMENT) Slog.v(TAG,
                            "removeAppToken make exiting: " + wtoken);
                    mExitingAppTokens.add(wtoken);
                } else {
                    // Make sure there is no animation running on this token,
                    // so any windows associated with it will be removed as
                    // soon as their animations are complete
                    wtoken.animation = null;
                    wtoken.animating = false;
                }
                if (DEBUG_ADD_REMOVE || DEBUG_TOKEN_MOVEMENT) Slog.v(TAG,
                        "removeAppToken: " + wtoken);
                mAppTokens.remove(wtoken);
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
        for (int i=0; i<NW; i++) {
            WindowState win = token.windows.get(i);
            if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG, "Tmp removing app window " + win);
            mWindows.remove(win);
            mWindowsChanged = true;
            int j = win.mChildWindows.size();
            while (j > 0) {
                j--;
                WindowState cwin = win.mChildWindows.get(j);
                if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG,
                        "Tmp removing child window " + cwin);
                mWindows.remove(cwin);
            }
        }
        return NW > 0;
    }

    void dumpAppTokensLocked() {
        for (int i=mAppTokens.size()-1; i>=0; i--) {
            Slog.v(TAG, "  #" + i + ": " + mAppTokens.get(i).token);
        }
    }

    void dumpWindowsLocked() {
        for (int i=mWindows.size()-1; i>=0; i--) {
            Slog.v(TAG, "  #" + i + ": " + mWindows.get(i));
        }
    }

    private int findWindowOffsetLocked(int tokenPos) {
        final int NW = mWindows.size();

        if (tokenPos >= mAppTokens.size()) {
            int i = NW;
            while (i > 0) {
                i--;
                WindowState win = mWindows.get(i);
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
                            if (mWindows.get(pos) == cwin) {
                                if (DEBUG_REORDER) Slog.v(TAG,
                                        "Found child win @" + (pos+1));
                                return pos+1;
                            }
                        }
                    }
                }
                for (int pos=NW-1; pos>=0; pos--) {
                    if (mWindows.get(pos) == win) {
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
        final int NCW = win.mChildWindows.size();
        boolean added = false;
        for (int j=0; j<NCW; j++) {
            WindowState cwin = win.mChildWindows.get(j);
            if (!added && cwin.mSubLayer >= 0) {
                if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG, "Re-adding child window at "
                        + index + ": " + cwin);
                win.mRebuilding = false;
                mWindows.add(index, win);
                index++;
                added = true;
            }
            if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG, "Re-adding window at "
                    + index + ": " + cwin);
            cwin.mRebuilding = false;
            mWindows.add(index, cwin);
            index++;
        }
        if (!added) {
            if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG, "Re-adding window at "
                    + index + ": " + win);
            win.mRebuilding = false;
            mWindows.add(index, win);
            index++;
        }
        mWindowsChanged = true;
        return index;
    }

    private final int reAddAppWindowsLocked(int index, WindowToken token) {
        final int NW = token.windows.size();
        for (int i=0; i<NW; i++) {
            index = reAddWindowLocked(index, token.windows.get(i));
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
            if (DEBUG_TOKEN_MOVEMENT || DEBUG_REORDER) Slog.v(TAG,
                    "Start moving token " + wtoken + " initially at "
                    + mAppTokens.indexOf(wtoken));
            if (wtoken == null || !mAppTokens.remove(wtoken)) {
                Slog.w(TAG, "Attempting to reorder token that doesn't exist: "
                      + token + " (" + wtoken + ")");
                return;
            }
            mAppTokens.add(index, wtoken);
            if (DEBUG_REORDER) Slog.v(TAG, "Moved " + token + " to " + index + ":");
            else if (DEBUG_TOKEN_MOVEMENT) Slog.v(TAG, "Moved " + token + " to " + index);
            if (DEBUG_REORDER) dumpAppTokensLocked();

            final long origId = Binder.clearCallingIdentity();
            if (DEBUG_REORDER) Slog.v(TAG, "Removing windows in " + token + ":");
            if (DEBUG_REORDER) dumpWindowsLocked();
            if (tmpRemoveAppWindowsLocked(wtoken)) {
                if (DEBUG_REORDER) Slog.v(TAG, "Adding windows back in:");
                if (DEBUG_REORDER) dumpWindowsLocked();
                reAddAppWindowsLocked(findWindowOffsetLocked(index), wtoken);
                if (DEBUG_REORDER) Slog.v(TAG, "Final window list:");
                if (DEBUG_REORDER) dumpWindowsLocked();
                updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                        false /*updateInputWindows*/);
                mLayoutNeeded = true;
                mInputMonitor.setUpdateInputWindowsNeededLw();
                performLayoutAndPlaceSurfacesLocked();
                mInputMonitor.updateInputWindowsLw(false /*force*/);
            }
            Binder.restoreCallingIdentity(origId);
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

        // Where to start adding?
        int pos = findWindowOffsetLocked(tokenPos);

        // And now add them back at the correct place.
        pos = reAddAppWindowsLocked(pos, wtoken);

        if (updateFocusAndLayout) {
            mInputMonitor.setUpdateInputWindowsNeededLw();
            if (!updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                    false /*updateInputWindows*/)) {
                assignLayersLocked();
            }
            mLayoutNeeded = true;
            performLayoutAndPlaceSurfacesLocked();
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

        // Where to start adding?
        int pos = findWindowOffsetLocked(tokenPos);

        // And now add them back at the correct place.
        for (i=0; i<N; i++) {
            WindowToken token = mTokenMap.get(tokens.get(i));
            if (token != null) {
                pos = reAddAppWindowsLocked(pos, token);
            }
        }

        mInputMonitor.setUpdateInputWindowsNeededLw();
        if (!updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                false /*updateInputWindows*/)) {
            assignLayersLocked();
        }
        mLayoutNeeded = true;
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
                        mToTopApps.remove(wt);
                        mToBottomApps.remove(wt);
                        mToTopApps.add(wt);
                        wt.sendingToBottom = false;
                        wt.sendingToTop = true;
                    }
                }
            }

            if (mNextAppTransition == WindowManagerPolicy.TRANSIT_UNSET) {
                moveAppWindowsLocked(tokens, mAppTokens.size());
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    public void moveAppTokensToBottom(List<IBinder> tokens) {
        if (!checkCallingPermission(android.Manifest.permission.MANAGE_APP_TOKENS,
                "moveAppTokensToBottom()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        final long origId = Binder.clearCallingIdentity();
        synchronized(mWindowMap) {
            removeAppTokensLocked(tokens);
            final int N = tokens.size();
            int pos = 0;
            for (int i=0; i<N; i++) {
                AppWindowToken wt = findAppWindowToken(tokens.get(i));
                if (wt != null) {
                    if (DEBUG_TOKEN_MOVEMENT) Slog.v(TAG,
                            "Adding next to bottom: " + wt + " at " + pos);
                    mAppTokens.add(pos, wt);
                    if (mNextAppTransition != WindowManagerPolicy.TRANSIT_UNSET) {
                        mToTopApps.remove(wt);
                        mToBottomApps.remove(wt);
                        mToBottomApps.add(i, wt);
                        wt.sendingToTop = false;
                        wt.sendingToBottom = true;
                    }
                    pos++;
                }
            }

            if (mNextAppTransition == WindowManagerPolicy.TRANSIT_UNSET) {
                moveAppWindowsLocked(tokens, 0);
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    // -------------------------------------------------------------
    // Misc IWindowSession methods
    // -------------------------------------------------------------

    private boolean shouldAllowDisableKeyguard()
    {
        // We fail safe and prevent disabling keyguard in the unlikely event this gets 
        // called before DevicePolicyManagerService has started.
        if (mAllowDisableKeyguard == ALLOW_DISABLE_UNKNOWN) {
            DevicePolicyManager dpm = (DevicePolicyManager) mContext.getSystemService(
                    Context.DEVICE_POLICY_SERVICE);
            if (dpm != null) {
                mAllowDisableKeyguard = dpm.getPasswordQuality(null)
                        == DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED ?
                                ALLOW_DISABLE_YES : ALLOW_DISABLE_NO;
            }
        }
        return mAllowDisableKeyguard == ALLOW_DISABLE_YES;
    }

    public void disableKeyguard(IBinder token, String tag) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DISABLE_KEYGUARD)
            != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }

        synchronized (mKeyguardTokenWatcher) {
            mKeyguardTokenWatcher.acquire(token, tag);
        }
    }

    public void reenableKeyguard(IBinder token) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DISABLE_KEYGUARD)
            != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires DISABLE_KEYGUARD permission");
        }

        synchronized (mKeyguardTokenWatcher) {
            mKeyguardTokenWatcher.release(token);

            if (!mKeyguardTokenWatcher.isAcquired()) {
                // If we are the last one to reenable the keyguard wait until
                // we have actually finished reenabling until returning.
                // It is possible that reenableKeyguard() can be called before
                // the previous disableKeyguard() is handled, in which case
                // neither mKeyguardTokenWatcher.acquired() or released() would
                // be called. In that case mKeyguardDisabled will be false here
                // and we have nothing to wait for.
                while (mKeyguardDisabled) {
                    try {
                        mKeyguardTokenWatcher.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
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

    public void closeSystemDialogs(String reason) {
        synchronized(mWindowMap) {
            for (int i=mWindows.size()-1; i>=0; i--) {
                WindowState w = mWindows.get(i);
                if (w.mSurface != null) {
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
        }

        // Persist setting
        mH.obtainMessage(H.PERSIST_ANIMATION_SCALE).sendToTarget();
    }

    public float getAnimationScale(int which) {
        switch (which) {
            case 0: return mWindowAnimationScale;
            case 1: return mTransitionAnimationScale;
        }
        return 0;
    }

    public float[] getAnimationScales() {
        return new float[] { mWindowAnimationScale, mTransitionAnimationScale };
    }

    public int getSwitchState(int sw) {
        if (!checkCallingPermission(android.Manifest.permission.READ_INPUT_STATE,
                "getSwitchState()")) {
            throw new SecurityException("Requires READ_INPUT_STATE permission");
        }
        return mInputManager.getSwitchState(-1, InputDevice.SOURCE_ANY, sw);
    }

    public int getSwitchStateForDevice(int devid, int sw) {
        if (!checkCallingPermission(android.Manifest.permission.READ_INPUT_STATE,
                "getSwitchStateForDevice()")) {
            throw new SecurityException("Requires READ_INPUT_STATE permission");
        }
        return mInputManager.getSwitchState(devid, InputDevice.SOURCE_ANY, sw);
    }

    public int getScancodeState(int sw) {
        if (!checkCallingPermission(android.Manifest.permission.READ_INPUT_STATE,
                "getScancodeState()")) {
            throw new SecurityException("Requires READ_INPUT_STATE permission");
        }
        return mInputManager.getScanCodeState(-1, InputDevice.SOURCE_ANY, sw);
    }

    public int getScancodeStateForDevice(int devid, int sw) {
        if (!checkCallingPermission(android.Manifest.permission.READ_INPUT_STATE,
                "getScancodeStateForDevice()")) {
            throw new SecurityException("Requires READ_INPUT_STATE permission");
        }
        return mInputManager.getScanCodeState(devid, InputDevice.SOURCE_ANY, sw);
    }

    public int getTrackballScancodeState(int sw) {
        if (!checkCallingPermission(android.Manifest.permission.READ_INPUT_STATE,
                "getTrackballScancodeState()")) {
            throw new SecurityException("Requires READ_INPUT_STATE permission");
        }
        return mInputManager.getScanCodeState(-1, InputDevice.SOURCE_TRACKBALL, sw);
    }

    public int getDPadScancodeState(int sw) {
        if (!checkCallingPermission(android.Manifest.permission.READ_INPUT_STATE,
                "getDPadScancodeState()")) {
            throw new SecurityException("Requires READ_INPUT_STATE permission");
        }
        return mInputManager.getScanCodeState(-1, InputDevice.SOURCE_DPAD, sw);
    }

    public int getKeycodeState(int sw) {
        if (!checkCallingPermission(android.Manifest.permission.READ_INPUT_STATE,
                "getKeycodeState()")) {
            throw new SecurityException("Requires READ_INPUT_STATE permission");
        }
        return mInputManager.getKeyCodeState(-1, InputDevice.SOURCE_ANY, sw);
    }

    public int getKeycodeStateForDevice(int devid, int sw) {
        if (!checkCallingPermission(android.Manifest.permission.READ_INPUT_STATE,
                "getKeycodeStateForDevice()")) {
            throw new SecurityException("Requires READ_INPUT_STATE permission");
        }
        return mInputManager.getKeyCodeState(devid, InputDevice.SOURCE_ANY, sw);
    }

    public int getTrackballKeycodeState(int sw) {
        if (!checkCallingPermission(android.Manifest.permission.READ_INPUT_STATE,
                "getTrackballKeycodeState()")) {
            throw new SecurityException("Requires READ_INPUT_STATE permission");
        }
        return mInputManager.getKeyCodeState(-1, InputDevice.SOURCE_TRACKBALL, sw);
    }

    public int getDPadKeycodeState(int sw) {
        if (!checkCallingPermission(android.Manifest.permission.READ_INPUT_STATE,
                "getDPadKeycodeState()")) {
            throw new SecurityException("Requires READ_INPUT_STATE permission");
        }
        return mInputManager.getKeyCodeState(-1, InputDevice.SOURCE_DPAD, sw);
    }
    
    public boolean hasKeys(int[] keycodes, boolean[] keyExists) {
        return mInputManager.hasKeys(-1, InputDevice.SOURCE_ANY, keycodes, keyExists);
    }

    public InputChannel monitorInput(String inputChannelName) {
        if (!checkCallingPermission(android.Manifest.permission.READ_INPUT_STATE,
                "monitorInput()")) {
            throw new SecurityException("Requires READ_INPUT_STATE permission");
        }
        return mInputManager.monitorInput(inputChannelName);
    }

    public void setInputFilter(InputFilter filter) {
        mInputManager.setInputFilter(filter);
    }

    public InputDevice getInputDevice(int deviceId) {
        return mInputManager.getInputDevice(deviceId);
    }

    public int[] getInputDeviceIds() {
        return mInputManager.getInputDeviceIds();
    }

    public void enableScreenAfterBoot() {
        synchronized(mWindowMap) {
            if (mSystemBooted) {
                return;
            }
            mSystemBooted = true;
        }

        performEnableScreen();
    }

    public void enableScreenIfNeededLocked() {
        if (mDisplayEnabled) {
            return;
        }
        if (!mSystemBooted) {
            return;
        }
        mH.sendMessage(mH.obtainMessage(H.ENABLE_SCREEN));
    }

    public void performEnableScreen() {
        synchronized(mWindowMap) {
            if (mDisplayEnabled) {
                return;
            }
            if (!mSystemBooted) {
                return;
            }

            // Don't enable the screen until all existing windows
            // have been drawn.
            final int N = mWindows.size();
            for (int i=0; i<N; i++) {
                WindowState w = mWindows.get(i);
                if (w.isVisibleLw() && !w.mObscured && !w.isDrawnLw()) {
                    return;
                }
            }

            mDisplayEnabled = true;
            if (false) {
                Slog.i(TAG, "ENABLING SCREEN!");
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
                    surfaceFlinger.transact(IBinder.FIRST_CALL_TRANSACTION,
                                            data, null, 0);
                    data.recycle();
                }
            } catch (RemoteException ex) {
                Slog.e(TAG, "Boot completed: SurfaceFlinger is dead!");
            }
        }

        mPolicy.enableScreenAfterBoot();

        // Make sure the last requested orientation has been applied.
        setRotationUnchecked(WindowManagerPolicy.USE_LAST_ROTATION, false,
                mLastRotationFlags | Surface.FLAGS_ORIENTATION_ANIMATION_DISABLE);
    }

    public void setInTouchMode(boolean mode) {
        synchronized(mWindowMap) {
            mInTouchMode = mode;
        }
    }

    // TODO: more accounting of which pid(s) turned it on, keep count,
    // only allow disables from pids which have count on, etc.
    public void showStrictModeViolation(boolean on) {
        int pid = Binder.getCallingPid();
        synchronized(mWindowMap) {
            // Ignoring requests to enable the red border from clients
            // which aren't on screen.  (e.g. Broadcast Receivers in
            // the background..)
            if (on) {
                boolean isVisible = false;
                for (WindowState ws : mWindows) {
                    if (ws.mSession.mPid == pid && ws.isVisibleLw()) {
                        isVisible = true;
                        break;
                    }
                }
                if (!isVisible) {
                    return;
                }
            }

            if (SHOW_TRANSACTIONS) Slog.i(TAG, ">>> OPEN TRANSACTION showStrictModeViolation");
            Surface.openTransaction();
            try {
                if (mStrictModeFlash == null) {
                    mStrictModeFlash = new StrictModeFlash(mDisplay, mFxSession);
                }
                mStrictModeFlash.setVisibility(on);
            } finally {
                Surface.closeTransaction();
                if (SHOW_TRANSACTIONS) Slog.i(TAG, "<<< CLOSE TRANSACTION showStrictModeViolation");
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
     * @param width the width of the target bitmap
     * @param height the height of the target bitmap
     */
    public Bitmap screenshotApplications(IBinder appToken, int width, int height) {
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

            dw = mAppDisplayWidth;
            dh = mAppDisplayHeight;

            int aboveAppLayer = mPolicy.windowTypeToLayerLw(
                    WindowManager.LayoutParams.TYPE_APPLICATION) * TYPE_LAYER_MULTIPLIER
                    + TYPE_LAYER_OFFSET;
            aboveAppLayer += TYPE_LAYER_MULTIPLIER;

            boolean isImeTarget = mInputMethodTarget != null
                    && mInputMethodTarget.mAppToken != null
                    && mInputMethodTarget.mAppToken.appToken != null
                    && mInputMethodTarget.mAppToken.appToken.asBinder() == appToken;

            // Figure out the part of the screen that is actually the app.
            boolean including = false;
            for (int i=mWindows.size()-1; i>=0; i--) {
                WindowState ws = mWindows.get(i);
                if (ws.mSurface == null) {
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

                if (maxLayer < ws.mAnimLayer) {
                    maxLayer = ws.mAnimLayer;
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
            rot = mDisplay.getRotation();
            int fw = frame.width();
            int fh = frame.height();

            // Constrain thumbnail to smaller of screen width or height. Assumes aspect
            // of thumbnail is the same as the screen (in landscape) or square.
            if (dw <= dh) {
                scale = width / (float) fw; // portrait
            } else {
                scale = height / (float) fh; // landscape
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
            rawss = Surface.screenshot(dw, dh, 0, maxLayer);
        }

        if (rawss == null) {
            Log.w(TAG, "Failure taking screenshot for (" + dw + "x" + dh
                    + ") to layer " + maxLayer);
            return null;
        }

        Bitmap bm = Bitmap.createBitmap(width, height, rawss.getConfig());
        Matrix matrix = new Matrix();
        ScreenRotationAnimation.createRotationMatrix(rot, dw, dh, matrix);
        matrix.postTranslate(-(int)(frame.left*scale), -(int)(frame.top*scale));
        Canvas canvas = new Canvas(bm);
        canvas.drawBitmap(rawss, matrix, null);
        canvas.setBitmap(null);

        rawss.recycle();
        return bm;
    }

    public void freezeRotation() {
        if (!checkCallingPermission(android.Manifest.permission.SET_ORIENTATION,
                "freezeRotation()")) {
            throw new SecurityException("Requires SET_ORIENTATION permission");
        }

        if (DEBUG_ORIENTATION) Slog.v(TAG, "freezeRotation: mRotation=" + mRotation);

        mPolicy.setUserRotationMode(WindowManagerPolicy.USER_ROTATION_LOCKED, mRotation);
        setRotationUnchecked(WindowManagerPolicy.USE_LAST_ROTATION, false, 0);
    }

    public void thawRotation() {
        if (!checkCallingPermission(android.Manifest.permission.SET_ORIENTATION,
                "thawRotation()")) {
            throw new SecurityException("Requires SET_ORIENTATION permission");
        }

        if (DEBUG_ORIENTATION) Slog.v(TAG, "thawRotation: mRotation=" + mRotation);

        mPolicy.setUserRotationMode(WindowManagerPolicy.USER_ROTATION_FREE, 777); // rot not used
        setRotationUnchecked(WindowManagerPolicy.USE_LAST_ROTATION, false, 0);
    }

    public void setRotation(int rotation,
            boolean alwaysSendConfiguration, int animFlags) {
        if (!checkCallingPermission(android.Manifest.permission.SET_ORIENTATION,
                "setRotation()")) {
            throw new SecurityException("Requires SET_ORIENTATION permission");
        }

        setRotationUnchecked(rotation, alwaysSendConfiguration, animFlags);
    }

    public void setRotationUnchecked(int rotation,
            boolean alwaysSendConfiguration, int animFlags) {
        if(DEBUG_ORIENTATION) Slog.v(TAG,
                   "setRotationUnchecked(rotation=" + rotation +
                   " alwaysSendConfiguration=" + alwaysSendConfiguration +
                   " animFlags=" + animFlags);

        long origId = Binder.clearCallingIdentity();
        boolean changed;
        synchronized(mWindowMap) {
            changed = setRotationUncheckedLocked(rotation, animFlags, false);
        }

        if (changed || alwaysSendConfiguration) {
            sendNewConfiguration();
        }

        Binder.restoreCallingIdentity(origId);
    }

    /**
     * Apply a new rotation to the screen, respecting the requests of
     * applications.  Use WindowManagerPolicy.USE_LAST_ROTATION to simply
     * re-evaluate the desired rotation.
     * 
     * Returns null if the rotation has been changed.  In this case YOU
     * MUST CALL setNewConfiguration() TO UNFREEZE THE SCREEN.
     */
    public boolean setRotationUncheckedLocked(int rotation, int animFlags, boolean inTransaction) {
        if (mDragState != null
                || (mScreenRotationAnimation != null && mScreenRotationAnimation.isAnimating())) {
            // Potential rotation during a drag or while waiting for a previous orientation
            // change to finish (rotation animation will be dismissed).
            // Don't do the rotation now, but make a note to perform the rotation later.
            if (DEBUG_ORIENTATION) Slog.v(TAG, "Deferring rotation.");
            if (rotation != WindowManagerPolicy.USE_LAST_ROTATION) {
                mDeferredRotation = rotation;
                mDeferredRotationAnimFlags = animFlags;
            }
            return false;
        }

        boolean changed;
        if (rotation == WindowManagerPolicy.USE_LAST_ROTATION) {
            if (mDeferredRotation != WindowManagerPolicy.USE_LAST_ROTATION) {
                rotation = mDeferredRotation;
                mRequestedRotation = rotation;
                mLastRotationFlags = mDeferredRotationAnimFlags;
            }
            rotation = mRequestedRotation;
        } else {
            mRequestedRotation = rotation;
            mLastRotationFlags = animFlags;
        }
        mDeferredRotation = WindowManagerPolicy.USE_LAST_ROTATION;
        if (DEBUG_ORIENTATION) Slog.v(TAG, "Overwriting rotation value from " + rotation);
        rotation = mPolicy.rotationForOrientationLw(mForcedAppOrientation,
                mRotation, mDisplayEnabled);
        if (DEBUG_ORIENTATION) Slog.v(TAG, "new rotation is set to " + rotation);

        int desiredRotation = rotation;
        int lockedRotation = mPolicy.getLockedRotationLw();
        if (lockedRotation >= 0 && rotation != lockedRotation) {
            // We are locked in a rotation but something is requesting
            // a different rotation...  we will either keep the locked
            // rotation if it results in the same orientation, or have to
            // switch into an emulated orientation mode.

            // First, we know that our rotation is actually going to be
            // the locked rotation.
            rotation = lockedRotation;

            // Now the difference between the desired and lockedRotation
            // may mean that the orientation is different...  if that is
            // not the case, we can just make the desired rotation be the
            // same as the new locked rotation.
            switch (lockedRotation) {
                case Surface.ROTATION_0:
                    if (rotation == Surface.ROTATION_180) {
                        desiredRotation = lockedRotation;
                    }
                    break;
                case Surface.ROTATION_90:
                    if (rotation == Surface.ROTATION_270) {
                        desiredRotation = lockedRotation;
                    }
                    break;
                case Surface.ROTATION_180:
                    if (rotation == Surface.ROTATION_0) {
                        desiredRotation = lockedRotation;
                    }
                    break;
                case Surface.ROTATION_270:
                    if (rotation == Surface.ROTATION_90) {
                        desiredRotation = lockedRotation;
                    }
                    break;
            }
        }

        changed = mDisplayEnabled && mRotation != rotation;
        if (mAltOrientation != (rotation != desiredRotation)) {
            changed = true;
            mAltOrientation = rotation != desiredRotation;
        }

        if (changed) {
            if (DEBUG_ORIENTATION) Slog.v(TAG,
                    "Rotation changed to " + rotation
                    + " from " + mRotation
                    + " (forceApp=" + mForcedAppOrientation
                    + ", req=" + mRequestedRotation + ")");
            mRotation = rotation;
            mWindowsFreezingScreen = true;
            mH.removeMessages(H.WINDOW_FREEZE_TIMEOUT);
            mH.sendMessageDelayed(mH.obtainMessage(H.WINDOW_FREEZE_TIMEOUT),
                    2000);
            mWaitingForConfig = true;
            mLayoutNeeded = true;
            startFreezingDisplayLocked(inTransaction);
            //Slog.i(TAG, "Setting rotation to " + rotation + ", animFlags=" + animFlags);
            mInputManager.setDisplayOrientation(0, rotation);
            if (mDisplayEnabled) {
                // NOTE: We disable the rotation in the emulator because
                //       it doesn't support hardware OpenGL emulation yet.
                if (CUSTOM_SCREEN_ROTATION && mScreenRotationAnimation != null
                        && mScreenRotationAnimation.hasScreenshot()) {
                    Surface.freezeDisplay(0);
                    if (!inTransaction) {
                        if (SHOW_TRANSACTIONS) Slog.i(TAG,
                                ">>> OPEN TRANSACTION setRotationUnchecked");
                        Surface.openTransaction();
                    }
                    try {
                        if (mScreenRotationAnimation != null) {
                            mScreenRotationAnimation.setRotation(rotation);
                        }
                    } finally {
                        if (!inTransaction) {
                            Surface.closeTransaction();
                            if (SHOW_TRANSACTIONS) Slog.i(TAG,
                                    "<<< CLOSE TRANSACTION setRotationUnchecked");
                        }
                    }
                    Surface.setOrientation(0, rotation, animFlags);
                    Surface.unfreezeDisplay(0);
                } else {
                    Surface.setOrientation(0, rotation, animFlags);
                }
                rebuildBlackFrame(inTransaction);
            }

            for (int i=mWindows.size()-1; i>=0; i--) {
                WindowState w = mWindows.get(i);
                if (w.mSurface != null) {
                    w.mOrientationChanging = true;
                }
            }
            for (int i=mRotationWatchers.size()-1; i>=0; i--) {
                try {
                    mRotationWatchers.get(i).onRotationChanged(rotation);
                } catch (RemoteException e) {
                }
            }
        } //end if changed

        return changed;
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

            if (mInitialDisplayWidth < mInitialDisplayHeight) {
                // On devices with a natural orientation of portrait
                switch (rotation) {
                    default:
                    case Surface.ROTATION_0:
                        return Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
                    case Surface.ROTATION_90:
                        return Gravity.RIGHT | Gravity.CENTER_VERTICAL;
                    case Surface.ROTATION_180:
                        return Gravity.CENTER_HORIZONTAL | Gravity.TOP;
                    case Surface.ROTATION_270:
                        return Gravity.LEFT | Gravity.CENTER_VERTICAL;
                }
            } else {
                // On devices with a natural orientation of landscape
                switch (rotation) {
                    default:
                    case Surface.ROTATION_0:
                        return Gravity.RIGHT | Gravity.CENTER_VERTICAL;
                    case Surface.ROTATION_90:
                        return Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
                    case Surface.ROTATION_180:
                        return Gravity.LEFT | Gravity.CENTER_VERTICAL;
                    case Surface.ROTATION_270:
                        return Gravity.CENTER_HORIZONTAL | Gravity.TOP;
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

        WindowState[] windows;
        synchronized (mWindowMap) {
            //noinspection unchecked
            windows = mWindows.toArray(new WindowState[mWindows.size()]);
        }

        BufferedWriter out = null;

        // Any uncaught exception will crash the system process
        try {
            OutputStream clientStream = client.getOutputStream();
            out = new BufferedWriter(new OutputStreamWriter(clientStream), 8 * 1024);

            final int count = windows.length;
            for (int i = 0; i < count; i++) {
                final WindowState w = windows[i];
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
            return getFocusedWindow();
        }

        synchronized (mWindowMap) {
            final ArrayList<WindowState> windows = mWindows;
            final int count = windows.size();

            for (int i = 0; i < count; i++) {
                WindowState w = windows.get(i);
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
        if (!computeNewConfigurationLocked(config)) {
            return null;
        }
        return config;
    }

    private int reduceConfigWidthSize(int curSize, int rotation, float density, int dw) {
        int size = (int)(mPolicy.getConfigDisplayWidth(rotation, dw) / density);
        if (size < curSize) {
            curSize = size;
        }
        return curSize;
    }

    private int computeSmallestWidth(boolean rotated, int dw, int dh, float density) {
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
        int sw = reduceConfigWidthSize(unrotDw, Surface.ROTATION_0, density, unrotDw);
        sw = reduceConfigWidthSize(sw, Surface.ROTATION_90, density, unrotDh);
        sw = reduceConfigWidthSize(sw, Surface.ROTATION_180, density, unrotDw);
        sw = reduceConfigWidthSize(sw, Surface.ROTATION_270, density, unrotDh);
        return sw;
    }

    private int reduceCompatConfigWidthSize(int curSize, int rotation, DisplayMetrics dm,
            int dw, int dh) {
        dm.noncompatWidthPixels = mPolicy.getNonDecorDisplayWidth(rotation, dw);
        dm.noncompatHeightPixels = mPolicy.getNonDecorDisplayHeight(rotation, dh);
        float scale = CompatibilityInfo.computeCompatibleScaling(dm, null);
        int size = (int)(((dm.noncompatWidthPixels / scale) / dm.density) + .5f);
        if (curSize == 0 || size < curSize) {
            curSize = size;
        }
        return curSize;
    }

    private int computeCompatSmallestWidth(boolean rotated, DisplayMetrics dm, int dw, int dh) {
        mTmpDisplayMetrics.setTo(dm);
        dm = mTmpDisplayMetrics;
        int unrotDw, unrotDh;
        if (rotated) {
            unrotDw = dh;
            unrotDh = dw;
        } else {
            unrotDw = dw;
            unrotDh = dh;
        }
        int sw = reduceCompatConfigWidthSize(0, Surface.ROTATION_0, dm, unrotDw, unrotDh);
        sw = reduceCompatConfigWidthSize(sw, Surface.ROTATION_90, dm, unrotDh, unrotDw);
        sw = reduceCompatConfigWidthSize(sw, Surface.ROTATION_180, dm, unrotDw, unrotDh);
        sw = reduceCompatConfigWidthSize(sw, Surface.ROTATION_270, dm, unrotDh, unrotDw);
        return sw;
    }

    boolean computeNewConfigurationLocked(Configuration config) {
        if (mDisplay == null) {
            return false;
        }
        
        mInputManager.getInputConfiguration(config);

        // Use the effective "visual" dimensions based on current rotation
        final boolean rotated = (mRotation == Surface.ROTATION_90
                || mRotation == Surface.ROTATION_270);
        final int realdw = rotated ? mBaseDisplayHeight : mBaseDisplayWidth;
        final int realdh = rotated ? mBaseDisplayWidth : mBaseDisplayHeight;

        if (mAltOrientation) {
            mCurDisplayWidth = realdw;
            mCurDisplayHeight = realdh;
            if (realdw > realdh) {
                // Turn landscape into portrait.
                int maxw = (int)(realdh/1.3f);
                if (maxw < realdw) {
                    mCurDisplayWidth = maxw;
                }
            } else {
                // Turn portrait into landscape.
                int maxh = (int)(realdw/1.3f);
                if (maxh < realdh) {
                    mCurDisplayHeight = maxh;
                }
            }
        } else {
            mCurDisplayWidth = realdw;
            mCurDisplayHeight = realdh;
        }

        final int dw = mCurDisplayWidth;
        final int dh = mCurDisplayHeight;

        int orientation = Configuration.ORIENTATION_SQUARE;
        if (dw < dh) {
            orientation = Configuration.ORIENTATION_PORTRAIT;
        } else if (dw > dh) {
            orientation = Configuration.ORIENTATION_LANDSCAPE;
        }
        config.orientation = orientation;

        // Update real display metrics.
        mDisplay.getMetricsWithSize(mRealDisplayMetrics, mCurDisplayWidth, mCurDisplayHeight);

        // Update application display metrics.
        final DisplayMetrics dm = mDisplayMetrics;
        mAppDisplayWidth = mPolicy.getNonDecorDisplayWidth(mRotation, dw);
        mAppDisplayHeight = mPolicy.getNonDecorDisplayHeight(mRotation, dh);
        mDisplay.getMetricsWithSize(dm, mAppDisplayWidth, mAppDisplayHeight);

        mCompatibleScreenScale = CompatibilityInfo.computeCompatibleScaling(dm,
                mCompatDisplayMetrics);

        config.screenWidthDp = (int)(mPolicy.getConfigDisplayWidth(mRotation, dw) / dm.density);
        config.screenHeightDp = (int)(mPolicy.getConfigDisplayHeight(mRotation, dh) / dm.density);
        config.smallestScreenWidthDp = computeSmallestWidth(rotated, dw, dh, dm.density);

        config.compatScreenWidthDp = (int)(config.screenWidthDp / mCompatibleScreenScale);
        config.compatScreenHeightDp = (int)(config.screenHeightDp / mCompatibleScreenScale);
        config.compatSmallestScreenWidthDp = computeCompatSmallestWidth(rotated, dm, dw, dh);

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
        config.smallestScreenWidthDp = reduceConfigWidthSize(unrotDw,
                Surface.ROTATION_0, dm.density, unrotDw);
        config.smallestScreenWidthDp = reduceConfigWidthSize(config.smallestScreenWidthDp,
                Surface.ROTATION_90, dm.density, unrotDh);
        config.smallestScreenWidthDp = reduceConfigWidthSize(config.smallestScreenWidthDp,
                Surface.ROTATION_180, dm.density, unrotDw);
        config.smallestScreenWidthDp = reduceConfigWidthSize(config.smallestScreenWidthDp,
                Surface.ROTATION_270, dm.density, unrotDh);

        // Compute the screen layout size class.
        int screenLayout;
        int longSize = dw;
        int shortSize = dh;
        if (longSize < shortSize) {
            int tmp = longSize;
            longSize = shortSize;
            shortSize = tmp;
        }
        longSize = (int)(longSize/dm.density);
        shortSize = (int)(shortSize/dm.density);

        // These semi-magic numbers define our compatibility modes for
        // applications with different screens.  These are guarantees to
        // app developers about the space they can expect for a particular
        // configuration.  DO NOT CHANGE!
        if (longSize < 470) {
            // This is shorter than an HVGA normal density screen (which
            // is 480 pixels on its long side).
            screenLayout = Configuration.SCREENLAYOUT_SIZE_SMALL
                    | Configuration.SCREENLAYOUT_LONG_NO;
        } else {
            // What size is this screen screen?
            if (longSize >= 960 && shortSize >= 720) {
                // 1.5xVGA or larger screens at medium density are the point
                // at which we consider it to be an extra large screen.
                screenLayout = Configuration.SCREENLAYOUT_SIZE_XLARGE;
            } else if (longSize >= 640 && shortSize >= 480) {
                // VGA or larger screens at medium density are the point
                // at which we consider it to be a large screen.
                screenLayout = Configuration.SCREENLAYOUT_SIZE_LARGE;
            } else {
                screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL;
            }

            // If this screen is wider than normal HVGA, or taller
            // than FWVGA, then for old apps we want to run in size
            // compatibility mode.
            if (shortSize > 321 || longSize > 570) {
                screenLayout |= Configuration.SCREENLAYOUT_COMPAT_NEEDED;
            }

            // Is this a long screen?
            if (((longSize*3)/5) >= (shortSize-1)) {
                // Anything wider than WVGA (5:3) is considering to be long.
                screenLayout |= Configuration.SCREENLAYOUT_LONG_YES;
            } else {
                screenLayout |= Configuration.SCREENLAYOUT_LONG_NO;
            }
        }
        config.screenLayout = screenLayout;

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

        // Update value of keyboardHidden, hardKeyboardHidden and navigationHidden
        // based on whether a hard or soft keyboard is present, whether navigation keys
        // are present and the lid switch state.
        config.keyboardHidden = Configuration.KEYBOARDHIDDEN_NO;
        config.hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO;
        config.navigationHidden = Configuration.NAVIGATIONHIDDEN_NO;
        mPolicy.adjustConfigurationLw(config);
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
                        Surface surface = new Surface(session, callerPid, "drag surface", 0,
                                width, height, PixelFormat.TRANSLUCENT, Surface.HIDDEN);
                        if (SHOW_TRANSACTIONS) Slog.i(TAG, "  DRAG "
                                + surface + ": CREATE");
                        outSurface.copyFrom(surface);
                        final IBinder winBinder = window.asBinder();
                        token = new Binder();
                        mDragState = new DragState(this, token, surface, /*flags*/ 0, winBinder);
                        mDragState.mSurface = surface;
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
                "resumeKeyDispatching()")) {
            throw new SecurityException("Requires MANAGE_APP_TOKENS permission");
        }

        synchronized (mWindowMap) {
            mInputMonitor.setEventDispatchingLw(enabled);
        }
    }

    /**
     * Injects a keystroke event into the UI.
     * Even when sync is false, this method may block while waiting for current
     * input events to be dispatched.
     *
     * @param ev A motion event describing the keystroke action.  (Be sure to use
     * {@link SystemClock#uptimeMillis()} as the timebase.)
     * @param sync If true, wait for the event to be completed before returning to the caller.
     * @return Returns true if event was dispatched, false if it was dropped for any reason
     */
    public boolean injectKeyEvent(KeyEvent ev, boolean sync) {
        long downTime = ev.getDownTime();
        long eventTime = ev.getEventTime();

        int action = ev.getAction();
        int code = ev.getKeyCode();
        int repeatCount = ev.getRepeatCount();
        int metaState = ev.getMetaState();
        int deviceId = ev.getDeviceId();
        int scancode = ev.getScanCode();
        int source = ev.getSource();
        int flags = ev.getFlags();
        
        if (source == InputDevice.SOURCE_UNKNOWN) {
            source = InputDevice.SOURCE_KEYBOARD;
        }

        if (eventTime == 0) eventTime = SystemClock.uptimeMillis();
        if (downTime == 0) downTime = eventTime;

        KeyEvent newEvent = new KeyEvent(downTime, eventTime, action, code, repeatCount, metaState,
                deviceId, scancode, flags | KeyEvent.FLAG_FROM_SYSTEM, source);

        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        
        final int result = mInputManager.injectInputEvent(newEvent, pid, uid,
                sync ? InputManager.INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_FINISH
                        : InputManager.INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_RESULT,
                INJECTION_TIMEOUT_MILLIS);
        
        Binder.restoreCallingIdentity(ident);
        return reportInjectionResult(result);
    }

    /**
     * Inject a pointer (touch) event into the UI.
     * Even when sync is false, this method may block while waiting for current
     * input events to be dispatched.
     *
     * @param ev A motion event describing the pointer (touch) action.  (As noted in
     * {@link MotionEvent#obtain(long, long, int, float, float, int)}, be sure to use
     * {@link SystemClock#uptimeMillis()} as the timebase.)
     * @param sync If true, wait for the event to be completed before returning to the caller.
     * @return Returns true if event was dispatched, false if it was dropped for any reason
     */
    public boolean injectPointerEvent(MotionEvent ev, boolean sync) {
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        
        MotionEvent newEvent = MotionEvent.obtain(ev);
        if ((newEvent.getSource() & InputDevice.SOURCE_CLASS_POINTER) == 0) {
            newEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        }
        
        final int result = mInputManager.injectInputEvent(newEvent, pid, uid,
                sync ? InputManager.INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_FINISH
                        : InputManager.INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_RESULT,
                INJECTION_TIMEOUT_MILLIS);
        
        Binder.restoreCallingIdentity(ident);
        return reportInjectionResult(result);
    }

    /**
     * Inject a trackball (navigation device) event into the UI.
     * Even when sync is false, this method may block while waiting for current
     * input events to be dispatched.
     *
     * @param ev A motion event describing the trackball action.  (As noted in
     * {@link MotionEvent#obtain(long, long, int, float, float, int)}, be sure to use
     * {@link SystemClock#uptimeMillis()} as the timebase.)
     * @param sync If true, wait for the event to be completed before returning to the caller.
     * @return Returns true if event was dispatched, false if it was dropped for any reason
     */
    public boolean injectTrackballEvent(MotionEvent ev, boolean sync) {
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        
        MotionEvent newEvent = MotionEvent.obtain(ev);
        if ((newEvent.getSource() & InputDevice.SOURCE_CLASS_TRACKBALL) == 0) {
            newEvent.setSource(InputDevice.SOURCE_TRACKBALL);
        }
        
        final int result = mInputManager.injectInputEvent(newEvent, pid, uid,
                sync ? InputManager.INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_FINISH
                        : InputManager.INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_RESULT,
                INJECTION_TIMEOUT_MILLIS);
        
        Binder.restoreCallingIdentity(ident);
        return reportInjectionResult(result);
    }
    
    /**
     * Inject an input event into the UI without waiting for dispatch to commence.
     * This variant is useful for fire-and-forget input event injection.  It does not
     * block any longer than it takes to enqueue the input event.
     *
     * @param ev An input event.  (Be sure to set the input source correctly.)
     * @return Returns true if event was dispatched, false if it was dropped for any reason
     */
    public boolean injectInputEventNoWait(InputEvent ev) {
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        
        final int result = mInputManager.injectInputEvent(ev, pid, uid,
                InputManager.INPUT_EVENT_INJECTION_SYNC_NONE,
                INJECTION_TIMEOUT_MILLIS);
        
        Binder.restoreCallingIdentity(ident);
        return reportInjectionResult(result);
    }
    
    private boolean reportInjectionResult(int result) {
        switch (result) {
            case InputManager.INPUT_EVENT_INJECTION_PERMISSION_DENIED:
                Slog.w(TAG, "Input event injection permission denied.");
                throw new SecurityException(
                        "Injecting to another application requires INJECT_EVENTS permission");
            case InputManager.INPUT_EVENT_INJECTION_SUCCEEDED:
                //Slog.v(TAG, "Input event injection succeeded.");
                return true;
            case InputManager.INPUT_EVENT_INJECTION_TIMED_OUT:
                Slog.w(TAG, "Input event injection timed out.");
                return false;
            case InputManager.INPUT_EVENT_INJECTION_FAILED:
            default:
                Slog.w(TAG, "Input event injection failed.");
                return false;
        }
    }

    /**
     * Temporarily set the pointer speed.  Does not save the new setting.
     * Used by the settings application.
     */
    public void setPointerSpeed(int speed) {
        if (!checkCallingPermission(android.Manifest.permission.SET_POINTER_SPEED,
                "setPointerSpeed()")) {
            throw new SecurityException("Requires SET_POINTER_SPEED permission");
        }

        mInputManager.setPointerSpeed(speed);
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

        mSafeMode = mPolicy.detectSafeMode();
        return mSafeMode;
    }

    public void systemReady() {
        synchronized(mWindowMap) {
            if (mDisplay != null) {
                throw new IllegalStateException("Display already initialized");
            }
            WindowManager wm = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
            mDisplay = wm.getDefaultDisplay();
            mInitialDisplayWidth = mDisplay.getRawWidth();
            mInitialDisplayHeight = mDisplay.getRawHeight();
            int rot = mDisplay.getRotation();
            if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
                // If the screen is currently rotated, we need to swap the
                // initial width and height to get the true natural values.
                int tmp = mInitialDisplayWidth;
                mInitialDisplayWidth = mInitialDisplayHeight;
                mInitialDisplayHeight = tmp;
            }
            mBaseDisplayWidth = mCurDisplayWidth = mAppDisplayWidth = mInitialDisplayWidth;
            mBaseDisplayHeight = mCurDisplayHeight = mAppDisplayHeight = mInitialDisplayHeight;
            mInputManager.setDisplaySize(Display.DEFAULT_DISPLAY,
                    mDisplay.getRawWidth(), mDisplay.getRawHeight(),
                    mDisplay.getRawExternalWidth(), mDisplay.getRawExternalHeight());
            mPolicy.setInitialDisplaySize(mInitialDisplayWidth, mInitialDisplayHeight);
        }

        try {
            mActivityManager.updateConfiguration(null);
        } catch (RemoteException e) {
        }

        mPolicy.systemReady();

        synchronized (mWindowMap) {
            readForcedDisplaySizeLocked();
        }
    }

    // This is an animation that does nothing: it just immediately finishes
    // itself every time it is called.  It is used as a stub animation in cases
    // where we want to synchronize multiple things that may be animating.
    static final class DummyAnimation extends Animation {
        public boolean getTransformation(long currentTime, Transformation outTransformation) {
            return false;
        }
    }
    static final Animation sDummyAnimation = new DummyAnimation();

    // -------------------------------------------------------------
    // Async Handler
    // -------------------------------------------------------------

    final class H extends Handler {
        public static final int REPORT_FOCUS_CHANGE = 2;
        public static final int REPORT_LOSING_FOCUS = 3;
        public static final int ANIMATE = 4;
        public static final int ADD_STARTING = 5;
        public static final int REMOVE_STARTING = 6;
        public static final int FINISHED_STARTING = 7;
        public static final int REPORT_APPLICATION_TOKEN_WINDOWS = 8;
        public static final int WINDOW_FREEZE_TIMEOUT = 11;
        public static final int HOLD_SCREEN_CHANGED = 12;
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

        private Session mLastReportedHold;

        public H() {
        }

        @Override
        public void handleMessage(Message msg) {
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

                        mPolicy.focusChanged(lastFocus, newFocus);
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

                case ANIMATE: {
                    synchronized(mWindowMap) {
                        mAnimationPending = false;
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
                        }

                        try {
                            mPolicy.removeStartingWindow(token, view);
                        } catch (Exception e) {
                            Slog.w(TAG, "Exception when removing starting window", e);
                        }
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
                    synchronized (mWindowMap) {
                        Slog.w(TAG, "Window freeze timeout expired.");
                        int i = mWindows.size();
                        while (i > 0) {
                            i--;
                            WindowState w = mWindows.get(i);
                            if (w.mOrientationChanging) {
                                w.mOrientationChanging = false;
                                Slog.w(TAG, "Force clearing orientation change: " + w);
                            }
                        }
                        performLayoutAndPlaceSurfacesLocked();
                    }
                    break;
                }

                case HOLD_SCREEN_CHANGED: {
                    Session oldHold;
                    Session newHold;
                    synchronized (mWindowMap) {
                        oldHold = mLastReportedHold;
                        newHold = (Session)msg.obj;
                        mLastReportedHold = newHold;
                    }

                    if (oldHold != newHold) {
                        try {
                            if (oldHold != null) {
                                mBatteryStats.noteStopWakelock(oldHold.mUid, -1,
                                        "window",
                                        BatteryStats.WAKE_TYPE_WINDOW);
                            }
                            if (newHold != null) {
                                mBatteryStats.noteStartWakelock(newHold.mUid, -1,
                                        "window",
                                        BatteryStats.WAKE_TYPE_WINDOW);
                            }
                        } catch (RemoteException e) {
                        }
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
                            performLayoutAndPlaceSurfacesLocked();
                        }
                    }
                    break;
                }

                case PERSIST_ANIMATION_SCALE: {
                    Settings.System.putFloat(mContext.getContentResolver(),
                            Settings.System.WINDOW_ANIMATION_SCALE, mWindowAnimationScale);
                    Settings.System.putFloat(mContext.getContentResolver(),
                            Settings.System.TRANSITION_ANIMATION_SCALE, mTransitionAnimationScale);
                    break;
                }

                case FORCE_GC: {
                    synchronized(mWindowMap) {
                        if (mAnimationPending) {
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
                    Runtime.getRuntime().gc();
                    break;
                }

                case ENABLE_SCREEN: {
                    performEnableScreen();
                    break;
                }

                case APP_FREEZE_TIMEOUT: {
                    synchronized (mWindowMap) {
                        Slog.w(TAG, "App freeze timeout expired.");
                        int i = mAppTokens.size();
                        while (i > 0) {
                            i--;
                            AppWindowToken tok = mAppTokens.get(i);
                            if (tok.freezingScreen) {
                                Slog.w(TAG, "Force clearing freeze: " + tok);
                                unsetAppFreezingScreenLocked(tok, true, true);
                            }
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
                        mDragState.mDragResult = false;
                        mDragState.endDragLw();
                    }
                    break;
                }

                case REPORT_HARD_KEYBOARD_STATUS_CHANGE: {
                    notifyHardKeyboardStatusChange();
                    break;
                }
            }
        }
    }

    // -------------------------------------------------------------
    // IWindowManager API
    // -------------------------------------------------------------

    public IWindowSession openSession(IInputMethodClient client,
            IInputContext inputContext) {
        if (client == null) throw new IllegalArgumentException("null client");
        if (inputContext == null) throw new IllegalArgumentException("null inputContext");
        Session session = new Session(this, client, inputContext);
        return session;
    }

    public boolean inputMethodClientHasFocus(IInputMethodClient client) {
        synchronized (mWindowMap) {
            // The focus for the client is the window immediately below
            // where we would place the input method window.
            int idx = findDesiredInputMethodWindowIndexLocked(false);
            WindowState imFocus;
            if (idx > 0) {
                imFocus = mWindows.get(idx-1);
                //Log.i(TAG, "Desired input method target: " + imFocus);
                //Log.i(TAG, "Current focus: " + this.mCurrentFocus);
                //Log.i(TAG, "Last focus: " + this.mLastFocus);
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
                                //Log.i(TAG, "Switching to real app window: " + w);
                                imFocus = w;
                                break;
                            }
                        }
                    }
                    //Log.i(TAG, "IM target client: " + imFocus.mSession.mClient);
                    //if (imFocus.mSession.mClient != null) {
                    //    Log.i(TAG, "IM target client binder: " + imFocus.mSession.mClient.asBinder());
                    //    Log.i(TAG, "Requesting client binder: " + client.asBinder());
                    //}
                    if (imFocus.mSession.mClient != null &&
                            imFocus.mSession.mClient.asBinder() == client.asBinder()) {
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
                    if (mCurrentFocus != null && mCurrentFocus.mSession.mClient != null &&
                            mCurrentFocus.mSession.mClient.asBinder() == client.asBinder()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void getDisplaySize(Point size) {
        synchronized(mWindowMap) {
            size.x = mAppDisplayWidth;
            size.y = mAppDisplayHeight;
        }
    }

    public void getRealDisplaySize(Point size) {
        synchronized(mWindowMap) {
            size.x = mCurDisplayWidth;
            size.y = mCurDisplayHeight;
        }
    }

    public int getMaximumSizeDimension() {
        synchronized(mWindowMap) {
            // Do this based on the raw screen size, until we are smarter.
            return mBaseDisplayWidth > mBaseDisplayHeight
                    ? mBaseDisplayWidth : mBaseDisplayHeight;
        }
    }

    public void setForcedDisplaySize(int longDimen, int shortDimen) {
        synchronized(mWindowMap) {
            int width, height;
            if (mInitialDisplayWidth < mInitialDisplayHeight) {
                width = shortDimen < mInitialDisplayWidth
                        ? shortDimen : mInitialDisplayWidth;
                height = longDimen < mInitialDisplayHeight
                        ? longDimen : mInitialDisplayHeight;
            } else {
                width = longDimen < mInitialDisplayWidth
                        ? longDimen : mInitialDisplayWidth;
                height = shortDimen < mInitialDisplayHeight
                        ? shortDimen : mInitialDisplayHeight;
            }
            setForcedDisplaySizeLocked(width, height);
            Settings.Secure.putString(mContext.getContentResolver(),
                    Settings.Secure.DISPLAY_SIZE_FORCED, width + "," + height);
        }
    }

    private void rebuildBlackFrame(boolean inTransaction) {
        if (!inTransaction) {
            if (SHOW_TRANSACTIONS) Slog.i(TAG,
                    ">>> OPEN TRANSACTION rebuildBlackFrame");
            Surface.openTransaction();
        }
        try {
            if (mBlackFrame != null) {
                mBlackFrame.kill();
                mBlackFrame = null;
            }
            if (mBaseDisplayWidth < mInitialDisplayWidth
                    || mBaseDisplayHeight < mInitialDisplayHeight) {
                int initW, initH, baseW, baseH;
                final boolean rotated = (mRotation == Surface.ROTATION_90
                        || mRotation == Surface.ROTATION_270);
                if (rotated) {
                    initW = mInitialDisplayHeight;
                    initH = mInitialDisplayWidth;
                    baseW = mBaseDisplayHeight;
                    baseH = mBaseDisplayWidth;
                } else {
                    initW = mInitialDisplayWidth;
                    initH = mInitialDisplayHeight;
                    baseW = mBaseDisplayWidth;
                    baseH = mBaseDisplayHeight;
                }
                Rect outer = new Rect(0, 0, initW, initH);
                Rect inner = new Rect(0, 0, baseW, baseH);
                try {
                    mBlackFrame = new BlackFrame(mFxSession, outer, inner, MASK_LAYER);
                } catch (Surface.OutOfResourcesException e) {
                }
            }
        } finally {
            if (!inTransaction) {
                Surface.closeTransaction();
                if (SHOW_TRANSACTIONS) Slog.i(TAG,
                        "<<< CLOSE TRANSACTION rebuildBlackFrame");
            }
        }
    }

    private void readForcedDisplaySizeLocked() {
        final String str = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.DISPLAY_SIZE_FORCED);
        if (str == null || str.length() == 0) {
            return;
        }
        final int pos = str.indexOf(',');
        if (pos <= 0 || str.lastIndexOf(',') != pos) {
            return;
        }
        int width, height;
        try {
            width = Integer.parseInt(str.substring(0, pos));
            height = Integer.parseInt(str.substring(pos+1));
        } catch (NumberFormatException ex) {
            return;
        }
        setForcedDisplaySizeLocked(width, height);
    }

    private void setForcedDisplaySizeLocked(int width, int height) {
        Slog.i(TAG, "Using new display size: " + width + "x" + height);

        mBaseDisplayWidth = width;
        mBaseDisplayHeight = height;
        mPolicy.setInitialDisplaySize(mBaseDisplayWidth, mBaseDisplayHeight);

        mLayoutNeeded = true;

        boolean configChanged = updateOrientationFromAppTokensLocked(false);
        mTempConfiguration.setToDefaults();
        mTempConfiguration.fontScale = mCurConfiguration.fontScale;
        if (computeNewConfigurationLocked(mTempConfiguration)) {
            if (mCurConfiguration.diff(mTempConfiguration) != 0) {
                configChanged = true;
            }
        }

        if (configChanged) {
            mWaitingForConfig = true;
            startFreezingDisplayLocked(false);
            mH.sendEmptyMessage(H.SEND_NEW_CONFIGURATION);
        }

        rebuildBlackFrame(false);

        performLayoutAndPlaceSurfacesLocked();
    }

    public void clearForcedDisplaySize() {
        synchronized(mWindowMap) {
            setForcedDisplaySizeLocked(mInitialDisplayWidth, mInitialDisplayHeight);
            Settings.Secure.putString(mContext.getContentResolver(),
                    Settings.Secure.DISPLAY_SIZE_FORCED, "");
        }
    }

    public boolean canStatusBarHide() {
        return mPolicy.canStatusBarHide();
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
        int NW = mWindows.size();
        int i;
        int lastWallpaper = -1;
        int numRemoved = 0;

        if (mRebuildTmp.length < NW) {
            mRebuildTmp = new WindowState[NW+10];
        }

        // First remove all existing app windows.
        i=0;
        while (i < NW) {
            WindowState w = mWindows.get(i);
            if (w.mAppToken != null) {
                WindowState win = mWindows.remove(i);
                win.mRebuilding = true;
                mRebuildTmp[numRemoved] = win;
                mWindowsChanged = true;
                if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG,
                        "Rebuild removing window: " + win);
                NW--;
                numRemoved++;
                continue;
            } else if (w.mAttrs.type == WindowManager.LayoutParams.TYPE_WALLPAPER
                    && lastWallpaper == i-1) {
                lastWallpaper = i;
            }
            i++;
        }

        // The wallpaper window(s) typically live at the bottom of the stack,
        // so skip them before adding app tokens.
        lastWallpaper++;
        i = lastWallpaper;

        // First add all of the exiting app tokens...  these are no longer
        // in the main app list, but still have windows shown.  We put them
        // in the back because now that the animation is over we no longer
        // will care about them.
        int NT = mExitingAppTokens.size();
        for (int j=0; j<NT; j++) {
            i = reAddAppWindowsLocked(i, mExitingAppTokens.get(j));
        }

        // And add in the still active app tokens in Z order.
        NT = mAppTokens.size();
        for (int j=0; j<NT; j++) {
            i = reAddAppWindowsLocked(i, mAppTokens.get(j));
        }

        i -= lastWallpaper;
        if (i != numRemoved) {
            Slog.w(TAG, "Rebuild removed " + numRemoved
                    + " windows but added " + i);
            for (i=0; i<numRemoved; i++) {
                WindowState ws = mRebuildTmp[i];
                if (ws.mRebuilding) {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    ws.dump(pw, "");
                    pw.flush();
                    Slog.w(TAG, "This window was lost: " + ws);
                    Slog.w(TAG, sw.toString());
                }
            }
            Slog.w(TAG, "Current app token list:");
            dumpAppTokensLocked();
            Slog.w(TAG, "Final window list:");
            dumpWindowsLocked();
        }
    }

    private final void assignLayersLocked() {
        int N = mWindows.size();
        int curBaseLayer = 0;
        int curLayer = 0;
        int i;

        if (DEBUG_LAYERS) {
            RuntimeException here = new RuntimeException("here");
            here.fillInStackTrace();
            Log.v(TAG, "Assigning layers", here);
        }

        for (i=0; i<N; i++) {
            WindowState w = mWindows.get(i);
            if (w.mBaseLayer == curBaseLayer || w.mIsImWindow
                    || (i > 0 && w.mIsWallpaper)) {
                curLayer += WINDOW_LAYER_MULTIPLIER;
                w.mLayer = curLayer;
            } else {
                curBaseLayer = curLayer = w.mBaseLayer;
                w.mLayer = curLayer;
            }
            if (w.mTargetAppToken != null) {
                w.mAnimLayer = w.mLayer + w.mTargetAppToken.animLayerAdjustment;
            } else if (w.mAppToken != null) {
                w.mAnimLayer = w.mLayer + w.mAppToken.animLayerAdjustment;
            } else {
                w.mAnimLayer = w.mLayer;
            }
            if (w.mIsImWindow) {
                w.mAnimLayer += mInputMethodAnimLayerAdjustment;
            } else if (w.mIsWallpaper) {
                w.mAnimLayer += mWallpaperAnimLayerAdjustment;
            }
            if (DEBUG_LAYERS) Slog.v(TAG, "Assign layer " + w + ": "
                    + w.mAnimLayer);
            //System.out.println(
            //    "Assigned layer " + curLayer + " to " + w.mClient.asBinder());
        }
    }

    private boolean mInLayout = false;
    private final void performLayoutAndPlaceSurfacesLocked() {
        if (mInLayout) {
            if (DEBUG) {
                throw new RuntimeException("Recursive call!");
            }
            Slog.w(TAG, "performLayoutAndPlaceSurfacesLocked called while in layout");
            return;
        }

        if (mWaitingForConfig) {
            // Our configuration has changed (most likely rotation), but we
            // don't yet have the complete configuration to report to
            // applications.  Don't do any window layout until we have it.
            return;
        }
        
        if (mDisplay == null) {
            // Not yet initialized, nothing to do.
            return;
        }

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
            Slog.e(TAG, "Unhandled exception while force removing for memory", e);
        }
        
        try {
            performLayoutAndPlaceSurfacesLockedInner(recoveringMemory);

            int N = mPendingRemove.size();
            if (N > 0) {
                if (mPendingRemoveTmp.length < N) {
                    mPendingRemoveTmp = new WindowState[N+10];
                }
                mPendingRemove.toArray(mPendingRemoveTmp);
                mPendingRemove.clear();
                for (int i=0; i<N; i++) {
                    WindowState w = mPendingRemoveTmp[i];
                    removeWindowInnerLocked(w.mSession, w);
                }

                mInLayout = false;
                assignLayersLocked();
                mLayoutNeeded = true;
                performLayoutAndPlaceSurfacesLocked();

            } else {
                mInLayout = false;
                if (mLayoutNeeded) {
                    requestAnimationLocked(0);
                }
            }
            if (mWindowsChanged && !mWindowChangeListeners.isEmpty()) {
                mH.removeMessages(H.REPORT_WINDOWS_CHANGE);
                mH.sendMessage(mH.obtainMessage(H.REPORT_WINDOWS_CHANGE));
            }
        } catch (RuntimeException e) {
            mInLayout = false;
            Slog.e(TAG, "Unhandled exception while layout out windows", e);
        }
    }

    private final int performLayoutLockedInner(boolean initial, boolean updateInputWindows) {
        if (!mLayoutNeeded) {
            return 0;
        }
        
        mLayoutNeeded = false;
        
        final int dw = mCurDisplayWidth;
        final int dh = mCurDisplayHeight;

        final int N = mWindows.size();
        int i;

        if (DEBUG_LAYOUT) Slog.v(TAG, "performLayout: needed="
                + mLayoutNeeded + " dw=" + dw + " dh=" + dh);
        
        mPolicy.beginLayoutLw(dw, dh);

        int seq = mLayoutSeq+1;
        if (seq < 0) seq = 0;
        mLayoutSeq = seq;
        
        // First perform layout of any root windows (not attached
        // to another window).
        int topAttached = -1;
        for (i = N-1; i >= 0; i--) {
            WindowState win = mWindows.get(i);

            // Don't do layout of a window if it is not visible, or
            // soon won't be visible, to avoid wasting time and funky
            // changes while a window is animating away.
            final AppWindowToken atoken = win.mAppToken;
            final boolean gone = win.mViewVisibility == View.GONE
                    || !win.mRelayoutCalled
                    || (atoken == null && win.mRootToken.hidden)
                    || (atoken != null && atoken.hiddenRequested)
                    || win.mAttachedHidden
                    || win.mExiting || win.mDestroying;

            if (DEBUG_LAYOUT && !win.mLayoutAttached) {
                Slog.v(TAG, "First pass " + win
                        + ": gone=" + gone + " mHaveFrame=" + win.mHaveFrame
                        + " mLayoutAttached=" + win.mLayoutAttached);
                if (gone) Slog.v(TAG, "  (mViewVisibility="
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
            if (!gone || !win.mHaveFrame) {
                if (!win.mLayoutAttached) {
                    if (initial) {
                        //Slog.i(TAG, "Window " + this + " clearing mContentChanged - initial");
                        win.mContentChanged = false;
                    }
                    win.prelayout();
                    mPolicy.layoutWindowLw(win, win.mAttrs, null);
                    win.mLayoutSeq = seq;
                    if (DEBUG_LAYOUT) Slog.v(TAG, "-> mFrame="
                            + win.mFrame + " mContainingFrame="
                            + win.mContainingFrame + " mDisplayFrame="
                            + win.mDisplayFrame);
                } else {
                    if (topAttached < 0) topAttached = i;
                }
            }
        }

        // Now perform layout of attached windows, which usually
        // depend on the position of the window they are attached to.
        // XXX does not deal with windows that are attached to windows
        // that are themselves attached.
        for (i = topAttached; i >= 0; i--) {
            WindowState win = mWindows.get(i);

            if (win.mLayoutAttached) {
                if (DEBUG_LAYOUT) Slog.v(TAG, "Second pass " + win
                        + " mHaveFrame=" + win.mHaveFrame
                        + " mViewVisibility=" + win.mViewVisibility
                        + " mRelayoutCalled=" + win.mRelayoutCalled);
                // If this view is GONE, then skip it -- keep the current
                // frame, and let the caller know so they can ignore it
                // if they want.  (We do the normal layout for INVISIBLE
                // windows, since that means "perform layout as normal,
                // just don't display").
                if ((win.mViewVisibility != View.GONE && win.mRelayoutCalled)
                        || !win.mHaveFrame) {
                    if (initial) {
                        //Slog.i(TAG, "Window " + this + " clearing mContentChanged - initial");
                        win.mContentChanged = false;
                    }
                    win.prelayout();
                    mPolicy.layoutWindowLw(win, win.mAttrs, win.mAttachedWindow);
                    win.mLayoutSeq = seq;
                    if (DEBUG_LAYOUT) Slog.v(TAG, "-> mFrame="
                            + win.mFrame + " mContainingFrame="
                            + win.mContainingFrame + " mDisplayFrame="
                            + win.mDisplayFrame);
                }
            }
        }
        
        // Window frames may have changed.  Tell the input dispatcher about it.
        mInputMonitor.setUpdateInputWindowsNeededLw();
        if (updateInputWindows) {
            mInputMonitor.updateInputWindowsLw(false /*force*/);
        }

        return mPolicy.finishLayoutLw();
    }

    // "Something has changed!  Let's make it correct now."
    private final void performLayoutAndPlaceSurfacesLockedInner(
            boolean recoveringMemory) {
        if (mDisplay == null) {
            Slog.i(TAG, "skipping performLayoutAndPlaceSurfacesLockedInner with no mDisplay");
            return;
        }

        final long currentTime = SystemClock.uptimeMillis();
        final int dw = mCurDisplayWidth;
        final int dh = mCurDisplayHeight;
        final int innerDw = mAppDisplayWidth;
        final int innerDh = mAppDisplayHeight;

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

        boolean orientationChangeComplete = true;
        Session holdScreen = null;
        float screenBrightness = -1;
        float buttonBrightness = -1;
        boolean focusDisplayed = false;
        boolean animating = false;
        boolean createWatermark = false;
        boolean updateRotation = false;
        boolean screenRotationFinished = false;

        if (mFxSession == null) {
            mFxSession = new SurfaceSession();
            createWatermark = true;
        }

        if (SHOW_TRANSACTIONS) Slog.i(TAG, ">>> OPEN TRANSACTION performLayoutAndPlaceSurfaces");

        Surface.openTransaction();

        if (createWatermark) {
            createWatermark();
        }
        if (mWatermark != null) {
            mWatermark.positionSurface(dw, dh);
        }
        if (mStrictModeFlash != null) {
            mStrictModeFlash.positionSurface(dw, dh);
        }

        try {
            boolean wallpaperForceHidingChanged = false;
            int repeats = 0;
            int changes = 0;
            
            do {
                repeats++;
                if (repeats > 6) {
                    Slog.w(TAG, "Animation repeat aborted after too many iterations");
                    mLayoutNeeded = false;
                    break;
                }
                
                if ((changes&(WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER
                        | WindowManagerPolicy.FINISH_LAYOUT_REDO_CONFIG
                        | WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT)) != 0) {
                    if ((changes&WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER) != 0) {
                        if ((adjustWallpaperWindowsLocked()&ADJUST_WALLPAPER_LAYERS_CHANGED) != 0) {
                            assignLayersLocked();
                            mLayoutNeeded = true;
                        }
                    }
                    if ((changes&WindowManagerPolicy.FINISH_LAYOUT_REDO_CONFIG) != 0) {
                        if (DEBUG_LAYOUT) Slog.v(TAG, "Computing new config from layout");
                        if (updateOrientationFromAppTokensLocked(true)) {
                            mLayoutNeeded = true;
                            mH.sendEmptyMessage(H.SEND_NEW_CONFIGURATION);
                        }
                    }
                    if ((changes&WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT) != 0) {
                        mLayoutNeeded = true;
                    }
                }
                
                // FIRST LOOP: Perform a layout, if needed.
                if (repeats < 4) {
                    changes = performLayoutLockedInner(repeats == 0, false /*updateInputWindows*/);
                    if (changes != 0) {
                        continue;
                    }
                } else {
                    Slog.w(TAG, "Layout repeat skipped after too many iterations");
                    changes = 0;
                }
                
                final int transactionSequence = ++mTransactionSequence;

                // Update animations of all applications, including those
                // associated with exiting/removed apps
                boolean tokensAnimating = false;
                final int NAT = mAppTokens.size();
                for (i=0; i<NAT; i++) {
                    if (mAppTokens.get(i).stepAnimationLocked(currentTime,
                            innerDw, innerDh)) {
                        tokensAnimating = true;
                    }
                }
                final int NEAT = mExitingAppTokens.size();
                for (i=0; i<NEAT; i++) {
                    if (mExitingAppTokens.get(i).stepAnimationLocked(currentTime,
                            innerDw, innerDh)) {
                        tokensAnimating = true;
                    }
                }

                // SECOND LOOP: Execute animations and update visibility of windows.
                
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "*** ANIM STEP: seq="
                        + transactionSequence + " tokensAnimating="
                        + tokensAnimating);
                        
                animating = tokensAnimating;

                if (mScreenRotationAnimation != null) {
                    if (mScreenRotationAnimation.isAnimating()) {
                        if (mScreenRotationAnimation.stepAnimation(currentTime)) {
                            animating = true;
                        } else {
                            screenRotationFinished = true;
                            updateRotation = true;
                        }
                    }
                }

                boolean tokenMayBeDrawn = false;
                boolean wallpaperMayChange = false;
                boolean forceHiding = false;
                WindowState windowDetachedWallpaper = null;
                WindowState windowAnimationBackground = null;
                int windowAnimationBackgroundColor = 0;

                mPolicy.beginAnimationLw(dw, dh);

                final int N = mWindows.size();

                for (i=N-1; i>=0; i--) {
                    WindowState w = mWindows.get(i);

                    final WindowManager.LayoutParams attrs = w.mAttrs;

                    if (w.mSurface != null) {
                        // Take care of the window being ready to display.
                        if (w.commitFinishDrawingLocked(currentTime)) {
                            if ((w.mAttrs.flags
                                    & WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER) != 0) {
                                if (DEBUG_WALLPAPER) Slog.v(TAG,
                                        "First draw done in potential wallpaper target " + w);
                                wallpaperMayChange = true;
                            }
                        }

                        final boolean wasAnimating = w.mAnimating;

                        int animDw = innerDw;
                        int animDh = innerDh;

                        // If the window has moved due to its containing
                        // content frame changing, then we'd like to animate
                        // it.  The checks here are ordered by what is least
                        // likely to be true first.
                        if (w.shouldAnimateMove()) {
                            // Frame has moved, containing content frame
                            // has also moved, and we're not currently animating...
                            // let's do something.
                            Animation a = AnimationUtils.loadAnimation(mContext,
                                    com.android.internal.R.anim.window_move_from_decor);
                            w.setAnimation(a);
                            animDw = w.mLastFrame.left - w.mFrame.left;
                            animDh = w.mLastFrame.top - w.mFrame.top;
                        }

                        // Execute animation.
                        final boolean nowAnimating = w.stepAnimationLocked(currentTime,
                                animDw, animDh);

                        // If this window is animating, make a note that we have
                        // an animating window and take care of a request to run
                        // a detached wallpaper animation.
                        if (nowAnimating) {
                            if (w.mAnimation != null) {
                                if (w.mAnimation.getDetachWallpaper()) {
                                    windowDetachedWallpaper = w;
                                }
                                if (w.mAnimation.getBackgroundColor() != 0) {
                                    windowAnimationBackground = w;
                                    windowAnimationBackgroundColor =
                                            w.mAnimation.getBackgroundColor();
                                }
                            }
                            animating = true;
                        }

                        // If this window's app token is running a detached wallpaper
                        // animation, make a note so we can ensure the wallpaper is
                        // displayed behind it.
                        if (w.mAppToken != null && w.mAppToken.animation != null) {
                            if (w.mAppToken.animation.getDetachWallpaper()) {
                                windowDetachedWallpaper = w;
                            }
                            if (w.mAppToken.animation.getBackgroundColor() != 0) {
                                windowAnimationBackground = w;
                                windowAnimationBackgroundColor =
                                        w.mAppToken.animation.getBackgroundColor();
                            }
                        }

                        if (wasAnimating && !w.mAnimating && mWallpaperTarget == w) {
                            wallpaperMayChange = true;
                        }

                        if (mPolicy.doesForceHide(w, attrs)) {
                            if (!wasAnimating && nowAnimating) {
                                if (DEBUG_VISIBILITY) Slog.v(TAG,
                                        "Animation started that could impact force hide: "
                                        + w);
                                wallpaperForceHidingChanged = true;
                                mFocusMayChange = true;
                            } else if (w.isReadyForDisplay() && w.mAnimation == null) {
                                forceHiding = true;
                            }
                        } else if (mPolicy.canBeForceHidden(w, attrs)) {
                            boolean changed;
                            if (forceHiding) {
                                changed = w.hideLw(false, false);
                                if (DEBUG_VISIBILITY && changed) Slog.v(TAG,
                                        "Now policy hidden: " + w);
                            } else {
                                changed = w.showLw(false, false);
                                if (DEBUG_VISIBILITY && changed) Slog.v(TAG,
                                        "Now policy shown: " + w);
                                if (changed) {
                                    if (wallpaperForceHidingChanged
                                            && w.isVisibleNow() /*w.isReadyForDisplay()*/) {
                                        // Assume we will need to animate.  If
                                        // we don't (because the wallpaper will
                                        // stay with the lock screen), then we will
                                        // clean up later.
                                        Animation a = mPolicy.createForceHideEnterAnimation();
                                        if (a != null) {
                                            w.setAnimation(a);
                                        }
                                    }
                                    if (mCurrentFocus == null ||
                                            mCurrentFocus.mLayer < w.mLayer) {
                                        // We are showing on to of the current
                                        // focus, so re-evaluate focus to make
                                        // sure it is correct.
                                        mFocusMayChange = true;
                                    }
                                }
                            }
                            if (changed && (attrs.flags
                                    & WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER) != 0) {
                                wallpaperMayChange = true;
                            }
                        }

                        mPolicy.animatingWindowLw(w, attrs);
                    }

                    final AppWindowToken atoken = w.mAppToken;
                    if (atoken != null && (!atoken.allDrawn || atoken.freezingScreen)) {
                        if (atoken.lastTransactionSequence != transactionSequence) {
                            atoken.lastTransactionSequence = transactionSequence;
                            atoken.numInterestingWindows = atoken.numDrawnWindows = 0;
                            atoken.startingDisplayed = false;
                        }
                        if ((w.isOnScreen() || w.mAttrs.type
                                == WindowManager.LayoutParams.TYPE_BASE_APPLICATION)
                                && !w.mExiting && !w.mDestroying) {
                            if (DEBUG_VISIBILITY || DEBUG_ORIENTATION) {
                                Slog.v(TAG, "Eval win " + w + ": isDrawn="
                                        + w.isDrawnLw()
                                        + ", isAnimating=" + w.isAnimating());
                                if (!w.isDrawnLw()) {
                                    Slog.v(TAG, "Not displayed: s=" + w.mSurface
                                            + " pv=" + w.mPolicyVisibility
                                            + " dp=" + w.mDrawPending
                                            + " cdp=" + w.mCommitDrawPending
                                            + " ah=" + w.mAttachedHidden
                                            + " th=" + atoken.hiddenRequested
                                            + " a=" + w.mAnimating);
                                }
                            }
                            if (w != atoken.startingWindow) {
                                if (!atoken.freezingScreen || !w.mAppFreezing) {
                                    atoken.numInterestingWindows++;
                                    if (w.isDrawnLw()) {
                                        atoken.numDrawnWindows++;
                                        if (DEBUG_VISIBILITY || DEBUG_ORIENTATION) Slog.v(TAG,
                                                "tokenMayBeDrawn: " + atoken
                                                + " freezingScreen=" + atoken.freezingScreen
                                                + " mAppFreezing=" + w.mAppFreezing);
                                        tokenMayBeDrawn = true;
                                    }
                                }
                            } else if (w.isDrawnLw()) {
                                atoken.startingDisplayed = true;
                            }
                        }
                    } else if (w.mReadyToShow) {
                        w.performShowLocked();
                    }
                }

                changes |= mPolicy.finishAnimationLw();

                if (tokenMayBeDrawn) {
                    // See if any windows have been drawn, so they (and others
                    // associated with them) can now be shown.
                    final int NT = mAppTokens.size();
                    for (i=0; i<NT; i++) {
                        AppWindowToken wtoken = mAppTokens.get(i);
                        if (wtoken.freezingScreen) {
                            int numInteresting = wtoken.numInterestingWindows;
                            if (numInteresting > 0 && wtoken.numDrawnWindows >= numInteresting) {
                                if (DEBUG_VISIBILITY) Slog.v(TAG,
                                        "allDrawn: " + wtoken
                                        + " interesting=" + numInteresting
                                        + " drawn=" + wtoken.numDrawnWindows);
                                wtoken.showAllWindowsLocked();
                                unsetAppFreezingScreenLocked(wtoken, false, true);
                                orientationChangeComplete = true;
                            }
                        } else if (!wtoken.allDrawn) {
                            int numInteresting = wtoken.numInterestingWindows;
                            if (numInteresting > 0 && wtoken.numDrawnWindows >= numInteresting) {
                                if (DEBUG_VISIBILITY) Slog.v(TAG,
                                        "allDrawn: " + wtoken
                                        + " interesting=" + numInteresting
                                        + " drawn=" + wtoken.numDrawnWindows);
                                wtoken.allDrawn = true;
                                changes |= PhoneWindowManager.FINISH_LAYOUT_REDO_ANIM;

                                // We can now show all of the drawn windows!
                                if (!mOpeningApps.contains(wtoken)) {
                                    wtoken.showAllWindowsLocked();
                                }
                            }
                        }
                    }
                }

                // If we are ready to perform an app transition, check through
                // all of the app tokens to be shown and see if they are ready
                // to go.
                if (mAppTransitionReady) {
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
                                    "Check opening app" + wtoken + ": allDrawn="
                                    + wtoken.allDrawn + " startingDisplayed="
                                    + wtoken.startingDisplayed);
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

                        // If there are applications waiting to come to the
                        // top of the stack, now is the time to move their windows.
                        // (Note that we don't do apps going to the bottom
                        // here -- we want to keep their windows in the old
                        // Z-order until the animation completes.)
                        if (mToTopApps.size() > 0) {
                            NN = mAppTokens.size();
                            for (i=0; i<NN; i++) {
                                AppWindowToken wtoken = mAppTokens.get(i);
                                if (wtoken.sendingToTop) {
                                    wtoken.sendingToTop = false;
                                    moveAppWindowsLocked(wtoken, NN, false);
                                }
                            }
                            mToTopApps.clear();
                        }

                        WindowState oldWallpaper = mWallpaperTarget;

                        adjustWallpaperWindowsLocked();
                        wallpaperMayChange = false;

                        // The top-most window will supply the layout params,
                        // and we will determine it below.
                        LayoutParams animLp = null;
                        int bestAnimLayer = -1;
                        boolean fullscreenAnim = false;

                        if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                                "New wallpaper target=" + mWallpaperTarget
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
                        } else if (oldWallpaper != null) {
                            // We are transitioning from an activity with
                            // a wallpaper to one without.
                            transit = WindowManagerPolicy.TRANSIT_WALLPAPER_CLOSE;
                            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                                    "New transit away from wallpaper: " + transit);
                        } else if (mWallpaperTarget != null) {
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
                        
                        NN = mOpeningApps.size();
                        for (i=0; i<NN; i++) {
                            AppWindowToken wtoken = mOpeningApps.get(i);
                            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                                    "Now opening app" + wtoken);
                            wtoken.reportedVisible = false;
                            wtoken.inPendingTransaction = false;
                            wtoken.animation = null;
                            setTokenVisibilityLocked(wtoken, animLp, true,
                                    transit, false);
                            wtoken.updateReportedVisibilityLocked();
                            wtoken.waitingToShow = false;
                            wtoken.showAllWindowsLocked();
                        }
                        NN = mClosingApps.size();
                        for (i=0; i<NN; i++) {
                            AppWindowToken wtoken = mClosingApps.get(i);
                            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                                    "Now closing app" + wtoken);
                            wtoken.inPendingTransaction = false;
                            wtoken.animation = null;
                            setTokenVisibilityLocked(wtoken, animLp, false,
                                    transit, false);
                            wtoken.updateReportedVisibilityLocked();
                            wtoken.waitingToHide = false;
                            // Force the allDrawn flag, because we want to start
                            // this guy's animations regardless of whether it's
                            // gotten drawn.
                            wtoken.allDrawn = true;
                        }

                        mNextAppTransitionPackage = null;

                        mOpeningApps.clear();
                        mClosingApps.clear();

                        // This has changed the visibility of windows, so perform
                        // a new layout to get them all up-to-date.
                        changes |= PhoneWindowManager.FINISH_LAYOUT_REDO_LAYOUT
                                | WindowManagerPolicy.FINISH_LAYOUT_REDO_CONFIG;
                        mLayoutNeeded = true;
                        if (!moveInputMethodWindowsIfNeededLocked(true)) {
                            assignLayersLocked();
                        }
                        updateFocusedWindowLocked(UPDATE_FOCUS_PLACING_SURFACES,
                                false /*updateInputWindows*/);
                        mFocusMayChange = false;
                    }
                }

                int adjResult = 0;

                if (!animating && mAppTransitionRunning) {
                    // We have finished the animation of an app transition.  To do
                    // this, we have delayed a lot of operations like showing and
                    // hiding apps, moving apps in Z-order, etc.  The app token list
                    // reflects the correct Z-order, but the window list may now
                    // be out of sync with it.  So here we will just rebuild the
                    // entire app window list.  Fun!
                    mAppTransitionRunning = false;
                    // Clear information about apps that were moving.
                    mToBottomApps.clear();

                    rebuildAppWindowListLocked();
                    changes |= PhoneWindowManager.FINISH_LAYOUT_REDO_LAYOUT;
                    adjResult |= ADJUST_WALLPAPER_LAYERS_CHANGED;
                    moveInputMethodWindowsIfNeededLocked(false);
                    wallpaperMayChange = true;
                    // Since the window list has been rebuilt, focus might
                    // have to be recomputed since the actual order of windows
                    // might have changed again.
                    mFocusMayChange = true;
                }

                if (wallpaperForceHidingChanged && changes == 0 && !mAppTransitionReady) {
                    // At this point, there was a window with a wallpaper that
                    // was force hiding other windows behind it, but now it
                    // is going away.  This may be simple -- just animate
                    // away the wallpaper and its window -- or it may be
                    // hard -- the wallpaper now needs to be shown behind
                    // something that was hidden.
                    WindowState oldWallpaper = mWallpaperTarget;
                    if (mLowerWallpaperTarget != null
                            && mLowerWallpaperTarget.mAppToken != null) {
                        if (DEBUG_WALLPAPER) Slog.v(TAG,
                                "wallpaperForceHiding changed with lower="
                                + mLowerWallpaperTarget);
                        if (DEBUG_WALLPAPER) Slog.v(TAG,
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
                    adjResult |= adjustWallpaperWindowsLocked();
                    wallpaperMayChange = false;
                    wallpaperForceHidingChanged = false;
                    if (DEBUG_WALLPAPER) Slog.v(TAG, "****** OLD: " + oldWallpaper
                            + " NEW: " + mWallpaperTarget
                            + " LOWER: " + mLowerWallpaperTarget);
                    if (mLowerWallpaperTarget == null) {
                        // Whoops, we don't need a special wallpaper animation.
                        // Clear them out.
                        forceHiding = false;
                        for (i=N-1; i>=0; i--) {
                            WindowState w = mWindows.get(i);
                            if (w.mSurface != null) {
                                final WindowManager.LayoutParams attrs = w.mAttrs;
                                if (mPolicy.doesForceHide(w, attrs) && w.isVisibleLw()) {
                                    if (DEBUG_FOCUS) Slog.i(TAG, "win=" + w + " force hides other windows");
                                    forceHiding = true;
                                } else if (mPolicy.canBeForceHidden(w, attrs)) {
                                    if (!w.mAnimating) {
                                        // We set the animation above so it
                                        // is not yet running.
                                        w.clearAnimation();
                                    }
                                }
                            }
                        }
                    }
                }

                if (mWindowDetachedWallpaper != windowDetachedWallpaper) {
                    if (DEBUG_WALLPAPER) Slog.v(TAG,
                            "Detached wallpaper changed from " + mWindowDetachedWallpaper
                            + windowDetachedWallpaper);
                    mWindowDetachedWallpaper = windowDetachedWallpaper;
                    wallpaperMayChange = true;
                }

                if (windowAnimationBackgroundColor != 0) {
                    if (mWindowAnimationBackgroundSurface == null) {
                        mWindowAnimationBackgroundSurface = new DimSurface(mFxSession);
                    }
                    mWindowAnimationBackgroundSurface.show(dw, dh,
                            windowAnimationBackground.mAnimLayer - LAYER_OFFSET_DIM,
                            windowAnimationBackgroundColor);
                } else if (mWindowAnimationBackgroundSurface != null) {
                    mWindowAnimationBackgroundSurface.hide();
                }

                if (wallpaperMayChange) {
                    if (DEBUG_WALLPAPER) Slog.v(TAG,
                            "Wallpaper may change!  Adjusting");
                    adjResult |= adjustWallpaperWindowsLocked();
                }

                if ((adjResult&ADJUST_WALLPAPER_LAYERS_CHANGED) != 0) {
                    if (DEBUG_WALLPAPER) Slog.v(TAG,
                            "Wallpaper layer changed: assigning layers + relayout");
                    changes |= PhoneWindowManager.FINISH_LAYOUT_REDO_LAYOUT;
                    assignLayersLocked();
                } else if ((adjResult&ADJUST_WALLPAPER_VISIBILITY_CHANGED) != 0) {
                    if (DEBUG_WALLPAPER) Slog.v(TAG,
                            "Wallpaper visibility changed: relayout");
                    changes |= PhoneWindowManager.FINISH_LAYOUT_REDO_LAYOUT;
                }

                if (mFocusMayChange) {
                    mFocusMayChange = false;
                    if (updateFocusedWindowLocked(UPDATE_FOCUS_PLACING_SURFACES,
                            false /*updateInputWindows*/)) {
                        changes |= PhoneWindowManager.FINISH_LAYOUT_REDO_ANIM;
                        adjResult = 0;
                    }
                }

                if (mLayoutNeeded) {
                    changes |= PhoneWindowManager.FINISH_LAYOUT_REDO_LAYOUT;
                }

                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "*** ANIM STEP: changes=0x"
                        + Integer.toHexString(changes));
            } while (changes != 0);

            // THIRD LOOP: Update the surfaces of all windows.

            final boolean someoneLosingFocus = mLosingFocus.size() != 0;

            boolean obscured = false;
            boolean blurring = false;
            boolean dimming = false;
            boolean covered = false;
            boolean syswin = false;

            final int N = mWindows.size();

            for (i=N-1; i>=0; i--) {
                WindowState w = mWindows.get(i);

                boolean displayed = false;
                final WindowManager.LayoutParams attrs = w.mAttrs;
                final int attrFlags = attrs.flags;

                if (w.mSurface != null) {
                    // XXX NOTE: The logic here could be improved.  We have
                    // the decision about whether to resize a window separated
                    // from whether to hide the surface.  This can cause us to
                    // resize a surface even if we are going to hide it.  You
                    // can see this by (1) holding device in landscape mode on
                    // home screen; (2) tapping browser icon (device will rotate
                    // to landscape; (3) tap home.  The wallpaper will be resized
                    // in step 2 but then immediately hidden, causing us to
                    // have to resize and then redraw it again in step 3.  It
                    // would be nice to figure out how to avoid this, but it is
                    // difficult because we do need to resize surfaces in some
                    // cases while they are hidden such as when first showing a
                    // window.
                    
                    w.computeShownFrameLocked();
                    if (localLOGV) Slog.v(
                            TAG, "Placing surface #" + i + " " + w.mSurface
                            + ": new=" + w.mShownFrame);

                    if (w.mSurface != null) {
                        int width, height;
                        if ((w.mAttrs.flags & w.mAttrs.FLAG_SCALED) != 0) {
                            // for a scaled surface, we just want to use
                            // the requested size.
                            width  = w.mRequestedWidth;
                            height = w.mRequestedHeight;
                        } else {
                            width = w.mCompatFrame.width();
                            height = w.mCompatFrame.height();
                        }

                        if (width < 1) {
                            width = 1;
                        }
                        if (height < 1) {
                            height = 1;
                        }
                        final boolean surfaceResized = w.mSurfaceW != width || w.mSurfaceH != height;
                        if (surfaceResized) {
                            w.mSurfaceW = width;
                            w.mSurfaceH = height;
                        }

                        if (w.mSurfaceX != w.mShownFrame.left
                                || w.mSurfaceY != w.mShownFrame.top) {
                            try {
                                if (SHOW_TRANSACTIONS) logSurface(w,
                                        "POS " + w.mShownFrame.left
                                        + ", " + w.mShownFrame.top, null);
                                w.mSurfaceX = w.mShownFrame.left;
                                w.mSurfaceY = w.mShownFrame.top;
                                w.mSurface.setPosition(w.mShownFrame.left, w.mShownFrame.top);
                            } catch (RuntimeException e) {
                                Slog.w(TAG, "Error positioning surface of " + w
                                        + " pos=(" + w.mShownFrame.left
                                        + "," + w.mShownFrame.top + ")", e);
                                if (!recoveringMemory) {
                                    reclaimSomeSurfaceMemoryLocked(w, "position", true);
                                }
                            }
                        }

                        if (surfaceResized) {
                            try {
                                if (SHOW_TRANSACTIONS) logSurface(w,
                                        "SIZE " + width + "x" + height, null);
                                w.mSurfaceResized = true;
                                w.mSurface.setSize(width, height);
                            } catch (RuntimeException e) {
                                // If something goes wrong with the surface (such
                                // as running out of memory), don't take down the
                                // entire system.
                                Slog.e(TAG, "Error resizing surface of " + w
                                        + " size=(" + width + "x" + height + ")", e);
                                if (!recoveringMemory) {
                                    reclaimSomeSurfaceMemoryLocked(w, "size", true);
                                }
                            }
                        }
                    }

                    if (!w.mAppFreezing && w.mLayoutSeq == mLayoutSeq) {
                        w.mContentInsetsChanged |=
                            !w.mLastContentInsets.equals(w.mContentInsets);
                        w.mVisibleInsetsChanged |=
                            !w.mLastVisibleInsets.equals(w.mVisibleInsets);
                        boolean configChanged =
                            w.mConfiguration != mCurConfiguration
                            && (w.mConfiguration == null
                                    || mCurConfiguration.diff(w.mConfiguration) != 0);
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
                                || w.mSurfaceResized
                                || configChanged) {
                            if (DEBUG_RESIZE || DEBUG_ORIENTATION) {
                                Slog.v(TAG, "Resize reasons: "
                                        + " contentInsetsChanged=" + w.mContentInsetsChanged
                                        + " visibleInsetsChanged=" + w.mVisibleInsetsChanged
                                        + " surfaceResized=" + w.mSurfaceResized
                                        + " configChanged=" + configChanged);
                            }

                            w.mLastContentInsets.set(w.mContentInsets);
                            w.mLastVisibleInsets.set(w.mVisibleInsets);
                            // If the screen is currently frozen, then keep
                            // it frozen until this window draws at its new
                            // orientation.
                            if (mDisplayFrozen) {
                                if (DEBUG_ORIENTATION) Slog.v(TAG,
                                        "Resizing while display frozen: " + w);
                                w.mOrientationChanging = true;
                                if (!mWindowsFreezingScreen) {
                                    mWindowsFreezingScreen = true;
                                    // XXX should probably keep timeout from
                                    // when we first froze the display.
                                    mH.removeMessages(H.WINDOW_FREEZE_TIMEOUT);
                                    mH.sendMessageDelayed(mH.obtainMessage(
                                            H.WINDOW_FREEZE_TIMEOUT), 2000);
                                }
                            }
                            // If the orientation is changing, then we need to
                            // hold off on unfreezing the display until this
                            // window has been redrawn; to do that, we need
                            // to go through the process of getting informed
                            // by the application when it has finished drawing.
                            if (w.mOrientationChanging) {
                                if (DEBUG_ORIENTATION) Slog.v(TAG,
                                        "Orientation start waiting for draw in "
                                        + w + ", surface " + w.mSurface);
                                w.mDrawPending = true;
                                w.mCommitDrawPending = false;
                                w.mReadyToShow = false;
                                if (w.mAppToken != null) {
                                    w.mAppToken.allDrawn = false;
                                }
                            }
                            if (!mResizingWindows.contains(w)) {
                                if (DEBUG_RESIZE || DEBUG_ORIENTATION) Slog.v(TAG,
                                        "Resizing window " + w + " to " + w.mSurfaceW
                                        + "x" + w.mSurfaceH);
                                mResizingWindows.add(w);
                            }
                        } else if (w.mOrientationChanging) {
                            if (!w.mDrawPending && !w.mCommitDrawPending) {
                                if (DEBUG_ORIENTATION) Slog.v(TAG,
                                        "Orientation not waiting for draw in "
                                        + w + ", surface " + w.mSurface);
                                w.mOrientationChanging = false;
                            }
                        }
                    }

                    if (w.mAttachedHidden || !w.isReadyForDisplay()) {
                        if (!w.mLastHidden) {
                            //dump();
                            w.mLastHidden = true;
                            if (SHOW_TRANSACTIONS) logSurface(w,
                                    "HIDE (performLayout)", null);
                            if (w.mSurface != null) {
                                w.mSurfaceShown = false;
                                try {
                                    w.mSurface.hide();
                                } catch (RuntimeException e) {
                                    Slog.w(TAG, "Exception hiding surface in " + w);
                                }
                            }
                        }
                        // If we are waiting for this window to handle an
                        // orientation change, well, it is hidden, so
                        // doesn't really matter.  Note that this does
                        // introduce a potential glitch if the window
                        // becomes unhidden before it has drawn for the
                        // new orientation.
                        if (w.mOrientationChanging) {
                            w.mOrientationChanging = false;
                            if (DEBUG_ORIENTATION) Slog.v(TAG,
                                    "Orientation change skips hidden " + w);
                        }
                    } else if (w.mLastLayer != w.mAnimLayer
                            || w.mLastAlpha != w.mShownAlpha
                            || w.mLastDsDx != w.mDsDx
                            || w.mLastDtDx != w.mDtDx
                            || w.mLastDsDy != w.mDsDy
                            || w.mLastDtDy != w.mDtDy
                            || w.mLastHScale != w.mHScale
                            || w.mLastVScale != w.mVScale
                            || w.mLastHidden) {
                        displayed = true;
                        w.mLastAlpha = w.mShownAlpha;
                        w.mLastLayer = w.mAnimLayer;
                        w.mLastDsDx = w.mDsDx;
                        w.mLastDtDx = w.mDtDx;
                        w.mLastDsDy = w.mDsDy;
                        w.mLastDtDy = w.mDtDy;
                        w.mLastHScale = w.mHScale;
                        w.mLastVScale = w.mVScale;
                        if (SHOW_TRANSACTIONS) logSurface(w,
                                "alpha=" + w.mShownAlpha + " layer=" + w.mAnimLayer
                                + " matrix=[" + (w.mDsDx*w.mHScale)
                                + "," + (w.mDtDx*w.mVScale)
                                + "][" + (w.mDsDy*w.mHScale)
                                + "," + (w.mDtDy*w.mVScale) + "]", null);
                        if (w.mSurface != null) {
                            try {
                                w.mSurfaceAlpha = w.mShownAlpha;
                                w.mSurface.setAlpha(w.mShownAlpha);
                                w.mSurfaceLayer = w.mAnimLayer;
                                w.mSurface.setLayer(w.mAnimLayer);
                                w.mSurface.setMatrix(
                                        w.mDsDx*w.mHScale, w.mDtDx*w.mVScale,
                                        w.mDsDy*w.mHScale, w.mDtDy*w.mVScale);
                            } catch (RuntimeException e) {
                                Slog.w(TAG, "Error updating surface in " + w, e);
                                if (!recoveringMemory) {
                                    reclaimSomeSurfaceMemoryLocked(w, "update", true);
                                }
                            }
                        }

                        if (w.mLastHidden && !w.mDrawPending
                                && !w.mCommitDrawPending
                                && !w.mReadyToShow) {
                            if (SHOW_TRANSACTIONS) logSurface(w,
                                    "SHOW (performLayout)", null);
                            if (DEBUG_VISIBILITY) Slog.v(TAG, "Showing " + w
                                    + " during relayout");
                            if (showSurfaceRobustlyLocked(w)) {
                                w.mHasDrawn = true;
                                w.mLastHidden = false;
                            } else {
                                w.mOrientationChanging = false;
                            }
                        }
                        if (w.mSurface != null) {
                            w.mToken.hasVisible = true;
                        }
                    } else {
                        displayed = true;
                    }

                    if (displayed) {
                        if (!covered) {
                            if (attrs.width == LayoutParams.MATCH_PARENT
                                    && attrs.height == LayoutParams.MATCH_PARENT) {
                                covered = true;
                            }
                        }
                        if (w.mOrientationChanging) {
                            if (w.mDrawPending || w.mCommitDrawPending) {
                                orientationChangeComplete = false;
                                if (DEBUG_ORIENTATION) Slog.v(TAG,
                                        "Orientation continue waiting for draw in " + w);
                            } else {
                                w.mOrientationChanging = false;
                                if (DEBUG_ORIENTATION) Slog.v(TAG,
                                        "Orientation change complete in " + w);
                            }
                        }
                        w.mToken.hasVisible = true;
                    }
                } else if (w.mOrientationChanging) {
                    if (DEBUG_ORIENTATION) Slog.v(TAG,
                            "Orientation change skips hidden " + w);
                    w.mOrientationChanging = false;
                }

                if (w.mContentChanged) {
                    //Slog.i(TAG, "Window " + this + " clearing mContentChanged - done placing");
                    w.mContentChanged = false;
                }

                final boolean canBeSeen = w.isDisplayedLw();

                if (someoneLosingFocus && w == mCurrentFocus && canBeSeen) {
                    focusDisplayed = true;
                }

                final boolean obscuredChanged = w.mObscured != obscured;

                // Update effect.
                if (!(w.mObscured=obscured)) {
                    if (w.mSurface != null) {
                        if ((attrFlags&FLAG_KEEP_SCREEN_ON) != 0) {
                            holdScreen = w.mSession;
                        }
                        if (!syswin && w.mAttrs.screenBrightness >= 0
                                && screenBrightness < 0) {
                            screenBrightness = w.mAttrs.screenBrightness;
                        }
                        if (!syswin && w.mAttrs.buttonBrightness >= 0
                                && buttonBrightness < 0) {
                            buttonBrightness = w.mAttrs.buttonBrightness;
                        }
                        if (canBeSeen
                                && (attrs.type == WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG
                                 || attrs.type == WindowManager.LayoutParams.TYPE_KEYGUARD
                                 || attrs.type == WindowManager.LayoutParams.TYPE_SYSTEM_ERROR)) {
                            syswin = true;
                        }
                    }

                    boolean opaqueDrawn = canBeSeen && w.isOpaqueDrawn();
                    if (opaqueDrawn && w.isFullscreen(innerDw, innerDh)) {
                        // This window completely covers everything behind it,
                        // so we want to leave all of them as unblurred (for
                        // performance reasons).
                        obscured = true;
                    } else if (canBeSeen && !obscured &&
                            (attrFlags&FLAG_BLUR_BEHIND|FLAG_DIM_BEHIND) != 0) {
                        if (localLOGV) Slog.v(TAG, "Win " + w
                                + ": blurring=" + blurring
                                + " obscured=" + obscured
                                + " displayed=" + displayed);
                        if ((attrFlags&FLAG_DIM_BEHIND) != 0) {
                            if (!dimming) {
                                //Slog.i(TAG, "DIM BEHIND: " + w);
                                dimming = true;
                                if (mDimAnimator == null) {
                                    mDimAnimator = new DimAnimator(mFxSession);
                                }
                                mDimAnimator.show(innerDw, innerDh);
                                mDimAnimator.updateParameters(mContext.getResources(),
                                        w, currentTime);
                            }
                        }
                        if ((attrFlags&FLAG_BLUR_BEHIND) != 0) {
                            if (!blurring) {
                                //Slog.i(TAG, "BLUR BEHIND: " + w);
                                blurring = true;
                                if (mBlurSurface == null) {
                                    try {
                                        mBlurSurface = new Surface(mFxSession, 0,
                                                "BlurSurface",
                                                -1, 16, 16,
                                                PixelFormat.OPAQUE,
                                                Surface.FX_SURFACE_BLUR);
                                    } catch (Exception e) {
                                        Slog.e(TAG, "Exception creating Blur surface", e);
                                    }
                                    if (SHOW_TRANSACTIONS) Slog.i(TAG, "  BLUR "
                                            + mBlurSurface + ": CREATE");
                                }
                                if (mBlurSurface != null) {
                                    if (SHOW_TRANSACTIONS) Slog.i(TAG, "  BLUR "
                                            + mBlurSurface + ": pos=(0,0) (" +
                                            dw + "x" + dh + "), layer=" + (w.mAnimLayer-1));
                                    mBlurSurface.setPosition(0, 0);
                                    mBlurSurface.setSize(dw, dh);
                                    mBlurSurface.setLayer(w.mAnimLayer-LAYER_OFFSET_BLUR);
                                    if (!mBlurShown) {
                                        try {
                                            if (SHOW_TRANSACTIONS) Slog.i(TAG, "  BLUR "
                                                    + mBlurSurface + ": SHOW");
                                            mBlurSurface.show();
                                        } catch (RuntimeException e) {
                                            Slog.w(TAG, "Failure showing blur surface", e);
                                        }
                                        mBlurShown = true;
                                    }
                                }
                            }
                        }
                    }
                }

                if (obscuredChanged && mWallpaperTarget == w) {
                    // This is the wallpaper target and its obscured state
                    // changed... make sure the current wallaper's visibility
                    // has been updated accordingly.
                    updateWallpaperVisibilityLocked();
                }
            }

            if (mDimAnimator != null && mDimAnimator.mDimShown) {
                animating |= mDimAnimator.updateSurface(dimming, currentTime,
                        mDisplayFrozen || !mPolicy.isScreenOn());
            }

            if (!blurring && mBlurShown) {
                if (SHOW_TRANSACTIONS) Slog.i(TAG, "  BLUR " + mBlurSurface
                        + ": HIDE");
                try {
                    mBlurSurface.hide();
                } catch (IllegalArgumentException e) {
                    Slog.w(TAG, "Illegal argument exception hiding blur surface");
                }
                mBlurShown = false;
            }

            if (mBlackFrame != null) {
                if (mScreenRotationAnimation != null) {
                    mBlackFrame.setMatrix(
                            mScreenRotationAnimation.getEnterTransformation().getMatrix());
                } else {
                    mBlackFrame.clearMatrix();
                }
            }
        } catch (RuntimeException e) {
            Slog.e(TAG, "Unhandled exception in Window Manager", e);
        }

        Surface.closeTransaction();

        if (SHOW_TRANSACTIONS) Slog.i(TAG, "<<< CLOSE TRANSACTION performLayoutAndPlaceSurfaces");

        if (mWatermark != null) {
            mWatermark.drawIfNeeded();
        }

        if (DEBUG_ORIENTATION && mDisplayFrozen) Slog.v(TAG,
                "With display frozen, orientationChangeComplete="
                + orientationChangeComplete);
        if (orientationChangeComplete) {
            if (mWindowsFreezingScreen) {
                mWindowsFreezingScreen = false;
                mH.removeMessages(H.WINDOW_FREEZE_TIMEOUT);
            }
            stopFreezingDisplayLocked();
        }

        i = mResizingWindows.size();
        if (i > 0) {
            do {
                i--;
                WindowState win = mResizingWindows.get(i);
                try {
                    if (DEBUG_RESIZE || DEBUG_ORIENTATION) Slog.v(TAG,
                            "Reporting new frame to " + win + ": " + win.mCompatFrame);
                    int diff = 0;
                    boolean configChanged =
                        win.mConfiguration != mCurConfiguration
                        && (win.mConfiguration == null
                                || (diff=mCurConfiguration.diff(win.mConfiguration)) != 0);
                    if ((DEBUG_RESIZE || DEBUG_ORIENTATION || DEBUG_CONFIGURATION)
                            && configChanged) {
                        Slog.i(TAG, "Sending new config to window " + win + ": "
                                + win.mSurfaceW + "x" + win.mSurfaceH
                                + " / " + mCurConfiguration + " / 0x"
                                + Integer.toHexString(diff));
                    }
                    win.mConfiguration = mCurConfiguration;
                    win.mClient.resized(win.mSurfaceW, win.mSurfaceH, win.mLastContentInsets,
                            win.mLastVisibleInsets, win.mDrawPending,
                            configChanged ? win.mConfiguration : null);
                    win.mContentInsetsChanged = false;
                    win.mVisibleInsetsChanged = false;
                    win.mSurfaceResized = false;
                } catch (RemoteException e) {
                    win.mOrientationChanging = false;
                }
            } while (i > 0);
            mResizingWindows.clear();
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
                win.destroySurfaceLocked();
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
                token.animation = null;
                token.animating = false;
                if (DEBUG_ADD_REMOVE || DEBUG_TOKEN_MOVEMENT) Slog.v(TAG,
                        "performLayout: App token exiting now removed" + token);
                mAppTokens.remove(token);
                mExitingAppTokens.remove(i);
            }
        }

        boolean needRelayout = false;

        if (!animating && mAppTransitionRunning) {
            // We have finished the animation of an app transition.  To do
            // this, we have delayed a lot of operations like showing and
            // hiding apps, moving apps in Z-order, etc.  The app token list
            // reflects the correct Z-order, but the window list may now
            // be out of sync with it.  So here we will just rebuild the
            // entire app window list.  Fun!
            mAppTransitionRunning = false;
            needRelayout = true;
            rebuildAppWindowListLocked();
            assignLayersLocked();
            // Clear information about apps that were moving.
            mToBottomApps.clear();
        }

        if (focusDisplayed) {
            mH.sendEmptyMessage(H.REPORT_LOSING_FOCUS);
        }
        if (wallpaperDestroyed) {
            needRelayout = adjustWallpaperWindowsLocked() != 0;
        }
        if (needRelayout) {
            requestAnimationLocked(0);
        } else if (animating) {
            requestAnimationLocked(currentTime+(1000/60)-SystemClock.uptimeMillis());
        }

        // Finally update all input windows now that the window changes have stabilized.
        mInputMonitor.updateInputWindowsLw(true /*force*/);

        setHoldScreenLocked(holdScreen != null);
        if (!mDisplayFrozen) {
            if (screenBrightness < 0 || screenBrightness > 1.0f) {
                mPowerManager.setScreenBrightnessOverride(-1);
            } else {
                mPowerManager.setScreenBrightnessOverride((int)
                        (screenBrightness * Power.BRIGHTNESS_ON));
            }
            if (buttonBrightness < 0 || buttonBrightness > 1.0f) {
                mPowerManager.setButtonBrightnessOverride(-1);
            } else {
                mPowerManager.setButtonBrightnessOverride((int)
                        (buttonBrightness * Power.BRIGHTNESS_ON));
            }
        }
        if (holdScreen != mHoldingScreenOn) {
            mHoldingScreenOn = holdScreen;
            Message m = mH.obtainMessage(H.HOLD_SCREEN_CHANGED, holdScreen);
            mH.sendMessage(m);
        }

        if (mTurnOnScreen) {
            if (DEBUG_VISIBILITY) Slog.v(TAG, "Turning screen on after layout!");
            mPowerManager.userActivity(SystemClock.uptimeMillis(), false,
                    LocalPowerManager.BUTTON_EVENT, true);
            mTurnOnScreen = false;
        }
        
        if (screenRotationFinished && mScreenRotationAnimation != null) {
            mScreenRotationAnimation.kill();
            mScreenRotationAnimation = null;
        }

        if (updateRotation) {
            if (DEBUG_ORIENTATION) Slog.d(TAG, "Performing post-rotate rotation");
            boolean changed = setRotationUncheckedLocked(
                    WindowManagerPolicy.USE_LAST_ROTATION, 0, false);
            if (changed) {
                mH.sendEmptyMessage(H.SEND_NEW_CONFIGURATION);
            }
        }
        
        // Check to see if we are now in a state where the screen should
        // be enabled, because the window obscured flags have changed.
        enableScreenIfNeededLocked();
    }
    
    /**
     * Must be called with the main window manager lock held.
     */
    void setHoldScreenLocked(boolean holding) {
        boolean state = mHoldingScreenWakeLock.isHeld();
        if (holding != state) {
            if (holding) {
                mPolicy.screenOnStartedLw();
                mHoldingScreenWakeLock.acquire();
            } else {
                mPolicy.screenOnStoppedLw();
                mHoldingScreenWakeLock.release();
            }
        }
    }

    void requestAnimationLocked(long delay) {
        if (!mAnimationPending) {
            mAnimationPending = true;
            mH.sendMessageDelayed(mH.obtainMessage(H.ANIMATE), delay);
        }
    }

    /**
     * Have the surface flinger show a surface, robustly dealing with
     * error conditions.  In particular, if there is not enough memory
     * to show the surface, then we will try to get rid of other surfaces
     * in order to succeed.
     *
     * @return Returns true if the surface was successfully shown.
     */
    boolean showSurfaceRobustlyLocked(WindowState win) {
        try {
            if (win.mSurface != null) {
                win.mSurfaceShown = true;
                win.mSurface.show();
                if (win.mTurnOnScreen) {
                    if (DEBUG_VISIBILITY) Slog.v(TAG,
                            "Show surface turning screen on: " + win);
                    win.mTurnOnScreen = false;
                    mTurnOnScreen = true;
                }
            }
            return true;
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failure showing surface " + win.mSurface + " in " + win, e);
        }

        reclaimSomeSurfaceMemoryLocked(win, "show", true);

        return false;
    }

    boolean reclaimSomeSurfaceMemoryLocked(WindowState win, String operation, boolean secure) {
        final Surface surface = win.mSurface;
        boolean leakedSurface = false;
        boolean killedApps = false;

        EventLog.writeEvent(EventLogTags.WM_NO_SURFACE_MEMORY, win.toString(),
                win.mSession.mPid, operation);

        if (mForceRemoves == null) {
            mForceRemoves = new ArrayList<WindowState>();
        }

        long callingIdentity = Binder.clearCallingIdentity();
        try {
            // There was some problem...   first, do a sanity check of the
            // window list to make sure we haven't left any dangling surfaces
            // around.
            int N = mWindows.size();
            Slog.i(TAG, "Out of memory for surface!  Looking for leaks...");
            for (int i=0; i<N; i++) {
                WindowState ws = mWindows.get(i);
                if (ws.mSurface != null) {
                    if (!mSessions.contains(ws.mSession)) {
                        Slog.w(TAG, "LEAKED SURFACE (session doesn't exist): "
                                + ws + " surface=" + ws.mSurface
                                + " token=" + win.mToken
                                + " pid=" + ws.mSession.mPid
                                + " uid=" + ws.mSession.mUid);
                        if (SHOW_TRANSACTIONS) logSurface(ws, "LEAK DESTROY", null);
                        ws.mSurface.destroy();
                        ws.mSurfaceShown = false;
                        ws.mSurface = null;
                        mForceRemoves.add(ws);
                        i--;
                        N--;
                        leakedSurface = true;
                    } else if (ws.mAppToken != null && ws.mAppToken.clientHidden) {
                        Slog.w(TAG, "LEAKED SURFACE (app token hidden): "
                                + ws + " surface=" + ws.mSurface
                                + " token=" + win.mAppToken);
                        if (SHOW_TRANSACTIONS) logSurface(ws, "LEAK DESTROY", null);
                        ws.mSurface.destroy();
                        ws.mSurfaceShown = false;
                        ws.mSurface = null;
                        leakedSurface = true;
                    }
                }
            }

            if (!leakedSurface) {
                Slog.w(TAG, "No leaked surfaces; killing applicatons!");
                SparseIntArray pidCandidates = new SparseIntArray();
                for (int i=0; i<N; i++) {
                    WindowState ws = mWindows.get(i);
                    if (ws.mSurface != null) {
                        pidCandidates.append(ws.mSession.mPid, ws.mSession.mPid);
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
                    if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) logSurface(win,
                            "RECOVER DESTROY", null);
                    surface.destroy();
                    win.mSurfaceShown = false;
                    win.mSurface = null;
                }

                try {
                    win.mClient.dispatchGetNewSurface();
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
            // This check makes sure that we don't already have the focus
            // change message pending.
            mH.removeMessages(H.REPORT_FOCUS_CHANGE);
            mH.sendEmptyMessage(H.REPORT_FOCUS_CHANGE);
            if (localLOGV) Slog.v(
                TAG, "Changing focus from " + mCurrentFocus + " to " + newFocus);
            final WindowState oldFocus = mCurrentFocus;
            mCurrentFocus = newFocus;
            mLosingFocus.remove(newFocus);

            final WindowState imWindow = mInputMethodWindow;
            if (newFocus != imWindow && oldFocus != imWindow) {
                if (moveInputMethodWindowsIfNeededLocked(
                        mode != UPDATE_FOCUS_WILL_ASSIGN_LAYERS &&
                        mode != UPDATE_FOCUS_WILL_PLACE_SURFACES)) {
                    mLayoutNeeded = true;
                }
                if (mode == UPDATE_FOCUS_PLACING_SURFACES) {
                    performLayoutLockedInner(true /*initial*/, updateInputWindows);
                } else if (mode == UPDATE_FOCUS_WILL_PLACE_SURFACES) {
                    // Client will do the layout, but we need to assign layers
                    // for handleNewWindowLocked() below.
                    assignLayersLocked();
                }
            }
            
            if (mode != UPDATE_FOCUS_WILL_ASSIGN_LAYERS) {
                // If we defer assigning layers, then the caller is responsible for
                // doing this part.
                finishUpdateFocusedWindowAfterAssignLayersLocked(updateInputWindows);
            }
            return true;
        }
        return false;
    }
    
    private void finishUpdateFocusedWindowAfterAssignLayersLocked(boolean updateInputWindows) {
        mInputMonitor.setInputFocusLw(mCurrentFocus, updateInputWindows);
    }

    private WindowState computeFocusedWindowLocked() {
        WindowState result = null;
        WindowState win;

        int i = mWindows.size() - 1;
        int nextAppIndex = mAppTokens.size()-1;
        WindowToken nextApp = nextAppIndex >= 0
            ? mAppTokens.get(nextAppIndex) : null;

        while (i >= 0) {
            win = mWindows.get(i);

            if (localLOGV || DEBUG_FOCUS) Slog.v(
                TAG, "Looking for focus: " + i
                + " = " + win
                + ", flags=" + win.mAttrs.flags
                + ", canReceive=" + win.canReceiveKeys());

            AppWindowToken thisApp = win.mAppToken;

            // If this window's application has been removed, just skip it.
            if (thisApp != null && thisApp.removed) {
                i--;
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
                result = win;
                break;
            }

            i--;
        }

        return result;
    }

    private void startFreezingDisplayLocked(boolean inTransaction) {
        if (mDisplayFrozen) {
            return;
        }

        mScreenFrozenLock.acquire();

        mDisplayFrozen = true;
        
        mInputMonitor.freezeInputDispatchingLw();
        
        if (mNextAppTransition != WindowManagerPolicy.TRANSIT_UNSET) {
            mNextAppTransition = WindowManagerPolicy.TRANSIT_UNSET;
            mNextAppTransitionPackage = null;
            mAppTransitionReady = true;
        }

        if (PROFILE_ORIENTATION) {
            File file = new File("/data/system/frozen");
            Debug.startMethodTracing(file.toString(), 8 * 1024 * 1024);
        }

        if (CUSTOM_SCREEN_ROTATION) {
            if (mScreenRotationAnimation != null && mScreenRotationAnimation.isAnimating()) {
                mScreenRotationAnimation.kill();
                mScreenRotationAnimation = null;
            }
            if (mScreenRotationAnimation == null) {
                mScreenRotationAnimation = new ScreenRotationAnimation(mContext,
                        mFxSession, inTransaction, mCurDisplayWidth, mCurDisplayHeight,
                        mDisplay.getRotation());
            }
            if (!mScreenRotationAnimation.hasScreenshot()) {
                Surface.freezeDisplay(0);
            }
        } else {
            Surface.freezeDisplay(0);
        }
    }

    private void stopFreezingDisplayLocked() {
        if (!mDisplayFrozen) {
            return;
        }

        if (mWaitingForConfig || mAppsFreezingScreen > 0 || mWindowsFreezingScreen) {
            return;
        }
        
        mDisplayFrozen = false;
        mH.removeMessages(H.APP_FREEZE_TIMEOUT);
        if (PROFILE_ORIENTATION) {
            Debug.stopMethodTracing();
        }

        boolean updateRotation = false;
        
        if (CUSTOM_SCREEN_ROTATION && mScreenRotationAnimation != null
                && mScreenRotationAnimation.hasScreenshot()) {
            if (mScreenRotationAnimation.dismiss(mFxSession, MAX_ANIMATION_DURATION,
                    mTransitionAnimationScale, mCurDisplayWidth, mCurDisplayHeight)) {
                requestAnimationLocked(0);
            } else {
                mScreenRotationAnimation = null;
                updateRotation = true;
            }
        } else {
            if (mScreenRotationAnimation != null) {
                mScreenRotationAnimation.kill();
                mScreenRotationAnimation = null;
            }
            updateRotation = true;
            Surface.unfreezeDisplay(0);
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
            configChanged |= setRotationUncheckedLocked(
                    WindowManagerPolicy.USE_LAST_ROTATION, 0, false);
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

    void createWatermark() {
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
                    mWatermark = new Watermark(mRealDisplayMetrics, mFxSession, toks);
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
        mInputManager.setSystemUiVisibility(visibility);
        synchronized (mWindowMap) {
            final int N = mWindows.size();
            for (int i = 0; i < N; i++) {
                WindowState ws = mWindows.get(i);
                try {
                    if (ws.getAttrs().hasSystemUiListeners) {
                        ws.mClient.dispatchSystemUiVisibilityChanged(visibility);
                    }
                } catch (RemoteException e) {
                    // so sorry
                }
            }
        }
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

        mInputManager.dump(pw);
        pw.println(" ");
        
        synchronized(mWindowMap) {
            pw.println("Current Window Manager state:");
            for (int i=mWindows.size()-1; i>=0; i--) {
                WindowState w = mWindows.get(i);
                pw.print("  Window #"); pw.print(i); pw.print(' ');
                        pw.print(w); pw.println(":");
                w.dump(pw, "    ");
            }
            if (mInputMethodDialogs.size() > 0) {
                pw.println(" ");
                pw.println("  Input method dialogs:");
                for (int i=mInputMethodDialogs.size()-1; i>=0; i--) {
                    WindowState w = mInputMethodDialogs.get(i);
                    pw.print("  IM Dialog #"); pw.print(i); pw.print(": "); pw.println(w);
                }
            }
            if (mPendingRemove.size() > 0) {
                pw.println(" ");
                pw.println("  Remove pending for:");
                for (int i=mPendingRemove.size()-1; i>=0; i--) {
                    WindowState w = mPendingRemove.get(i);
                    pw.print("  Remove #"); pw.print(i); pw.print(' ');
                            pw.print(w); pw.println(":");
                    w.dump(pw, "    ");
                }
            }
            if (mForceRemoves != null && mForceRemoves.size() > 0) {
                pw.println(" ");
                pw.println("  Windows force removing:");
                for (int i=mForceRemoves.size()-1; i>=0; i--) {
                    WindowState w = mForceRemoves.get(i);
                    pw.print("  Removing #"); pw.print(i); pw.print(' ');
                            pw.print(w); pw.println(":");
                    w.dump(pw, "    ");
                }
            }
            if (mDestroySurface.size() > 0) {
                pw.println(" ");
                pw.println("  Windows waiting to destroy their surface:");
                for (int i=mDestroySurface.size()-1; i>=0; i--) {
                    WindowState w = mDestroySurface.get(i);
                    pw.print("  Destroy #"); pw.print(i); pw.print(' ');
                            pw.print(w); pw.println(":");
                    w.dump(pw, "    ");
                }
            }
            if (mLosingFocus.size() > 0) {
                pw.println(" ");
                pw.println("  Windows losing focus:");
                for (int i=mLosingFocus.size()-1; i>=0; i--) {
                    WindowState w = mLosingFocus.get(i);
                    pw.print("  Losing #"); pw.print(i); pw.print(' ');
                            pw.print(w); pw.println(":");
                    w.dump(pw, "    ");
                }
            }
            if (mResizingWindows.size() > 0) {
                pw.println(" ");
                pw.println("  Windows waiting to resize:");
                for (int i=mResizingWindows.size()-1; i>=0; i--) {
                    WindowState w = mResizingWindows.get(i);
                    pw.print("  Resizing #"); pw.print(i); pw.print(' ');
                            pw.print(w); pw.println(":");
                    w.dump(pw, "    ");
                }
            }
            if (mSessions.size() > 0) {
                pw.println(" ");
                pw.println("  All active sessions:");
                Iterator<Session> it = mSessions.iterator();
                while (it.hasNext()) {
                    Session s = it.next();
                    pw.print("  Session "); pw.print(s); pw.println(':');
                    s.dump(pw, "    ");
                }
            }
            if (mTokenMap.size() > 0) {
                pw.println(" ");
                pw.println("  All tokens:");
                Iterator<WindowToken> it = mTokenMap.values().iterator();
                while (it.hasNext()) {
                    WindowToken token = it.next();
                    pw.print("  Token "); pw.print(token.token); pw.println(':');
                    token.dump(pw, "    ");
                }
            }
            if (mWallpaperTokens.size() > 0) {
                pw.println(" ");
                pw.println("  Wallpaper tokens:");
                for (int i=mWallpaperTokens.size()-1; i>=0; i--) {
                    WindowToken token = mWallpaperTokens.get(i);
                    pw.print("  Wallpaper #"); pw.print(i);
                            pw.print(' '); pw.print(token); pw.println(':');
                    token.dump(pw, "    ");
                }
            }
            if (mAppTokens.size() > 0) {
                pw.println(" ");
                pw.println("  Application tokens in Z order:");
                for (int i=mAppTokens.size()-1; i>=0; i--) {
                    pw.print("  App #"); pw.print(i); pw.print(": ");
                            pw.println(mAppTokens.get(i));
                }
            }
            if (mFinishedStarting.size() > 0) {
                pw.println(" ");
                pw.println("  Finishing start of application tokens:");
                for (int i=mFinishedStarting.size()-1; i>=0; i--) {
                    WindowToken token = mFinishedStarting.get(i);
                    pw.print("  Finished Starting #"); pw.print(i);
                            pw.print(' '); pw.print(token); pw.println(':');
                    token.dump(pw, "    ");
                }
            }
            if (mExitingTokens.size() > 0) {
                pw.println(" ");
                pw.println("  Exiting tokens:");
                for (int i=mExitingTokens.size()-1; i>=0; i--) {
                    WindowToken token = mExitingTokens.get(i);
                    pw.print("  Exiting #"); pw.print(i);
                            pw.print(' '); pw.print(token); pw.println(':');
                    token.dump(pw, "    ");
                }
            }
            if (mExitingAppTokens.size() > 0) {
                pw.println(" ");
                pw.println("  Exiting application tokens:");
                for (int i=mExitingAppTokens.size()-1; i>=0; i--) {
                    WindowToken token = mExitingAppTokens.get(i);
                    pw.print("  Exiting App #"); pw.print(i);
                            pw.print(' '); pw.print(token); pw.println(':');
                    token.dump(pw, "    ");
                }
            }
            pw.println(" ");
            pw.print("  mCurrentFocus="); pw.println(mCurrentFocus);
            pw.print("  mLastFocus="); pw.println(mLastFocus);
            pw.print("  mFocusedApp="); pw.println(mFocusedApp);
            pw.print("  mInputMethodTarget="); pw.println(mInputMethodTarget);
            pw.print("  mInputMethodWindow="); pw.println(mInputMethodWindow);
            pw.print("  mWallpaperTarget="); pw.println(mWallpaperTarget);
            if (mLowerWallpaperTarget != null && mUpperWallpaperTarget != null) {
                pw.print("  mLowerWallpaperTarget="); pw.println(mLowerWallpaperTarget);
                pw.print("  mUpperWallpaperTarget="); pw.println(mUpperWallpaperTarget);
            }
            if (mWindowDetachedWallpaper != null) {
                pw.print("  mWindowDetachedWallpaper="); pw.println(mWindowDetachedWallpaper);
            }
            if (mWindowAnimationBackgroundSurface != null) {
                pw.println("  mWindowAnimationBackgroundSurface:");
                mWindowAnimationBackgroundSurface.printTo("    ", pw);
            }
            pw.print("  mCurConfiguration="); pw.println(this.mCurConfiguration);
            pw.print("  mInTouchMode="); pw.print(mInTouchMode);
                    pw.print(" mLayoutSeq="); pw.println(mLayoutSeq);
            pw.print("  mSystemBooted="); pw.print(mSystemBooted);
                    pw.print(" mDisplayEnabled="); pw.println(mDisplayEnabled);
            pw.print("  mLayoutNeeded="); pw.print(mLayoutNeeded);
                    pw.print(" mBlurShown="); pw.println(mBlurShown);
            if (mDimAnimator != null) {
                pw.println("  mDimAnimator:");
                mDimAnimator.printTo("    ", pw);
            } else {
                pw.println( "  no DimAnimator ");
            }
            pw.print("  mInputMethodAnimLayerAdjustment=");
                    pw.print(mInputMethodAnimLayerAdjustment);
                    pw.print("  mWallpaperAnimLayerAdjustment=");
                    pw.println(mWallpaperAnimLayerAdjustment);
            pw.print("  mLastWallpaperX="); pw.print(mLastWallpaperX);
                    pw.print(" mLastWallpaperY="); pw.println(mLastWallpaperY);
            pw.print("  mDisplayFrozen="); pw.print(mDisplayFrozen);
                    pw.print(" mWindowsFreezingScreen="); pw.print(mWindowsFreezingScreen);
                    pw.print(" mAppsFreezingScreen="); pw.print(mAppsFreezingScreen);
                    pw.print(" mWaitingForConfig="); pw.println(mWaitingForConfig);
            pw.print("  mRotation="); pw.print(mRotation);
                    pw.print(" mRequestedRotation="); pw.print(mRequestedRotation);
                    pw.print(" mAltOrientation="); pw.println(mAltOrientation);
            pw.print("  mLastWindowForcedOrientation"); pw.print(mLastWindowForcedOrientation);
                    pw.print(" mForcedAppOrientation="); pw.println(mForcedAppOrientation);
            pw.print("  mDeferredRotation="); pw.print(mDeferredRotation);
                    pw.print(", mDeferredRotationAnimFlags="); pw.println(mDeferredRotationAnimFlags);
            pw.print("  mAnimationPending="); pw.print(mAnimationPending);
                    pw.print(" mWindowAnimationScale="); pw.print(mWindowAnimationScale);
                    pw.print(" mTransitionWindowAnimationScale="); pw.println(mTransitionAnimationScale);
            pw.print("  mNextAppTransition=0x");
                    pw.print(Integer.toHexString(mNextAppTransition));
                    pw.print(" mAppTransitionReady="); pw.println(mAppTransitionReady);
            pw.print("  mAppTransitionRunning="); pw.print(mAppTransitionRunning);
                    pw.print(" mAppTransitionTimeout="); pw.println( mAppTransitionTimeout);
            if (mNextAppTransitionPackage != null) {
                pw.print("  mNextAppTransitionPackage=");
                    pw.print(mNextAppTransitionPackage);
                    pw.print(" mNextAppTransitionEnter=0x");
                    pw.print(Integer.toHexString(mNextAppTransitionEnter));
                    pw.print(" mNextAppTransitionExit=0x");
                    pw.print(Integer.toHexString(mNextAppTransitionExit));
            }
            pw.print("  mStartingIconInTransition="); pw.print(mStartingIconInTransition);
                    pw.print(", mSkipAppTransitionAnimation="); pw.println(mSkipAppTransitionAnimation);
            if (mOpeningApps.size() > 0) {
                pw.print("  mOpeningApps="); pw.println(mOpeningApps);
            }
            if (mClosingApps.size() > 0) {
                pw.print("  mClosingApps="); pw.println(mClosingApps);
            }
            if (mToTopApps.size() > 0) {
                pw.print("  mToTopApps="); pw.println(mToTopApps);
            }
            if (mToBottomApps.size() > 0) {
                pw.print("  mToBottomApps="); pw.println(mToBottomApps);
            }
            if (mDisplay != null) {
                pw.print("  Display: init="); pw.print(mInitialDisplayWidth); pw.print("x");
                        pw.print(mInitialDisplayHeight); pw.print(" base=");
                        pw.print(mBaseDisplayWidth); pw.print("x"); pw.print(mBaseDisplayHeight);
                        pw.print(" cur=");
                        pw.print(mCurDisplayWidth); pw.print("x"); pw.print(mCurDisplayHeight);
                        pw.print(" app=");
                        pw.print(mAppDisplayWidth); pw.print("x"); pw.print(mAppDisplayHeight);
                        pw.print(" raw="); pw.print(mDisplay.getRawWidth());
                        pw.print("x"); pw.println(mDisplay.getRawHeight());
            } else {
                pw.println("  NO DISPLAY");
            }
            pw.println("  Policy:");
            mPolicy.dump("    ", fd, pw, args);
        }
    }

    // Called by the heartbeat to ensure locks are not held indefnitely (for deadlock detection).
    public void monitor() {
        synchronized (mWindowMap) { }
        synchronized (mKeyguardTokenWatcher) { }
    }

    public interface OnHardKeyboardStatusChangeListener {
        public void onHardKeyboardStatusChange(boolean available, boolean enabled);
    }
}
