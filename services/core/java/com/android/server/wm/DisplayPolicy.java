/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.res.Configuration.UI_MODE_TYPE_CAR;
import static android.content.res.Configuration.UI_MODE_TYPE_MASK;
import static android.view.Display.TYPE_BUILT_IN;
import static android.view.InsetsState.TYPE_TOP_BAR;
import static android.view.InsetsState.TYPE_TOP_GESTURES;
import static android.view.InsetsState.TYPE_TOP_TAPPABLE_ELEMENT;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_NONE;
import static android.view.WindowManager.INPUT_CONSUMER_NAVIGATION;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_ATTACHED_IN_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_STATUS_BAR_VISIBLE_TRANSPARENT;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_INHERIT_TRANSLUCENT_DECOR;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_SCREEN_DECOR;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_BOOT_PROGRESS;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_DREAM;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_CONSUMER;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_SCREENSHOT;
import static android.view.WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManagerGlobal.ADD_OKAY;
import static android.view.WindowManagerPolicyConstants.ACTION_HDMI_PLUGGED;
import static android.view.WindowManagerPolicyConstants.EXTRA_HDMI_PLUGGED_STATE;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_BOTTOM;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_LEFT;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_RIGHT;

import static com.android.server.policy.PhoneWindowManager.TOAST_WINDOW_TIMEOUT;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_ENTER;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_EXIT;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_HIDE;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_PREVIEW_DONE;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_SHOW;
import static com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs.LID_ABSENT;
import static com.android.server.wm.ActivityTaskManagerInternal.SleepToken;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SCREEN_ON;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.Manifest.permission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Px;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.LoadedApk;
import android.app.ResourcesManager;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.hardware.power.V1_0.PowerHint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Pair;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.IApplicationToken;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InsetsState;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerPolicyConstants;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.internal.util.ScreenShapeHelper;
import com.android.internal.util.ScreenshotHelper;
import com.android.internal.util.function.TriConsumer;
import com.android.internal.widget.PointerLocationView;
import com.android.server.LocalServices;
import com.android.server.UiThread;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.policy.WindowManagerPolicy.InputConsumer;
import com.android.server.policy.WindowManagerPolicy.NavigationBarPosition;
import com.android.server.policy.WindowManagerPolicy.ScreenOnListener;
import com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs;
import com.android.server.policy.WindowOrientationListener;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.wallpaper.WallpaperManagerInternal;
import com.android.server.wm.utils.InsetUtils;

import java.io.PrintWriter;

/**
 * The policy that provides the basic behaviors and states of a display to show UI.
 */
public class DisplayPolicy {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "DisplayPolicy" : TAG_WM;
    private static final boolean DEBUG = false;

    private static final boolean ALTERNATE_CAR_MODE_NAV_SIZE = false;

    // The panic gesture may become active only after the keyguard is dismissed and the immersive
    // app shows again. If that doesn't happen for 30s we drop the gesture.
    private static final long PANIC_GESTURE_EXPIRATION = 30000;

    // Controls navigation bar opacity depending on which workspace stacks are currently
    // visible.
    // Nav bar is always opaque when either the freeform stack or docked stack is visible.
    private static final int NAV_BAR_OPAQUE_WHEN_FREEFORM_OR_DOCKED = 0;
    // Nav bar is always translucent when the freeform stack is visible, otherwise always opaque.
    private static final int NAV_BAR_TRANSLUCENT_WHEN_FREEFORM_OPAQUE_OTHERWISE = 1;
    // Nav bar is never forced opaque.
    private static final int NAV_BAR_FORCE_TRANSPARENT = 2;

    /**
     * These are the system UI flags that, when changing, can cause the layout
     * of the screen to change.
     */
    private static final int SYSTEM_UI_CHANGING_LAYOUT =
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.STATUS_BAR_TRANSLUCENT
                    | View.NAVIGATION_BAR_TRANSLUCENT
                    | View.STATUS_BAR_TRANSPARENT
                    | View.NAVIGATION_BAR_TRANSPARENT;

    private final WindowManagerService mService;
    private final Context mContext;
    private final DisplayContent mDisplayContent;
    private final Object mLock;
    private final Handler mHandler;

    private Resources mCurrentUserResources;

    private final boolean mCarDockEnablesAccelerometer;
    private final boolean mDeskDockEnablesAccelerometer;
    private final AccessibilityManager mAccessibilityManager;
    private final ImmersiveModeConfirmation mImmersiveModeConfirmation;
    private final ScreenshotHelper mScreenshotHelper;

    private final Object mServiceAcquireLock = new Object();
    private StatusBarManagerInternal mStatusBarManagerInternal;

    @Px
    private int mBottomGestureAdditionalInset;
    @Px
    private int mSideGestureInset;

    private StatusBarManagerInternal getStatusBarManagerInternal() {
        synchronized (mServiceAcquireLock) {
            if (mStatusBarManagerInternal == null) {
                mStatusBarManagerInternal =
                        LocalServices.getService(StatusBarManagerInternal.class);
            }
            return mStatusBarManagerInternal;
        }
    }

    private final SystemGesturesPointerEventListener mSystemGestures;

    private volatile int mLidState = LID_ABSENT;
    private volatile int mDockMode = Intent.EXTRA_DOCK_STATE_UNDOCKED;
    private volatile boolean mHdmiPlugged;

    private volatile boolean mHasStatusBar;
    private volatile boolean mHasNavigationBar;
    // Can the navigation bar ever move to the side?
    private volatile boolean mNavigationBarCanMove;
    private volatile boolean mNavigationBarLetsThroughTaps;
    private volatile boolean mNavigationBarAlwaysShowOnSideGesture;

    // Written by vr manager thread, only read in this class.
    private volatile boolean mPersistentVrModeEnabled;

    private volatile boolean mAwake;
    private volatile boolean mScreenOnEarly;
    private volatile boolean mScreenOnFully;
    private volatile ScreenOnListener mScreenOnListener;

    private volatile boolean mKeyguardDrawComplete;
    private volatile boolean mWindowManagerDrawComplete;

    private final ArraySet<WindowState> mScreenDecorWindows = new ArraySet<>();
    private WindowState mStatusBar = null;
    private final int[] mStatusBarHeightForRotation = new int[4];
    private WindowState mNavigationBar = null;
    @NavigationBarPosition
    private int mNavigationBarPosition = NAV_BAR_BOTTOM;
    private int[] mNavigationBarHeightForRotationDefault = new int[4];
    private int[] mNavigationBarWidthForRotationDefault = new int[4];
    private int[] mNavigationBarHeightForRotationInCarMode = new int[4];
    private int[] mNavigationBarWidthForRotationInCarMode = new int[4];

    /** See {@link #getNavigationBarFrameHeight} */
    private int[] mNavigationBarFrameHeightForRotationDefault = new int[4];

    private boolean mIsFreeformWindowOverlappingWithNavBar;

    /** Cached value of {@link ScreenShapeHelper#getWindowOutsetBottomPx} */
    @Px private int mWindowOutsetBottom;

    private final StatusBarController mStatusBarController;

    private final BarController mNavigationBarController;

    private final BarController.OnBarVisibilityChangedListener mNavBarVisibilityListener =
            new BarController.OnBarVisibilityChangedListener() {
                @Override
                public void onBarVisibilityChanged(boolean visible) {
                    if (mAccessibilityManager == null) {
                        return;
                    }
                    mAccessibilityManager.notifyAccessibilityButtonVisibilityChanged(visible);
                }
            };

    @GuardedBy("mHandler")
    private SleepToken mDreamingSleepToken;

    @GuardedBy("mHandler")
    private SleepToken mWindowSleepToken;

    private final Runnable mAcquireSleepTokenRunnable;
    private final Runnable mReleaseSleepTokenRunnable;

    // The windows we were told about in focusChanged.
    private WindowState mFocusedWindow;
    private WindowState mLastFocusedWindow;

    IApplicationToken mFocusedApp;

    int mLastSystemUiFlags;
    // Bits that we are in the process of clearing, so we want to prevent
    // them from being set by applications until everything has been updated
    // to have them clear.
    private int mResettingSystemUiFlags = 0;
    // Bits that we are currently always keeping cleared.
    private int mForceClearedSystemUiFlags = 0;
    private int mLastFullscreenStackSysUiFlags;
    private int mLastDockedStackSysUiFlags;
    private final Rect mNonDockedStackBounds = new Rect();
    private final Rect mDockedStackBounds = new Rect();
    private final Rect mLastNonDockedStackBounds = new Rect();
    private final Rect mLastDockedStackBounds = new Rect();

    // What we last reported to system UI about whether the compatibility
    // menu needs to be displayed.
    private boolean mLastFocusNeedsMenu = false;
    // If nonzero, a panic gesture was performed at that time in uptime millis and is still pending.
    private long mPendingPanicGestureUptime;

    private static final Rect sTmpDisplayCutoutSafeExceptMaybeBarsRect = new Rect();
    private static final Rect sTmpRect = new Rect();
    private static final Rect sTmpDockedFrame = new Rect();
    private static final Rect sTmpNavFrame = new Rect();
    private static final Rect sTmpLastParentFrame = new Rect();

    private WindowState mTopFullscreenOpaqueWindowState;
    private WindowState mTopFullscreenOpaqueOrDimmingWindowState;
    private WindowState mTopDockedOpaqueWindowState;
    private WindowState mTopDockedOpaqueOrDimmingWindowState;
    private boolean mTopIsFullscreen;
    private boolean mForceStatusBar;
    private boolean mForceStatusBarFromKeyguard;
    private boolean mForceStatusBarTransparent;
    private int mNavBarOpacityMode = NAV_BAR_OPAQUE_WHEN_FREEFORM_OR_DOCKED;
    private boolean mForcingShowNavBar;
    private int mForcingShowNavBarLayer;
    private boolean mForceShowSystemBars;

    /**
     * Force the display of system bars regardless of other settings.
     */
    private boolean mForceShowSystemBarsFromExternal;

    private boolean mShowingDream;
    private boolean mLastShowingDream;
    private boolean mDreamingLockscreen;
    private boolean mDreamingSleepTokenNeeded;
    private boolean mWindowSleepTokenNeeded;
    private boolean mLastWindowSleepTokenNeeded;
    private boolean mAllowLockscreenWhenOn;

    private InputConsumer mInputConsumer = null;

    private PointerLocationView mPointerLocationView;

    /**
     * The area covered by system windows which belong to another display. Forwarded insets is set
     * in case this is a virtual display, this is displayed on another display that has insets, and
     * the bounds of this display is overlapping with the insets of the host display (e.g. IME is
     * displayed on the host display, and it covers a part of this virtual display.)
     * The forwarded insets is used to compute display frames of this virtual display, which will
     * be then used to layout windows in the virtual display.
     */
    @NonNull private Insets mForwardedInsets = Insets.NONE;

    private RefreshRatePolicy mRefreshRatePolicy;

    // -------- PolicyHandler --------
    private static final int MSG_UPDATE_DREAMING_SLEEP_TOKEN = 1;
    private static final int MSG_REQUEST_TRANSIENT_BARS = 2;
    private static final int MSG_DISPOSE_INPUT_CONSUMER = 3;
    private static final int MSG_ENABLE_POINTER_LOCATION = 4;
    private static final int MSG_DISABLE_POINTER_LOCATION = 5;

    private static final int MSG_REQUEST_TRANSIENT_BARS_ARG_STATUS = 0;
    private static final int MSG_REQUEST_TRANSIENT_BARS_ARG_NAVIGATION = 1;

    private class PolicyHandler extends Handler {

        PolicyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_DREAMING_SLEEP_TOKEN:
                    updateDreamingSleepToken(msg.arg1 != 0);
                    break;
                case MSG_REQUEST_TRANSIENT_BARS:
                    WindowState targetBar = (msg.arg1 == MSG_REQUEST_TRANSIENT_BARS_ARG_STATUS)
                            ? mStatusBar : mNavigationBar;
                    if (targetBar != null) {
                        requestTransientBars(targetBar);
                    }
                    break;
                case MSG_DISPOSE_INPUT_CONSUMER:
                    disposeInputConsumer((InputConsumer) msg.obj);
                    break;
                case MSG_ENABLE_POINTER_LOCATION:
                    enablePointerLocation();
                    break;
                case MSG_DISABLE_POINTER_LOCATION:
                    disablePointerLocation();
                    break;
            }
        }
    }

    DisplayPolicy(WindowManagerService service, DisplayContent displayContent) {
        mService = service;
        mContext = displayContent.isDefaultDisplay ? service.mContext
                : service.mContext.createDisplayContext(displayContent.getDisplay());
        mDisplayContent = displayContent;
        mLock = service.getWindowManagerLock();

        final int displayId = displayContent.getDisplayId();
        mStatusBarController = new StatusBarController(displayId);
        mNavigationBarController = new BarController("NavigationBar",
                displayId,
                View.NAVIGATION_BAR_TRANSIENT,
                View.NAVIGATION_BAR_UNHIDE,
                View.NAVIGATION_BAR_TRANSLUCENT,
                StatusBarManager.WINDOW_NAVIGATION_BAR,
                FLAG_TRANSLUCENT_NAVIGATION,
                View.NAVIGATION_BAR_TRANSPARENT);

        final Resources r = mContext.getResources();
        mCarDockEnablesAccelerometer = r.getBoolean(R.bool.config_carDockEnablesAccelerometer);
        mDeskDockEnablesAccelerometer = r.getBoolean(R.bool.config_deskDockEnablesAccelerometer);
        mForceShowSystemBarsFromExternal = r.getBoolean(R.bool.config_forceShowSystemBars);

        mAccessibilityManager = (AccessibilityManager) mContext.getSystemService(
                Context.ACCESSIBILITY_SERVICE);
        if (!displayContent.isDefaultDisplay) {
            mAwake = true;
            mScreenOnEarly = true;
            mScreenOnFully = true;
        }

        final Looper looper = UiThread.getHandler().getLooper();
        mHandler = new PolicyHandler(looper);
        mSystemGestures = new SystemGesturesPointerEventListener(mContext, mHandler,
                new SystemGesturesPointerEventListener.Callbacks() {
                    @Override
                    public void onSwipeFromTop() {
                        if (mStatusBar != null) {
                            requestTransientBars(mStatusBar);
                        }
                    }

                    @Override
                    public void onSwipeFromBottom() {
                        if (mNavigationBar != null && mNavigationBarPosition == NAV_BAR_BOTTOM) {
                            requestTransientBars(mNavigationBar);
                        }
                    }

                    @Override
                    public void onSwipeFromRight() {
                        final Region excludedRegion = Region.obtain();
                        synchronized (mLock) {
                            mDisplayContent.calculateSystemGestureExclusion(
                                    excludedRegion, null /* outUnrestricted */);
                        }
                        final boolean sideAllowed = mNavigationBarAlwaysShowOnSideGesture
                                || mNavigationBarPosition == NAV_BAR_RIGHT;
                        if (mNavigationBar != null && sideAllowed
                                && !mSystemGestures.currentGestureStartedInRegion(excludedRegion)) {
                            requestTransientBars(mNavigationBar);
                        }
                        excludedRegion.recycle();
                    }

                    @Override
                    public void onSwipeFromLeft() {
                        final Region excludedRegion = Region.obtain();
                        synchronized (mLock) {
                            mDisplayContent.calculateSystemGestureExclusion(
                                    excludedRegion, null /* outUnrestricted */);
                        }
                        final boolean sideAllowed = mNavigationBarAlwaysShowOnSideGesture
                                || mNavigationBarPosition == NAV_BAR_LEFT;
                        if (mNavigationBar != null && sideAllowed
                                && !mSystemGestures.currentGestureStartedInRegion(excludedRegion)) {
                            requestTransientBars(mNavigationBar);
                        }
                        excludedRegion.recycle();
                    }

                    @Override
                    public void onFling(int duration) {
                        if (mService.mPowerManagerInternal != null) {
                            mService.mPowerManagerInternal.powerHint(
                                    PowerHint.INTERACTION, duration);
                        }
                    }

                    @Override
                    public void onDebug() {
                        // no-op
                    }

                    private WindowOrientationListener getOrientationListener() {
                        final DisplayRotation rotation = mDisplayContent.getDisplayRotation();
                        return rotation != null ? rotation.getOrientationListener() : null;
                    }

                    @Override
                    public void onDown() {
                        final WindowOrientationListener listener = getOrientationListener();
                        if (listener != null) {
                            listener.onTouchStart();
                        }
                    }

                    @Override
                    public void onUpOrCancel() {
                        final WindowOrientationListener listener = getOrientationListener();
                        if (listener != null) {
                            listener.onTouchEnd();
                        }
                    }

                    @Override
                    public void onMouseHoverAtTop() {
                        mHandler.removeMessages(MSG_REQUEST_TRANSIENT_BARS);
                        Message msg = mHandler.obtainMessage(MSG_REQUEST_TRANSIENT_BARS);
                        msg.arg1 = MSG_REQUEST_TRANSIENT_BARS_ARG_STATUS;
                        mHandler.sendMessageDelayed(msg, 500 /* delayMillis */);
                    }

                    @Override
                    public void onMouseHoverAtBottom() {
                        mHandler.removeMessages(MSG_REQUEST_TRANSIENT_BARS);
                        Message msg = mHandler.obtainMessage(MSG_REQUEST_TRANSIENT_BARS);
                        msg.arg1 = MSG_REQUEST_TRANSIENT_BARS_ARG_NAVIGATION;
                        mHandler.sendMessageDelayed(msg, 500 /* delayMillis */);
                    }

                    @Override
                    public void onMouseLeaveFromEdge() {
                        mHandler.removeMessages(MSG_REQUEST_TRANSIENT_BARS);
                    }
                });
        displayContent.registerPointerEventListener(mSystemGestures);
        displayContent.mAppTransition.registerListenerLocked(
                mStatusBarController.getAppTransitionListener());
        mImmersiveModeConfirmation = new ImmersiveModeConfirmation(mContext, looper,
                mService.mVrModeEnabled);
        mAcquireSleepTokenRunnable = () -> {
            if (mWindowSleepToken != null) {
                return;
            }
            mWindowSleepToken = service.mAtmInternal.acquireSleepToken(
                    "WindowSleepTokenOnDisplay" + displayId, displayId);
        };
        mReleaseSleepTokenRunnable = () -> {
            if (mWindowSleepToken == null) {
                return;
            }
            mWindowSleepToken.release();
            mWindowSleepToken = null;
        };

        // TODO: Make it can take screenshot on external display
        mScreenshotHelper = displayContent.isDefaultDisplay
                ? new ScreenshotHelper(mContext) : null;

        if (mDisplayContent.isDefaultDisplay) {
            mHasStatusBar = true;
            mHasNavigationBar = mContext.getResources().getBoolean(R.bool.config_showNavigationBar);

            // Allow a system property to override this. Used by the emulator.
            // See also hasNavigationBar().
            String navBarOverride = SystemProperties.get("qemu.hw.mainkeys");
            if ("1".equals(navBarOverride)) {
                mHasNavigationBar = false;
            } else if ("0".equals(navBarOverride)) {
                mHasNavigationBar = true;
            }
        } else {
            mHasStatusBar = false;
            mHasNavigationBar = mDisplayContent.supportsSystemDecorations();
        }

        mRefreshRatePolicy = new RefreshRatePolicy(mService,
                mDisplayContent.getDisplayInfo(),
                mService.mHighRefreshRateBlacklist);
    }

    void systemReady() {
        mSystemGestures.systemReady();
        if (mService.mPointerLocationEnabled) {
            setPointerLocationEnabled(true);
        }
    }

    private int getDisplayId() {
        return mDisplayContent.getDisplayId();
    }

    public void setHdmiPlugged(boolean plugged) {
        setHdmiPlugged(plugged, false /* force */);
    }

    public void setHdmiPlugged(boolean plugged, boolean force) {
        if (force || mHdmiPlugged != plugged) {
            mHdmiPlugged = plugged;
            mService.updateRotation(true /* alwaysSendConfiguration */, true /* forceRelayout */);
            final Intent intent = new Intent(ACTION_HDMI_PLUGGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(EXTRA_HDMI_PLUGGED_STATE, plugged);
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    boolean isHdmiPlugged() {
        return mHdmiPlugged;
    }

    boolean isCarDockEnablesAccelerometer() {
        return mCarDockEnablesAccelerometer;
    }

    boolean isDeskDockEnablesAccelerometer() {
        return mDeskDockEnablesAccelerometer;
    }

    public void setPersistentVrModeEnabled(boolean persistentVrModeEnabled) {
        mPersistentVrModeEnabled = persistentVrModeEnabled;
    }

    public boolean isPersistentVrModeEnabled() {
        return mPersistentVrModeEnabled;
    }

    public void setDockMode(int dockMode) {
        mDockMode = dockMode;
    }

    public int getDockMode() {
        return mDockMode;
    }

    /**
     * @see WindowManagerService.setForceShowSystemBars
     */
    void setForceShowSystemBars(boolean forceShowSystemBars) {
        mForceShowSystemBarsFromExternal = forceShowSystemBars;
    }

    public boolean hasNavigationBar() {
        return mHasNavigationBar;
    }

    public boolean hasStatusBar() {
        return mHasStatusBar;
    }

    boolean hasSideGestures() {
        return mHasNavigationBar && mSideGestureInset > 0;
    }

    public boolean navigationBarCanMove() {
        return mNavigationBarCanMove;
    }

    public void setLidState(int lidState) {
        mLidState = lidState;
    }

    public int getLidState() {
        return mLidState;
    }

    public void setAwake(boolean awake) {
        mAwake = awake;
    }

    public boolean isAwake() {
        return mAwake;
    }

    public boolean isScreenOnEarly() {
        return mScreenOnEarly;
    }

    public boolean isScreenOnFully() {
        return mScreenOnFully;
    }

    public boolean isKeyguardDrawComplete() {
        return mKeyguardDrawComplete;
    }

    public boolean isWindowManagerDrawComplete() {
        return mWindowManagerDrawComplete;
    }

    public ScreenOnListener getScreenOnListener() {
        return mScreenOnListener;
    }

    public void screenTurnedOn(ScreenOnListener screenOnListener) {
        synchronized (mLock) {
            mScreenOnEarly = true;
            mScreenOnFully = false;
            mKeyguardDrawComplete = false;
            mWindowManagerDrawComplete = false;
            mScreenOnListener = screenOnListener;
        }
    }

    public void screenTurnedOff() {
        synchronized (mLock) {
            mScreenOnEarly = false;
            mScreenOnFully = false;
            mKeyguardDrawComplete = false;
            mWindowManagerDrawComplete = false;
            mScreenOnListener = null;
        }
    }

    /** Return false if we are not awake yet or we have already informed of this event. */
    public boolean finishKeyguardDrawn() {
        synchronized (mLock) {
            if (!mScreenOnEarly || mKeyguardDrawComplete) {
                return false;
            }

            mKeyguardDrawComplete = true;
            mWindowManagerDrawComplete = false;
        }
        return true;
    }

    /** Return false if screen is not turned on or we did already handle this case earlier. */
    public boolean finishWindowsDrawn() {
        synchronized (mLock) {
            if (!mScreenOnEarly || mWindowManagerDrawComplete) {
                return false;
            }

            mWindowManagerDrawComplete = true;
        }
        return true;
    }

    /** Return false if it is not ready to turn on. */
    public boolean finishScreenTurningOn() {
        synchronized (mLock) {
            if (DEBUG_SCREEN_ON) Slog.d(TAG,
                    "finishScreenTurningOn: mAwake=" + mAwake
                            + ", mScreenOnEarly=" + mScreenOnEarly
                            + ", mScreenOnFully=" + mScreenOnFully
                            + ", mKeyguardDrawComplete=" + mKeyguardDrawComplete
                            + ", mWindowManagerDrawComplete=" + mWindowManagerDrawComplete);

            if (mScreenOnFully || !mScreenOnEarly || !mWindowManagerDrawComplete
                    || (mAwake && !mKeyguardDrawComplete)) {
                return false;
            }

            if (DEBUG_SCREEN_ON) Slog.i(TAG, "Finished screen turning on...");
            mScreenOnListener = null;
            mScreenOnFully = true;
        }
        return true;
    }

    private boolean hasStatusBarServicePermission(int pid, int uid) {
        return mContext.checkPermission(permission.STATUS_BAR_SERVICE, pid, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Sanitize the layout parameters coming from a client.  Allows the policy
     * to do things like ensure that windows of a specific type can't take
     * input focus.
     *
     * @param attrs The window layout parameters to be modified.  These values
     * are modified in-place.
     */
    public void adjustWindowParamsLw(WindowState win, WindowManager.LayoutParams attrs,
            int callingPid, int callingUid) {

        final boolean isScreenDecor = (attrs.privateFlags & PRIVATE_FLAG_IS_SCREEN_DECOR) != 0;
        if (mScreenDecorWindows.contains(win)) {
            if (!isScreenDecor) {
                // No longer has the flag set, so remove from the set.
                mScreenDecorWindows.remove(win);
            }
        } else if (isScreenDecor && hasStatusBarServicePermission(callingPid, callingUid)) {
            mScreenDecorWindows.add(win);
        }

        switch (attrs.type) {
            case TYPE_SYSTEM_OVERLAY:
            case TYPE_SECURE_SYSTEM_OVERLAY:
                // These types of windows can't receive input events.
                attrs.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                attrs.flags &= ~WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
                break;
            case TYPE_DREAM:
            case TYPE_WALLPAPER:
                // Dreams and wallpapers don't have an app window token and can thus not be
                // letterboxed. Hence always let them extend under the cutout.
                attrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                break;
            case TYPE_STATUS_BAR:

                // If the Keyguard is in a hidden state (occluded by another window), we force to
                // remove the wallpaper and keyguard flag so that any change in-flight after setting
                // the keyguard as occluded wouldn't set these flags again.
                // See {@link #processKeyguardSetHiddenResultLw}.
                if (mService.mPolicy.isKeyguardOccluded()) {
                    attrs.flags &= ~WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
                    attrs.privateFlags &= ~WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
                }
                break;

            case TYPE_SCREENSHOT:
                attrs.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                break;

            case TYPE_TOAST:
                // While apps should use the dedicated toast APIs to add such windows
                // it possible legacy apps to add the window directly. Therefore, we
                // make windows added directly by the app behave as a toast as much
                // as possible in terms of timeout and animation.
                if (attrs.hideTimeoutMilliseconds < 0
                        || attrs.hideTimeoutMilliseconds > TOAST_WINDOW_TIMEOUT) {
                    attrs.hideTimeoutMilliseconds = TOAST_WINDOW_TIMEOUT;
                }
                // Accessibility users may need longer timeout duration. This api compares
                // original timeout with user's preference and return longer one. It returns
                // original timeout if there's no preference.
                attrs.hideTimeoutMilliseconds = mAccessibilityManager.getRecommendedTimeoutMillis(
                        (int) attrs.hideTimeoutMilliseconds,
                        AccessibilityManager.FLAG_CONTENT_TEXT);
                attrs.windowAnimations = com.android.internal.R.style.Animation_Toast;
                // Toast can show with below conditions when the screen is locked.
                if (canToastShowWhenLocked(callingPid)) {
                    attrs.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
                }
                break;
        }

        if (attrs.type != TYPE_STATUS_BAR) {
            // The status bar is the only window allowed to exhibit keyguard behavior.
            attrs.privateFlags &= ~WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
        }
    }

    /**
     * @return {@code true} if the calling activity initiate toast and is visible with
     * {@link WindowManager.LayoutParams#FLAG_SHOW_WHEN_LOCKED} flag.
     */
    boolean canToastShowWhenLocked(int callingPid) {
        return mDisplayContent.forAllWindows(w -> {
            return callingPid == w.mSession.mPid && w.isVisible() && w.canShowWhenLocked();
        }, true /* traverseTopToBottom */);
    }

    /**
     * Check if a window can be added to the system.
     *
     * Currently enforces that two window types are singletons per display:
     * <ul>
     * <li>{@link WindowManager.LayoutParams#TYPE_STATUS_BAR}</li>
     * <li>{@link WindowManager.LayoutParams#TYPE_NAVIGATION_BAR}</li>
     * </ul>
     *
     * @param attrs Information about the window to be added.
     *
     * @return If ok, WindowManagerImpl.ADD_OKAY.  If too many singletons,
     * WindowManagerImpl.ADD_MULTIPLE_SINGLETON
     */
    int validateAddingWindowLw(WindowManager.LayoutParams attrs) {
        if ((attrs.privateFlags & PRIVATE_FLAG_IS_SCREEN_DECOR) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.STATUS_BAR_SERVICE,
                    "DisplayPolicy");
        }

        switch (attrs.type) {
            case TYPE_STATUS_BAR:
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.STATUS_BAR_SERVICE,
                        "DisplayPolicy");
                if (mStatusBar != null) {
                    if (mStatusBar.isAlive()) {
                        return WindowManagerGlobal.ADD_MULTIPLE_SINGLETON;
                    }
                }
                break;
            case TYPE_NAVIGATION_BAR:
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.STATUS_BAR_SERVICE,
                        "DisplayPolicy");
                if (mNavigationBar != null) {
                    if (mNavigationBar.isAlive()) {
                        return WindowManagerGlobal.ADD_MULTIPLE_SINGLETON;
                    }
                }
                break;
            case TYPE_NAVIGATION_BAR_PANEL:
            case TYPE_STATUS_BAR_PANEL:
            case TYPE_STATUS_BAR_SUB_PANEL:
            case TYPE_VOICE_INTERACTION_STARTING:
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.STATUS_BAR_SERVICE,
                        "DisplayPolicy");
                break;
        }
        return ADD_OKAY;
    }

    /**
     * Called when a window is being added to the system.  Must not throw an exception.
     *
     * @param win The window being added.
     * @param attrs Information about the window to be added.
     */
    void addWindowLw(WindowState win, WindowManager.LayoutParams attrs) {
        if ((attrs.privateFlags & PRIVATE_FLAG_IS_SCREEN_DECOR) != 0) {
            mScreenDecorWindows.add(win);
        }

        switch (attrs.type) {
            case TYPE_STATUS_BAR:
                mStatusBar = win;
                mStatusBarController.setWindow(win);
                if (mDisplayContent.isDefaultDisplay) {
                    mService.mPolicy.setKeyguardCandidateLw(win);
                }
                final TriConsumer<DisplayFrames, WindowState, Rect> frameProvider =
                        (displayFrames, windowState, rect) -> {
                            rect.top = 0;
                            rect.bottom = getStatusBarHeight(displayFrames);
                        };
                mDisplayContent.setInsetProvider(TYPE_TOP_BAR, win, frameProvider);
                mDisplayContent.setInsetProvider(TYPE_TOP_GESTURES, win, frameProvider);
                mDisplayContent.setInsetProvider(TYPE_TOP_TAPPABLE_ELEMENT, win, frameProvider);
                break;
            case TYPE_NAVIGATION_BAR:
                mNavigationBar = win;
                mNavigationBarController.setWindow(win);
                mNavigationBarController.setOnBarVisibilityChangedListener(
                        mNavBarVisibilityListener, true);
                mDisplayContent.setInsetProvider(InsetsState.TYPE_NAVIGATION_BAR,
                        win, null /* frameProvider */);
                mDisplayContent.setInsetProvider(InsetsState.TYPE_BOTTOM_GESTURES, win,
                        (displayFrames, windowState, inOutFrame) -> {
                            inOutFrame.top -= mBottomGestureAdditionalInset;
                        });
                mDisplayContent.setInsetProvider(InsetsState.TYPE_LEFT_GESTURES, win,
                        (displayFrames, windowState, inOutFrame) -> {
                            inOutFrame.left = 0;
                            inOutFrame.top = 0;
                            inOutFrame.bottom = displayFrames.mDisplayHeight;
                            inOutFrame.right = displayFrames.mUnrestricted.left + mSideGestureInset;
                        });
                mDisplayContent.setInsetProvider(InsetsState.TYPE_RIGHT_GESTURES, win,
                        (displayFrames, windowState, inOutFrame) -> {
                            inOutFrame.left = displayFrames.mUnrestricted.right - mSideGestureInset;
                            inOutFrame.top = 0;
                            inOutFrame.bottom = displayFrames.mDisplayHeight;
                            inOutFrame.right = displayFrames.mDisplayWidth;
                        });
                mDisplayContent.setInsetProvider(InsetsState.TYPE_BOTTOM_TAPPABLE_ELEMENT, win,
                        (displayFrames, windowState, inOutFrame) -> {
                            if ((windowState.getAttrs().flags & FLAG_NOT_TOUCHABLE) != 0
                                    || mNavigationBarLetsThroughTaps) {
                                inOutFrame.setEmpty();
                            }
                        });
                if (DEBUG_LAYOUT) Slog.i(TAG, "NAVIGATION BAR: " + mNavigationBar);
                break;
        }
    }

    /**
     * Called when a window is being removed from a window manager.  Must not
     * throw an exception -- clean up as much as possible.
     *
     * @param win The window being removed.
     */
    void removeWindowLw(WindowState win) {
        if (mStatusBar == win) {
            mStatusBar = null;
            mStatusBarController.setWindow(null);
            if (mDisplayContent.isDefaultDisplay) {
                mService.mPolicy.setKeyguardCandidateLw(null);
            }
            mDisplayContent.setInsetProvider(TYPE_TOP_BAR, null, null);
        } else if (mNavigationBar == win) {
            mNavigationBar = null;
            mNavigationBarController.setWindow(null);
            mDisplayContent.setInsetProvider(InsetsState.TYPE_NAVIGATION_BAR, null, null);
        }
        if (mLastFocusedWindow == win) {
            mLastFocusedWindow = null;
        }
        mScreenDecorWindows.remove(win);
    }

    private int getStatusBarHeight(DisplayFrames displayFrames) {
        return Math.max(mStatusBarHeightForRotation[displayFrames.mRotation],
                displayFrames.mDisplayCutoutSafe.top);
    }

    WindowState getStatusBar() {
        return mStatusBar;
    }

    WindowState getNavigationBar() {
        return mNavigationBar;
    }

    /**
     * Control the animation to run when a window's state changes.  Return a
     * non-0 number to force the animation to a specific resource ID, or 0
     * to use the default animation.
     *
     * @param win The window that is changing.
     * @param transit What is happening to the window:
     *                {@link com.android.server.policy.WindowManagerPolicy#TRANSIT_ENTER},
     *                {@link com.android.server.policy.WindowManagerPolicy#TRANSIT_EXIT},
     *                {@link com.android.server.policy.WindowManagerPolicy#TRANSIT_SHOW}, or
     *                {@link com.android.server.policy.WindowManagerPolicy#TRANSIT_HIDE}.
     *
     * @return Resource ID of the actual animation to use, or 0 for none.
     */
    public int selectAnimationLw(WindowState win, int transit) {
        if (DEBUG_ANIM) Slog.i(TAG, "selectAnimation in " + win
                + ": transit=" + transit);
        if (win == mStatusBar) {
            final boolean isKeyguard = (win.getAttrs().privateFlags & PRIVATE_FLAG_KEYGUARD) != 0;
            final boolean expanded = win.getAttrs().height == MATCH_PARENT
                    && win.getAttrs().width == MATCH_PARENT;
            if (isKeyguard || expanded) {
                return -1;
            }
            if (transit == TRANSIT_EXIT
                    || transit == TRANSIT_HIDE) {
                return R.anim.dock_top_exit;
            } else if (transit == TRANSIT_ENTER
                    || transit == TRANSIT_SHOW) {
                return R.anim.dock_top_enter;
            }
        } else if (win == mNavigationBar) {
            if (win.getAttrs().windowAnimations != 0) {
                return 0;
            }
            // This can be on either the bottom or the right or the left.
            if (mNavigationBarPosition == NAV_BAR_BOTTOM) {
                if (transit == TRANSIT_EXIT
                        || transit == TRANSIT_HIDE) {
                    if (mService.mPolicy.isKeyguardShowingAndNotOccluded()) {
                        return R.anim.dock_bottom_exit_keyguard;
                    } else {
                        return R.anim.dock_bottom_exit;
                    }
                } else if (transit == TRANSIT_ENTER
                        || transit == TRANSIT_SHOW) {
                    return R.anim.dock_bottom_enter;
                }
            } else if (mNavigationBarPosition == NAV_BAR_RIGHT) {
                if (transit == TRANSIT_EXIT
                        || transit == TRANSIT_HIDE) {
                    return R.anim.dock_right_exit;
                } else if (transit == TRANSIT_ENTER
                        || transit == TRANSIT_SHOW) {
                    return R.anim.dock_right_enter;
                }
            } else if (mNavigationBarPosition == NAV_BAR_LEFT) {
                if (transit == TRANSIT_EXIT
                        || transit == TRANSIT_HIDE) {
                    return R.anim.dock_left_exit;
                } else if (transit == TRANSIT_ENTER
                        || transit == TRANSIT_SHOW) {
                    return R.anim.dock_left_enter;
                }
            }
        } else if (win.getAttrs().type == TYPE_DOCK_DIVIDER) {
            return selectDockedDividerAnimationLw(win, transit);
        }

        if (transit == TRANSIT_PREVIEW_DONE) {
            if (win.hasAppShownWindows()) {
                if (DEBUG_ANIM) Slog.i(TAG, "**** STARTING EXIT");
                return R.anim.app_starting_exit;
            }
        } else if (win.getAttrs().type == TYPE_DREAM && mDreamingLockscreen
                && transit == TRANSIT_ENTER) {
            // Special case: we are animating in a dream, while the keyguard
            // is shown.  We don't want an animation on the dream, because
            // we need it shown immediately with the keyguard animating away
            // to reveal it.
            return -1;
        }

        return 0;
    }

    private int selectDockedDividerAnimationLw(WindowState win, int transit) {
        int insets = mDisplayContent.getDockedDividerController().getContentInsets();

        // If the divider is behind the navigation bar, don't animate.
        final Rect frame = win.getFrameLw();
        final boolean behindNavBar = mNavigationBar != null
                && ((mNavigationBarPosition == NAV_BAR_BOTTOM
                && frame.top + insets >= mNavigationBar.getFrameLw().top)
                || (mNavigationBarPosition == NAV_BAR_RIGHT
                && frame.left + insets >= mNavigationBar.getFrameLw().left)
                || (mNavigationBarPosition == NAV_BAR_LEFT
                && frame.right - insets <= mNavigationBar.getFrameLw().right));
        final boolean landscape = frame.height() > frame.width();
        final boolean offscreenLandscape = landscape && (frame.right - insets <= 0
                || frame.left + insets >= win.getDisplayFrameLw().right);
        final boolean offscreenPortrait = !landscape && (frame.top - insets <= 0
                || frame.bottom + insets >= win.getDisplayFrameLw().bottom);
        final boolean offscreen = offscreenLandscape || offscreenPortrait;
        if (behindNavBar || offscreen) {
            return 0;
        }
        if (transit == TRANSIT_ENTER || transit == TRANSIT_SHOW) {
            return R.anim.fade_in;
        } else if (transit == TRANSIT_EXIT) {
            return R.anim.fade_out;
        } else {
            return 0;
        }
    }

    /**
     * Called when a new system UI visibility is being reported, allowing
     * the policy to adjust what is actually reported.
     * @param visibility The raw visibility reported by the status bar.
     * @return The new desired visibility.
     */
    public int adjustSystemUiVisibilityLw(int visibility) {
        mStatusBarController.adjustSystemUiVisibilityLw(mLastSystemUiFlags, visibility);
        mNavigationBarController.adjustSystemUiVisibilityLw(mLastSystemUiFlags, visibility);

        // Reset any bits in mForceClearingStatusBarVisibility that
        // are now clear.
        mResettingSystemUiFlags &= visibility;
        // Clear any bits in the new visibility that are currently being
        // force cleared, before reporting it.
        return visibility & ~mResettingSystemUiFlags
                & ~mForceClearedSystemUiFlags;
    }

    /**
     * @return true if the system bars are forced to stay visible
     */
    public boolean areSystemBarsForcedShownLw(WindowState windowState) {
        return mForceShowSystemBars;
    }

    // TODO: Should probably be moved into DisplayFrames.
    /**
     * Return the layout hints for a newly added window. These values are computed on the
     * most recent layout, so they are not guaranteed to be correct.
     *
     * @param attrs The LayoutParams of the window.
     * @param taskBounds The bounds of the task this window is on or {@code null} if no task is
     *                   associated with the window.
     * @param displayFrames display frames.
     * @param floatingStack Whether the window's stack is floating.
     * @param outFrame The frame of the window.
     * @param outContentInsets The areas covered by system windows, expressed as positive insets.
     * @param outStableInsets The areas covered by stable system windows irrespective of their
     *                        current visibility. Expressed as positive insets.
     * @param outOutsets The areas that are not real display, but we would like to treat as such.
     * @param outDisplayCutout The area that has been cut away from the display.
     * @return Whether to always consume the system bars.
     *         See {@link #areSystemBarsForcedShownLw(WindowState)}.
     */
    public boolean getLayoutHintLw(LayoutParams attrs, Rect taskBounds,
            DisplayFrames displayFrames, boolean floatingStack, Rect outFrame,
            Rect outContentInsets, Rect outStableInsets,
            Rect outOutsets, DisplayCutout.ParcelableWrapper outDisplayCutout) {
        final int fl = PolicyControl.getWindowFlags(null, attrs);
        final int pfl = attrs.privateFlags;
        final int requestedSysUiVis = PolicyControl.getSystemUiVisibility(null, attrs);
        final int sysUiVis = requestedSysUiVis | getImpliedSysUiFlagsForLayout(attrs);
        final int displayRotation = displayFrames.mRotation;

        final boolean useOutsets = outOutsets != null && shouldUseOutsets(attrs, fl);
        if (useOutsets) {
            int outset = mWindowOutsetBottom;
            if (outset > 0) {
                if (displayRotation == Surface.ROTATION_0) {
                    outOutsets.bottom += outset;
                } else if (displayRotation == Surface.ROTATION_90) {
                    outOutsets.right += outset;
                } else if (displayRotation == Surface.ROTATION_180) {
                    outOutsets.top += outset;
                } else if (displayRotation == Surface.ROTATION_270) {
                    outOutsets.left += outset;
                }
            }
        }

        final boolean layoutInScreen = (fl & FLAG_LAYOUT_IN_SCREEN) != 0;
        final boolean layoutInScreenAndInsetDecor = layoutInScreen
                && (fl & FLAG_LAYOUT_INSET_DECOR) != 0;
        final boolean screenDecor = (pfl & PRIVATE_FLAG_IS_SCREEN_DECOR) != 0;

        if (layoutInScreenAndInsetDecor && !screenDecor) {
            if ((sysUiVis & SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) != 0) {
                outFrame.set(displayFrames.mUnrestricted);
            } else {
                outFrame.set(displayFrames.mRestricted);
            }

            final Rect sf;
            if (floatingStack) {
                sf = null;
            } else {
                sf = displayFrames.mStable;
            }

            final Rect cf;
            if (floatingStack) {
                cf = null;
            } else if ((sysUiVis & View.SYSTEM_UI_FLAG_LAYOUT_STABLE) != 0) {
                if ((fl & FLAG_FULLSCREEN) != 0) {
                    cf = displayFrames.mStableFullscreen;
                } else {
                    cf = displayFrames.mStable;
                }
            } else if ((fl & FLAG_FULLSCREEN) != 0 || (fl & FLAG_LAYOUT_IN_OVERSCAN) != 0) {
                cf = displayFrames.mOverscan;
            } else {
                cf = displayFrames.mCurrent;
            }

            if (taskBounds != null) {
                outFrame.intersect(taskBounds);
            }
            InsetUtils.insetsBetweenFrames(outFrame, cf, outContentInsets);
            InsetUtils.insetsBetweenFrames(outFrame, sf, outStableInsets);
            outDisplayCutout.set(displayFrames.mDisplayCutout.calculateRelativeTo(outFrame)
                    .getDisplayCutout());
            return mForceShowSystemBars;
        } else {
            if (layoutInScreen) {
                outFrame.set(displayFrames.mUnrestricted);
            } else {
                outFrame.set(displayFrames.mStable);
            }
            if (taskBounds != null) {
                outFrame.intersect(taskBounds);
            }

            outContentInsets.setEmpty();
            outStableInsets.setEmpty();
            outDisplayCutout.set(DisplayCutout.NO_CUTOUT);
            return mForceShowSystemBars;
        }
    }

    private static int getImpliedSysUiFlagsForLayout(LayoutParams attrs) {
        int impliedFlags = 0;
        final boolean forceWindowDrawsBarBackgrounds =
                (attrs.privateFlags & PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS) != 0
                && attrs.height == MATCH_PARENT && attrs.width == MATCH_PARENT;
        if ((attrs.flags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0
                || forceWindowDrawsBarBackgrounds) {
            impliedFlags |= SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            impliedFlags |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        }
        return impliedFlags;
    }

    private static boolean shouldUseOutsets(WindowManager.LayoutParams attrs, int fl) {
        return attrs.type == TYPE_WALLPAPER || (fl & (WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN)) != 0;
    }

    private final Runnable mClearHideNavigationFlag = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                mForceClearedSystemUiFlags &= ~View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
                mDisplayContent.reevaluateStatusBarVisibility();
            }
        }
    };

    /**
     * Input handler used while nav bar is hidden.  Captures any touch on the screen,
     * to determine when the nav bar should be shown and prevent applications from
     * receiving those touches.
     */
    private final class HideNavInputEventReceiver extends InputEventReceiver {
        HideNavInputEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            try {
                if (event instanceof MotionEvent
                        && (event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
                    final MotionEvent motionEvent = (MotionEvent) event;
                    if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                        // When the user taps down, we re-show the nav bar.
                        boolean changed = false;
                        synchronized (mLock) {
                            if (mInputConsumer == null) {
                                return;
                            }
                            // Any user activity always causes us to show the
                            // navigation controls, if they had been hidden.
                            // We also clear the low profile and only content
                            // flags so that tapping on the screen will atomically
                            // restore all currently hidden screen decorations.
                            int newVal = mResettingSystemUiFlags
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LOW_PROFILE
                                    | View.SYSTEM_UI_FLAG_FULLSCREEN;
                            if (mResettingSystemUiFlags != newVal) {
                                mResettingSystemUiFlags = newVal;
                                changed = true;
                            }
                            // We don't allow the system's nav bar to be hidden
                            // again for 1 second, to prevent applications from
                            // spamming us and keeping it from being shown.
                            newVal = mForceClearedSystemUiFlags
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
                            if (mForceClearedSystemUiFlags != newVal) {
                                mForceClearedSystemUiFlags = newVal;
                                changed = true;
                                mHandler.postDelayed(mClearHideNavigationFlag, 1000);
                            }
                            if (changed) {
                                mDisplayContent.reevaluateStatusBarVisibility();
                            }
                        }
                    }
                }
            } finally {
                finishInputEvent(event, false /* handled */);
            }
        }
    }

    /**
     * Called when layout of the windows is about to start.
     *
     * @param displayFrames frames of the display we are doing layout on.
     * @param uiMode The current uiMode in configuration.
     */
    public void beginLayoutLw(DisplayFrames displayFrames, int uiMode) {
        displayFrames.onBeginLayout();
        mSystemGestures.screenWidth = displayFrames.mUnrestricted.width();
        mSystemGestures.screenHeight = displayFrames.mUnrestricted.height();

        // For purposes of putting out fake window up to steal focus, we will
        // drive nav being hidden only by whether it is requested.
        final int sysui = mLastSystemUiFlags;
        boolean navVisible = (sysui & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0;
        boolean navTranslucent = (sysui
                & (View.NAVIGATION_BAR_TRANSLUCENT | View.NAVIGATION_BAR_TRANSPARENT)) != 0;
        boolean immersive = (sysui & View.SYSTEM_UI_FLAG_IMMERSIVE) != 0;
        boolean immersiveSticky = (sysui & View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) != 0;
        boolean navAllowedHidden = immersive || immersiveSticky;
        navTranslucent &= !immersiveSticky;  // transient trumps translucent
        boolean isKeyguardShowing = isStatusBarKeyguard()
                && !mService.mPolicy.isKeyguardOccluded();
        boolean statusBarForcesShowingNavigation = !isKeyguardShowing && mStatusBar != null
                && (mStatusBar.getAttrs().privateFlags
                & PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION) != 0;

        // When the navigation bar isn't visible, we put up a fake input window to catch all
        // touch events. This way we can detect when the user presses anywhere to bring back the
        // nav bar and ensure the application doesn't see the event.
        if (navVisible || navAllowedHidden) {
            if (mInputConsumer != null) {
                mHandler.sendMessage(
                        mHandler.obtainMessage(MSG_DISPOSE_INPUT_CONSUMER, mInputConsumer));
                mInputConsumer = null;
            }
        } else if (mInputConsumer == null && mStatusBar != null && canHideNavigationBar()) {
            mInputConsumer = mService.createInputConsumer(mHandler.getLooper(),
                    INPUT_CONSUMER_NAVIGATION,
                    HideNavInputEventReceiver::new,
                    displayFrames.mDisplayId);
            // As long as mInputConsumer is active, hover events are not dispatched to the app
            // and the pointer icon is likely to become stale. Hide it to avoid confusion.
            InputManager.getInstance().setPointerIconType(PointerIcon.TYPE_NULL);
        }

        // For purposes of positioning and showing the nav bar, if we have decided that it can't
        // be hidden (because of the screen aspect ratio), then take that into account.
        navVisible |= !canHideNavigationBar();

        boolean updateSysUiVisibility = layoutNavigationBar(displayFrames, uiMode, navVisible,
                navTranslucent, navAllowedHidden, statusBarForcesShowingNavigation);
        if (DEBUG_LAYOUT) Slog.i(TAG, "mDock rect:" + displayFrames.mDock);
        updateSysUiVisibility |= layoutStatusBar(displayFrames, sysui, isKeyguardShowing);
        if (updateSysUiVisibility) {
            updateSystemUiVisibilityLw();
        }
        layoutScreenDecorWindows(displayFrames);

        if (displayFrames.mDisplayCutoutSafe.top > displayFrames.mUnrestricted.top) {
            // Make sure that the zone we're avoiding for the cutout is at least as tall as the
            // status bar; otherwise fullscreen apps will end up cutting halfway into the status
            // bar.
            displayFrames.mDisplayCutoutSafe.top = Math.max(displayFrames.mDisplayCutoutSafe.top,
                    displayFrames.mStable.top);
        }

        // In case this is a virtual display, and the host display has insets that overlap this
        // virtual display, apply the insets of the overlapped area onto the current and content
        // frame of this virtual display. This let us layout windows in the virtual display as
        // expected when the window needs to avoid overlap with the system windows.
        // TODO: Generalize the forwarded insets, so that we can handle system windows other than
        // IME.
        displayFrames.mCurrent.inset(mForwardedInsets);
        displayFrames.mContent.inset(mForwardedInsets);
    }

    private void layoutScreenDecorWindows(DisplayFrames displayFrames) {
        if (mScreenDecorWindows.isEmpty()) {
            return;
        }

        sTmpRect.setEmpty();
        final int displayId = displayFrames.mDisplayId;
        final Rect dockFrame = displayFrames.mDock;
        final int displayHeight = displayFrames.mDisplayHeight;
        final int displayWidth = displayFrames.mDisplayWidth;

        for (int i = mScreenDecorWindows.size() - 1; i >= 0; --i) {
            final WindowState w = mScreenDecorWindows.valueAt(i);
            if (w.getDisplayId() != displayId || !w.isVisibleLw()) {
                // Skip if not on the same display or not visible.
                continue;
            }

            w.getWindowFrames().setFrames(displayFrames.mUnrestricted /* parentFrame */,
                    displayFrames.mUnrestricted /* displayFrame */,
                    displayFrames.mUnrestricted /* overscanFrame */,
                    displayFrames.mUnrestricted /* contentFrame */,
                    displayFrames.mUnrestricted /* visibleFrame */, sTmpRect /* decorFrame */,
                    displayFrames.mUnrestricted /* stableFrame */,
                    displayFrames.mUnrestricted /* outsetFrame */);
            w.getWindowFrames().setDisplayCutout(displayFrames.mDisplayCutout);
            w.computeFrameLw();
            final Rect frame = w.getFrameLw();

            if (frame.left <= 0 && frame.top <= 0) {
                // Docked at left or top.
                if (frame.bottom >= displayHeight) {
                    // Docked left.
                    dockFrame.left = Math.max(frame.right, dockFrame.left);
                } else if (frame.right >= displayWidth) {
                    // Docked top.
                    dockFrame.top = Math.max(frame.bottom, dockFrame.top);
                } else {
                    Slog.w(TAG, "layoutScreenDecorWindows: Ignoring decor win=" + w
                            + " not docked on left or top of display. frame=" + frame
                            + " displayWidth=" + displayWidth + " displayHeight=" + displayHeight);
                }
            } else if (frame.right >= displayWidth && frame.bottom >= displayHeight) {
                // Docked at right or bottom.
                if (frame.top <= 0) {
                    // Docked right.
                    dockFrame.right = Math.min(frame.left, dockFrame.right);
                } else if (frame.left <= 0) {
                    // Docked bottom.
                    dockFrame.bottom = Math.min(frame.top, dockFrame.bottom);
                } else {
                    Slog.w(TAG, "layoutScreenDecorWindows: Ignoring decor win=" + w
                            + " not docked on right or bottom" + " of display. frame=" + frame
                            + " displayWidth=" + displayWidth + " displayHeight=" + displayHeight);
                }
            } else {
                // Screen decor windows are required to be docked on one of the sides of the screen.
                Slog.w(TAG, "layoutScreenDecorWindows: Ignoring decor win=" + w
                        + " not docked on one of the sides of the display. frame=" + frame
                        + " displayWidth=" + displayWidth + " displayHeight=" + displayHeight);
            }
        }

        displayFrames.mRestricted.set(dockFrame);
        displayFrames.mCurrent.set(dockFrame);
        displayFrames.mVoiceContent.set(dockFrame);
        displayFrames.mSystem.set(dockFrame);
        displayFrames.mContent.set(dockFrame);
        displayFrames.mRestrictedOverscan.set(dockFrame);
    }

    private boolean layoutStatusBar(DisplayFrames displayFrames, int sysui,
            boolean isKeyguardShowing) {
        // decide where the status bar goes ahead of time
        if (mStatusBar == null) {
            return false;
        }
        // apply any navigation bar insets
        sTmpRect.setEmpty();
        final WindowFrames windowFrames = mStatusBar.getWindowFrames();
        windowFrames.setFrames(displayFrames.mUnrestricted /* parentFrame */,
                displayFrames.mUnrestricted /* displayFrame */,
                displayFrames.mStable /* overscanFrame */, displayFrames.mStable /* contentFrame */,
                displayFrames.mStable /* visibleFrame */, sTmpRect /* decorFrame */,
                displayFrames.mStable /* stableFrame */, displayFrames.mStable /* outsetFrame */);
        windowFrames.setDisplayCutout(displayFrames.mDisplayCutout);

        // Let the status bar determine its size.
        mStatusBar.computeFrameLw();

        // For layout, the status bar is always at the top with our fixed height.
        displayFrames.mStable.top = displayFrames.mUnrestricted.top
                + mStatusBarHeightForRotation[displayFrames.mRotation];
        // Make sure the status bar covers the entire cutout height
        displayFrames.mStable.top = Math.max(displayFrames.mStable.top,
                displayFrames.mDisplayCutoutSafe.top);

        // Tell the bar controller where the collapsed status bar content is
        sTmpRect.set(mStatusBar.getContentFrameLw());
        sTmpRect.intersect(displayFrames.mDisplayCutoutSafe);
        sTmpRect.top = mStatusBar.getContentFrameLw().top;  // Ignore top display cutout inset
        sTmpRect.bottom = displayFrames.mStable.top;  // Use collapsed status bar size
        mStatusBarController.setContentFrame(sTmpRect);

        boolean statusBarTransient = (sysui & View.STATUS_BAR_TRANSIENT) != 0;
        boolean statusBarTranslucent = (sysui
                & (View.STATUS_BAR_TRANSLUCENT | View.STATUS_BAR_TRANSPARENT)) != 0;

        // If the status bar is hidden, we don't want to cause windows behind it to scroll.
        if (mStatusBar.isVisibleLw() && !statusBarTransient) {
            // Status bar may go away, so the screen area it occupies is available to apps but just
            // covering them when the status bar is visible.
            final Rect dockFrame = displayFrames.mDock;
            dockFrame.top = displayFrames.mStable.top;
            displayFrames.mContent.set(dockFrame);
            displayFrames.mVoiceContent.set(dockFrame);
            displayFrames.mCurrent.set(dockFrame);

            if (DEBUG_LAYOUT) Slog.v(TAG, "Status bar: " + String.format(
                    "dock=%s content=%s cur=%s", dockFrame.toString(),
                    displayFrames.mContent.toString(), displayFrames.mCurrent.toString()));

            if (!statusBarTranslucent && !mStatusBarController.wasRecentlyTranslucent()
                    && !mStatusBar.isAnimatingLw()) {

                // If the opaque status bar is currently requested to be visible, and not in the
                // process of animating on or off, then we can tell the app that it is covered by
                // it.
                displayFrames.mSystem.top = displayFrames.mStable.top;
            }
        }
        return mStatusBarController.checkHiddenLw();
    }

    private boolean layoutNavigationBar(DisplayFrames displayFrames, int uiMode, boolean navVisible,
            boolean navTranslucent, boolean navAllowedHidden,
            boolean statusBarForcesShowingNavigation) {
        if (mNavigationBar == null) {
            return false;
        }

        final Rect navigationFrame = sTmpNavFrame;
        boolean transientNavBarShowing = mNavigationBarController.isTransientShowing();
        // Force the navigation bar to its appropriate place and size. We need to do this directly,
        // instead of relying on it to bubble up from the nav bar, because this needs to change
        // atomically with screen rotations.
        final int rotation = displayFrames.mRotation;
        final int displayHeight = displayFrames.mDisplayHeight;
        final int displayWidth = displayFrames.mDisplayWidth;
        final Rect dockFrame = displayFrames.mDock;
        mNavigationBarPosition = navigationBarPosition(displayWidth, displayHeight, rotation);

        final Rect cutoutSafeUnrestricted = sTmpRect;
        cutoutSafeUnrestricted.set(displayFrames.mUnrestricted);
        cutoutSafeUnrestricted.intersectUnchecked(displayFrames.mDisplayCutoutSafe);

        if (mNavigationBarPosition == NAV_BAR_BOTTOM) {
            // It's a system nav bar or a portrait screen; nav bar goes on bottom.
            final int top = cutoutSafeUnrestricted.bottom
                    - getNavigationBarHeight(rotation, uiMode);
            final int topNavBar = cutoutSafeUnrestricted.bottom
                    - getNavigationBarFrameHeight(rotation, uiMode);
            navigationFrame.set(0, topNavBar, displayWidth, displayFrames.mUnrestricted.bottom);
            displayFrames.mStable.bottom = displayFrames.mStableFullscreen.bottom = top;
            if (transientNavBarShowing) {
                mNavigationBarController.setBarShowingLw(true);
            } else if (navVisible) {
                mNavigationBarController.setBarShowingLw(true);
                dockFrame.bottom = displayFrames.mRestricted.bottom =
                        displayFrames.mRestrictedOverscan.bottom = top;
            } else {
                // We currently want to hide the navigation UI - unless we expanded the status bar.
                mNavigationBarController.setBarShowingLw(statusBarForcesShowingNavigation);
            }
            if (navVisible && !navTranslucent && !navAllowedHidden
                    && !mNavigationBar.isAnimatingLw()
                    && !mNavigationBarController.wasRecentlyTranslucent()) {
                // If the opaque nav bar is currently requested to be visible and not in the process
                // of animating on or off, then we can tell the app that it is covered by it.
                displayFrames.mSystem.bottom = top;
            }
        } else if (mNavigationBarPosition == NAV_BAR_RIGHT) {
            // Landscape screen; nav bar goes to the right.
            final int left = cutoutSafeUnrestricted.right
                    - getNavigationBarWidth(rotation, uiMode);
            navigationFrame.set(left, 0, displayFrames.mUnrestricted.right, displayHeight);
            displayFrames.mStable.right = displayFrames.mStableFullscreen.right = left;
            if (transientNavBarShowing) {
                mNavigationBarController.setBarShowingLw(true);
            } else if (navVisible) {
                mNavigationBarController.setBarShowingLw(true);
                dockFrame.right = displayFrames.mRestricted.right =
                        displayFrames.mRestrictedOverscan.right = left;
            } else {
                // We currently want to hide the navigation UI - unless we expanded the status bar.
                mNavigationBarController.setBarShowingLw(statusBarForcesShowingNavigation);
            }
            if (navVisible && !navTranslucent && !navAllowedHidden
                    && !mNavigationBar.isAnimatingLw()
                    && !mNavigationBarController.wasRecentlyTranslucent()) {
                // If the nav bar is currently requested to be visible, and not in the process of
                // animating on or off, then we can tell the app that it is covered by it.
                displayFrames.mSystem.right = left;
            }
        } else if (mNavigationBarPosition == NAV_BAR_LEFT) {
            // Seascape screen; nav bar goes to the left.
            final int right = cutoutSafeUnrestricted.left
                    + getNavigationBarWidth(rotation, uiMode);
            navigationFrame.set(displayFrames.mUnrestricted.left, 0, right, displayHeight);
            displayFrames.mStable.left = displayFrames.mStableFullscreen.left = right;
            if (transientNavBarShowing) {
                mNavigationBarController.setBarShowingLw(true);
            } else if (navVisible) {
                mNavigationBarController.setBarShowingLw(true);
                dockFrame.left = displayFrames.mRestricted.left =
                        displayFrames.mRestrictedOverscan.left = right;
            } else {
                // We currently want to hide the navigation UI - unless we expanded the status bar.
                mNavigationBarController.setBarShowingLw(statusBarForcesShowingNavigation);
            }
            if (navVisible && !navTranslucent && !navAllowedHidden
                    && !mNavigationBar.isAnimatingLw()
                    && !mNavigationBarController.wasRecentlyTranslucent()) {
                // If the nav bar is currently requested to be visible, and not in the process of
                // animating on or off, then we can tell the app that it is covered by it.
                displayFrames.mSystem.left = right;
            }
        }

        // Make sure the content and current rectangles are updated to account for the restrictions
        // from the navigation bar.
        displayFrames.mCurrent.set(dockFrame);
        displayFrames.mVoiceContent.set(dockFrame);
        displayFrames.mContent.set(dockFrame);
        // And compute the final frame.
        sTmpRect.setEmpty();
        mNavigationBar.getWindowFrames().setFrames(navigationFrame /* parentFrame */,
                navigationFrame /* displayFrame */, navigationFrame /* overscanFrame */,
                displayFrames.mDisplayCutoutSafe /* contentFrame */,
                navigationFrame /* visibleFrame */, sTmpRect /* decorFrame */,
                navigationFrame /* stableFrame */,
                displayFrames.mDisplayCutoutSafe /* outsetFrame */);
        mNavigationBar.getWindowFrames().setDisplayCutout(displayFrames.mDisplayCutout);
        mNavigationBar.computeFrameLw();
        mNavigationBarController.setContentFrame(mNavigationBar.getContentFrameLw());

        if (DEBUG_LAYOUT) Slog.i(TAG, "mNavigationBar frame: " + navigationFrame);
        return mNavigationBarController.checkHiddenLw();
    }

    private void setAttachedWindowFrames(WindowState win, int fl, int adjust, WindowState attached,
            boolean insetDecors, Rect pf, Rect df, Rect of, Rect cf, Rect vf,
            DisplayFrames displayFrames) {
        if (!win.isInputMethodTarget() && attached.isInputMethodTarget()) {
            // Here's a special case: if the child window is not the 'dock window'
            // or input method target, and the window it is attached to is below
            // the dock window, then the frames we computed for the window it is
            // attached to can not be used because the dock is effectively part
            // of the underlying window and the attached window is floating on top
            // of the whole thing. So, we ignore the attached window and explicitly
            // compute the frames that would be appropriate without the dock.
            vf.set(displayFrames.mDock);
            cf.set(displayFrames.mDock);
            of.set(displayFrames.mDock);
            df.set(displayFrames.mDock);
        } else {

            // In case we forced the window to draw behind the navigation bar, restrict df/of to
            // DF.RestrictedOverscan to simulate old compat behavior.
            Rect parentDisplayFrame = attached.getDisplayFrameLw();
            Rect parentOverscan = attached.getOverscanFrameLw();
            final WindowManager.LayoutParams attachedAttrs = attached.mAttrs;
            if ((attachedAttrs.privateFlags & PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS) != 0
                    && (attachedAttrs.flags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) == 0
                    && (attachedAttrs.systemUiVisibility
                            & SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) == 0) {
                parentOverscan = new Rect(parentOverscan);
                parentOverscan.intersect(displayFrames.mRestrictedOverscan);
                parentDisplayFrame = new Rect(parentDisplayFrame);
                parentDisplayFrame.intersect(displayFrames.mRestrictedOverscan);
            }

            // The effective display frame of the attached window depends on whether it is taking
            // care of insetting its content. If not, we need to use the parent's content frame so
            // that the entire window is positioned within that content. Otherwise we can use the
            // overscan frame and let the attached window take care of positioning its content
            // appropriately.
            if (adjust != SOFT_INPUT_ADJUST_RESIZE) {
                // Set the content frame of the attached window to the parent's decor frame
                // (same as content frame when IME isn't present) if specifically requested by
                // setting {@link WindowManager.LayoutParams#FLAG_LAYOUT_ATTACHED_IN_DECOR} flag.
                // Otherwise, use the overscan frame.
                cf.set((fl & FLAG_LAYOUT_ATTACHED_IN_DECOR) != 0
                        ? attached.getContentFrameLw() : parentOverscan);
            } else {
                // If the window is resizing, then we want to base the content frame on our attached
                // content frame to resize...however, things can be tricky if the attached window is
                // NOT in resize mode, in which case its content frame will be larger.
                // Ungh. So to deal with that, make sure the content frame we end up using is not
                // covering the IM dock.
                cf.set(attached.getContentFrameLw());
                if (attached.isVoiceInteraction()) {
                    cf.intersectUnchecked(displayFrames.mVoiceContent);
                } else if (win.isInputMethodTarget() || attached.isInputMethodTarget()) {
                    cf.intersectUnchecked(displayFrames.mContent);
                }
            }
            df.set(insetDecors ? parentDisplayFrame : cf);
            of.set(insetDecors ? parentOverscan : cf);
            vf.set(attached.getVisibleFrameLw());
        }
        // The LAYOUT_IN_SCREEN flag is used to determine whether the attached window should be
        // positioned relative to its parent or the entire screen.
        pf.set((fl & FLAG_LAYOUT_IN_SCREEN) == 0 ? attached.getFrameLw() : df);
    }

    private void applyStableConstraints(int sysui, int fl, Rect r, DisplayFrames displayFrames) {
        if ((sysui & View.SYSTEM_UI_FLAG_LAYOUT_STABLE) == 0) {
            return;
        }
        // If app is requesting a stable layout, don't let the content insets go below the stable
        // values.
        if ((fl & FLAG_FULLSCREEN) != 0) {
            r.intersectUnchecked(displayFrames.mStableFullscreen);
        } else {
            r.intersectUnchecked(displayFrames.mStable);
        }
    }

    private boolean canReceiveInput(WindowState win) {
        boolean notFocusable =
                (win.getAttrs().flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0;
        boolean altFocusableIm =
                (win.getAttrs().flags & WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) != 0;
        boolean notFocusableForIm = notFocusable ^ altFocusableIm;
        return !notFocusableForIm;
    }

    /**
     * Called for each window attached to the window manager as layout is proceeding. The
     * implementation of this function must take care of setting the window's frame, either here or
     * in finishLayout().
     *
     * @param win The window being positioned.
     * @param attached For sub-windows, the window it is attached to; this
     *                 window will already have had layoutWindow() called on it
     *                 so you can use its Rect.  Otherwise null.
     * @param displayFrames The display frames.
     */
    public void layoutWindowLw(WindowState win, WindowState attached, DisplayFrames displayFrames) {
        // We've already done the navigation bar, status bar, and all screen decor windows. If the
        // status bar can receive input, we need to layout it again to accommodate for the IME
        // window.
        if ((win == mStatusBar && !canReceiveInput(win)) || win == mNavigationBar
                || mScreenDecorWindows.contains(win)) {
            return;
        }
        final WindowManager.LayoutParams attrs = win.getAttrs();
        final boolean isDefaultDisplay = win.isDefaultDisplay();

        final int type = attrs.type;
        final int fl = PolicyControl.getWindowFlags(win, attrs);
        final int pfl = attrs.privateFlags;
        final int sim = attrs.softInputMode;
        final int requestedSysUiFl = PolicyControl.getSystemUiVisibility(null, attrs);
        final int sysUiFl = requestedSysUiFl | getImpliedSysUiFlagsForLayout(attrs);

        final WindowFrames windowFrames = win.getWindowFrames();

        windowFrames.setHasOutsets(false);
        sTmpLastParentFrame.set(windowFrames.mParentFrame);
        final Rect pf = windowFrames.mParentFrame;
        final Rect df = windowFrames.mDisplayFrame;
        final Rect of = windowFrames.mOverscanFrame;
        final Rect cf = windowFrames.mContentFrame;
        final Rect vf = windowFrames.mVisibleFrame;
        final Rect dcf = windowFrames.mDecorFrame;
        final Rect sf = windowFrames.mStableFrame;
        dcf.setEmpty();
        windowFrames.setParentFrameWasClippedByDisplayCutout(false);
        windowFrames.setDisplayCutout(displayFrames.mDisplayCutout);

        final boolean hasNavBar = hasNavigationBar() && mNavigationBar != null
                && mNavigationBar.isVisibleLw();

        final int adjust = sim & SOFT_INPUT_MASK_ADJUST;

        final boolean requestedFullscreen = (fl & FLAG_FULLSCREEN) != 0
                || (requestedSysUiFl & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0;

        final boolean layoutInScreen = (fl & FLAG_LAYOUT_IN_SCREEN) == FLAG_LAYOUT_IN_SCREEN;
        final boolean layoutInsetDecor = (fl & FLAG_LAYOUT_INSET_DECOR) == FLAG_LAYOUT_INSET_DECOR;

        sf.set(displayFrames.mStable);

        if (type == TYPE_INPUT_METHOD) {
            vf.set(displayFrames.mDock);
            cf.set(displayFrames.mDock);
            of.set(displayFrames.mDock);
            df.set(displayFrames.mDock);
            windowFrames.mParentFrame.set(displayFrames.mDock);
            // IM dock windows layout below the nav bar...
            pf.bottom = df.bottom = of.bottom = displayFrames.mUnrestricted.bottom;
            // ...with content insets above the nav bar
            cf.bottom = vf.bottom = displayFrames.mStable.bottom;
            if (mStatusBar != null && mFocusedWindow == mStatusBar && canReceiveInput(mStatusBar)) {
                // The status bar forces the navigation bar while it's visible. Make sure the IME
                // avoids the navigation bar in that case.
                if (mNavigationBarPosition == NAV_BAR_RIGHT) {
                    pf.right = df.right = of.right = cf.right = vf.right =
                            displayFrames.mStable.right;
                } else if (mNavigationBarPosition == NAV_BAR_LEFT) {
                    pf.left = df.left = of.left = cf.left = vf.left = displayFrames.mStable.left;
                }
            }

            // In case the navigation bar is on the bottom, we use the frame height instead of the
            // regular height for the insets we send to the IME as we need some space to show
            // additional buttons in SystemUI when the IME is up.
            if (mNavigationBarPosition == NAV_BAR_BOTTOM) {
                final int rotation = displayFrames.mRotation;
                final int uimode = mService.mPolicy.getUiMode();
                final int navHeightOffset = getNavigationBarFrameHeight(rotation, uimode)
                        - getNavigationBarHeight(rotation, uimode);
                if (navHeightOffset > 0) {
                    cf.bottom -= navHeightOffset;
                    sf.bottom -= navHeightOffset;
                    vf.bottom -= navHeightOffset;
                    dcf.bottom -= navHeightOffset;
                }
            }

            // IM dock windows always go to the bottom of the screen.
            attrs.gravity = Gravity.BOTTOM;
        } else if (type == TYPE_VOICE_INTERACTION) {
            of.set(displayFrames.mUnrestricted);
            df.set(displayFrames.mUnrestricted);
            pf.set(displayFrames.mUnrestricted);
            if (adjust != SOFT_INPUT_ADJUST_RESIZE) {
                cf.set(displayFrames.mDock);
            } else {
                cf.set(displayFrames.mContent);
            }
            if (adjust != SOFT_INPUT_ADJUST_NOTHING) {
                vf.set(displayFrames.mCurrent);
            } else {
                vf.set(cf);
            }
        } else if (type == TYPE_WALLPAPER) {
            layoutWallpaper(displayFrames, pf, df, of, cf);
        } else if (win == mStatusBar) {
            of.set(displayFrames.mUnrestricted);
            df.set(displayFrames.mUnrestricted);
            pf.set(displayFrames.mUnrestricted);
            cf.set(displayFrames.mStable);
            vf.set(displayFrames.mStable);

            if (adjust == SOFT_INPUT_ADJUST_RESIZE) {
                cf.bottom = displayFrames.mContent.bottom;
            } else {
                cf.bottom = displayFrames.mDock.bottom;
                vf.bottom = displayFrames.mContent.bottom;
            }
        } else {
            dcf.set(displayFrames.mSystem);
            final boolean inheritTranslucentDecor =
                    (attrs.privateFlags & PRIVATE_FLAG_INHERIT_TRANSLUCENT_DECOR) != 0;
            final boolean isAppWindow =
                    type >= FIRST_APPLICATION_WINDOW && type <= LAST_APPLICATION_WINDOW;
            final boolean topAtRest =
                    win == mTopFullscreenOpaqueWindowState && !win.isAnimatingLw();
            if (isAppWindow && !inheritTranslucentDecor && !topAtRest) {
                if ((sysUiFl & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0
                        && (fl & FLAG_FULLSCREEN) == 0
                        && (fl & FLAG_TRANSLUCENT_STATUS) == 0
                        && (fl & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) == 0
                        && (pfl & PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS) == 0) {
                    // Ensure policy decor includes status bar
                    dcf.top = displayFrames.mStable.top;
                }
                if ((fl & FLAG_TRANSLUCENT_NAVIGATION) == 0
                        && (sysUiFl & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0
                        && (fl & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) == 0
                        && (pfl & PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS) == 0) {
                    // Ensure policy decor includes navigation bar
                    dcf.bottom = displayFrames.mStable.bottom;
                    dcf.right = displayFrames.mStable.right;
                }
            }

            if (layoutInScreen && layoutInsetDecor) {
                if (DEBUG_LAYOUT) Slog.v(TAG, "layoutWindowLw(" + attrs.getTitle()
                        + "): IN_SCREEN, INSET_DECOR");
                // This is the case for a normal activity window: we want it to cover all of the
                // screen space, and it can take care of moving its contents to account for screen
                // decorations that intrude into that space.
                if (attached != null) {
                    // If this window is attached to another, our display
                    // frame is the same as the one we are attached to.
                    setAttachedWindowFrames(win, fl, adjust, attached, true, pf, df, of, cf, vf,
                            displayFrames);
                } else {
                    if (type == TYPE_STATUS_BAR_PANEL || type == TYPE_STATUS_BAR_SUB_PANEL) {
                        // Status bar panels are the only windows who can go on top of the status
                        // bar. They are protected by the STATUS_BAR_SERVICE permission, so they
                        // have the same privileges as the status bar itself.
                        //
                        // However, they should still dodge the navigation bar if it exists.

                        pf.left = df.left = of.left = hasNavBar
                                ? displayFrames.mDock.left : displayFrames.mUnrestricted.left;
                        pf.top = df.top = of.top = displayFrames.mUnrestricted.top;
                        pf.right = df.right = of.right = hasNavBar
                                ? displayFrames.mRestricted.right
                                : displayFrames.mUnrestricted.right;
                        pf.bottom = df.bottom = of.bottom = hasNavBar
                                ? displayFrames.mRestricted.bottom
                                : displayFrames.mUnrestricted.bottom;

                        if (DEBUG_LAYOUT) Slog.v(TAG, "Laying out status bar window: " + pf);
                    } else if ((fl & FLAG_LAYOUT_IN_OVERSCAN) != 0
                            && type >= FIRST_APPLICATION_WINDOW && type <= LAST_SUB_WINDOW) {
                        // Asking to layout into the overscan region, so give it that pure
                        // unrestricted area.
                        of.set(displayFrames.mOverscan);
                        df.set(displayFrames.mOverscan);
                        pf.set(displayFrames.mOverscan);
                    } else if ((sysUiFl & SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) != 0
                            && (type >= FIRST_APPLICATION_WINDOW && type <= LAST_SUB_WINDOW
                            || type == TYPE_VOLUME_OVERLAY
                            || type == TYPE_KEYGUARD_DIALOG)) {
                        // Asking for layout as if the nav bar is hidden, lets the application
                        // extend into the unrestricted overscan screen area. We only do this for
                        // application windows and certain system windows to ensure no window that
                        // can be above the nav bar can do this.
                        df.set(displayFrames.mOverscan);
                        pf.set(displayFrames.mOverscan);
                        // We need to tell the app about where the frame inside the overscan is, so
                        // it can inset its content by that amount -- it didn't ask to actually
                        // extend itself into the overscan region.
                        of.set(displayFrames.mUnrestricted);
                    } else {
                        df.set(displayFrames.mRestrictedOverscan);
                        pf.set(displayFrames.mRestrictedOverscan);
                        // We need to tell the app about where the frame inside the overscan
                        // is, so it can inset its content by that amount -- it didn't ask
                        // to actually extend itself into the overscan region.
                        of.set(displayFrames.mUnrestricted);
                    }

                    if ((fl & FLAG_FULLSCREEN) == 0) {
                        if (win.isVoiceInteraction()) {
                            cf.set(displayFrames.mVoiceContent);
                        } else {
                            // IME Insets are handled on the client for ADJUST_RESIZE in the new
                            // insets world
                            if (ViewRootImpl.sNewInsetsMode != NEW_INSETS_MODE_NONE
                                    || adjust != SOFT_INPUT_ADJUST_RESIZE) {
                                cf.set(displayFrames.mDock);
                            } else {
                                cf.set(displayFrames.mContent);
                            }
                        }
                    } else {
                        // Full screen windows are always given a layout that is as if the status
                        // bar and other transient decors are gone. This is to avoid bad states when
                        // moving from a window that is not hiding the status bar to one that is.
                        cf.set(displayFrames.mRestricted);
                    }
                    applyStableConstraints(sysUiFl, fl, cf, displayFrames);
                    if (adjust != SOFT_INPUT_ADJUST_NOTHING) {
                        vf.set(displayFrames.mCurrent);
                    } else {
                        vf.set(cf);
                    }
                }
            } else if (layoutInScreen || (sysUiFl
                    & (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)) != 0) {
                if (DEBUG_LAYOUT) Slog.v(TAG, "layoutWindowLw(" + attrs.getTitle()
                        + "): IN_SCREEN");
                // A window that has requested to fill the entire screen just
                // gets everything, period.
                if (type == TYPE_STATUS_BAR_PANEL || type == TYPE_STATUS_BAR_SUB_PANEL) {
                    cf.set(displayFrames.mUnrestricted);
                    of.set(displayFrames.mUnrestricted);
                    df.set(displayFrames.mUnrestricted);
                    pf.set(displayFrames.mUnrestricted);
                    if (hasNavBar) {
                        pf.left = df.left = of.left = cf.left = displayFrames.mDock.left;
                        pf.right = df.right = of.right = cf.right = displayFrames.mRestricted.right;
                        pf.bottom = df.bottom = of.bottom = cf.bottom =
                                displayFrames.mRestricted.bottom;
                    }
                    if (DEBUG_LAYOUT) Slog.v(TAG, "Laying out IN_SCREEN status bar window: " + pf);
                } else if (type == TYPE_NAVIGATION_BAR || type == TYPE_NAVIGATION_BAR_PANEL) {
                    // The navigation bar has Real Ultimate Power.
                    of.set(displayFrames.mUnrestricted);
                    df.set(displayFrames.mUnrestricted);
                    pf.set(displayFrames.mUnrestricted);
                    if (DEBUG_LAYOUT) Slog.v(TAG, "Laying out navigation bar window: " + pf);
                } else if ((type == TYPE_SECURE_SYSTEM_OVERLAY || type == TYPE_SCREENSHOT)
                        && ((fl & FLAG_FULLSCREEN) != 0)) {
                    // Fullscreen secure system overlays get what they ask for. Screenshot region
                    // selection overlay should also expand to full screen.
                    cf.set(displayFrames.mOverscan);
                    of.set(displayFrames.mOverscan);
                    df.set(displayFrames.mOverscan);
                    pf.set(displayFrames.mOverscan);
                } else if (type == TYPE_BOOT_PROGRESS) {
                    // Boot progress screen always covers entire display.
                    cf.set(displayFrames.mOverscan);
                    of.set(displayFrames.mOverscan);
                    df.set(displayFrames.mOverscan);
                    pf.set(displayFrames.mOverscan);
                } else if ((fl & FLAG_LAYOUT_IN_OVERSCAN) != 0
                        && type >= FIRST_APPLICATION_WINDOW && type <= LAST_SUB_WINDOW) {
                    // Asking to layout into the overscan region, so give it that pure unrestricted
                    // area.
                    cf.set(displayFrames.mOverscan);
                    of.set(displayFrames.mOverscan);
                    df.set(displayFrames.mOverscan);
                    pf.set(displayFrames.mOverscan);
                } else if ((sysUiFl & SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) != 0
                        && (type == TYPE_STATUS_BAR
                        || type == TYPE_TOAST
                        || type == TYPE_DOCK_DIVIDER
                        || type == TYPE_VOICE_INTERACTION_STARTING
                        || (type >= FIRST_APPLICATION_WINDOW && type <= LAST_SUB_WINDOW))) {
                    // Asking for layout as if the nav bar is hidden, lets the
                    // application extend into the unrestricted screen area.  We
                    // only do this for application windows (or toasts) to ensure no window that
                    // can be above the nav bar can do this.
                    // XXX This assumes that an app asking for this will also
                    // ask for layout in only content.  We can't currently figure out
                    // what the screen would be if only laying out to hide the nav bar.
                    cf.set(displayFrames.mUnrestricted);
                    of.set(displayFrames.mUnrestricted);
                    df.set(displayFrames.mUnrestricted);
                    pf.set(displayFrames.mUnrestricted);
                } else if ((sysUiFl & View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN) != 0) {
                    of.set(displayFrames.mRestricted);
                    df.set(displayFrames.mRestricted);
                    pf.set(displayFrames.mRestricted);

                    // IME Insets are handled on the client for ADJUST_RESIZE in the new insets
                    // world
                    if (ViewRootImpl.sNewInsetsMode != NEW_INSETS_MODE_NONE
                            || adjust != SOFT_INPUT_ADJUST_RESIZE) {
                        cf.set(displayFrames.mDock);
                    } else {
                        cf.set(displayFrames.mContent);
                    }
                } else {
                    cf.set(displayFrames.mRestricted);
                    of.set(displayFrames.mRestricted);
                    df.set(displayFrames.mRestricted);
                    pf.set(displayFrames.mRestricted);
                }

                applyStableConstraints(sysUiFl, fl, cf, displayFrames);

                if (adjust != SOFT_INPUT_ADJUST_NOTHING) {
                    vf.set(displayFrames.mCurrent);
                } else {
                    vf.set(cf);
                }
            } else if (attached != null) {
                if (DEBUG_LAYOUT) Slog.v(TAG, "layoutWindowLw(" + attrs.getTitle()
                        + "): attached to " + attached);
                // A child window should be placed inside of the same visible
                // frame that its parent had.
                setAttachedWindowFrames(win, fl, adjust, attached, false, pf, df, of, cf, vf,
                        displayFrames);
            } else {
                if (DEBUG_LAYOUT) Slog.v(TAG, "layoutWindowLw(" + attrs.getTitle()
                        + "): normal window");
                // Otherwise, a normal window must be placed inside the content
                // of all screen decorations.
                if (type == TYPE_STATUS_BAR_PANEL) {
                    // Status bar panels can go on
                    // top of the status bar. They are protected by the STATUS_BAR_SERVICE
                    // permission, so they have the same privileges as the status bar itself.
                    cf.set(displayFrames.mRestricted);
                    of.set(displayFrames.mRestricted);
                    df.set(displayFrames.mRestricted);
                    pf.set(displayFrames.mRestricted);
                } else if (type == TYPE_TOAST || type == TYPE_SYSTEM_ALERT) {
                    // These dialogs are stable to interim decor changes.
                    cf.set(displayFrames.mStable);
                    of.set(displayFrames.mStable);
                    df.set(displayFrames.mStable);
                    pf.set(displayFrames.mStable);
                } else {
                    pf.set(displayFrames.mContent);
                    if (win.isVoiceInteraction()) {
                        cf.set(displayFrames.mVoiceContent);
                        of.set(displayFrames.mVoiceContent);
                        df.set(displayFrames.mVoiceContent);
                    } else if (adjust != SOFT_INPUT_ADJUST_RESIZE) {
                        cf.set(displayFrames.mDock);
                        of.set(displayFrames.mDock);
                        df.set(displayFrames.mDock);
                    } else {
                        cf.set(displayFrames.mContent);
                        of.set(displayFrames.mContent);
                        df.set(displayFrames.mContent);
                    }
                    if (adjust != SOFT_INPUT_ADJUST_NOTHING) {
                        vf.set(displayFrames.mCurrent);
                    } else {
                        vf.set(cf);
                    }
                }
            }
        }

        final int cutoutMode = attrs.layoutInDisplayCutoutMode;
        final boolean attachedInParent = attached != null && !layoutInScreen;
        final boolean requestedHideNavigation =
                (requestedSysUiFl & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0;

        // TYPE_BASE_APPLICATION windows are never considered floating here because they don't get
        // cropped / shifted to the displayFrame in WindowState.
        final boolean floatingInScreenWindow = !attrs.isFullscreen() && layoutInScreen
                && type != TYPE_BASE_APPLICATION;

        // Ensure that windows with a DEFAULT or NEVER display cutout mode are laid out in
        // the cutout safe zone.
        if (cutoutMode != LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
            final Rect displayCutoutSafeExceptMaybeBars = sTmpDisplayCutoutSafeExceptMaybeBarsRect;
            displayCutoutSafeExceptMaybeBars.set(displayFrames.mDisplayCutoutSafe);
            if (layoutInScreen && layoutInsetDecor && !requestedFullscreen
                    && cutoutMode == LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT) {
                // At the top we have the status bar, so apps that are
                // LAYOUT_IN_SCREEN | LAYOUT_INSET_DECOR but not FULLSCREEN
                // already expect that there's an inset there and we don't need to exclude
                // the window from that area.
                displayCutoutSafeExceptMaybeBars.top = Integer.MIN_VALUE;
            }
            if (layoutInScreen && layoutInsetDecor && !requestedHideNavigation
                    && cutoutMode == LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT) {
                // Same for the navigation bar.
                switch (mNavigationBarPosition) {
                    case NAV_BAR_BOTTOM:
                        displayCutoutSafeExceptMaybeBars.bottom = Integer.MAX_VALUE;
                        break;
                    case NAV_BAR_RIGHT:
                        displayCutoutSafeExceptMaybeBars.right = Integer.MAX_VALUE;
                        break;
                    case NAV_BAR_LEFT:
                        displayCutoutSafeExceptMaybeBars.left = Integer.MIN_VALUE;
                        break;
                }
            }
            if (type == TYPE_INPUT_METHOD && mNavigationBarPosition == NAV_BAR_BOTTOM) {
                // The IME can always extend under the bottom cutout if the navbar is there.
                displayCutoutSafeExceptMaybeBars.bottom = Integer.MAX_VALUE;
            }
            // Windows that are attached to a parent and laid out in said parent already avoid
            // the cutout according to that parent and don't need to be further constrained.
            // Floating IN_SCREEN windows get what they ask for and lay out in the full screen.
            // They will later be cropped or shifted using the displayFrame in WindowState,
            // which prevents overlap with the DisplayCutout.
            if (!attachedInParent && !floatingInScreenWindow) {
                sTmpRect.set(pf);
                pf.intersectUnchecked(displayCutoutSafeExceptMaybeBars);
                windowFrames.setParentFrameWasClippedByDisplayCutout(!sTmpRect.equals(pf));
            }
            // Make sure that NO_LIMITS windows clipped to the display don't extend under the
            // cutout.
            df.intersectUnchecked(displayCutoutSafeExceptMaybeBars);
        }

        // Content should never appear in the cutout.
        cf.intersectUnchecked(displayFrames.mDisplayCutoutSafe);

        // TYPE_SYSTEM_ERROR is above the NavigationBar so it can't be allowed to extend over it.
        // Also, we don't allow windows in multi-window mode to extend out of the screen.
        if ((fl & FLAG_LAYOUT_NO_LIMITS) != 0 && type != TYPE_SYSTEM_ERROR
                && !win.inMultiWindowMode()) {
            df.left = df.top = -10000;
            df.right = df.bottom = 10000;
            if (type != TYPE_WALLPAPER) {
                of.left = of.top = cf.left = cf.top = vf.left = vf.top = -10000;
                of.right = of.bottom = cf.right = cf.bottom = vf.right = vf.bottom = 10000;
            }
        }

        // If the device has a chin (e.g. some watches), a dead area at the bottom of the screen we
        // need to provide information to the clients that want to pretend that you can draw there.
        // We only want to apply outsets to certain types of windows. For example, we never want to
        // apply the outsets to floating dialogs, because they wouldn't make sense there.
        final boolean useOutsets = shouldUseOutsets(attrs, fl);
        if (isDefaultDisplay && useOutsets) {
            final Rect osf = windowFrames.mOutsetFrame;
            osf.set(cf.left, cf.top, cf.right, cf.bottom);
            windowFrames.setHasOutsets(true);
            int outset = mWindowOutsetBottom;
            if (outset > 0) {
                int rotation = displayFrames.mRotation;
                if (rotation == Surface.ROTATION_0) {
                    osf.bottom += outset;
                } else if (rotation == Surface.ROTATION_90) {
                    osf.right += outset;
                } else if (rotation == Surface.ROTATION_180) {
                    osf.top -= outset;
                } else if (rotation == Surface.ROTATION_270) {
                    osf.left -= outset;
                }
                if (DEBUG_LAYOUT) Slog.v(TAG, "applying bottom outset of " + outset
                        + " with rotation " + rotation + ", result: " + osf);
            }
        }

        if (DEBUG_LAYOUT) Slog.v(TAG, "Compute frame " + attrs.getTitle()
                + ": sim=#" + Integer.toHexString(sim)
                + " attach=" + attached + " type=" + type
                + String.format(" flags=0x%08x", fl)
                + " pf=" + pf.toShortString() + " df=" + df.toShortString()
                + " of=" + of.toShortString()
                + " cf=" + cf.toShortString() + " vf=" + vf.toShortString()
                + " dcf=" + dcf.toShortString()
                + " sf=" + sf.toShortString()
                + " osf=" + windowFrames.mOutsetFrame.toShortString() + " " + win);

        if (!sTmpLastParentFrame.equals(pf)) {
            windowFrames.setContentChanged(true);
        }

        win.computeFrameLw();
        // Dock windows carve out the bottom of the screen, so normal windows
        // can't appear underneath them.
        if (type == TYPE_INPUT_METHOD && win.isVisibleLw()
                && !win.getGivenInsetsPendingLw()) {
            offsetInputMethodWindowLw(win, displayFrames);
        }
        if (type == TYPE_VOICE_INTERACTION && win.isVisibleLw()
                && !win.getGivenInsetsPendingLw()) {
            offsetVoiceInputWindowLw(win, displayFrames);
        }
    }

    private void layoutWallpaper(DisplayFrames displayFrames, Rect pf, Rect df, Rect of, Rect cf) {
        // The wallpaper has Real Ultimate Power, but we want to tell it about the overscan area.
        df.set(displayFrames.mOverscan);
        pf.set(displayFrames.mOverscan);
        cf.set(displayFrames.mUnrestricted);
        of.set(displayFrames.mUnrestricted);
    }

    private void offsetInputMethodWindowLw(WindowState win, DisplayFrames displayFrames) {
        int top = Math.max(win.getDisplayFrameLw().top, win.getContentFrameLw().top);
        top += win.getGivenContentInsetsLw().top;
        displayFrames.mContent.bottom = Math.min(displayFrames.mContent.bottom, top);
        displayFrames.mVoiceContent.bottom = Math.min(displayFrames.mVoiceContent.bottom, top);
        top = win.getVisibleFrameLw().top;
        top += win.getGivenVisibleInsetsLw().top;
        displayFrames.mCurrent.bottom = Math.min(displayFrames.mCurrent.bottom, top);
        if (DEBUG_LAYOUT) Slog.v(TAG, "Input method: mDockBottom="
                + displayFrames.mDock.bottom + " mContentBottom="
                + displayFrames.mContent.bottom + " mCurBottom=" + displayFrames.mCurrent.bottom);
    }

    private void offsetVoiceInputWindowLw(WindowState win, DisplayFrames displayFrames) {
        int top = Math.max(win.getDisplayFrameLw().top, win.getContentFrameLw().top);
        top += win.getGivenContentInsetsLw().top;
        displayFrames.mVoiceContent.bottom = Math.min(displayFrames.mVoiceContent.bottom, top);
    }

    WindowState getTopFullscreenOpaqueWindow() {
        return mTopFullscreenOpaqueWindowState;
    }

    boolean isTopLayoutFullscreen() {
        return mTopIsFullscreen;
    }

    /**
     * Called following layout of all windows before each window has policy applied.
     */
    public void beginPostLayoutPolicyLw() {
        mTopFullscreenOpaqueWindowState = null;
        mTopFullscreenOpaqueOrDimmingWindowState = null;
        mTopDockedOpaqueWindowState = null;
        mTopDockedOpaqueOrDimmingWindowState = null;
        mForceStatusBar = false;
        mForceStatusBarFromKeyguard = false;
        mForceStatusBarTransparent = false;
        mForcingShowNavBar = false;
        mForcingShowNavBarLayer = -1;

        mAllowLockscreenWhenOn = false;
        mShowingDream = false;
        mWindowSleepTokenNeeded = false;
        mIsFreeformWindowOverlappingWithNavBar = false;
    }

    /**
     * Called following layout of all window to apply policy to each window.
     *
     * @param win The window being positioned.
     * @param attrs The LayoutParams of the window.
     * @param attached For sub-windows, the window it is attached to. Otherwise null.
     */
    public void applyPostLayoutPolicyLw(WindowState win, WindowManager.LayoutParams attrs,
            WindowState attached, WindowState imeTarget) {
        final boolean affectsSystemUi = win.canAffectSystemUiFlags();
        if (DEBUG_LAYOUT) Slog.i(TAG, "Win " + win + ": affectsSystemUi=" + affectsSystemUi);
        mService.mPolicy.applyKeyguardPolicyLw(win, imeTarget);
        final int fl = PolicyControl.getWindowFlags(win, attrs);
        if (mTopFullscreenOpaqueWindowState == null && affectsSystemUi
                && attrs.type == TYPE_INPUT_METHOD) {
            mForcingShowNavBar = true;
            mForcingShowNavBarLayer = win.getSurfaceLayer();
        }
        if (attrs.type == TYPE_STATUS_BAR) {
            if ((attrs.privateFlags & PRIVATE_FLAG_KEYGUARD) != 0) {
                mForceStatusBarFromKeyguard = true;
            }
            if ((attrs.privateFlags & PRIVATE_FLAG_FORCE_STATUS_BAR_VISIBLE_TRANSPARENT) != 0) {
                mForceStatusBarTransparent = true;
            }
        }

        boolean appWindow = attrs.type >= FIRST_APPLICATION_WINDOW
                && attrs.type < FIRST_SYSTEM_WINDOW;
        final int windowingMode = win.getWindowingMode();
        final boolean inFullScreenOrSplitScreenSecondaryWindowingMode =
                windowingMode == WINDOWING_MODE_FULLSCREEN
                        || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
        if (mTopFullscreenOpaqueWindowState == null && affectsSystemUi) {
            if ((fl & FLAG_FORCE_NOT_FULLSCREEN) != 0) {
                mForceStatusBar = true;
            }
            if (attrs.type == TYPE_DREAM) {
                // If the lockscreen was showing when the dream started then wait
                // for the dream to draw before hiding the lockscreen.
                if (!mDreamingLockscreen
                        || (win.isVisibleLw() && win.hasDrawnLw())) {
                    mShowingDream = true;
                    appWindow = true;
                }
            }

            // For app windows that are not attached, we decide if all windows in the app they
            // represent should be hidden or if we should hide the lockscreen. For attached app
            // windows we defer the decision to the window it is attached to.
            if (appWindow && attached == null) {
                if (attrs.isFullscreen() && inFullScreenOrSplitScreenSecondaryWindowingMode) {
                    if (DEBUG_LAYOUT) Slog.v(TAG, "Fullscreen window: " + win);
                    mTopFullscreenOpaqueWindowState = win;
                    if (mTopFullscreenOpaqueOrDimmingWindowState == null) {
                        mTopFullscreenOpaqueOrDimmingWindowState = win;
                    }
                    if ((fl & FLAG_ALLOW_LOCK_WHILE_SCREEN_ON) != 0) {
                        mAllowLockscreenWhenOn = true;
                    }
                }
            }
        }

        // Voice interaction overrides both top fullscreen and top docked.
        if (affectsSystemUi && attrs.type == TYPE_VOICE_INTERACTION) {
            if (mTopFullscreenOpaqueWindowState == null) {
                mTopFullscreenOpaqueWindowState = win;
                if (mTopFullscreenOpaqueOrDimmingWindowState == null) {
                    mTopFullscreenOpaqueOrDimmingWindowState = win;
                }
            }
            if (mTopDockedOpaqueWindowState == null) {
                mTopDockedOpaqueWindowState = win;
                if (mTopDockedOpaqueOrDimmingWindowState == null) {
                    mTopDockedOpaqueOrDimmingWindowState = win;
                }
            }
        }

        // Keep track of the window if it's dimming but not necessarily fullscreen.
        if (mTopFullscreenOpaqueOrDimmingWindowState == null && affectsSystemUi
                && win.isDimming() && inFullScreenOrSplitScreenSecondaryWindowingMode) {
            mTopFullscreenOpaqueOrDimmingWindowState = win;
        }

        // We need to keep track of the top "fullscreen" opaque window for the docked stack
        // separately, because both the "real fullscreen" opaque window and the one for the docked
        // stack can control View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.
        if (mTopDockedOpaqueWindowState == null && affectsSystemUi && appWindow && attached == null
                && attrs.isFullscreen() && windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            mTopDockedOpaqueWindowState = win;
            if (mTopDockedOpaqueOrDimmingWindowState == null) {
                mTopDockedOpaqueOrDimmingWindowState = win;
            }
        }

        // Check if the freeform window overlaps with the navigation bar area.
        final WindowState navBarWin = hasNavigationBar() ? mNavigationBar : null;
        if (!mIsFreeformWindowOverlappingWithNavBar && win.inFreeformWindowingMode()
                && isOverlappingWithNavBar(win, navBarWin)) {
            mIsFreeformWindowOverlappingWithNavBar = true;
        }

        // Also keep track of any windows that are dimming but not necessarily fullscreen in the
        // docked stack.
        if (mTopDockedOpaqueOrDimmingWindowState == null && affectsSystemUi && win.isDimming()
                && windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            mTopDockedOpaqueOrDimmingWindowState = win;
        }
    }

    /**
     * Called following layout of all windows and after policy has been applied
     * to each window. If in this function you do
     * something that may have modified the animation state of another window,
     * be sure to return non-zero in order to perform another pass through layout.
     *
     * @return Return any bit set of
     *         {@link WindowManagerPolicy#FINISH_LAYOUT_REDO_LAYOUT},
     *         {@link WindowManagerPolicy#FINISH_LAYOUT_REDO_CONFIG},
     *         {@link WindowManagerPolicy#FINISH_LAYOUT_REDO_WALLPAPER}, or
     *         {@link WindowManagerPolicy#FINISH_LAYOUT_REDO_ANIM}.
     */
    public int finishPostLayoutPolicyLw() {
        int changes = 0;
        boolean topIsFullscreen = false;

        // If we are not currently showing a dream then remember the current
        // lockscreen state.  We will use this to determine whether the dream
        // started while the lockscreen was showing and remember this state
        // while the dream is showing.
        if (!mShowingDream) {
            mDreamingLockscreen = mService.mPolicy.isKeyguardShowingAndNotOccluded();
            if (mDreamingSleepTokenNeeded) {
                mDreamingSleepTokenNeeded = false;
                mHandler.obtainMessage(MSG_UPDATE_DREAMING_SLEEP_TOKEN, 0, 1).sendToTarget();
            }
        } else {
            if (!mDreamingSleepTokenNeeded) {
                mDreamingSleepTokenNeeded = true;
                mHandler.obtainMessage(MSG_UPDATE_DREAMING_SLEEP_TOKEN, 1, 1).sendToTarget();
            }
        }

        if (mStatusBar != null) {
            if (DEBUG_LAYOUT) Slog.i(TAG, "force=" + mForceStatusBar
                    + " forcefkg=" + mForceStatusBarFromKeyguard
                    + " top=" + mTopFullscreenOpaqueWindowState);
            boolean shouldBeTransparent = mForceStatusBarTransparent
                    && !mForceStatusBar
                    && !mForceStatusBarFromKeyguard;
            if (!shouldBeTransparent) {
                mStatusBarController.setShowTransparent(false /* transparent */);
            } else if (!mStatusBar.isVisibleLw()) {
                mStatusBarController.setShowTransparent(true /* transparent */);
            }

            boolean statusBarForcesShowingNavigation =
                    (mStatusBar.getAttrs().privateFlags
                            & PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION) != 0;
            boolean topAppHidesStatusBar = topAppHidesStatusBar();
            if (mForceStatusBar || mForceStatusBarFromKeyguard || mForceStatusBarTransparent
                    || statusBarForcesShowingNavigation) {
                if (DEBUG_LAYOUT) Slog.v(TAG, "Showing status bar: forced");
                if (mStatusBarController.setBarShowingLw(true)) {
                    changes |= FINISH_LAYOUT_REDO_LAYOUT;
                }
                // Maintain fullscreen layout until incoming animation is complete.
                topIsFullscreen = mTopIsFullscreen && mStatusBar.isAnimatingLw();
                // Transient status bar is not allowed if status bar is on lockscreen or status bar
                // is expecting the navigation keys from the user.
                if ((mForceStatusBarFromKeyguard || statusBarForcesShowingNavigation)
                        && mStatusBarController.isTransientShowing()) {
                    mStatusBarController.updateVisibilityLw(false /*transientAllowed*/,
                            mLastSystemUiFlags, mLastSystemUiFlags);
                }
            } else if (mTopFullscreenOpaqueWindowState != null) {
                topIsFullscreen = topAppHidesStatusBar;
                // The subtle difference between the window for mTopFullscreenOpaqueWindowState
                // and mTopIsFullscreen is that mTopIsFullscreen is set only if the window
                // has the FLAG_FULLSCREEN set.  Not sure if there is another way that to be the
                // case though.
                if (mStatusBarController.isTransientShowing()) {
                    if (mStatusBarController.setBarShowingLw(true)) {
                        changes |= FINISH_LAYOUT_REDO_LAYOUT;
                    }
                } else if (topIsFullscreen
                        && !mDisplayContent.isStackVisible(WINDOWING_MODE_FREEFORM)
                        && !mDisplayContent.isStackVisible(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY)) {
                    if (DEBUG_LAYOUT) Slog.v(TAG, "** HIDING status bar");
                    if (mStatusBarController.setBarShowingLw(false)) {
                        changes |= FINISH_LAYOUT_REDO_LAYOUT;
                    } else {
                        if (DEBUG_LAYOUT) Slog.v(TAG, "Status bar already hiding");
                    }
                } else {
                    if (DEBUG_LAYOUT) Slog.v(TAG, "** SHOWING status bar: top is not fullscreen");
                    if (mStatusBarController.setBarShowingLw(true)) {
                        changes |= FINISH_LAYOUT_REDO_LAYOUT;
                    }
                    topAppHidesStatusBar = false;
                }
            }
            mStatusBarController.setTopAppHidesStatusBar(topAppHidesStatusBar);
        }

        if (mTopIsFullscreen != topIsFullscreen) {
            if (!topIsFullscreen) {
                // Force another layout when status bar becomes fully shown.
                changes |= FINISH_LAYOUT_REDO_LAYOUT;
            }
            mTopIsFullscreen = topIsFullscreen;
        }

        if ((updateSystemUiVisibilityLw() & SYSTEM_UI_CHANGING_LAYOUT) != 0) {
            // If the navigation bar has been hidden or shown, we need to do another
            // layout pass to update that window.
            changes |= FINISH_LAYOUT_REDO_LAYOUT;
        }

        if (mShowingDream != mLastShowingDream) {
            mLastShowingDream = mShowingDream;
            mService.notifyShowingDreamChanged();
        }

        updateWindowSleepToken();

        mService.mPolicy.setAllowLockscreenWhenOn(getDisplayId(), mAllowLockscreenWhenOn);
        return changes;
    }

    private void updateWindowSleepToken() {
        if (mWindowSleepTokenNeeded && !mLastWindowSleepTokenNeeded) {
            mHandler.removeCallbacks(mReleaseSleepTokenRunnable);
            mHandler.post(mAcquireSleepTokenRunnable);
        } else if (!mWindowSleepTokenNeeded && mLastWindowSleepTokenNeeded) {
            mHandler.removeCallbacks(mAcquireSleepTokenRunnable);
            mHandler.post(mReleaseSleepTokenRunnable);
        }
        mLastWindowSleepTokenNeeded = mWindowSleepTokenNeeded;
    }

    /**
     * @return Whether the top app should hide the statusbar based on the top fullscreen opaque
     *         window.
     */
    private boolean topAppHidesStatusBar() {
        if (mTopFullscreenOpaqueWindowState == null) {
            return false;
        }
        final int fl = PolicyControl.getWindowFlags(null,
                mTopFullscreenOpaqueWindowState.getAttrs());
        if (WindowManagerDebugConfig.DEBUG) {
            Slog.d(TAG, "frame: " + mTopFullscreenOpaqueWindowState.getFrameLw());
            Slog.d(TAG, "attr: " + mTopFullscreenOpaqueWindowState.getAttrs()
                    + " lp.flags=0x" + Integer.toHexString(fl));
        }
        return (fl & LayoutParams.FLAG_FULLSCREEN) != 0
                || (mLastSystemUiFlags & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0;
    }

    /**
     * Called when the user is switched.
     */
    public void switchUser() {
        updateCurrentUserResources();
    }

    /**
     * Called when the resource overlays change.
     */
    public void onOverlayChangedLw() {
        updateCurrentUserResources();
        onConfigurationChanged();
        mSystemGestures.onConfigurationChanged();
    }

    /**
     * Called when the configuration has changed, and it's safe to load new values from resources.
     */
    public void onConfigurationChanged() {
        final DisplayRotation displayRotation = mDisplayContent.getDisplayRotation();

        final Resources res = getCurrentUserResources();
        final int portraitRotation = displayRotation.getPortraitRotation();
        final int upsideDownRotation = displayRotation.getUpsideDownRotation();
        final int landscapeRotation = displayRotation.getLandscapeRotation();
        final int seascapeRotation = displayRotation.getSeascapeRotation();
        final int uiMode = mService.mPolicy.getUiMode();

        if (hasStatusBar()) {
            mStatusBarHeightForRotation[portraitRotation] =
                    mStatusBarHeightForRotation[upsideDownRotation] =
                            res.getDimensionPixelSize(R.dimen.status_bar_height_portrait);
            mStatusBarHeightForRotation[landscapeRotation] =
                    mStatusBarHeightForRotation[seascapeRotation] =
                            res.getDimensionPixelSize(R.dimen.status_bar_height_landscape);
        } else {
            mStatusBarHeightForRotation[portraitRotation] =
                    mStatusBarHeightForRotation[upsideDownRotation] =
                            mStatusBarHeightForRotation[landscapeRotation] =
                                    mStatusBarHeightForRotation[seascapeRotation] = 0;
        }

        // Height of the navigation bar when presented horizontally at bottom
        mNavigationBarHeightForRotationDefault[portraitRotation] =
        mNavigationBarHeightForRotationDefault[upsideDownRotation] =
                res.getDimensionPixelSize(R.dimen.navigation_bar_height);
        mNavigationBarHeightForRotationDefault[landscapeRotation] =
        mNavigationBarHeightForRotationDefault[seascapeRotation] =
                res.getDimensionPixelSize(R.dimen.navigation_bar_height_landscape);

        // Height of the navigation bar frame when presented horizontally at bottom
        mNavigationBarFrameHeightForRotationDefault[portraitRotation] =
        mNavigationBarFrameHeightForRotationDefault[upsideDownRotation] =
                res.getDimensionPixelSize(R.dimen.navigation_bar_frame_height);
        mNavigationBarFrameHeightForRotationDefault[landscapeRotation] =
        mNavigationBarFrameHeightForRotationDefault[seascapeRotation] =
                res.getDimensionPixelSize(R.dimen.navigation_bar_frame_height_landscape);

        // Width of the navigation bar when presented vertically along one side
        mNavigationBarWidthForRotationDefault[portraitRotation] =
        mNavigationBarWidthForRotationDefault[upsideDownRotation] =
        mNavigationBarWidthForRotationDefault[landscapeRotation] =
        mNavigationBarWidthForRotationDefault[seascapeRotation] =
                res.getDimensionPixelSize(R.dimen.navigation_bar_width);

        if (ALTERNATE_CAR_MODE_NAV_SIZE) {
            // Height of the navigation bar when presented horizontally at bottom
            mNavigationBarHeightForRotationInCarMode[portraitRotation] =
            mNavigationBarHeightForRotationInCarMode[upsideDownRotation] =
                    res.getDimensionPixelSize(R.dimen.navigation_bar_height_car_mode);
            mNavigationBarHeightForRotationInCarMode[landscapeRotation] =
            mNavigationBarHeightForRotationInCarMode[seascapeRotation] =
                    res.getDimensionPixelSize(R.dimen.navigation_bar_height_landscape_car_mode);

            // Width of the navigation bar when presented vertically along one side
            mNavigationBarWidthForRotationInCarMode[portraitRotation] =
            mNavigationBarWidthForRotationInCarMode[upsideDownRotation] =
            mNavigationBarWidthForRotationInCarMode[landscapeRotation] =
            mNavigationBarWidthForRotationInCarMode[seascapeRotation] =
                    res.getDimensionPixelSize(R.dimen.navigation_bar_width_car_mode);
        }

        mNavBarOpacityMode = res.getInteger(R.integer.config_navBarOpacityMode);
        mSideGestureInset = res.getDimensionPixelSize(R.dimen.config_backGestureInset);
        mNavigationBarLetsThroughTaps = res.getBoolean(R.bool.config_navBarTapThrough);
        mNavigationBarAlwaysShowOnSideGesture =
                res.getBoolean(R.bool.config_navBarAlwaysShowOnSideEdgeGesture);

        // This should calculate how much above the frame we accept gestures.
        mBottomGestureAdditionalInset =
                res.getDimensionPixelSize(R.dimen.navigation_bar_gesture_height)
                        - getNavigationBarFrameHeight(portraitRotation, uiMode);

        updateConfigurationAndScreenSizeDependentBehaviors();
        mWindowOutsetBottom = ScreenShapeHelper.getWindowOutsetBottomPx(mContext.getResources());
    }

    void updateConfigurationAndScreenSizeDependentBehaviors() {
        final Resources res = getCurrentUserResources();
        mNavigationBarCanMove =
                mDisplayContent.mBaseDisplayWidth != mDisplayContent.mBaseDisplayHeight
                        && res.getBoolean(R.bool.config_navBarCanMove);
        mDisplayContent.getDisplayRotation().updateUserDependentConfiguration(res);
    }

    /**
     * Updates the current user's resources to pick up any changes for the current user (including
     * overlay paths)
     */
    private void updateCurrentUserResources() {
        final int userId = mService.mAmInternal.getCurrentUserId();
        final Context uiContext = getSystemUiContext();

        if (userId == UserHandle.USER_SYSTEM) {
            // Skip the (expensive) recreation of resources for the system user below and just
            // use the resources from the system ui context
            mCurrentUserResources = uiContext.getResources();
            return;
        }

        // For non-system users, ensure that the resources are loaded from the current
        // user's package info (see ContextImpl.createDisplayContext)
        final LoadedApk pi = ActivityThread.currentActivityThread().getPackageInfo(
                uiContext.getPackageName(), null, 0, userId);
        mCurrentUserResources = ResourcesManager.getInstance().getResources(null,
                pi.getResDir(),
                null /* splitResDirs */,
                pi.getOverlayDirs(),
                pi.getApplicationInfo().sharedLibraryFiles,
                mDisplayContent.getDisplayId(),
                null /* overrideConfig */,
                uiContext.getResources().getCompatibilityInfo(),
                null /* classLoader */);
    }

    @VisibleForTesting
    Resources getCurrentUserResources() {
        if (mCurrentUserResources == null) {
            updateCurrentUserResources();
        }
        return mCurrentUserResources;
    }

    @VisibleForTesting
    Context getContext() {
        return mContext;
    }

    private Context getSystemUiContext() {
        final Context uiContext = ActivityThread.currentActivityThread().getSystemUiContext();
        return mDisplayContent.isDefaultDisplay
                ? uiContext : uiContext.createDisplayContext(mDisplayContent.getDisplay());
    }

    private int getNavigationBarWidth(int rotation, int uiMode) {
        if (ALTERNATE_CAR_MODE_NAV_SIZE && (uiMode & UI_MODE_TYPE_MASK) == UI_MODE_TYPE_CAR) {
            return mNavigationBarWidthForRotationInCarMode[rotation];
        } else {
            return mNavigationBarWidthForRotationDefault[rotation];
        }
    }

    void notifyDisplayReady() {
        mHandler.post(() -> {
            final int displayId = getDisplayId();
            getStatusBarManagerInternal().onDisplayReady(displayId);
            final WallpaperManagerInternal wpMgr = LocalServices
                    .getService(WallpaperManagerInternal.class);
            if (wpMgr != null) {
                wpMgr.onDisplayReady(displayId);
            }
        });
    }

    /**
     * Return the display width available after excluding any screen
     * decorations that could never be removed in Honeycomb. That is, system bar or
     * button bar.
     */
    public int getNonDecorDisplayWidth(int fullWidth, int fullHeight, int rotation, int uiMode,
            DisplayCutout displayCutout) {
        int width = fullWidth;
        if (hasNavigationBar()) {
            final int navBarPosition = navigationBarPosition(fullWidth, fullHeight, rotation);
            if (navBarPosition == NAV_BAR_LEFT || navBarPosition == NAV_BAR_RIGHT) {
                width -= getNavigationBarWidth(rotation, uiMode);
            }
        }
        if (displayCutout != null) {
            width -= displayCutout.getSafeInsetLeft() + displayCutout.getSafeInsetRight();
        }
        return width;
    }

    private int getNavigationBarHeight(int rotation, int uiMode) {
        if (ALTERNATE_CAR_MODE_NAV_SIZE && (uiMode & UI_MODE_TYPE_MASK) == UI_MODE_TYPE_CAR) {
            return mNavigationBarHeightForRotationInCarMode[rotation];
        } else {
            return mNavigationBarHeightForRotationDefault[rotation];
        }
    }

    /**
     * Get the Navigation Bar Frame height. This dimension is the height of the navigation bar that
     * is used for spacing to show additional buttons on the navigation bar (such as the ime
     * switcher when ime is visible) while {@link #getNavigationBarHeight} is used for the visible
     * height that we send to the app as content insets that can be smaller.
     * <p>
     * In car mode it will return the same height as {@link #getNavigationBarHeight}
     *
     * @param rotation specifies rotation to return dimension from
     * @param uiMode to determine if in car mode
     * @return navigation bar frame height
     */
    private int getNavigationBarFrameHeight(int rotation, int uiMode) {
        if (ALTERNATE_CAR_MODE_NAV_SIZE && (uiMode & UI_MODE_TYPE_MASK) == UI_MODE_TYPE_CAR) {
            return mNavigationBarHeightForRotationInCarMode[rotation];
        } else {
            return mNavigationBarFrameHeightForRotationDefault[rotation];
        }
    }

    /**
     * Return the display height available after excluding any screen
     * decorations that could never be removed in Honeycomb. That is, system bar or
     * button bar.
     */
    public int getNonDecorDisplayHeight(int fullWidth, int fullHeight, int rotation, int uiMode,
            DisplayCutout displayCutout) {
        int height = fullHeight;
        if (hasNavigationBar()) {
            final int navBarPosition = navigationBarPosition(fullWidth, fullHeight, rotation);
            if (navBarPosition == NAV_BAR_BOTTOM) {
                height -= getNavigationBarHeight(rotation, uiMode);
            }
        }
        if (displayCutout != null) {
            height -= displayCutout.getSafeInsetTop() + displayCutout.getSafeInsetBottom();
        }
        return height;
    }

    /**
     * Return the available screen width that we should report for the
     * configuration.  This must be no larger than
     * {@link #getNonDecorDisplayWidth(int, int, int, int, DisplayCutout)}; it may be smaller
     * than that to account for more transient decoration like a status bar.
     */
    public int getConfigDisplayWidth(int fullWidth, int fullHeight, int rotation, int uiMode,
            DisplayCutout displayCutout) {
        return getNonDecorDisplayWidth(fullWidth, fullHeight, rotation, uiMode, displayCutout);
    }

    /**
     * Return the available screen height that we should report for the
     * configuration.  This must be no larger than
     * {@link #getNonDecorDisplayHeight(int, int, int, int, DisplayCutout)}; it may be smaller
     * than that to account for more transient decoration like a status bar.
     */
    public int getConfigDisplayHeight(int fullWidth, int fullHeight, int rotation, int uiMode,
            DisplayCutout displayCutout) {
        // There is a separate status bar at the top of the display.  We don't count that as part
        // of the fixed decor, since it can hide; however, for purposes of configurations,
        // we do want to exclude it since applications can't generally use that part
        // of the screen.
        int statusBarHeight = mStatusBarHeightForRotation[rotation];
        if (displayCutout != null) {
            // If there is a cutout, it may already have accounted for some part of the status
            // bar height.
            statusBarHeight = Math.max(0, statusBarHeight - displayCutout.getSafeInsetTop());
        }
        return getNonDecorDisplayHeight(fullWidth, fullHeight, rotation, uiMode, displayCutout)
                - statusBarHeight;
    }

    /**
     * Return corner radius in pixels that should be used on windows in order to cover the display.
     * The radius is only valid for built-in displays since the one who configures window corner
     * radius cannot know the corner radius of non-built-in display.
     */
    float getWindowCornerRadius() {
        return mDisplayContent.getDisplay().getType() == TYPE_BUILT_IN
                ? ScreenDecorationsUtils.getWindowCornerRadius(mContext.getResources()) : 0f;
    }

    boolean isShowingDreamLw() {
        return mShowingDream;
    }

    /**
     * Calculates the stable insets if we already have the non-decor insets.
     *
     * @param inOutInsets The known non-decor insets. It will be modified to stable insets.
     * @param rotation The current display rotation.
     */
    void convertNonDecorInsetsToStableInsets(Rect inOutInsets, int rotation) {
        inOutInsets.top = Math.max(inOutInsets.top, mStatusBarHeightForRotation[rotation]);
    }

    /**
     * Calculates the stable insets without running a layout.
     *
     * @param displayRotation the current display rotation
     * @param displayWidth the current display width
     * @param displayHeight the current display height
     * @param displayCutout the current display cutout
     * @param outInsets the insets to return
     */
    public void getStableInsetsLw(int displayRotation, int displayWidth, int displayHeight,
            DisplayCutout displayCutout, Rect outInsets) {
        outInsets.setEmpty();

        // Navigation bar and status bar.
        getNonDecorInsetsLw(displayRotation, displayWidth, displayHeight, displayCutout, outInsets);
        convertNonDecorInsetsToStableInsets(outInsets, displayRotation);
    }

    /**
     * Calculates the insets for the areas that could never be removed in Honeycomb, i.e. system
     * bar or button bar. See {@link #getNonDecorDisplayWidth}.
     *
     * @param displayRotation the current display rotation
     * @param displayWidth the current display width
     * @param displayHeight the current display height
     * @param displayCutout the current display cutout
     * @param outInsets the insets to return
     */
    public void getNonDecorInsetsLw(int displayRotation, int displayWidth, int displayHeight,
            DisplayCutout displayCutout, Rect outInsets) {
        outInsets.setEmpty();

        // Only navigation bar
        if (hasNavigationBar()) {
            final int uiMode = mService.mPolicy.getUiMode();
            int position = navigationBarPosition(displayWidth, displayHeight, displayRotation);
            if (position == NAV_BAR_BOTTOM) {
                outInsets.bottom = getNavigationBarHeight(displayRotation, uiMode);
            } else if (position == NAV_BAR_RIGHT) {
                outInsets.right = getNavigationBarWidth(displayRotation, uiMode);
            } else if (position == NAV_BAR_LEFT) {
                outInsets.left = getNavigationBarWidth(displayRotation, uiMode);
            }
        }

        if (displayCutout != null) {
            outInsets.left += displayCutout.getSafeInsetLeft();
            outInsets.top += displayCutout.getSafeInsetTop();
            outInsets.right += displayCutout.getSafeInsetRight();
            outInsets.bottom += displayCutout.getSafeInsetBottom();
        }
    }

    /**
     * @see IWindowManager#setForwardedInsets
     */
    public void setForwardedInsets(@NonNull Insets forwardedInsets) {
        mForwardedInsets = forwardedInsets;
    }

    @NonNull
    public Insets getForwardedInsets() {
        return mForwardedInsets;
    }

    @NavigationBarPosition
    int navigationBarPosition(int displayWidth, int displayHeight, int displayRotation) {
        if (navigationBarCanMove() && displayWidth > displayHeight) {
            if (displayRotation == Surface.ROTATION_270) {
                return NAV_BAR_LEFT;
            } else if (displayRotation == Surface.ROTATION_90) {
                return NAV_BAR_RIGHT;
            }
        }
        return NAV_BAR_BOTTOM;
    }

    /**
     * @return The side of the screen where navigation bar is positioned.
     * @see WindowManagerPolicyConstants#NAV_BAR_LEFT
     * @see WindowManagerPolicyConstants#NAV_BAR_RIGHT
     * @see WindowManagerPolicyConstants#NAV_BAR_BOTTOM
     */
    @NavigationBarPosition
    public int getNavBarPosition() {
        return mNavigationBarPosition;
    }

    /**
     * A new window has been focused.
     */
    public int focusChangedLw(WindowState lastFocus, WindowState newFocus) {
        mFocusedWindow = newFocus;
        mLastFocusedWindow = lastFocus;
        if (mDisplayContent.isDefaultDisplay) {
            mService.mPolicy.onDefaultDisplayFocusChangedLw(newFocus);
        }
        if ((updateSystemUiVisibilityLw() & SYSTEM_UI_CHANGING_LAYOUT) != 0) {
            // If the navigation bar has been hidden or shown, we need to do another
            // layout pass to update that window.
            return FINISH_LAYOUT_REDO_LAYOUT;
        }
        return 0;
    }

    /**
     * Return true if it is okay to perform animations for an app transition
     * that is about to occur. You may return false for this if, for example,
     * the dream window is currently displayed so the switch should happen
     * immediately.
     */
    public boolean allowAppAnimationsLw() {
        return !mShowingDream;
    }

    private void updateDreamingSleepToken(boolean acquire) {
        if (acquire) {
            final int displayId = getDisplayId();
            if (mDreamingSleepToken == null) {
                mDreamingSleepToken = mService.mAtmInternal.acquireSleepToken(
                        "DreamOnDisplay" + displayId, displayId);
            }
        } else {
            if (mDreamingSleepToken != null) {
                mDreamingSleepToken.release();
                mDreamingSleepToken = null;
            }
        }
    }

    private void requestTransientBars(WindowState swipeTarget) {
        synchronized (mLock) {
            if (!mService.mPolicy.isUserSetupComplete()) {
                // Swipe-up for navigation bar is disabled during setup
                return;
            }
            boolean sb = mStatusBarController.checkShowTransientBarLw();
            boolean nb = mNavigationBarController.checkShowTransientBarLw()
                    && !isNavBarEmpty(mLastSystemUiFlags);
            if (sb || nb) {
                // Don't show status bar when swiping on already visible navigation bar
                if (!nb && swipeTarget == mNavigationBar) {
                    if (DEBUG) Slog.d(TAG, "Not showing transient bar, wrong swipe target");
                    return;
                }
                if (sb) mStatusBarController.showTransient();
                if (nb) mNavigationBarController.showTransient();
                mImmersiveModeConfirmation.confirmCurrentPrompt();
                updateSystemUiVisibilityLw();
            }
        }
    }

    private void disposeInputConsumer(InputConsumer inputConsumer) {
        if (inputConsumer != null) {
            inputConsumer.dismiss();
        }
    }

    private boolean isStatusBarKeyguard() {
        return mStatusBar != null
                && (mStatusBar.getAttrs().privateFlags & PRIVATE_FLAG_KEYGUARD) != 0;
    }

    private boolean isKeyguardOccluded() {
        // TODO (b/113840485): Handle per display keyguard.
        return mService.mPolicy.isKeyguardOccluded();
    }

    void resetSystemUiVisibilityLw() {
        mLastSystemUiFlags = 0;
        updateSystemUiVisibilityLw();
    }

    private int updateSystemUiVisibilityLw() {
        // If there is no window focused, there will be nobody to handle the events
        // anyway, so just hang on in whatever state we're in until things settle down.
        WindowState winCandidate = mFocusedWindow != null ? mFocusedWindow
                : mTopFullscreenOpaqueWindowState;
        if (winCandidate == null) {
            return 0;
        }

        // The immersive mode confirmation should never affect the system bar visibility, otherwise
        // it will unhide the navigation bar and hide itself.
        if (winCandidate.getAttrs().token == mImmersiveModeConfirmation.getWindowToken()) {

            // The immersive mode confirmation took the focus from mLastFocusedWindow which was
            // controlling the system ui visibility. So if mLastFocusedWindow can still receive
            // keys, we let it keep controlling the visibility.
            final boolean lastFocusCanReceiveKeys =
                    (mLastFocusedWindow != null && mLastFocusedWindow.canReceiveKeys());
            winCandidate = isStatusBarKeyguard() ? mStatusBar
                    : lastFocusCanReceiveKeys ? mLastFocusedWindow
                            : mTopFullscreenOpaqueWindowState;
            if (winCandidate == null) {
                return 0;
            }
        }
        final WindowState win = winCandidate;
        if ((win.getAttrs().privateFlags & PRIVATE_FLAG_KEYGUARD) != 0 && isKeyguardOccluded()) {
            // We are updating at a point where the keyguard has gotten
            // focus, but we were last in a state where the top window is
            // hiding it.  This is probably because the keyguard as been
            // shown while the top window was displayed, so we want to ignore
            // it here because this is just a very transient change and it
            // will quickly lose focus once it correctly gets hidden.
            return 0;
        }

        mDisplayContent.getInsetsPolicy().updateBarControlTarget(win);

        int tmpVisibility = PolicyControl.getSystemUiVisibility(win, null)
                & ~mResettingSystemUiFlags
                & ~mForceClearedSystemUiFlags;
        if (mForcingShowNavBar && win.getSurfaceLayer() < mForcingShowNavBarLayer) {
            tmpVisibility
                    &= ~PolicyControl.adjustClearableFlags(win, View.SYSTEM_UI_CLEARABLE_FLAGS);
        }

        final int fullscreenVisibility = updateLightStatusBarLw(0 /* vis */,
                mTopFullscreenOpaqueWindowState, mTopFullscreenOpaqueOrDimmingWindowState);
        final int dockedVisibility = updateLightStatusBarLw(0 /* vis */,
                mTopDockedOpaqueWindowState, mTopDockedOpaqueOrDimmingWindowState);
        mService.getStackBounds(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_HOME, mNonDockedStackBounds);
        mService.getStackBounds(
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD, mDockedStackBounds);
        final Pair<Integer, Boolean> result =
                updateSystemBarsLw(win, mLastSystemUiFlags, tmpVisibility);
        final int visibility = result.first;
        final int diff = visibility ^ mLastSystemUiFlags;
        final int fullscreenDiff = fullscreenVisibility ^ mLastFullscreenStackSysUiFlags;
        final int dockedDiff = dockedVisibility ^ mLastDockedStackSysUiFlags;
        final boolean needsMenu = win.getNeedsMenuLw(mTopFullscreenOpaqueWindowState);
        if (diff == 0 && fullscreenDiff == 0 && dockedDiff == 0 && mLastFocusNeedsMenu == needsMenu
                && mFocusedApp == win.getAppToken()
                && mLastNonDockedStackBounds.equals(mNonDockedStackBounds)
                && mLastDockedStackBounds.equals(mDockedStackBounds)) {
            return 0;
        }
        mLastSystemUiFlags = visibility;
        mLastFullscreenStackSysUiFlags = fullscreenVisibility;
        mLastDockedStackSysUiFlags = dockedVisibility;
        mLastFocusNeedsMenu = needsMenu;
        mFocusedApp = win.getAppToken();
        mLastNonDockedStackBounds.set(mNonDockedStackBounds);
        mLastDockedStackBounds.set(mDockedStackBounds);
        final Rect fullscreenStackBounds = new Rect(mNonDockedStackBounds);
        final Rect dockedStackBounds = new Rect(mDockedStackBounds);
        final boolean isNavbarColorManagedByIme = result.second;
        mHandler.post(() -> {
            StatusBarManagerInternal statusBar = getStatusBarManagerInternal();
            if (statusBar != null) {
                final int displayId = getDisplayId();
                statusBar.setSystemUiVisibility(displayId, visibility, fullscreenVisibility,
                        dockedVisibility, 0xffffffff, fullscreenStackBounds,
                        dockedStackBounds, isNavbarColorManagedByIme, win.toString());
                statusBar.topAppWindowChanged(displayId, needsMenu);
            }
        });
        return diff;
    }

    private int updateLightStatusBarLw(int vis, WindowState opaque, WindowState opaqueOrDimming) {
        final boolean onKeyguard = isStatusBarKeyguard() && !isKeyguardOccluded();
        final WindowState statusColorWin = onKeyguard ? mStatusBar : opaqueOrDimming;
        if (statusColorWin != null && (statusColorWin == opaque || onKeyguard)) {
            // If the top fullscreen-or-dimming window is also the top fullscreen, respect
            // its light flag.
            vis &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            vis |= PolicyControl.getSystemUiVisibility(statusColorWin, null)
                    & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else if (statusColorWin != null && statusColorWin.isDimming()) {
            // Otherwise if it's dimming, clear the light flag.
            vis &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        return vis;
    }

    @VisibleForTesting
    @Nullable
    static WindowState chooseNavigationColorWindowLw(WindowState opaque,
            WindowState opaqueOrDimming, WindowState imeWindow,
            @NavigationBarPosition int navBarPosition) {
        // If the IME window is visible and FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS is set, then IME
        // window can be navigation color window.
        final boolean imeWindowCanNavColorWindow = imeWindow != null
                && imeWindow.isVisibleLw()
                && navBarPosition == NAV_BAR_BOTTOM
                && (PolicyControl.getWindowFlags(imeWindow, null)
                & WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0;

        if (opaque != null && opaqueOrDimming == opaque) {
            // If the top fullscreen-or-dimming window is also the top fullscreen, respect it
            // unless IME window is also eligible, since currently the IME window is always show
            // above the opaque fullscreen app window, regardless of the IME target window.
            // TODO(b/31559891): Maybe we need to revisit this condition once b/31559891 is fixed.
            return imeWindowCanNavColorWindow ? imeWindow : opaque;
        }

        if (opaqueOrDimming == null || !opaqueOrDimming.isDimming()) {
            // No dimming window is involved. Determine the result only with the IME window.
            return imeWindowCanNavColorWindow ? imeWindow : null;
        }

        if (!imeWindowCanNavColorWindow) {
            // No IME window is involved. Determine the result only with opaqueOrDimming.
            return opaqueOrDimming;
        }

        // The IME window and the dimming window are competing.  Check if the dimming window can be
        // IME target or not.
        if (LayoutParams.mayUseInputMethod(PolicyControl.getWindowFlags(opaqueOrDimming, null))) {
            // The IME window is above the dimming window.
            return imeWindow;
        } else {
            // The dimming window is above the IME window.
            return opaqueOrDimming;
        }
    }

    @VisibleForTesting
    static int updateLightNavigationBarLw(int vis, WindowState opaque, WindowState opaqueOrDimming,
            WindowState imeWindow, WindowState navColorWin) {

        if (navColorWin != null) {
            if (navColorWin == imeWindow || navColorWin == opaque) {
                // Respect the light flag.
                vis &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                vis |= PolicyControl.getSystemUiVisibility(navColorWin, null)
                        & View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else if (navColorWin == opaqueOrDimming && navColorWin.isDimming()) {
                // Clear the light flag for dimming window.
                vis &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        return vis;
    }

    private Pair<Integer, Boolean> updateSystemBarsLw(WindowState win, int oldVis, int vis) {
        final boolean dockedStackVisible =
                mDisplayContent.isStackVisible(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        final boolean freeformStackVisible =
                mDisplayContent.isStackVisible(WINDOWING_MODE_FREEFORM);
        final boolean resizing = mDisplayContent.getDockedDividerController().isResizing();

        // We need to force system bars when the docked stack is visible, when the freeform stack
        // is visible but also when we are resizing for the transitions when docked stack
        // visibility changes.
        mForceShowSystemBars = dockedStackVisible || freeformStackVisible || resizing
                || mForceShowSystemBarsFromExternal;
        final boolean forceOpaqueStatusBar = mForceShowSystemBars && !mForceStatusBarFromKeyguard;

        // apply translucent bar vis flags
        WindowState fullscreenTransWin = isStatusBarKeyguard() && !isKeyguardOccluded()
                ? mStatusBar
                : mTopFullscreenOpaqueWindowState;
        vis = mStatusBarController.applyTranslucentFlagLw(fullscreenTransWin, vis, oldVis);
        vis = mNavigationBarController.applyTranslucentFlagLw(fullscreenTransWin, vis, oldVis);
        int dockedVis = mStatusBarController.applyTranslucentFlagLw(
                mTopDockedOpaqueWindowState, 0, 0);
        dockedVis = mNavigationBarController.applyTranslucentFlagLw(
                mTopDockedOpaqueWindowState, dockedVis, 0);

        final boolean fullscreenDrawsStatusBarBackground =
                drawsStatusBarBackground(vis, mTopFullscreenOpaqueWindowState);
        final boolean dockedDrawsStatusBarBackground =
                drawsStatusBarBackground(dockedVis, mTopDockedOpaqueWindowState);
        final boolean fullscreenDrawsNavBarBackground =
                drawsNavigationBarBackground(vis, mTopFullscreenOpaqueWindowState);
        final boolean dockedDrawsNavigationBarBackground =
                drawsNavigationBarBackground(dockedVis, mTopDockedOpaqueWindowState);

        // prevent status bar interaction from clearing certain flags
        int type = win.getAttrs().type;
        boolean statusBarHasFocus = type == TYPE_STATUS_BAR;
        if (statusBarHasFocus && !isStatusBarKeyguard()) {
            int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (isKeyguardOccluded()) {
                flags |= View.STATUS_BAR_TRANSLUCENT | View.NAVIGATION_BAR_TRANSLUCENT;
            }
            vis = (vis & ~flags) | (oldVis & flags);
        }

        if (fullscreenDrawsStatusBarBackground && dockedDrawsStatusBarBackground) {
            vis |= View.STATUS_BAR_TRANSPARENT;
            vis &= ~View.STATUS_BAR_TRANSLUCENT;
        } else if (forceOpaqueStatusBar) {
            vis &= ~(View.STATUS_BAR_TRANSLUCENT | View.STATUS_BAR_TRANSPARENT);
        }

        vis = configureNavBarOpacity(vis, dockedStackVisible, freeformStackVisible, resizing,
                fullscreenDrawsNavBarBackground, dockedDrawsNavigationBarBackground);

        // update status bar
        boolean immersiveSticky =
                (vis & View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) != 0;
        final boolean hideStatusBarWM =
                mTopFullscreenOpaqueWindowState != null
                        && (PolicyControl.getWindowFlags(mTopFullscreenOpaqueWindowState, null)
                        & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0;
        final boolean hideStatusBarSysui =
                (vis & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0;
        final boolean hideNavBarSysui =
                (vis & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0;

        final boolean transientStatusBarAllowed = mStatusBar != null
                && (statusBarHasFocus || (!mForceShowSystemBars
                && (hideStatusBarWM || (hideStatusBarSysui && immersiveSticky))));

        final boolean transientNavBarAllowed = mNavigationBar != null
                && !mForceShowSystemBars && hideNavBarSysui && immersiveSticky;

        final long now = SystemClock.uptimeMillis();
        final boolean pendingPanic = mPendingPanicGestureUptime != 0
                && now - mPendingPanicGestureUptime <= PANIC_GESTURE_EXPIRATION;
        final DisplayPolicy defaultDisplayPolicy =
                mService.getDefaultDisplayContentLocked().getDisplayPolicy();
        if (pendingPanic && hideNavBarSysui && !isStatusBarKeyguard()
                // TODO (b/111955725): Show keyguard presentation on all external displays
                && defaultDisplayPolicy.isKeyguardDrawComplete()) {
            // The user performed the panic gesture recently, we're about to hide the bars,
            // we're no longer on the Keyguard and the screen is ready. We can now request the bars.
            mPendingPanicGestureUptime = 0;
            mStatusBarController.showTransient();
            if (!isNavBarEmpty(vis)) {
                mNavigationBarController.showTransient();
            }
        }

        final boolean denyTransientStatus = mStatusBarController.isTransientShowRequested()
                && !transientStatusBarAllowed && hideStatusBarSysui;
        final boolean denyTransientNav = mNavigationBarController.isTransientShowRequested()
                && !transientNavBarAllowed;
        if (denyTransientStatus || denyTransientNav || mForceShowSystemBars) {
            // clear the clearable flags instead
            clearClearableFlagsLw();
            vis &= ~View.SYSTEM_UI_CLEARABLE_FLAGS;
        }

        final boolean immersive = (vis & View.SYSTEM_UI_FLAG_IMMERSIVE) != 0;
        immersiveSticky = (vis & View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) != 0;
        final boolean navAllowedHidden = immersive || immersiveSticky;

        if (hideNavBarSysui && !navAllowedHidden
                && mService.mPolicy.getWindowLayerLw(win)
                        > mService.mPolicy.getWindowLayerFromTypeLw(TYPE_INPUT_CONSUMER)) {
            // We can't hide the navbar from this window otherwise the input consumer would not get
            // the input events.
            vis = (vis & ~View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }

        vis = mStatusBarController.updateVisibilityLw(transientStatusBarAllowed, oldVis, vis);

        // update navigation bar
        boolean oldImmersiveMode = isImmersiveMode(oldVis);
        boolean newImmersiveMode = isImmersiveMode(vis);
        if (oldImmersiveMode != newImmersiveMode) {
            final String pkg = win.getOwningPackage();
            mImmersiveModeConfirmation.immersiveModeChangedLw(pkg, newImmersiveMode,
                    mService.mPolicy.isUserSetupComplete(),
                    isNavBarEmpty(win.getSystemUiVisibility()));
        }

        vis = mNavigationBarController.updateVisibilityLw(transientNavBarAllowed, oldVis, vis);

        final WindowState navColorWin = chooseNavigationColorWindowLw(
                mTopFullscreenOpaqueWindowState, mTopFullscreenOpaqueOrDimmingWindowState,
                mDisplayContent.mInputMethodWindow, mNavigationBarPosition);
        vis = updateLightNavigationBarLw(vis, mTopFullscreenOpaqueWindowState,
                mTopFullscreenOpaqueOrDimmingWindowState,
                mDisplayContent.mInputMethodWindow, navColorWin);
        // Navbar color is controlled by the IME.
        final boolean isManagedByIme =
                navColorWin != null && navColorWin == mDisplayContent.mInputMethodWindow;

        return Pair.create(vis, isManagedByIme);
    }

    private boolean drawsBarBackground(int vis, WindowState win, BarController controller,
            int translucentFlag) {
        if (!controller.isTransparentAllowed(win)) {
            return false;
        }
        if (win == null) {
            return true;
        }

        final boolean drawsSystemBars =
                (win.getAttrs().flags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0;
        final boolean forceDrawsSystemBars =
                (win.getAttrs().privateFlags & PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS) != 0;

        return forceDrawsSystemBars || drawsSystemBars && (vis & translucentFlag) == 0;
    }

    private boolean drawsStatusBarBackground(int vis, WindowState win) {
        return drawsBarBackground(vis, win, mStatusBarController, FLAG_TRANSLUCENT_STATUS);
    }

    private boolean drawsNavigationBarBackground(int vis, WindowState win) {
        return drawsBarBackground(vis, win, mNavigationBarController, FLAG_TRANSLUCENT_NAVIGATION);
    }

    /**
     * @return the current visibility flags with the nav-bar opacity related flags toggled based
     *         on the nav bar opacity rules chosen by {@link #mNavBarOpacityMode}.
     */
    private int configureNavBarOpacity(int visibility, boolean dockedStackVisible,
            boolean freeformStackVisible, boolean isDockedDividerResizing,
            boolean fullscreenDrawsBackground, boolean dockedDrawsNavigationBarBackground) {
        if (mNavBarOpacityMode == NAV_BAR_FORCE_TRANSPARENT) {
            if (fullscreenDrawsBackground && dockedDrawsNavigationBarBackground) {
                visibility = setNavBarTransparentFlag(visibility);
            } else if (dockedStackVisible) {
                visibility = setNavBarOpaqueFlag(visibility);
            }
        } else if (mNavBarOpacityMode == NAV_BAR_OPAQUE_WHEN_FREEFORM_OR_DOCKED) {
            if (dockedStackVisible || freeformStackVisible || isDockedDividerResizing) {
                if (mIsFreeformWindowOverlappingWithNavBar) {
                    visibility = setNavBarTranslucentFlag(visibility);
                } else {
                    visibility = setNavBarOpaqueFlag(visibility);
                }
            } else if (fullscreenDrawsBackground) {
                visibility = setNavBarTransparentFlag(visibility);
            }
        } else if (mNavBarOpacityMode == NAV_BAR_TRANSLUCENT_WHEN_FREEFORM_OPAQUE_OTHERWISE) {
            if (isDockedDividerResizing) {
                visibility = setNavBarOpaqueFlag(visibility);
            } else if (freeformStackVisible) {
                visibility = setNavBarTranslucentFlag(visibility);
            } else {
                visibility = setNavBarOpaqueFlag(visibility);
            }
        }

        return visibility;
    }

    private int setNavBarOpaqueFlag(int visibility) {
        return visibility & ~(View.NAVIGATION_BAR_TRANSLUCENT | View.NAVIGATION_BAR_TRANSPARENT);
    }

    private int setNavBarTranslucentFlag(int visibility) {
        visibility &= ~View.NAVIGATION_BAR_TRANSPARENT;
        return visibility | View.NAVIGATION_BAR_TRANSLUCENT;
    }

    private int setNavBarTransparentFlag(int visibility) {
        visibility &= ~View.NAVIGATION_BAR_TRANSLUCENT;
        return visibility | View.NAVIGATION_BAR_TRANSPARENT;
    }

    private void clearClearableFlagsLw() {
        int newVal = mResettingSystemUiFlags | View.SYSTEM_UI_CLEARABLE_FLAGS;
        if (newVal != mResettingSystemUiFlags) {
            mResettingSystemUiFlags = newVal;
            mDisplayContent.reevaluateStatusBarVisibility();
        }
    }

    private boolean isImmersiveMode(int vis) {
        final int flags = View.SYSTEM_UI_FLAG_IMMERSIVE | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        return mNavigationBar != null
                && (vis & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0
                && (vis & flags) != 0
                && canHideNavigationBar();
    }

    /**
     * @return whether the navigation bar can be hidden, e.g. the device has a navigation bar
     */
    private boolean canHideNavigationBar() {
        return hasNavigationBar();
    }

    private static boolean isNavBarEmpty(int systemUiFlags) {
        final int disableNavigationBar = (View.STATUS_BAR_DISABLE_HOME
                | View.STATUS_BAR_DISABLE_BACK
                | View.STATUS_BAR_DISABLE_RECENT);

        return (systemUiFlags & disableNavigationBar) == disableNavigationBar;
    }

    private final Runnable mHiddenNavPanic = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                if (!mService.mPolicy.isUserSetupComplete()) {
                    // Swipe-up for navigation bar is disabled during setup
                    return;
                }
                mPendingPanicGestureUptime = SystemClock.uptimeMillis();
                if (!isNavBarEmpty(mLastSystemUiFlags)) {
                    mNavigationBarController.showTransient();
                }
            }
        }
    };

    void onPowerKeyDown(boolean isScreenOn) {
        // Detect user pressing the power button in panic when an application has
        // taken over the whole screen.
        boolean panic = mImmersiveModeConfirmation.onPowerKeyDown(isScreenOn,
                SystemClock.elapsedRealtime(), isImmersiveMode(mLastSystemUiFlags),
                isNavBarEmpty(mLastSystemUiFlags));
        if (panic) {
            mHandler.post(mHiddenNavPanic);
        }
    }

    void onVrStateChangedLw(boolean enabled) {
        mImmersiveModeConfirmation.onVrStateChangedLw(enabled);
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
    public void onLockTaskStateChangedLw(int lockTaskState) {
        mImmersiveModeConfirmation.onLockTaskModeChangedLw(lockTaskState);
    }

    /**
     * Request a screenshot be taken.
     *
     * @param screenshotType The type of screenshot, for example either
     *                       {@link WindowManager#TAKE_SCREENSHOT_FULLSCREEN} or
     *                       {@link WindowManager#TAKE_SCREENSHOT_SELECTED_REGION}
     */
    public void takeScreenshot(int screenshotType) {
        if (mScreenshotHelper != null) {
            mScreenshotHelper.takeScreenshot(screenshotType,
                    mStatusBar != null && mStatusBar.isVisibleLw(),
                    mNavigationBar != null && mNavigationBar.isVisibleLw(),
                    mHandler, null /* completionConsumer */);
        }
    }

    RefreshRatePolicy getRefreshRatePolicy() {
        return mRefreshRatePolicy;
    }

    void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.println("DisplayPolicy");
        prefix += "  ";
        pw.print(prefix);
        pw.print("mCarDockEnablesAccelerometer="); pw.print(mCarDockEnablesAccelerometer);
        pw.print(" mDeskDockEnablesAccelerometer=");
        pw.println(mDeskDockEnablesAccelerometer);
        pw.print(prefix); pw.print("mDockMode="); pw.print(Intent.dockStateToString(mDockMode));
        pw.print(" mLidState="); pw.println(WindowManagerFuncs.lidStateToString(mLidState));
        pw.print(prefix); pw.print("mAwake="); pw.print(mAwake);
        pw.print(" mScreenOnEarly="); pw.print(mScreenOnEarly);
        pw.print(" mScreenOnFully="); pw.println(mScreenOnFully);
        pw.print(prefix); pw.print("mKeyguardDrawComplete="); pw.print(mKeyguardDrawComplete);
        pw.print(" mWindowManagerDrawComplete="); pw.println(mWindowManagerDrawComplete);
        pw.print(prefix); pw.print("mHdmiPlugged="); pw.println(mHdmiPlugged);
        if (mLastSystemUiFlags != 0 || mResettingSystemUiFlags != 0
                || mForceClearedSystemUiFlags != 0) {
            pw.print(prefix); pw.print("mLastSystemUiFlags=0x");
            pw.print(Integer.toHexString(mLastSystemUiFlags));
            pw.print(" mResettingSystemUiFlags=0x");
            pw.print(Integer.toHexString(mResettingSystemUiFlags));
            pw.print(" mForceClearedSystemUiFlags=0x");
            pw.println(Integer.toHexString(mForceClearedSystemUiFlags));
        }
        if (mLastFocusNeedsMenu) {
            pw.print(prefix); pw.print("mLastFocusNeedsMenu="); pw.println(mLastFocusNeedsMenu);
        }
        pw.print(prefix); pw.print("mShowingDream="); pw.print(mShowingDream);
        pw.print(" mDreamingLockscreen="); pw.print(mDreamingLockscreen);
        pw.print(" mDreamingSleepToken="); pw.println(mDreamingSleepToken);
        if (mStatusBar != null) {
            pw.print(prefix); pw.print("mStatusBar="); pw.print(mStatusBar);
                    pw.print(" isStatusBarKeyguard="); pw.println(isStatusBarKeyguard());
        }
        if (mNavigationBar != null) {
            pw.print(prefix); pw.print("mNavigationBar="); pw.println(mNavigationBar);
            pw.print(prefix); pw.print("mNavBarOpacityMode="); pw.println(mNavBarOpacityMode);
            pw.print(prefix); pw.print("mNavigationBarCanMove="); pw.println(mNavigationBarCanMove);
            pw.print(prefix); pw.print("mNavigationBarPosition=");
            pw.println(mNavigationBarPosition);
        }
        if (mFocusedWindow != null) {
            pw.print(prefix); pw.print("mFocusedWindow="); pw.println(mFocusedWindow);
        }
        if (mFocusedApp != null) {
            pw.print(prefix); pw.print("mFocusedApp="); pw.println(mFocusedApp);
        }
        if (mTopFullscreenOpaqueWindowState != null) {
            pw.print(prefix); pw.print("mTopFullscreenOpaqueWindowState=");
            pw.println(mTopFullscreenOpaqueWindowState);
        }
        if (mTopFullscreenOpaqueOrDimmingWindowState != null) {
            pw.print(prefix); pw.print("mTopFullscreenOpaqueOrDimmingWindowState=");
            pw.println(mTopFullscreenOpaqueOrDimmingWindowState);
        }
        if (mForcingShowNavBar) {
            pw.print(prefix); pw.print("mForcingShowNavBar="); pw.println(mForcingShowNavBar);
            pw.print(prefix); pw.print("mForcingShowNavBarLayer=");
            pw.println(mForcingShowNavBarLayer);
        }
        pw.print(prefix); pw.print("mTopIsFullscreen="); pw.println(mTopIsFullscreen);
        pw.print(prefix); pw.print("mForceStatusBar="); pw.print(mForceStatusBar);
        pw.print(" mForceStatusBarFromKeyguard="); pw.println(mForceStatusBarFromKeyguard);
        pw.print(prefix); pw.print("mForceShowSystemBarsFromExternal=");
        pw.print(mForceShowSystemBarsFromExternal);
        pw.print(" mAllowLockscreenWhenOn="); pw.println(mAllowLockscreenWhenOn);
        mStatusBarController.dump(pw, prefix);
        mNavigationBarController.dump(pw, prefix);

        pw.print(prefix); pw.println("Looper state:");
        mHandler.getLooper().dump(new PrintWriterPrinter(pw), prefix + "  ");
    }

    private boolean supportsPointerLocation() {
        return mDisplayContent.isDefaultDisplay || !mDisplayContent.isPrivate();
    }

    void setPointerLocationEnabled(boolean pointerLocationEnabled) {
        if (!supportsPointerLocation()) {
            return;
        }

        mHandler.sendEmptyMessage(pointerLocationEnabled
                ? MSG_ENABLE_POINTER_LOCATION : MSG_DISABLE_POINTER_LOCATION);
    }

    private void enablePointerLocation() {
        if (mPointerLocationView != null) {
            return;
        }

        mPointerLocationView = new PointerLocationView(mContext);
        mPointerLocationView.setPrintCoords(false);
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
        lp.type = WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
        lp.flags = WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (ActivityManager.isHighEndGfx()) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            lp.privateFlags |=
                    WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED;
        }
        lp.format = PixelFormat.TRANSLUCENT;
        lp.setTitle("PointerLocation - display " + getDisplayId());
        lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;
        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        wm.addView(mPointerLocationView, lp);
        mDisplayContent.registerPointerEventListener(mPointerLocationView);
    }

    private void disablePointerLocation() {
        if (mPointerLocationView == null) {
            return;
        }

        mDisplayContent.unregisterPointerEventListener(mPointerLocationView);
        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        wm.removeView(mPointerLocationView);
        mPointerLocationView = null;
    }

    /**
     * Check if the window could be excluded from checking if the display has content.
     *
     * @param w WindowState to check if should be excluded.
     * @return True if the window type is PointerLocation which is excluded.
     */
    boolean isWindowExcludedFromContent(WindowState w) {
        if (w != null && mPointerLocationView != null) {
            return w.mClient == mPointerLocationView.getWindowToken();
        }

        return false;
    }

    @VisibleForTesting
    static boolean isOverlappingWithNavBar(WindowState targetWindow, WindowState navBarWindow) {
        if (navBarWindow == null || !navBarWindow.isVisibleLw()
                || targetWindow.mAppToken == null || !targetWindow.isVisibleLw()) {
            return false;
        }

        return Rect.intersects(targetWindow.getFrameLw(), navBarWindow.getFrameLw());
    }
}
