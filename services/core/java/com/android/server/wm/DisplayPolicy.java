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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.view.Display.TYPE_INTERNAL;
import static android.view.InsetsFrameProvider.SOURCE_ARBITRARY_RECTANGLE;
import static android.view.InsetsFrameProvider.SOURCE_CONTAINER_BOUNDS;
import static android.view.InsetsFrameProvider.SOURCE_DISPLAY;
import static android.view.InsetsFrameProvider.SOURCE_FRAME;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_STATUS_BARS;
import static android.view.WindowInsetsController.APPEARANCE_SEMI_TRANSPARENT_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS;
import static android.view.WindowLayout.UNSPECIFIED_LENGTH;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_UNRESTRICTED_GESTURE_EXCLUSION;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManagerGlobal.ADD_OKAY;
import static android.view.WindowManagerPolicyConstants.ACTION_HDMI_PLUGGED;
import static android.view.WindowManagerPolicyConstants.EXTRA_HDMI_PLUGGED_STATE;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_BOTTOM;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_INVALID;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_LEFT;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_RIGHT;
import static android.window.DisplayAreaOrganizer.FEATURE_UNDEFINED;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ANIM;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_SCREEN_ON;
import static com.android.server.policy.PhoneWindowManager.TOAST_WINDOW_TIMEOUT;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_PREVIEW_DONE;
import static com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs.LID_ABSENT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Px;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.LoadedApk;
import android.app.ResourcesManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.gui.DropInputMode;
import android.hardware.power.Boost;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.InsetsFlags;
import android.view.InsetsFrameProvider;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.Surface;
import android.view.View;
import android.view.ViewDebug;
import android.view.WindowInsets.Type;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowLayout;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;
import android.window.ClientWindowFrames;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.ForceShowNavBarSettingsObserver;
import com.android.internal.policy.GestureNavigationSettingsObserver;
import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.statusbar.LetterboxDetails;
import com.android.internal.util.ScreenshotHelper;
import com.android.internal.util.ScreenshotRequest;
import com.android.internal.util.function.TriFunction;
import com.android.internal.view.AppearanceRegion;
import com.android.internal.widget.PointerLocationView;
import com.android.server.LocalServices;
import com.android.server.UiThread;
import com.android.server.policy.WindowManagerPolicy.NavigationBarPosition;
import com.android.server.policy.WindowManagerPolicy.ScreenOnListener;
import com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.wallpaper.WallpaperManagerInternal;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The policy that provides the basic behaviors and states of a display to show UI.
 */
public class DisplayPolicy {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "DisplayPolicy" : TAG_WM;

    // The panic gesture may become active only after the keyguard is dismissed and the immersive
    // app shows again. If that doesn't happen for 30s we drop the gesture.
    private static final long PANIC_GESTURE_EXPIRATION = 30000;

    // Controls navigation bar opacity depending on which workspace root tasks are currently
    // visible.
    // Nav bar is always opaque when either the freeform root task or docked root task is visible.
    private static final int NAV_BAR_OPAQUE_WHEN_FREEFORM_OR_DOCKED = 0;
    // Nav bar is always translucent when the freeform rootTask is visible, otherwise always opaque.
    private static final int NAV_BAR_TRANSLUCENT_WHEN_FREEFORM_OPAQUE_OTHERWISE = 1;
    // Nav bar is never forced opaque.
    private static final int NAV_BAR_FORCE_TRANSPARENT = 2;

    /** Don't apply window animation (see {@link #selectAnimation}). */
    static final int ANIMATION_NONE = -1;
    /** Use the transit animation in style resource (see {@link #selectAnimation}). */
    static final int ANIMATION_STYLEABLE = 0;

    private static final int SHOW_TYPES_FOR_SWIPE = Type.statusBars() | Type.navigationBars();
    private static final int SHOW_TYPES_FOR_PANIC = Type.navigationBars();

    private static final int INSETS_OVERRIDE_INDEX_INVALID = -1;

    private final WindowManagerService mService;
    private final Context mContext;
    private final Context mUiContext;
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
    private int mLeftGestureInset;
    @Px
    private int mRightGestureInset;

    private boolean mCanSystemBarsBeShownByUser;

    /**
     * Let remote insets controller control system bars regardless of other settings.
     */
    private boolean mRemoteInsetsControllerControlsSystemBars;

    StatusBarManagerInternal getStatusBarManagerInternal() {
        synchronized (mServiceAcquireLock) {
            if (mStatusBarManagerInternal == null) {
                mStatusBarManagerInternal =
                        LocalServices.getService(StatusBarManagerInternal.class);
            }
            return mStatusBarManagerInternal;
        }
    }

    private final SystemGesturesPointerEventListener mSystemGestures;

    final DecorInsets mDecorInsets;

    private volatile int mLidState = LID_ABSENT;
    private volatile int mDockMode = Intent.EXTRA_DOCK_STATE_UNDOCKED;
    private volatile boolean mHdmiPlugged;

    private volatile boolean mHasStatusBar;
    private volatile boolean mHasNavigationBar;
    // Can the navigation bar ever move to the side?
    private volatile boolean mNavigationBarCanMove;
    private volatile boolean mNavigationBarAlwaysShowOnSideGesture;

    // Written by vr manager thread, only read in this class.
    private volatile boolean mPersistentVrModeEnabled;

    private volatile boolean mAwake;
    private volatile boolean mScreenOnEarly;
    private volatile boolean mScreenOnFully;
    private volatile ScreenOnListener mScreenOnListener;

    private volatile boolean mKeyguardDrawComplete;
    private volatile boolean mWindowManagerDrawComplete;

    private WindowState mStatusBar = null;
    private volatile WindowState mNotificationShade;
    private WindowState mNavigationBar = null;
    @NavigationBarPosition
    private int mNavigationBarPosition = NAV_BAR_BOTTOM;

    private final ArraySet<WindowState> mInsetsSourceWindowsExceptIme = new ArraySet<>();

    /** Apps which are controlling the appearance of system bars */
    private final ArraySet<ActivityRecord> mSystemBarColorApps = new ArraySet<>();

    /** Apps which are relaunching and were controlling the appearance of system bars */
    private final ArraySet<ActivityRecord> mRelaunchingSystemBarColorApps = new ArraySet<>();

    private boolean mIsFreeformWindowOverlappingWithNavBar;

    private boolean mIsImmersiveMode;

    // The windows we were told about in focusChanged.
    private WindowState mFocusedWindow;
    private WindowState mLastFocusedWindow;

    private WindowState mSystemUiControllingWindow;

    // Candidate window to determine the color of navigation bar. The window needs to be top
    // fullscreen-app windows or dim layers that are intersecting with the window frame of status
    // bar.
    private WindowState mNavBarColorWindowCandidate;

    // The window to determine opacity and background of translucent navigation bar. The window
    // needs to be opaque.
    private WindowState mNavBarBackgroundWindow;

    /**
     * A collection of {@link AppearanceRegion} to indicate that which region of status bar applies
     * which appearance.
     */
    private final ArrayList<AppearanceRegion> mStatusBarAppearanceRegionList = new ArrayList<>();

    /**
     * Windows to determine opacity and background of translucent status bar. The window needs to be
     * opaque
     */
    private final ArrayList<WindowState> mStatusBarBackgroundWindows = new ArrayList<>();

    /**
     * A collection of {@link LetterboxDetails} of all visible activities to be sent to SysUI in
     * order to determine status bar appearance
     */
    private final ArrayList<LetterboxDetails> mLetterboxDetails = new ArrayList<>();

    private String mFocusedApp;
    private int mLastDisableFlags;
    private int mLastAppearance;
    private int mLastBehavior;
    private int mLastRequestedVisibleTypes = Type.defaultVisible();
    private AppearanceRegion[] mLastStatusBarAppearanceRegions;
    private LetterboxDetails[] mLastLetterboxDetails;

    /** The union of checked bounds while building {@link #mStatusBarAppearanceRegionList}. */
    private final Rect mStatusBarColorCheckedBounds = new Rect();

    /** The union of checked bounds while fetching {@link #mStatusBarBackgroundWindows}. */
    private final Rect mStatusBarBackgroundCheckedBounds = new Rect();

    // What we last reported to input dispatcher about whether the focused window is fullscreen.
    private boolean mLastFocusIsFullscreen = false;

    // If nonzero, a panic gesture was performed at that time in uptime millis and is still pending.
    private long mPendingPanicGestureUptime;

    private static final Rect sTmpRect = new Rect();
    private static final Rect sTmpRect2 = new Rect();
    private static final Rect sTmpDisplayCutoutSafe = new Rect();
    private static final ClientWindowFrames sTmpClientFrames = new ClientWindowFrames();

    private final WindowLayout mWindowLayout = new WindowLayout();

    private WindowState mTopFullscreenOpaqueWindowState;
    private boolean mTopIsFullscreen;
    private int mNavBarOpacityMode = NAV_BAR_OPAQUE_WHEN_FREEFORM_OR_DOCKED;
    private boolean mForceConsumeSystemBars;
    private boolean mForceShowSystemBars;

    /**
     * Windows that provides gesture insets. If multiple windows provide gesture insets at the same
     * side, the window with the highest z-order wins.
     */
    private WindowState mLeftGestureHost;
    private WindowState mTopGestureHost;
    private WindowState mRightGestureHost;
    private WindowState mBottomGestureHost;

    private boolean mShowingDream;
    private boolean mLastShowingDream;
    private boolean mDreamingLockscreen;
    private boolean mAllowLockscreenWhenOn;

    private PointerLocationView mPointerLocationView;

    private RefreshRatePolicy mRefreshRatePolicy;

    /**
     * If true, attach the navigation bar to the current transition app.
     * The value is read from config_attachNavBarToAppDuringTransition and could be overlaid by RRO
     * when the navigation bar mode is changed.
     */
    private boolean mShouldAttachNavBarToAppDuringTransition;

    // -------- PolicyHandler --------
    private static final int MSG_ENABLE_POINTER_LOCATION = 4;
    private static final int MSG_DISABLE_POINTER_LOCATION = 5;

    private final GestureNavigationSettingsObserver mGestureNavigationSettingsObserver;

    private final WindowManagerInternal.AppTransitionListener mAppTransitionListener;

    private final ForceShowNavBarSettingsObserver mForceShowNavBarSettingsObserver;
    private boolean mForceShowNavigationBarEnabled;

    private class PolicyHandler extends Handler {

        PolicyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
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
        mUiContext = displayContent.isDefaultDisplay ? service.mAtmService.getUiContext()
                : service.mAtmService.mSystemThread
                        .getSystemUiContext(displayContent.getDisplayId());
        mDisplayContent = displayContent;
        mDecorInsets = new DecorInsets(displayContent);
        mLock = service.getWindowManagerLock();

        final int displayId = displayContent.getDisplayId();

        final Resources r = mContext.getResources();
        mCarDockEnablesAccelerometer = r.getBoolean(R.bool.config_carDockEnablesAccelerometer);
        mDeskDockEnablesAccelerometer = r.getBoolean(R.bool.config_deskDockEnablesAccelerometer);
        mCanSystemBarsBeShownByUser = !r.getBoolean(
                R.bool.config_remoteInsetsControllerControlsSystemBars) || r.getBoolean(
                R.bool.config_remoteInsetsControllerSystemBarsCanBeShownByUserAction);

        mAccessibilityManager = (AccessibilityManager) mContext.getSystemService(
                Context.ACCESSIBILITY_SERVICE);
        if (!displayContent.isDefaultDisplay) {
            mAwake = true;
            mScreenOnEarly = true;
            mScreenOnFully = true;
        }

        final Looper looper = UiThread.getHandler().getLooper();
        mHandler = new PolicyHandler(looper);
        // TODO(b/181821798) Migrate SystemGesturesPointerEventListener to use window context.
        mSystemGestures = new SystemGesturesPointerEventListener(mUiContext, mHandler,
                new SystemGesturesPointerEventListener.Callbacks() {

                    private static final long MOUSE_GESTURE_DELAY_MS = 500;

                    private Runnable mOnSwipeFromLeft = this::onSwipeFromLeft;
                    private Runnable mOnSwipeFromTop = this::onSwipeFromTop;
                    private Runnable mOnSwipeFromRight = this::onSwipeFromRight;
                    private Runnable mOnSwipeFromBottom = this::onSwipeFromBottom;

                    private Insets getControllableInsets(WindowState win) {
                        if (win == null) {
                            return Insets.NONE;
                        }
                        final InsetsSourceProvider provider = win.getControllableInsetProvider();
                        if (provider == null) {
                            return  Insets.NONE;
                        }
                        return provider.getSource().calculateInsets(win.getBounds(),
                                true /* ignoreVisibility */);
                    }

                    @Override
                    public void onSwipeFromTop() {
                        synchronized (mLock) {
                            requestTransientBars(mTopGestureHost,
                                    getControllableInsets(mTopGestureHost).top > 0);
                        }
                    }

                    @Override
                    public void onSwipeFromBottom() {
                        synchronized (mLock) {
                            requestTransientBars(mBottomGestureHost,
                                    getControllableInsets(mBottomGestureHost).bottom > 0);
                        }
                    }

                    private boolean allowsSideSwipe(Region excludedRegion) {
                        return mNavigationBarAlwaysShowOnSideGesture
                                && !mSystemGestures.currentGestureStartedInRegion(excludedRegion);
                    }

                    @Override
                    public void onSwipeFromRight() {
                        final Region excludedRegion = Region.obtain();
                        synchronized (mLock) {
                            mDisplayContent.calculateSystemGestureExclusion(
                                    excludedRegion, null /* outUnrestricted */);
                            final boolean hasWindow =
                                    getControllableInsets(mRightGestureHost).right > 0;
                            if (hasWindow || allowsSideSwipe(excludedRegion)) {
                                requestTransientBars(mRightGestureHost, hasWindow);
                            }
                        }
                        excludedRegion.recycle();
                    }

                    @Override
                    public void onSwipeFromLeft() {
                        final Region excludedRegion = Region.obtain();
                        synchronized (mLock) {
                            mDisplayContent.calculateSystemGestureExclusion(
                                    excludedRegion, null /* outUnrestricted */);
                            final boolean hasWindow =
                                    getControllableInsets(mLeftGestureHost).left > 0;
                            if (hasWindow || allowsSideSwipe(excludedRegion)) {
                                requestTransientBars(mLeftGestureHost, hasWindow);
                            }
                        }
                        excludedRegion.recycle();
                    }

                    @Override
                    public void onFling(int duration) {
                        if (mService.mPowerManagerInternal != null) {
                            mService.mPowerManagerInternal.setPowerBoost(
                                    Boost.INTERACTION, duration);
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
                    public void onMouseHoverAtLeft() {
                        mHandler.removeCallbacks(mOnSwipeFromLeft);
                        mHandler.postDelayed(mOnSwipeFromLeft, MOUSE_GESTURE_DELAY_MS);
                    }

                    @Override
                    public void onMouseHoverAtTop() {
                        mHandler.removeCallbacks(mOnSwipeFromTop);
                        mHandler.postDelayed(mOnSwipeFromTop, MOUSE_GESTURE_DELAY_MS);
                    }

                    @Override
                    public void onMouseHoverAtRight() {
                        mHandler.removeCallbacks(mOnSwipeFromRight);
                        mHandler.postDelayed(mOnSwipeFromRight, MOUSE_GESTURE_DELAY_MS);
                    }

                    @Override
                    public void onMouseHoverAtBottom() {
                        mHandler.removeCallbacks(mOnSwipeFromBottom);
                        mHandler.postDelayed(mOnSwipeFromBottom, MOUSE_GESTURE_DELAY_MS);
                    }

                    @Override
                    public void onMouseLeaveFromLeft() {
                        mHandler.removeCallbacks(mOnSwipeFromLeft);
                    }

                    @Override
                    public void onMouseLeaveFromTop() {
                        mHandler.removeCallbacks(mOnSwipeFromTop);
                    }

                    @Override
                    public void onMouseLeaveFromRight() {
                        mHandler.removeCallbacks(mOnSwipeFromRight);
                    }

                    @Override
                    public void onMouseLeaveFromBottom() {
                        mHandler.removeCallbacks(mOnSwipeFromBottom);
                    }
                });
        displayContent.registerPointerEventListener(mSystemGestures);
        mAppTransitionListener = new WindowManagerInternal.AppTransitionListener() {

            private Runnable mAppTransitionPending = () -> {
                StatusBarManagerInternal statusBar = getStatusBarManagerInternal();
                if (statusBar != null) {
                    statusBar.appTransitionPending(displayId);
                }
            };

            private Runnable mAppTransitionCancelled = () -> {
                StatusBarManagerInternal statusBar = getStatusBarManagerInternal();
                if (statusBar != null) {
                    statusBar.appTransitionCancelled(displayId);
                }
            };

            private Runnable mAppTransitionFinished = () -> {
                StatusBarManagerInternal statusBar = getStatusBarManagerInternal();
                if (statusBar != null) {
                    statusBar.appTransitionFinished(displayId);
                }
            };

            @Override
            public void onAppTransitionPendingLocked() {
                mHandler.post(mAppTransitionPending);
            }

            @Override
            public int onAppTransitionStartingLocked(long statusBarAnimationStartTime,
                    long statusBarAnimationDuration) {
                mHandler.post(() -> {
                    StatusBarManagerInternal statusBar = getStatusBarManagerInternal();
                    if (statusBar != null) {
                        statusBar.appTransitionStarting(mContext.getDisplayId(),
                                statusBarAnimationStartTime, statusBarAnimationDuration);
                    }
                });
                return 0;
            }

            @Override
            public void onAppTransitionCancelledLocked(boolean keyguardGoingAwayCancelled) {
                mHandler.post(mAppTransitionCancelled);
            }

            @Override
            public void onAppTransitionFinishedLocked(IBinder token) {
                mHandler.post(mAppTransitionFinished);
            }
        };
        displayContent.mAppTransition.registerListenerLocked(mAppTransitionListener);
        displayContent.mTransitionController.registerLegacyListener(mAppTransitionListener);
        mImmersiveModeConfirmation = new ImmersiveModeConfirmation(mContext, looper,
                mService.mVrModeEnabled, mCanSystemBarsBeShownByUser);

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
                mService.mHighRefreshRateDenylist);

        mGestureNavigationSettingsObserver = new GestureNavigationSettingsObserver(mHandler,
                mContext, () -> {
            synchronized (mLock) {
                onConfigurationChanged();
                mSystemGestures.onConfigurationChanged();
                mDisplayContent.updateSystemGestureExclusion();
            }
        });
        mHandler.post(mGestureNavigationSettingsObserver::register);

        mForceShowNavBarSettingsObserver = new ForceShowNavBarSettingsObserver(
                mHandler, mContext);
        mForceShowNavBarSettingsObserver.setOnChangeRunnable(this::updateForceShowNavBarSettings);
        mForceShowNavigationBarEnabled = mForceShowNavBarSettingsObserver.isEnabled();
        mHandler.post(mForceShowNavBarSettingsObserver::register);
    }

    private void updateForceShowNavBarSettings() {
        synchronized (mLock) {
            mForceShowNavigationBarEnabled =
                    mForceShowNavBarSettingsObserver.isEnabled();
            updateSystemBarAttributes();
        }
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

    public boolean hasNavigationBar() {
        return mHasNavigationBar;
    }

    public boolean hasStatusBar() {
        return mHasStatusBar;
    }

    boolean hasSideGestures() {
        return mHasNavigationBar && (mLeftGestureInset > 0 || mRightGestureInset > 0);
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
        synchronized (mLock) {
            if (awake == mAwake) {
                return;
            }
            mAwake = awake;
            if (!mDisplayContent.isDefaultDisplay) {
                return;
            }
            mService.mAtmService.mKeyguardController.updateDeferTransitionForAod(
                    mAwake /* waiting */);
        }
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

    public boolean isForceShowNavigationBarEnabled() {
        return mForceShowNavigationBarEnabled;
    }

    public ScreenOnListener getScreenOnListener() {
        return mScreenOnListener;
    }


    boolean isRemoteInsetsControllerControllingSystemBars() {
        return mRemoteInsetsControllerControlsSystemBars;
    }

    @VisibleForTesting
    void setRemoteInsetsControllerControlsSystemBars(
            boolean remoteInsetsControllerControlsSystemBars) {
        mRemoteInsetsControllerControlsSystemBars = remoteInsetsControllerControlsSystemBars;
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
            ProtoLog.d(WM_DEBUG_SCREEN_ON,
                            "finishScreenTurningOn: mAwake=%b, mScreenOnEarly=%b, "
                                    + "mScreenOnFully=%b, mKeyguardDrawComplete=%b, "
                                    + "mWindowManagerDrawComplete=%b",
                            mAwake, mScreenOnEarly, mScreenOnFully, mKeyguardDrawComplete,
                            mWindowManagerDrawComplete);

            if (mScreenOnFully || !mScreenOnEarly || !mWindowManagerDrawComplete
                    || (mAwake && !mKeyguardDrawComplete)) {
                return false;
            }

            ProtoLog.i(WM_DEBUG_SCREEN_ON, "Finished screen turning on...");
            mScreenOnListener = null;
            mScreenOnFully = true;
        }
        return true;
    }

    /**
     * Sanitize the layout parameters coming from a client.  Allows the policy
     * to do things like ensure that windows of a specific type can't take
     * input focus.
     *
     * @param attrs The window layout parameters to be modified.  These values
     * are modified in-place.
     */
    public void adjustWindowParamsLw(WindowState win, WindowManager.LayoutParams attrs) {
        switch (attrs.type) {
            case TYPE_SYSTEM_OVERLAY:
            case TYPE_SECURE_SYSTEM_OVERLAY:
                // These types of windows can't receive input events.
                attrs.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                attrs.flags &= ~WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
                break;
            case TYPE_WALLPAPER:
                // Dreams and wallpapers don't have an app window token and can thus not be
                // letterboxed. Hence always let them extend under the cutout.
                attrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
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
                // Toasts can't be clickable
                attrs.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                break;

            case TYPE_BASE_APPLICATION:

                // A non-translucent main app window isn't allowed to fit insets, as it would create
                // a hole on the display!
                if (attrs.isFullscreen() && win.mActivityRecord != null
                        && win.mActivityRecord.fillsParent()
                        && (win.mAttrs.privateFlags & PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS) != 0
                        && attrs.getFitInsetsTypes() != 0) {
                    throw new IllegalArgumentException("Illegal attributes: Main activity window"
                            + " that isn't translucent trying to fit insets: "
                            + attrs.getFitInsetsTypes()
                            + " attrs=" + attrs);
                }
                break;
        }

        if (LayoutParams.isSystemAlertWindowType(attrs.type)) {
            float maxOpacity = mService.mMaximumObscuringOpacityForTouch;
            if (attrs.alpha > maxOpacity
                    && (attrs.flags & FLAG_NOT_TOUCHABLE) != 0
                    && !win.isTrustedOverlay()) {
                // The app is posting a SAW with the intent of letting touches pass through, but
                // they are going to be deemed untrusted and will be blocked. Try to honor the
                // intent of letting touches pass through at the cost of 0.2 opacity for app
                // compatibility reasons. More details on b/218777508.
                Slog.w(TAG, String.format(
                        "App %s has a system alert window (type = %d) with FLAG_NOT_TOUCHABLE and "
                                + "LayoutParams.alpha = %.2f > %.2f, setting alpha to %.2f to "
                                + "let touches pass through (if this is isn't desirable, remove "
                                + "flag FLAG_NOT_TOUCHABLE).",
                        attrs.packageName, attrs.type, attrs.alpha, maxOpacity, maxOpacity));
                attrs.alpha = maxOpacity;
                win.mWinAnimator.mAlpha = maxOpacity;
            }
        }

        if (!win.mSession.mCanSetUnrestrictedGestureExclusion) {
            attrs.privateFlags &= ~PRIVATE_FLAG_UNRESTRICTED_GESTURE_EXCLUSION;
        }

        final InsetsSourceProvider provider = win.getControllableInsetProvider();
        if (provider != null && provider.getSource().insetsRoundedCornerFrame()
                != attrs.insetsRoundedCornerFrame) {
            provider.getSource().setInsetsRoundedCornerFrame(attrs.insetsRoundedCornerFrame);
        }
    }

    /**
     * Add additional policy if needed to ensure the window or its children should not receive any
     * input.
     */
    public void setDropInputModePolicy(WindowState win, LayoutParams attrs) {
        if (attrs.type == TYPE_TOAST
                && (attrs.privateFlags & PRIVATE_FLAG_TRUSTED_OVERLAY) == 0) {
            // Toasts should not receive input. These windows should not have any children, so
            // force this hierarchy of windows to drop all input.
            mService.mTransactionFactory.get()
                    .setDropInputMode(win.getSurfaceControl(), DropInputMode.ALL).apply();
        }
    }

    /**
     * Check if a window can be added to the system.
     *
     * Currently enforces that two window types are singletons per display:
     * <ul>
     * <li>{@link WindowManager.LayoutParams#TYPE_STATUS_BAR}</li>
     * <li>{@link WindowManager.LayoutParams#TYPE_NOTIFICATION_SHADE}</li>
     * <li>{@link WindowManager.LayoutParams#TYPE_NAVIGATION_BAR}</li>
     * </ul>
     *
     * @param attrs Information about the window to be added.
     *
     * @return If ok, WindowManagerImpl.ADD_OKAY.  If too many singletons,
     * WindowManagerImpl.ADD_MULTIPLE_SINGLETON
     */
    int validateAddingWindowLw(WindowManager.LayoutParams attrs, int callingPid, int callingUid) {
        if ((attrs.privateFlags & PRIVATE_FLAG_TRUSTED_OVERLAY) != 0) {
            mContext.enforcePermission(
                    android.Manifest.permission.INTERNAL_SYSTEM_WINDOW, callingPid, callingUid,
                    "DisplayPolicy");
        }
        if ((attrs.privateFlags & PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP) != 0) {
            ActivityTaskManagerService.enforceTaskPermission("DisplayPolicy");
        }

        switch (attrs.type) {
            case TYPE_STATUS_BAR:
                mContext.enforcePermission(
                        android.Manifest.permission.STATUS_BAR_SERVICE, callingPid, callingUid,
                        "DisplayPolicy");
                if (mStatusBar != null && mStatusBar.isAlive()) {
                    return WindowManagerGlobal.ADD_MULTIPLE_SINGLETON;
                }
                break;
            case TYPE_NOTIFICATION_SHADE:
                mContext.enforcePermission(
                        android.Manifest.permission.STATUS_BAR_SERVICE, callingPid, callingUid,
                        "DisplayPolicy");
                if (mNotificationShade != null) {
                    if (mNotificationShade.isAlive()) {
                        return WindowManagerGlobal.ADD_MULTIPLE_SINGLETON;
                    }
                }
                break;
            case TYPE_NAVIGATION_BAR:
                mContext.enforcePermission(
                        android.Manifest.permission.STATUS_BAR_SERVICE, callingPid, callingUid,
                        "DisplayPolicy");
                if (mNavigationBar != null && mNavigationBar.isAlive()) {
                    return WindowManagerGlobal.ADD_MULTIPLE_SINGLETON;
                }
                break;
            case TYPE_NAVIGATION_BAR_PANEL:
                // Check for permission if the caller is not the recents component.
                if (!mService.mAtmService.isCallerRecents(callingUid)) {
                    mContext.enforcePermission(
                            android.Manifest.permission.STATUS_BAR_SERVICE, callingPid, callingUid,
                            "DisplayPolicy");
                }
                break;
            case TYPE_STATUS_BAR_ADDITIONAL:
            case TYPE_STATUS_BAR_SUB_PANEL:
            case TYPE_VOICE_INTERACTION_STARTING:
                mContext.enforcePermission(
                        android.Manifest.permission.STATUS_BAR_SERVICE, callingPid, callingUid,
                        "DisplayPolicy");
                break;
            case TYPE_STATUS_BAR_PANEL:
                return WindowManagerGlobal.ADD_INVALID_TYPE;
        }

        if (attrs.providedInsets != null) {
            // Recents component is allowed to add inset types.
            if (!mService.mAtmService.isCallerRecents(callingUid)) {
                mContext.enforcePermission(
                        android.Manifest.permission.STATUS_BAR_SERVICE, callingPid, callingUid,
                        "DisplayPolicy");
            }
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
        switch (attrs.type) {
            case TYPE_NOTIFICATION_SHADE:
                mNotificationShade = win;
                break;
            case TYPE_STATUS_BAR:
                mStatusBar = win;
                break;
            case TYPE_NAVIGATION_BAR:
                mNavigationBar = win;
                break;
        }
        if (attrs.providedInsets != null) {
            for (int i = attrs.providedInsets.length - 1; i >= 0; i--) {
                final InsetsFrameProvider provider = attrs.providedInsets[i];
                // The index of the provider and corresponding insets types cannot change at
                // runtime as ensured in WMS. Make use of the index in the provider directly
                // to access the latest provided size at runtime.
                final TriFunction<DisplayFrames, WindowContainer, Rect, Integer> frameProvider =
                        getFrameProvider(win, i, INSETS_OVERRIDE_INDEX_INVALID);
                final InsetsFrameProvider.InsetsSizeOverride[] overrides =
                        provider.getInsetsSizeOverrides();
                final SparseArray<TriFunction<DisplayFrames, WindowContainer, Rect, Integer>>
                        overrideProviders;
                if (overrides != null) {
                    overrideProviders = new SparseArray<>();
                    for (int j = overrides.length - 1; j >= 0; j--) {
                        overrideProviders.put(
                                overrides[j].getWindowType(), getFrameProvider(win, i, j));
                    }
                } else {
                    overrideProviders = null;
                }
                mDisplayContent.getInsetsStateController().getOrCreateSourceProvider(
                        provider.getId(), provider.getType()).setWindowContainer(
                                win, frameProvider, overrideProviders);
                mInsetsSourceWindowsExceptIme.add(win);
            }
        }
    }

    private static TriFunction<DisplayFrames, WindowContainer, Rect, Integer> getFrameProvider(
            WindowState win, int index, int overrideIndex) {
        return (displayFrames, windowContainer, inOutFrame) -> {
            final LayoutParams lp = win.mAttrs.forRotation(displayFrames.mRotation);
            final InsetsFrameProvider ifp = lp.providedInsets[index];
            final Rect displayFrame = displayFrames.mUnrestricted;
            final Rect safe = displayFrames.mDisplayCutoutSafe;
            boolean extendByCutout = false;
            switch (ifp.getSource()) {
                case SOURCE_DISPLAY:
                    inOutFrame.set(displayFrame);
                    break;
                case SOURCE_CONTAINER_BOUNDS:
                    inOutFrame.set(windowContainer.getBounds());
                    break;
                case SOURCE_FRAME:
                    extendByCutout =
                            (lp.privateFlags & PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT) != 0;
                    break;
                case SOURCE_ARBITRARY_RECTANGLE:
                    inOutFrame.set(ifp.getArbitraryRectangle());
                    break;
            }
            final Insets insetsSize = overrideIndex == INSETS_OVERRIDE_INDEX_INVALID
                    ? ifp.getInsetsSize()
                    : ifp.getInsetsSizeOverrides()[overrideIndex].getInsetsSize();

            if (ifp.getMinimalInsetsSizeInDisplayCutoutSafe() != null) {
                sTmpRect2.set(inOutFrame);
            }
            calculateInsetsFrame(inOutFrame, insetsSize);

            if (extendByCutout && insetsSize != null) {
                WindowLayout.extendFrameByCutout(safe, displayFrame, inOutFrame, sTmpRect);
            }

            if (ifp.getMinimalInsetsSizeInDisplayCutoutSafe() != null) {
                // The insets is at least with the given size within the display cutout safe area.
                // Calculate the smallest size.
                calculateInsetsFrame(sTmpRect2, ifp.getMinimalInsetsSizeInDisplayCutoutSafe());
                WindowLayout.extendFrameByCutout(safe, displayFrame, sTmpRect2, sTmpRect);
                // If it's larger than previous calculation, use it.
                if (sTmpRect2.contains(inOutFrame)) {
                    inOutFrame.set(sTmpRect2);
                }
            }
            return ifp.getFlags();
        };
    }

    /**
     * Calculate the insets frame given the insets size and the source frame.
     * @param inOutFrame the source frame.
     * @param insetsSize the insets size. Only the first non-zero value will be taken.
     */
    private static void calculateInsetsFrame(Rect inOutFrame, Insets insetsSize) {
        if (insetsSize == null) {
            return;
        }
        // Only one side of the provider shall be applied. Check in the order of left - top -
        // right - bottom, only the first non-zero value will be applied.
        if (insetsSize.left != 0) {
            inOutFrame.right = inOutFrame.left + insetsSize.left;
        } else if (insetsSize.top != 0) {
            inOutFrame.bottom = inOutFrame.top + insetsSize.top;
        } else if (insetsSize.right != 0) {
            inOutFrame.left = inOutFrame.right - insetsSize.right;
        } else if (insetsSize.bottom != 0) {
            inOutFrame.top = inOutFrame.bottom - insetsSize.bottom;
        } else {
            inOutFrame.setEmpty();
        }
    }

    TriFunction<DisplayFrames, WindowContainer, Rect, Integer> getImeSourceFrameProvider() {
        return (displayFrames, windowContainer, inOutFrame) -> {
            WindowState windowState = windowContainer.asWindowState();
            if (windowState == null) {
                throw new IllegalArgumentException("IME insets must be provided by a window.");
            }

            if (mNavigationBar != null && navigationBarPosition(displayFrames.mRotation)
                    == NAV_BAR_BOTTOM) {
                // In gesture navigation, nav bar frame is larger than frame to calculate insets.
                // IME should not provide frame which is smaller than the nav bar frame. Otherwise,
                // nav bar might be overlapped with the content of the client when IME is shown.
                sTmpRect.set(inOutFrame);
                sTmpRect.intersectUnchecked(mNavigationBar.getFrame());
                inOutFrame.inset(windowState.mGivenContentInsets);
                inOutFrame.union(sTmpRect);
            } else {
                inOutFrame.inset(windowState.mGivenContentInsets);
            }
            return 0;
        };
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
        } else if (mNavigationBar == win) {
            mNavigationBar = null;
        } else if (mNotificationShade == win) {
            mNotificationShade = null;
        }
        if (mLastFocusedWindow == win) {
            mLastFocusedWindow = null;
        }

        if (win.hasInsetsSourceProvider()) {
            final SparseArray<InsetsSourceProvider> providers = win.getInsetsSourceProviders();
            final InsetsStateController controller = mDisplayContent.getInsetsStateController();
            for (int index = providers.size() - 1; index >= 0; index--) {
                final InsetsSourceProvider provider = providers.valueAt(index);
                provider.setWindowContainer(
                        null /* windowContainer */,
                        null /* frameProvider */,
                        null /* overrideFrameProviders */);
                controller.removeSourceProvider(provider.getSource().getId());
            }
        }
        mInsetsSourceWindowsExceptIme.remove(win);
    }

    WindowState getStatusBar() {
        return mStatusBar;
    }

    WindowState getNotificationShade() {
        return mNotificationShade;
    }

    WindowState getNavigationBar() {
        return mNavigationBar;
    }

    /**
     * Control the animation to run when a window's state changes.  Return a positive number to
     * force the animation to a specific resource ID, {@link #ANIMATION_STYLEABLE} to use the
     * style resource defining the animation, or {@link #ANIMATION_NONE} for no animation.
     *
     * @param win The window that is changing.
     * @param transit What is happening to the window:
     *                {@link com.android.server.policy.WindowManagerPolicy#TRANSIT_ENTER},
     *                {@link com.android.server.policy.WindowManagerPolicy#TRANSIT_EXIT},
     *                {@link com.android.server.policy.WindowManagerPolicy#TRANSIT_SHOW}, or
     *                {@link com.android.server.policy.WindowManagerPolicy#TRANSIT_HIDE}.
     *
     * @return Resource ID of the actual animation to use, or {@link #ANIMATION_NONE} for none.
     */
    int selectAnimation(WindowState win, int transit) {
        ProtoLog.i(WM_DEBUG_ANIM, "selectAnimation in %s: transit=%d", win, transit);

        if (transit == TRANSIT_PREVIEW_DONE) {
            if (win.hasAppShownWindows()) {
                if (win.isActivityTypeHome()) {
                    // Dismiss the starting window as soon as possible to avoid the crossfade out
                    // with old content because home is easier to have different UI states.
                    return ANIMATION_NONE;
                }
                ProtoLog.i(WM_DEBUG_ANIM, "**** STARTING EXIT");
                return R.anim.app_starting_exit;
            }
        }

        return ANIMATION_STYLEABLE;
    }

    /**
     * @return true if the system bars are forced to be consumed
     */
    public boolean areSystemBarsForcedConsumedLw() {
        return mForceConsumeSystemBars;
    }

    /**
     * @return true if the system bars are forced to stay visible
     */
    public boolean areSystemBarsForcedShownLw() {
        return mForceShowSystemBars;
    }

    /**
     * Computes the frames of display (its logical size, rotation and cutout should already be set)
     * used to layout window. This method only changes the given display frames, insets state and
     * some temporal states, but doesn't change the window frames used to show on screen.
     */
    void simulateLayoutDisplay(DisplayFrames displayFrames) {
        sTmpClientFrames.attachedFrame = null;
        for (int i = mInsetsSourceWindowsExceptIme.size() - 1; i >= 0; i--) {
            final WindowState win = mInsetsSourceWindowsExceptIme.valueAt(i);
            mWindowLayout.computeFrames(win.mAttrs.forRotation(displayFrames.mRotation),
                    displayFrames.mInsetsState, displayFrames.mDisplayCutoutSafe,
                    displayFrames.mUnrestricted, win.getWindowingMode(), UNSPECIFIED_LENGTH,
                    UNSPECIFIED_LENGTH, win.getRequestedVisibleTypes(), win.mGlobalScale,
                    sTmpClientFrames);
            final SparseArray<InsetsSourceProvider> providers = win.getInsetsSourceProviders();
            final InsetsState state = displayFrames.mInsetsState;
            for (int index = providers.size() - 1; index >= 0; index--) {
                state.addSource(providers.valueAt(index).createSimulatedSource(
                        displayFrames, sTmpClientFrames.frame));
            }
        }
    }

    void onDisplayInfoChanged(DisplayInfo info) {
        mSystemGestures.onDisplayInfoChanged(info);
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
        if (win.skipLayout()) {
            return;
        }

        // This window might be in the simulated environment.
        // We invoke this to get the proper DisplayFrames.
        displayFrames = win.getDisplayFrames(displayFrames);

        final WindowManager.LayoutParams attrs = win.mAttrs.forRotation(displayFrames.mRotation);
        sTmpClientFrames.attachedFrame = attached != null ? attached.getFrame() : null;

        // If this window has different LayoutParams for rotations, we cannot trust its requested
        // size. Because it might have not sent its requested size for the new rotation.
        final boolean trustedSize = attrs == win.mAttrs;
        final int requestedWidth = trustedSize ? win.mRequestedWidth : UNSPECIFIED_LENGTH;
        final int requestedHeight = trustedSize ? win.mRequestedHeight : UNSPECIFIED_LENGTH;

        mWindowLayout.computeFrames(attrs, win.getInsetsState(), displayFrames.mDisplayCutoutSafe,
                win.getBounds(), win.getWindowingMode(), requestedWidth, requestedHeight,
                win.getRequestedVisibleTypes(), win.mGlobalScale, sTmpClientFrames);

        win.setFrames(sTmpClientFrames, win.mRequestedWidth, win.mRequestedHeight);
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
        mLeftGestureHost = null;
        mTopGestureHost = null;
        mRightGestureHost = null;
        mBottomGestureHost = null;
        mTopFullscreenOpaqueWindowState = null;
        mNavBarColorWindowCandidate = null;
        mNavBarBackgroundWindow = null;
        mStatusBarAppearanceRegionList.clear();
        mLetterboxDetails.clear();
        mStatusBarBackgroundWindows.clear();
        mStatusBarColorCheckedBounds.setEmpty();
        mStatusBarBackgroundCheckedBounds.setEmpty();
        mSystemBarColorApps.clear();

        mAllowLockscreenWhenOn = false;
        mShowingDream = false;
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
        if (attrs.type == TYPE_NAVIGATION_BAR) {
            // Keep mNavigationBarPosition updated to make sure the transient detection and bar
            // color control is working correctly.
            final DisplayFrames displayFrames = mDisplayContent.mDisplayFrames;
            mNavigationBarPosition = navigationBarPosition(displayFrames.mRotation);
        }
        final boolean affectsSystemUi = win.canAffectSystemUiFlags();
        if (DEBUG_LAYOUT) Slog.i(TAG, "Win " + win + ": affectsSystemUi=" + affectsSystemUi);
        applyKeyguardPolicy(win, imeTarget);

        // Check if the freeform window overlaps with the navigation bar area.
        if (!mIsFreeformWindowOverlappingWithNavBar && win.inFreeformWindowingMode()
                && win.mActivityRecord != null && isOverlappingWithNavBar(win)) {
            mIsFreeformWindowOverlappingWithNavBar = true;
        }

        if (win.hasInsetsSourceProvider()) {
            final SparseArray<InsetsSourceProvider> providers = win.getInsetsSourceProviders();
            final Rect bounds = win.getBounds();
            for (int index = providers.size() - 1; index >= 0; index--) {
                final InsetsSourceProvider provider = providers.valueAt(index);
                final InsetsSource source = provider.getSource();
                if ((source.getType()
                        & (Type.systemGestures() | Type.mandatorySystemGestures())) == 0) {
                    continue;
                }
                if (mLeftGestureHost != null && mTopGestureHost != null
                        && mRightGestureHost != null && mBottomGestureHost != null) {
                    continue;
                }
                final Insets insets = source.calculateInsets(bounds, false /* ignoreVisibility */);
                if (mLeftGestureHost == null && insets.left > 0) {
                    mLeftGestureHost = win;
                }
                if (mTopGestureHost == null && insets.top > 0) {
                    mTopGestureHost = win;
                }
                if (mRightGestureHost == null && insets.right > 0) {
                    mRightGestureHost = win;
                }
                if (mBottomGestureHost == null && insets.bottom > 0) {
                    mBottomGestureHost = win;
                }
            }
        }

        if (!affectsSystemUi) {
            return;
        }

        boolean appWindow = attrs.type >= FIRST_APPLICATION_WINDOW
                && attrs.type < FIRST_SYSTEM_WINDOW;
        if (mTopFullscreenOpaqueWindowState == null) {
            final int fl = attrs.flags;
            if (win.isDreamWindow()) {
                // If the lockscreen was showing when the dream started then wait
                // for the dream to draw before hiding the lockscreen.
                if (!mDreamingLockscreen || (win.isVisible() && win.hasDrawn())) {
                    mShowingDream = true;
                    appWindow = true;
                }
            }

            if (appWindow && attached == null && attrs.isFullscreen()
                    && (fl & FLAG_ALLOW_LOCK_WHILE_SCREEN_ON) != 0) {
                mAllowLockscreenWhenOn = true;
            }
        }

        // Check the windows that overlap with system bars to determine system bars' appearance.
        if ((appWindow && attached == null && attrs.isFullscreen())
                || attrs.type == TYPE_VOICE_INTERACTION) {
            // Record the top-fullscreen-app-window which will be used to determine system UI
            // controlling window.
            if (mTopFullscreenOpaqueWindowState == null) {
                mTopFullscreenOpaqueWindowState = win;
            }

            // Cache app windows that is overlapping with the status bar to determine appearance
            // of status bar.
            if (mStatusBar != null
                    && sTmpRect.setIntersect(win.getFrame(), mStatusBar.getFrame())
                    && !mStatusBarBackgroundCheckedBounds.contains(sTmpRect)) {
                mStatusBarBackgroundWindows.add(win);
                mStatusBarBackgroundCheckedBounds.union(sTmpRect);
                if (!mStatusBarColorCheckedBounds.contains(sTmpRect)) {
                    mStatusBarAppearanceRegionList.add(new AppearanceRegion(
                            win.mAttrs.insetsFlags.appearance & APPEARANCE_LIGHT_STATUS_BARS,
                            new Rect(win.getFrame())));
                    mStatusBarColorCheckedBounds.union(sTmpRect);
                    addSystemBarColorApp(win);
                }
            }

            // Cache app window that overlaps with the navigation bar area to determine opacity
            // and appearance of the navigation bar. We only need to cache one window because
            // there should be only one overlapping window if it's not in gesture navigation
            // mode; if it's in gesture navigation mode, the navigation bar will be
            // NAV_BAR_FORCE_TRANSPARENT and its appearance won't be decided by overlapping
            // windows.
            if (isOverlappingWithNavBar(win)) {
                if (mNavBarColorWindowCandidate == null) {
                    mNavBarColorWindowCandidate = win;
                    addSystemBarColorApp(win);
                }
                if (mNavBarBackgroundWindow == null) {
                    mNavBarBackgroundWindow = win;
                }
            }

            // Check if current activity is letterboxed in order create a LetterboxDetails
            // component to be passed to SysUI for status bar treatment
            final ActivityRecord currentActivity = win.getActivityRecord();
            if (currentActivity != null) {
                final LetterboxDetails currentLetterboxDetails = currentActivity
                        .mLetterboxUiController.getLetterboxDetails();
                if (currentLetterboxDetails != null) {
                    mLetterboxDetails.add(currentLetterboxDetails);
                }
            }
        } else if (win.isDimming()) {
            if (mStatusBar != null) {
                if (addStatusBarAppearanceRegionsForDimmingWindow(
                        win.mAttrs.insetsFlags.appearance & APPEARANCE_LIGHT_STATUS_BARS,
                        mStatusBar.getFrame(), win.getBounds(), win.getFrame())) {
                    addSystemBarColorApp(win);
                }
            }
            if (isOverlappingWithNavBar(win) && mNavBarColorWindowCandidate == null) {
                mNavBarColorWindowCandidate = win;
            }
        }
    }

    /**
     * Returns true if mStatusBarAppearanceRegionList is changed.
     */
    private boolean addStatusBarAppearanceRegionsForDimmingWindow(
            int appearance, Rect statusBarFrame, Rect winBounds, Rect winFrame) {
        if (!sTmpRect.setIntersect(winBounds, statusBarFrame)) {
            return false;
        }
        if (mStatusBarColorCheckedBounds.contains(sTmpRect)) {
            return false;
        }
        if (appearance == 0 || !sTmpRect2.setIntersect(winFrame, statusBarFrame)) {
            mStatusBarAppearanceRegionList.add(new AppearanceRegion(0, new Rect(winBounds)));
            mStatusBarColorCheckedBounds.union(sTmpRect);
            return true;
        }
        // A dimming window can divide status bar into different appearance regions (up to 3).
        // +---------+-------------+---------+
        // |/////////|             |/////////| <-- Status Bar
        // +---------+-------------+---------+
        // |/////////|             |/////////|
        // |/////////|             |/////////|
        // |/////////|             |/////////|
        // |/////////|             |/////////|
        // |/////////|             |/////////|
        // +---------+-------------+---------+
        //      ^           ^           ^
        //  dim layer     window    dim layer
        mStatusBarAppearanceRegionList.add(new AppearanceRegion(appearance, new Rect(winFrame)));
        if (!sTmpRect.equals(sTmpRect2)) {
            if (sTmpRect.height() == sTmpRect2.height()) {
                if (sTmpRect.left != sTmpRect2.left) {
                    mStatusBarAppearanceRegionList.add(new AppearanceRegion(0, new Rect(
                            winBounds.left, winBounds.top, sTmpRect2.left, winBounds.bottom)));
                }
                if (sTmpRect.right != sTmpRect2.right) {
                    mStatusBarAppearanceRegionList.add(new AppearanceRegion(0, new Rect(
                            sTmpRect2.right, winBounds.top, winBounds.right, winBounds.bottom)));
                }
            }
            // We don't have vertical status bar yet, so we don't handle the other orientation.
        }
        mStatusBarColorCheckedBounds.union(sTmpRect);
        return true;
    }

    private void addSystemBarColorApp(WindowState win) {
        final ActivityRecord app = win.mActivityRecord;
        if (app != null) {
            mSystemBarColorApps.add(app);
        }
    }

    /**
     * Called following layout of all windows and after policy has been applied to each window.
     */
    public void finishPostLayoutPolicyLw() {
        // If we are not currently showing a dream then remember the current
        // lockscreen state.  We will use this to determine whether the dream
        // started while the lockscreen was showing and remember this state
        // while the dream is showing.
        if (!mShowingDream) {
            mDreamingLockscreen = mService.mPolicy.isKeyguardShowingAndNotOccluded();
        }

        updateSystemBarAttributes();

        if (mShowingDream != mLastShowingDream) {
            mLastShowingDream = mShowingDream;
            // Notify that isShowingDreamLw (which is checked in KeyguardController) has changed.
            mDisplayContent.notifyKeyguardFlagsChanged();
        }

        mService.mPolicy.setAllowLockscreenWhenOn(getDisplayId(), mAllowLockscreenWhenOn);
    }

    /**
     * Applies the keyguard policy to a specific window.
     *
     * @param win The window to apply the keyguard policy.
     * @param imeTarget The current IME target window.
     */
    private void applyKeyguardPolicy(WindowState win, WindowState imeTarget) {
        if (win.canBeHiddenByKeyguard()) {
            final boolean shouldBeHiddenByKeyguard = shouldBeHiddenByKeyguard(win, imeTarget);
            if (win.mIsImWindow) {
                // Notify IME insets provider to freeze the IME insets. In case when turning off
                // the screen, the IME insets source window will be hidden because of keyguard
                // policy change and affects the system to freeze the last insets state. (And
                // unfreeze when the IME is going to show)
                mDisplayContent.getInsetsStateController().getImeSourceProvider().setFrozen(
                        shouldBeHiddenByKeyguard);
            }
            if (shouldBeHiddenByKeyguard) {
                win.hide(false /* doAnimation */, true /* requestAnim */);
            } else {
                win.show(false /* doAnimation */, true /* requestAnim */);
            }
        }
    }

    private boolean shouldBeHiddenByKeyguard(WindowState win, WindowState imeTarget) {
        // If AOD is showing, the IME should be hidden. However, sometimes the AOD is considered
        // hidden because it's in the process of hiding, but it's still being shown on screen.
        // In that case, we want to continue hiding the IME until the windows have completed
        // drawing. This way, we know that the IME can be safely shown since the other windows are
        // now shown.
        final boolean hideIme = win.mIsImWindow
                && (mDisplayContent.isAodShowing()
                        || (mDisplayContent.isDefaultDisplay && !mWindowManagerDrawComplete));
        if (hideIme) {
            return true;
        }

        if (!mDisplayContent.isDefaultDisplay || !isKeyguardShowing()) {
            return false;
        }

        // Show IME over the keyguard if the target allows it.
        final boolean showImeOverKeyguard = imeTarget != null && imeTarget.isVisible()
                && win.mIsImWindow && (imeTarget.canShowWhenLocked()
                        || !imeTarget.canBeHiddenByKeyguard());
        if (showImeOverKeyguard) {
            return false;
        }

        // Show SHOW_WHEN_LOCKED windows if keyguard is occluded.
        final boolean allowShowWhenLocked = isKeyguardOccluded()
                // Show error dialogs over apps that are shown on keyguard.
                && (win.canShowWhenLocked()
                        || (win.mAttrs.privateFlags & LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR) != 0);
        return !allowShowWhenLocked;
    }

    /**
     * @return Whether the top fullscreen app hides the given type of system bar.
     */
    boolean topAppHidesSystemBar(@InsetsType int type) {
        if (mTopFullscreenOpaqueWindowState == null || mForceShowSystemBars) {
            return false;
        }
        return !mTopFullscreenOpaqueWindowState.isRequestedVisible(type);
    }

    /**
     * Called when the user is switched.
     */
    public void switchUser() {
        updateCurrentUserResources();
        updateForceShowNavBarSettings();
    }

    /**
     * Called when the resource overlays change.
     */
    void onOverlayChanged() {
        updateCurrentUserResources();
        // Update the latest display size, cutout.
        mDisplayContent.updateDisplayInfo();
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

        mNavBarOpacityMode = res.getInteger(R.integer.config_navBarOpacityMode);
        mLeftGestureInset = mGestureNavigationSettingsObserver.getLeftSensitivity(res);
        mRightGestureInset = mGestureNavigationSettingsObserver.getRightSensitivity(res);
        mNavigationBarAlwaysShowOnSideGesture =
                res.getBoolean(R.bool.config_navBarAlwaysShowOnSideEdgeGesture);
        mRemoteInsetsControllerControlsSystemBars = res.getBoolean(
                R.bool.config_remoteInsetsControllerControlsSystemBars);

        updateConfigurationAndScreenSizeDependentBehaviors();

        final boolean shouldAttach =
                res.getBoolean(R.bool.config_attachNavBarToAppDuringTransition);
        if (mShouldAttachNavBarToAppDuringTransition != shouldAttach) {
            mShouldAttachNavBarToAppDuringTransition = shouldAttach;
        }
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
        mCurrentUserResources = ResourcesManager.getInstance().getResources(
                uiContext.getWindowContextToken(),
                pi.getResDir(),
                null /* splitResDirs */,
                pi.getOverlayDirs(),
                pi.getOverlayPaths(),
                pi.getApplicationInfo().sharedLibraryFiles,
                mDisplayContent.getDisplayId(),
                null /* overrideConfig */,
                uiContext.getResources().getCompatibilityInfo(),
                null /* classLoader */,
                null /* loaders */);
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

    Context getSystemUiContext() {
        return mUiContext;
    }

    @VisibleForTesting
    void setCanSystemBarsBeShownByUser(boolean canBeShown) {
        mCanSystemBarsBeShownByUser = canBeShown;
    }

    void notifyDisplayReady() {
        mHandler.post(() -> {
            final int displayId = getDisplayId();
            StatusBarManagerInternal statusBar = getStatusBarManagerInternal();
            if (statusBar != null) {
                statusBar.onDisplayReady(displayId);
            }
            final WallpaperManagerInternal wpMgr = LocalServices
                    .getService(WallpaperManagerInternal.class);
            if (wpMgr != null) {
                wpMgr.onDisplayReady(displayId);
            }
        });
    }

    /**
     * Return corner radius in pixels that should be used on windows in order to cover the display.
     *
     * The radius is only valid for internal displays, since the corner radius of external displays
     * is not known at build time when window corners are configured.
     */
    float getWindowCornerRadius() {
        return mDisplayContent.getDisplay().getType() == TYPE_INTERNAL
                ? ScreenDecorationsUtils.getWindowCornerRadius(mContext) : 0f;
    }

    boolean isShowingDreamLw() {
        return mShowingDream;
    }

    /** The latest insets and frames for screen configuration calculation. */
    static class DecorInsets {
        static class Info {
            /**
             * The insets for the areas that could never be removed, i.e. display cutout and
             * navigation bar. Note that its meaning is actually "decor insets". The "non" is just
             * because it is used to calculate {@link #mNonDecorFrame}.
             */
            final Rect mNonDecorInsets = new Rect();

            /**
             * The stable insets that can affect configuration. The sources are usually from
             * display cutout, navigation bar, and status bar.
             */
            final Rect mConfigInsets = new Rect();

            /** The display frame available after excluding {@link #mNonDecorInsets}. */
            final Rect mNonDecorFrame = new Rect();

            /**
             * The available (stable) screen size that we should report for the configuration.
             * This must be no larger than {@link #mNonDecorFrame}; it may be smaller than that
             * to account for more transient decoration like a status bar.
             */
            final Rect mConfigFrame = new Rect();

            private boolean mNeedUpdate = true;

            void update(DisplayContent dc, int rotation, int w, int h) {
                final DisplayFrames df = new DisplayFrames();
                dc.updateDisplayFrames(df, rotation, w, h);
                dc.getDisplayPolicy().simulateLayoutDisplay(df);
                final InsetsState insetsState = df.mInsetsState;
                final Rect displayFrame = insetsState.getDisplayFrame();
                final Insets decor = insetsState.calculateInsets(displayFrame, DECOR_TYPES,
                        true /* ignoreVisibility */);
                final Insets statusBar = insetsState.calculateInsets(displayFrame,
                        Type.statusBars(), true /* ignoreVisibility */);
                mNonDecorInsets.set(decor.left, decor.top, decor.right, decor.bottom);
                mConfigInsets.set(Math.max(statusBar.left, decor.left),
                        Math.max(statusBar.top, decor.top),
                        Math.max(statusBar.right, decor.right),
                        Math.max(statusBar.bottom, decor.bottom));
                mNonDecorFrame.set(displayFrame);
                mNonDecorFrame.inset(mNonDecorInsets);
                mConfigFrame.set(displayFrame);
                mConfigFrame.inset(mConfigInsets);
                mNeedUpdate = false;
            }

            void set(Info other) {
                mNonDecorInsets.set(other.mNonDecorInsets);
                mConfigInsets.set(other.mConfigInsets);
                mNonDecorFrame.set(other.mNonDecorFrame);
                mConfigFrame.set(other.mConfigFrame);
                mNeedUpdate = false;
            }

            @Override
            public String toString() {
                return "{nonDecorInsets=" + mNonDecorInsets
                        + ", configInsets=" + mConfigInsets
                        + ", nonDecorFrame=" + mNonDecorFrame
                        + ", configFrame=" + mConfigFrame + '}';
            }
        }


        static final int DECOR_TYPES = Type.displayCutout() | Type.navigationBars();

        /**
         * The types that may affect display configuration. This excludes cutout because it is
         * known from display info.
         */
        static final int CONFIG_TYPES = Type.statusBars() | Type.navigationBars();

        private final DisplayContent mDisplayContent;
        private final Info[] mInfoForRotation = new Info[4];
        final Info mTmpInfo = new Info();

        DecorInsets(DisplayContent dc) {
            mDisplayContent = dc;
            for (int i = mInfoForRotation.length - 1; i >= 0; i--) {
                mInfoForRotation[i] = new Info();
            }
        }

        Info get(int rotation, int w, int h) {
            final Info info = mInfoForRotation[rotation];
            if (info.mNeedUpdate) {
                info.update(mDisplayContent, rotation, w, h);
            }
            return info;
        }

        /** Called when the screen decor insets providers have changed. */
        void invalidate() {
            for (Info info : mInfoForRotation) {
                info.mNeedUpdate = true;
            }
        }
    }

    /**
     * If the decor insets changes, the display configuration may be affected. The caller should
     * call {@link DisplayContent#sendNewConfiguration()} if this method returns {@code true}.
     */
    boolean updateDecorInsetsInfo() {
        final DisplayFrames displayFrames = mDisplayContent.mDisplayFrames;
        final int rotation = displayFrames.mRotation;
        final int dw = displayFrames.mWidth;
        final int dh = displayFrames.mHeight;
        final DecorInsets.Info newInfo = mDecorInsets.mTmpInfo;
        newInfo.update(mDisplayContent, rotation, dw, dh);
        final DecorInsets.Info currentInfo = getDecorInsetsInfo(rotation, dw, dh);
        if (newInfo.mConfigFrame.equals(currentInfo.mConfigFrame)) {
            return false;
        }
        mDecorInsets.invalidate();
        mDecorInsets.mInfoForRotation[rotation].set(newInfo);
        return true;
    }

    DecorInsets.Info getDecorInsetsInfo(int rotation, int w, int h) {
        return mDecorInsets.get(rotation, w, h);
    }

    @NavigationBarPosition
    int navigationBarPosition(int displayRotation) {
        if (mNavigationBar != null) {
            final int gravity = mNavigationBar.mAttrs.forRotation(displayRotation).gravity;
            switch (gravity) {
                case Gravity.LEFT:
                    return NAV_BAR_LEFT;
                case Gravity.RIGHT:
                    return NAV_BAR_RIGHT;
                default:
                    return NAV_BAR_BOTTOM;
            }
        }
        return NAV_BAR_INVALID;
    }

    /**
     * A new window has been focused.
     */
    public void focusChangedLw(WindowState lastFocus, WindowState newFocus) {
        mFocusedWindow = newFocus;
        mLastFocusedWindow = lastFocus;
        if (mDisplayContent.isDefaultDisplay) {
            mService.mPolicy.onDefaultDisplayFocusChangedLw(newFocus);
        }
        updateSystemBarAttributes();
    }

    @VisibleForTesting
    void requestTransientBars(WindowState swipeTarget, boolean isGestureOnSystemBar) {
        if (swipeTarget == null || !mService.mPolicy.isUserSetupComplete()) {
            // Swipe-up for navigation bar is disabled during setup
            return;
        }
        if (!mCanSystemBarsBeShownByUser) {
            Slog.d(TAG, "Remote insets controller disallows showing system bars - ignoring "
                    + "request");
            return;
        }
        final InsetsSourceProvider provider = swipeTarget.getControllableInsetProvider();
        final InsetsControlTarget controlTarget = provider != null
                ? provider.getControlTarget() : null;

        if (controlTarget == null || controlTarget == getNotificationShade()) {
            // No transient mode on lockscreen (in notification shade window).
            return;
        }

        if (controlTarget != null) {
            final WindowState win = controlTarget.getWindow();

            if (win != null && win.isActivityTypeDream()) {
                return;
            }
        }

        final @InsetsType int restorePositionTypes = (Type.statusBars() | Type.navigationBars())
                & controlTarget.getRequestedVisibleTypes();

        final InsetsSourceProvider sp = swipeTarget.getControllableInsetProvider();
        if (sp != null && sp.getSource().getType() == Type.navigationBars()
                && (restorePositionTypes & Type.navigationBars()) != 0) {
            // Don't show status bar when swiping on already visible navigation bar.
            // But restore the position of navigation bar if it has been moved by the control
            // target.
            controlTarget.showInsets(Type.navigationBars(), false /* fromIme */,
                    null /* statsToken */);
            return;
        }

        if (controlTarget.canShowTransient()) {
            // Show transient bars if they are hidden; restore position if they are visible.
            mDisplayContent.getInsetsPolicy().showTransient(SHOW_TYPES_FOR_SWIPE,
                    isGestureOnSystemBar);
            controlTarget.showInsets(restorePositionTypes, false /* fromIme */,
                    null /* statsToken */);
        } else {
            // Restore visibilities and positions of system bars.
            controlTarget.showInsets(Type.statusBars() | Type.navigationBars(),
                    false /* fromIme */, null /* statsToken */);
            // To further allow the pull-down-from-the-top gesture to pull down the notification
            // shade as a consistent motion, we reroute the touch events here from the currently
            // touched window to the status bar after making it visible.
            if (swipeTarget == mStatusBar) {
                final boolean transferred = mStatusBar.transferTouch();
                if (!transferred) {
                    Slog.i(TAG, "Could not transfer touch to the status bar");
                }
            }
        }
        mImmersiveModeConfirmation.confirmCurrentPrompt();
    }

    boolean isKeyguardShowing() {
        return mService.mPolicy.isKeyguardShowing();
    }
    private boolean isKeyguardOccluded() {
        // TODO (b/113840485): Handle per display keyguard.
        return mService.mPolicy.isKeyguardOccluded();
    }

    InsetsPolicy getInsetsPolicy() {
        return mDisplayContent.getInsetsPolicy();
    }

    /**
     * Called when an app has started replacing its main window.
     */
    void addRelaunchingApp(ActivityRecord app) {
        if (mSystemBarColorApps.contains(app) && !app.hasStartingWindow()) {
            mRelaunchingSystemBarColorApps.add(app);
        }
    }

    /**
     * Called when an app has finished replacing its main window or aborted.
     */
    void removeRelaunchingApp(ActivityRecord app) {
        final boolean removed = mRelaunchingSystemBarColorApps.remove(app);
        if (removed & mRelaunchingSystemBarColorApps.isEmpty()) {
            updateSystemBarAttributes();
        }
    }

    void resetSystemBarAttributes() {
        mLastDisableFlags = 0;
        updateSystemBarAttributes();
    }

    void updateSystemBarAttributes() {
        // If there is no window focused, there will be nobody to handle the events
        // anyway, so just hang on in whatever state we're in until things settle down.
        WindowState winCandidate = mFocusedWindow != null ? mFocusedWindow
                : mTopFullscreenOpaqueWindowState;
        if (winCandidate == null) {
            return;
        }

        // Immersive mode confirmation should never affect the system bar visibility, otherwise
        // it will unhide the navigation bar and hide itself.
        if (winCandidate.getAttrs().token == mImmersiveModeConfirmation.getWindowToken()) {
            if (mNotificationShade != null && mNotificationShade.canReceiveKeys()) {
                // Let notification shade control the system bar visibility.
                winCandidate = mNotificationShade;
            } else if (mLastFocusedWindow != null && mLastFocusedWindow.canReceiveKeys()) {
                // Immersive mode confirmation took the focus from mLastFocusedWindow which was
                // controlling the system bar visibility. Let it keep controlling the visibility.
                winCandidate = mLastFocusedWindow;
            } else {
                winCandidate = mTopFullscreenOpaqueWindowState;
            }
            if (winCandidate == null) {
                return;
            }
        }
        final WindowState win = winCandidate;
        mSystemUiControllingWindow = win;

        final int displayId = getDisplayId();
        final int disableFlags = win.getDisableFlags();
        final int opaqueAppearance = updateSystemBarsLw(win, disableFlags);
        if (!mRelaunchingSystemBarColorApps.isEmpty()) {
            // The appearance of system bars might change while relaunching apps. We don't report
            // the intermediate state to system UI. Otherwise, it might trigger redundant effects.
            return;
        }
        final WindowState navColorWin = chooseNavigationColorWindowLw(mNavBarColorWindowCandidate,
                mDisplayContent.mInputMethodWindow, mNavigationBarPosition);
        final boolean isNavbarColorManagedByIme =
                navColorWin != null && navColorWin == mDisplayContent.mInputMethodWindow;
        final int appearance = updateLightNavigationBarLw(win.mAttrs.insetsFlags.appearance,
                navColorWin) | opaqueAppearance;
        final WindowState navBarControlWin = topAppHidesSystemBar(Type.navigationBars())
                ? mTopFullscreenOpaqueWindowState
                : win;
        final int behavior = navBarControlWin.mAttrs.insetsFlags.behavior;
        final String focusedApp = win.mAttrs.packageName;
        final boolean isFullscreen = !win.isRequestedVisible(Type.statusBars())
                || !win.isRequestedVisible(Type.navigationBars());
        final AppearanceRegion[] statusBarAppearanceRegions =
                new AppearanceRegion[mStatusBarAppearanceRegionList.size()];
        mStatusBarAppearanceRegionList.toArray(statusBarAppearanceRegions);
        if (mLastDisableFlags != disableFlags) {
            mLastDisableFlags = disableFlags;
            final String cause = win.toString();
            callStatusBarSafely(statusBar -> statusBar.setDisableFlags(displayId, disableFlags,
                    cause));
        }
        final @InsetsType int requestedVisibleTypes = win.getRequestedVisibleTypes();
        final LetterboxDetails[] letterboxDetails = new LetterboxDetails[mLetterboxDetails.size()];
        mLetterboxDetails.toArray(letterboxDetails);
        if (mLastAppearance == appearance
                && mLastBehavior == behavior
                && mLastRequestedVisibleTypes == requestedVisibleTypes
                && Objects.equals(mFocusedApp, focusedApp)
                && mLastFocusIsFullscreen == isFullscreen
                && Arrays.equals(mLastStatusBarAppearanceRegions, statusBarAppearanceRegions)
                && Arrays.equals(mLastLetterboxDetails, letterboxDetails)) {
            return;
        }
        if (mDisplayContent.isDefaultDisplay && mLastFocusIsFullscreen != isFullscreen
                && ((mLastAppearance ^ appearance) & APPEARANCE_LOW_PROFILE_BARS) != 0) {
            mService.mInputManager.setSystemUiLightsOut(
                    isFullscreen || (appearance & APPEARANCE_LOW_PROFILE_BARS) != 0);
        }
        mLastAppearance = appearance;
        mLastBehavior = behavior;
        mLastRequestedVisibleTypes = requestedVisibleTypes;
        mFocusedApp = focusedApp;
        mLastFocusIsFullscreen = isFullscreen;
        mLastStatusBarAppearanceRegions = statusBarAppearanceRegions;
        mLastLetterboxDetails = letterboxDetails;
        callStatusBarSafely(statusBar -> statusBar.onSystemBarAttributesChanged(displayId,
                appearance, statusBarAppearanceRegions, isNavbarColorManagedByIme, behavior,
                requestedVisibleTypes, focusedApp, letterboxDetails));
    }

    private void callStatusBarSafely(Consumer<StatusBarManagerInternal> consumer) {
        mHandler.post(() -> {
            StatusBarManagerInternal statusBar = getStatusBarManagerInternal();
            if (statusBar != null) {
                consumer.accept(statusBar);
            }
        });
    }

    @VisibleForTesting
    @Nullable
    static WindowState chooseNavigationColorWindowLw(WindowState candidate, WindowState imeWindow,
            @NavigationBarPosition int navBarPosition) {
        // If the IME window is visible and FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS is set, then IME
        // window can be navigation color window.
        final boolean imeWindowCanNavColorWindow = imeWindow != null
                && imeWindow.isVisible()
                && navBarPosition == NAV_BAR_BOTTOM
                && (imeWindow.mAttrs.flags
                        & WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0;
        if (!imeWindowCanNavColorWindow) {
            // No IME window is involved. Determine the result only with candidate window.
            return candidate;
        }

        if (candidate != null && candidate.isDimming()) {
            // The IME window and the dimming window are competing. Check if the dimming window can
            // be IME target or not.
            if (LayoutParams.mayUseInputMethod(candidate.mAttrs.flags)) {
                // The IME window is above the dimming window.
                return imeWindow;
            } else {
                // The dimming window is above the IME window.
                return candidate;
            }
        }

        return imeWindow;
    }

    @VisibleForTesting
    int updateLightNavigationBarLw(int appearance, WindowState navColorWin) {
        if (navColorWin == null || !isLightBarAllowed(navColorWin, Type.navigationBars())) {
            // Clear the light flag while not allowed.
            appearance &= ~APPEARANCE_LIGHT_NAVIGATION_BARS;
            return appearance;
        }

        // Respect the light flag of navigation color window.
        appearance &= ~APPEARANCE_LIGHT_NAVIGATION_BARS;
        appearance |= navColorWin.mAttrs.insetsFlags.appearance
                & APPEARANCE_LIGHT_NAVIGATION_BARS;
        return appearance;
    }

    private int updateSystemBarsLw(WindowState win, int disableFlags) {
        final TaskDisplayArea defaultTaskDisplayArea = mDisplayContent.getDefaultTaskDisplayArea();
        final boolean adjacentTasksVisible =
                defaultTaskDisplayArea.getRootTask(task -> task.isVisible()
                        && task.getTopLeafTask().getAdjacentTask() != null)
                        != null;
        final boolean freeformRootTaskVisible =
                defaultTaskDisplayArea.isRootTaskVisible(WINDOWING_MODE_FREEFORM);

        // We need to force showing system bars when adjacent tasks or freeform roots visible.
        mForceShowSystemBars = adjacentTasksVisible || freeformRootTaskVisible;
        // We need to force the consumption of the system bars if they are force shown or if they
        // are controlled by a remote insets controller.
        mForceConsumeSystemBars = mForceShowSystemBars
                || mDisplayContent.getInsetsPolicy().remoteInsetsControllerControlsSystemBars(win);
        mDisplayContent.getInsetsPolicy().updateBarControlTarget(win);

        final boolean topAppHidesStatusBar = topAppHidesSystemBar(Type.statusBars());
        if (getStatusBar() != null) {
            final StatusBarManagerInternal statusBar = getStatusBarManagerInternal();
            if (statusBar != null) {
                statusBar.setTopAppHidesStatusBar(topAppHidesStatusBar);
            }
        }

        // If the top app is not fullscreen, only the default rotation animation is allowed.
        mTopIsFullscreen = topAppHidesStatusBar
                && (mNotificationShade == null || !mNotificationShade.isVisible());

        int appearance = APPEARANCE_OPAQUE_NAVIGATION_BARS | APPEARANCE_OPAQUE_STATUS_BARS;
        appearance = configureStatusBarOpacity(appearance);
        appearance = configureNavBarOpacity(appearance, adjacentTasksVisible,
                freeformRootTaskVisible);

        // Show immersive mode confirmation if needed.
        final boolean wasImmersiveMode = mIsImmersiveMode;
        final boolean isImmersiveMode = isImmersiveMode(win);
        if (wasImmersiveMode != isImmersiveMode) {
            mIsImmersiveMode = isImmersiveMode;
            // The immersive confirmation window should be attached to the immersive window root.
            final RootDisplayArea root = win.getRootDisplayArea();
            final int rootDisplayAreaId = root == null ? FEATURE_UNDEFINED : root.mFeatureId;
            mImmersiveModeConfirmation.immersiveModeChangedLw(rootDisplayAreaId, isImmersiveMode,
                    mService.mPolicy.isUserSetupComplete(),
                    isNavBarEmpty(disableFlags));
        }

        // Show transient bars for panic if needed.
        final boolean requestHideNavBar = !win.isRequestedVisible(Type.navigationBars());
        final long now = SystemClock.uptimeMillis();
        final boolean pendingPanic = mPendingPanicGestureUptime != 0
                && now - mPendingPanicGestureUptime <= PANIC_GESTURE_EXPIRATION;
        final DisplayPolicy defaultDisplayPolicy =
                mService.getDefaultDisplayContentLocked().getDisplayPolicy();
        if (pendingPanic && requestHideNavBar && isImmersiveMode
                // TODO (b/111955725): Show keyguard presentation on all external displays
                && defaultDisplayPolicy.isKeyguardDrawComplete()) {
            // The user performed the panic gesture recently, we're about to hide the bars,
            // we're no longer on the Keyguard and the screen is ready. We can now request the bars.
            mPendingPanicGestureUptime = 0;
            if (!isNavBarEmpty(disableFlags)) {
                mDisplayContent.getInsetsPolicy().showTransient(SHOW_TYPES_FOR_PANIC,
                        true /* isGestureOnSystemBar */);
            }
        }

        return appearance;
    }

    private static boolean isLightBarAllowed(WindowState win, @InsetsType int type) {
        if (win == null) {
            return false;
        }
        return intersectsAnyInsets(win.getFrame(), win.getInsetsState(), type);
    }

    private Rect getBarContentFrameForWindow(WindowState win, @InsetsType int type) {
        final DisplayFrames displayFrames = win.getDisplayFrames(mDisplayContent.mDisplayFrames);
        final InsetsState state = displayFrames.mInsetsState;
        final Rect df = displayFrames.mUnrestricted;
        final Rect safe = sTmpDisplayCutoutSafe;
        final Insets waterfallInsets = state.getDisplayCutout().getWaterfallInsets();
        final Rect outRect = new Rect();
        final Rect sourceContent = sTmpRect;
        safe.set(displayFrames.mDisplayCutoutSafe);
        for (int i = state.sourceSize() - 1; i >= 0; i--) {
            final InsetsSource source = state.sourceAt(i);
            if (source.getType() != type) {
                continue;
            }
            if (type == Type.statusBars()) {
                safe.set(displayFrames.mDisplayCutoutSafe);
                final Insets insets = source.calculateInsets(df, true /* ignoreVisibility */);
                // The status bar content can extend into regular display cutout insets if they are
                // at the same side, but the content cannot extend into waterfall insets.
                if (insets.left > 0) {
                    safe.left = Math.max(df.left + waterfallInsets.left, df.left);
                } else if (insets.top > 0) {
                    safe.top = Math.max(df.top + waterfallInsets.top, df.top);
                } else if (insets.right > 0) {
                    safe.right = Math.max(df.right - waterfallInsets.right, df.right);
                } else if (insets.bottom > 0) {
                    safe.bottom = Math.max(df.bottom - waterfallInsets.bottom, df.bottom);
                }
            }
            sourceContent.set(source.getFrame());
            sourceContent.intersect(safe);
            outRect.union(sourceContent);
        }
        return outRect;
    }

    /**
     * @return {@code true} if bar is allowed to be fully transparent when given window is show.
     *
     * <p>Prevents showing a transparent bar over a letterboxed activity which can make
     * notification icons or navigation buttons unreadable due to contrast between letterbox
     * background and an activity. For instance, this happens when letterbox background is solid
     * black while activity is white. To resolve this, only semi-transparent bars are allowed to
     * be drawn over letterboxed activity.
     */
    @VisibleForTesting
    boolean isFullyTransparentAllowed(WindowState win, @InsetsType int type) {
        if (win == null) {
            return true;
        }
        return win.isFullyTransparentBarAllowed(getBarContentFrameForWindow(win, type));
    }

    private boolean drawsBarBackground(WindowState win) {
        if (win == null) {
            return true;
        }

        final boolean drawsSystemBars =
                (win.getAttrs().flags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0;
        final boolean forceDrawsSystemBars =
                (win.getAttrs().privateFlags & PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS) != 0;

        return forceDrawsSystemBars || drawsSystemBars;
    }

    /** @return the current visibility flags with the status bar opacity related flags toggled. */
    private int configureStatusBarOpacity(int appearance) {
        boolean drawBackground = true;
        boolean isFullyTransparentAllowed = true;
        for (int i = mStatusBarBackgroundWindows.size() - 1; i >= 0; i--) {
            final WindowState window = mStatusBarBackgroundWindows.get(i);
            drawBackground &= drawsBarBackground(window);
            isFullyTransparentAllowed &= isFullyTransparentAllowed(window, Type.statusBars());
        }

        if (drawBackground) {
            appearance &= ~APPEARANCE_OPAQUE_STATUS_BARS;
        }

        if (!isFullyTransparentAllowed) {
            appearance |= APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS;
        }

        return appearance;
    }

    /**
     * @return the current visibility flags with the nav-bar opacity related flags toggled based
     *         on the nav bar opacity rules chosen by {@link #mNavBarOpacityMode}.
     */
    private int configureNavBarOpacity(int appearance, boolean multiWindowTaskVisible,
            boolean freeformRootTaskVisible) {
        final boolean drawBackground = drawsBarBackground(mNavBarBackgroundWindow);

        if (mNavBarOpacityMode == NAV_BAR_FORCE_TRANSPARENT) {
            if (drawBackground) {
                appearance = clearNavBarOpaqueFlag(appearance);
            }
        } else if (mNavBarOpacityMode == NAV_BAR_OPAQUE_WHEN_FREEFORM_OR_DOCKED) {
            if (multiWindowTaskVisible || freeformRootTaskVisible) {
                if (mIsFreeformWindowOverlappingWithNavBar) {
                    appearance = clearNavBarOpaqueFlag(appearance);
                }
            } else if (drawBackground) {
                appearance = clearNavBarOpaqueFlag(appearance);
            }
        } else if (mNavBarOpacityMode == NAV_BAR_TRANSLUCENT_WHEN_FREEFORM_OPAQUE_OTHERWISE) {
            if (freeformRootTaskVisible) {
                appearance = clearNavBarOpaqueFlag(appearance);
            }
        }

        if (!isFullyTransparentAllowed(mNavBarBackgroundWindow, Type.navigationBars())) {
            appearance |= APPEARANCE_SEMI_TRANSPARENT_NAVIGATION_BARS;
        }

        return appearance;
    }

    private int clearNavBarOpaqueFlag(int appearance) {
        return appearance & ~APPEARANCE_OPAQUE_NAVIGATION_BARS;
    }

    private boolean isImmersiveMode(WindowState win) {
        if (win == null) {
            return false;
        }
        if (win == getNotificationShade() || win.isActivityTypeDream()) {
            return false;
        }
        return getInsetsPolicy().hasHiddenSources(Type.navigationBars());
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
                updateSystemBarAttributes();
            }
        }
    };

    void onPowerKeyDown(boolean isScreenOn) {
        // Detect user pressing the power button in panic when an application has
        // taken over the whole screen.
        boolean panic = mImmersiveModeConfirmation.onPowerKeyDown(isScreenOn,
                SystemClock.elapsedRealtime(), isImmersiveMode(mSystemUiControllingWindow),
                isNavBarEmpty(mLastDisableFlags));
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

    /** Called when a {@link android.os.PowerManager#USER_ACTIVITY_EVENT_TOUCH} is sent. */
    public void onUserActivityEventTouch() {
        // If there is keyguard, it may use INPUT_FEATURE_DISABLE_USER_ACTIVITY (InputDispatcher
        // won't trigger user activity for touch). So while the device is not interactive, the user
        // event is only sent explicitly from SystemUI.
        if (mAwake) return;
        // If the event is triggered while the display is not awake, the screen may be showing
        // dozing UI such as AOD or overlay UI of under display fingerprint. Then set the animating
        // state temporarily to make the process more responsive.
        final WindowState w = mNotificationShade;
        mService.mAtmService.setProcessAnimatingWhileDozing(w != null ? w.getProcess() : null);
    }

    boolean onSystemUiSettingsChanged() {
        return mImmersiveModeConfirmation.onSettingChanged(mService.mCurrentUserId);
    }

    /**
     * Request a screenshot be taken.
     *
     * @param screenshotType The type of screenshot, for example either
     *                       {@link WindowManager#TAKE_SCREENSHOT_FULLSCREEN} or
     *                       {@link WindowManager#TAKE_SCREENSHOT_PROVIDED_IMAGE}
     * @param source Where the screenshot originated from (see WindowManager.ScreenshotSource)
     */
    public void takeScreenshot(int screenshotType, int source) {
        if (mScreenshotHelper != null) {
            ScreenshotRequest request =
                    new ScreenshotRequest.Builder(screenshotType, source).build();
            mScreenshotHelper.takeScreenshot(request, mHandler, null /* completionConsumer */);
        }
    }

    RefreshRatePolicy getRefreshRatePolicy() {
        return mRefreshRatePolicy;
    }

    void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.println("DisplayPolicy");
        prefix += "  ";
        final String prefixInner = prefix + "  ";
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
        if (mLastDisableFlags != 0) {
            pw.print(prefix); pw.print("mLastDisableFlags=0x");
            pw.println(Integer.toHexString(mLastDisableFlags));
        }
        if (mLastAppearance != 0) {
            pw.print(prefix); pw.print("mLastAppearance=");
            pw.println(ViewDebug.flagsToString(InsetsFlags.class, "appearance", mLastAppearance));
        }
        if (mLastBehavior != 0) {
            pw.print(prefix); pw.print("mLastBehavior=");
            pw.println(ViewDebug.flagsToString(InsetsFlags.class, "behavior", mLastBehavior));
        }
        pw.print(prefix); pw.print("mShowingDream="); pw.print(mShowingDream);
        pw.print(" mDreamingLockscreen="); pw.println(mDreamingLockscreen);
        if (mStatusBar != null) {
            pw.print(prefix); pw.print("mStatusBar="); pw.println(mStatusBar);
        }
        if (mNotificationShade != null) {
            pw.print(prefix); pw.print("mExpandedPanel="); pw.println(mNotificationShade);
        }
        pw.print(prefix); pw.print("isKeyguardShowing="); pw.println(isKeyguardShowing());
        if (mNavigationBar != null) {
            pw.print(prefix); pw.print("mNavigationBar="); pw.println(mNavigationBar);
            pw.print(prefix); pw.print("mNavBarOpacityMode="); pw.println(mNavBarOpacityMode);
            pw.print(prefix); pw.print("mNavigationBarCanMove="); pw.println(mNavigationBarCanMove);
            pw.print(prefix); pw.print("mNavigationBarPosition=");
            pw.println(mNavigationBarPosition);
        }
        if (mLeftGestureHost != null) {
            pw.print(prefix); pw.print("mLeftGestureHost="); pw.println(mLeftGestureHost);
        }
        if (mTopGestureHost != null) {
            pw.print(prefix); pw.print("mTopGestureHost="); pw.println(mTopGestureHost);
        }
        if (mRightGestureHost != null) {
            pw.print(prefix); pw.print("mRightGestureHost="); pw.println(mRightGestureHost);
        }
        if (mBottomGestureHost != null) {
            pw.print(prefix); pw.print("mBottomGestureHost="); pw.println(mBottomGestureHost);
        }
        if (mFocusedWindow != null) {
            pw.print(prefix); pw.print("mFocusedWindow="); pw.println(mFocusedWindow);
        }
        if (mTopFullscreenOpaqueWindowState != null) {
            pw.print(prefix); pw.print("mTopFullscreenOpaqueWindowState=");
            pw.println(mTopFullscreenOpaqueWindowState);
        }
        if (!mSystemBarColorApps.isEmpty()) {
            pw.print(prefix); pw.print("mSystemBarColorApps=");
            pw.println(mSystemBarColorApps);
        }
        if (!mRelaunchingSystemBarColorApps.isEmpty()) {
            pw.print(prefix); pw.print("mRelaunchingSystemBarColorApps=");
            pw.println(mRelaunchingSystemBarColorApps);
        }
        if (mNavBarColorWindowCandidate != null) {
            pw.print(prefix); pw.print("mNavBarColorWindowCandidate=");
            pw.println(mNavBarColorWindowCandidate);
        }
        if (mNavBarBackgroundWindow != null) {
            pw.print(prefix); pw.print("mNavBarBackgroundWindow=");
            pw.println(mNavBarBackgroundWindow);
        }
        if (mLastStatusBarAppearanceRegions != null) {
            pw.print(prefix); pw.println("mLastStatusBarAppearanceRegions=");
            for (int i = mLastStatusBarAppearanceRegions.length - 1; i >= 0; i--) {
                pw.print(prefixInner);  pw.println(mLastStatusBarAppearanceRegions[i]);
            }
        }
        if (mLastLetterboxDetails != null) {
            pw.print(prefix); pw.println("mLastLetterboxDetails=");
            for (int i = mLastLetterboxDetails.length - 1; i >= 0; i--) {
                pw.print(prefixInner);  pw.println(mLastLetterboxDetails[i]);
            }
        }
        if (!mStatusBarBackgroundWindows.isEmpty()) {
            pw.print(prefix); pw.println("mStatusBarBackgroundWindows=");
            for (int i = mStatusBarBackgroundWindows.size() - 1; i >= 0; i--) {
                final WindowState win = mStatusBarBackgroundWindows.get(i);
                pw.print(prefixInner);  pw.println(win);
            }
        }
        pw.print(prefix); pw.print("mTopIsFullscreen="); pw.println(mTopIsFullscreen);
        pw.print(prefix); pw.print("mForceShowNavigationBarEnabled=");
        pw.print(mForceShowNavigationBarEnabled);
        pw.print(" mAllowLockscreenWhenOn="); pw.println(mAllowLockscreenWhenOn);
        pw.print(prefix); pw.print("mRemoteInsetsControllerControlsSystemBars=");
        pw.println(mRemoteInsetsControllerControlsSystemBars);
        pw.print(prefix); pw.println("mDecorInsetsInfo:");
        for (int rotation = 0; rotation < mDecorInsets.mInfoForRotation.length; rotation++) {
            final DecorInsets.Info info = mDecorInsets.mInfoForRotation[rotation];
            pw.println(prefixInner + Surface.rotationToString(rotation) + "=" + info);
        }
        mSystemGestures.dump(pw, prefix);

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
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.type = WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        lp.privateFlags |= LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
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

        if (!mDisplayContent.isRemoved()) {
            mDisplayContent.unregisterPointerEventListener(mPointerLocationView);
        }

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

    void release() {
        mDisplayContent.mTransitionController.unregisterLegacyListener(mAppTransitionListener);
        mHandler.post(mGestureNavigationSettingsObserver::unregister);
        mHandler.post(mForceShowNavBarSettingsObserver::unregister);
        mImmersiveModeConfirmation.release();
        if (mService.mPointerLocationEnabled) {
            setPointerLocationEnabled(false);
        }
    }

    @VisibleForTesting
    static boolean isOverlappingWithNavBar(@NonNull WindowState win) {
        if (!win.isVisible()) {
            return false;
        }

        // When the window is dimming means it's requesting dim layer to its host container, so
        // checking whether it's overlapping with a navigation bar by its container's bounds.
        return intersectsAnyInsets(win.isDimming() ? win.getBounds() : win.getFrame(),
                win.getInsetsState(), Type.navigationBars());
    }

    /**
     * Returns whether the given {@param bounds} intersects with any insets of the
     * provided {@param insetsType}.
     */
    private static boolean intersectsAnyInsets(Rect bounds, InsetsState insetsState,
            @InsetsType int insetsType) {
        for (int i = insetsState.sourceSize() - 1; i >= 0; i--) {
            final InsetsSource source = insetsState.sourceAt(i);
            if ((source.getType() & insetsType) == 0 || !source.isVisible()) {
                continue;
            }
            if (Rect.intersects(bounds, source.getFrame())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return Whether we should attach navigation bar to the app during transition.
     */
    boolean shouldAttachNavBarToAppDuringTransition() {
        return mShouldAttachNavBarToAppDuringTransition && mNavigationBar != null;
    }
}
