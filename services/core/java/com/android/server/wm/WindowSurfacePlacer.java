package com.android.server.wm;

import static android.app.ActivityManagerInternal.APP_TRANSITION_SAVED_SURFACE;
import static android.app.ActivityManagerInternal.APP_TRANSITION_STARTING_WINDOW;
import static android.app.ActivityManagerInternal.APP_TRANSITION_TIMEOUT;
import static android.app.ActivityManagerInternal.APP_TRANSITION_WINDOWS_DRAWN;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_DREAM;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_CONFIG;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
import static android.view.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_POWER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TOKEN_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_TRACE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_KEEP_SCREEN_ON;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_KEEP_SCREEN_ON;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.DO_TRAVERSAL;
import static com.android.server.wm.WindowManagerService.H.NOTIFY_ACTIVITY_DRAWN;
import static com.android.server.wm.WindowManagerService.H.NOTIFY_APP_TRANSITION_STARTING;
import static com.android.server.wm.WindowManagerService.H.NOTIFY_STARTING_WINDOW_DRAWN;
import static com.android.server.wm.WindowManagerService.H.REPORT_LOSING_FOCUS;
import static com.android.server.wm.WindowManagerService.H.REPORT_WINDOWS_CHANGE;
import static com.android.server.wm.WindowManagerService.H.SEND_NEW_CONFIGURATION;
import static com.android.server.wm.WindowManagerService.H.UPDATE_DOCKED_STACK_DIVIDER;
import static com.android.server.wm.WindowManagerService.H.WINDOW_FREEZE_TIMEOUT;
import static com.android.server.wm.WindowManagerService.LAYOUT_REPEAT_THRESHOLD;
import static com.android.server.wm.WindowManagerService.MAX_ANIMATION_DURATION;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_PLACING_SURFACES;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_WILL_PLACE_SURFACES;
import static com.android.server.wm.WindowManagerService.WINDOWS_FREEZING_SCREENS_NONE;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Debug;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager.LayoutParams;
import android.view.animation.Animation;

import com.android.server.wm.WindowManagerService.H;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Positions windows and their surfaces.
 *
 * It sets positions of windows by calculating their frames and then applies this by positioning
 * surfaces according to these frames. Z layer is still assigned withing WindowManagerService.
 */
class WindowSurfacePlacer {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "WindowSurfacePlacer" : TAG_WM;
    private final WindowManagerService mService;
    private final WallpaperController mWallpaperControllerLocked;

    private boolean mInLayout = false;

    /** Only do a maximum of 6 repeated layouts. After that quit */
    private int mLayoutRepeatCount;

    static final int SET_UPDATE_ROTATION                = 1 << 0;
    static final int SET_WALLPAPER_MAY_CHANGE           = 1 << 1;
    static final int SET_FORCE_HIDING_CHANGED           = 1 << 2;
    static final int SET_ORIENTATION_CHANGE_COMPLETE    = 1 << 3;
    static final int SET_TURN_ON_SCREEN                 = 1 << 4;
    static final int SET_WALLPAPER_ACTION_PENDING       = 1 << 5;

    boolean mWallpaperMayChange = false;
    boolean mOrientationChangeComplete = true;
    boolean mWallpaperActionPending = false;

    private boolean mWallpaperForceHidingChanged = false;
    private Object mLastWindowFreezeSource = null;
    private Session mHoldScreen = null;
    private boolean mObscured = false;
    private boolean mSyswin = false;
    private float mScreenBrightness = -1;
    private float mButtonBrightness = -1;
    private long mUserActivityTimeout = -1;
    private boolean mUpdateRotation = false;
    private final Rect mTmpStartRect = new Rect();
    private final Rect mTmpContentRect = new Rect();

    // Set to true when the display contains content to show the user.
    // When false, the display manager may choose to mirror or blank the display.
    private boolean mDisplayHasContent = false;

    // Only set while traversing the default display based on its content.
    // Affects the behavior of mirroring on secondary displays.
    private boolean mObscureApplicationContentOnSecondaryDisplays = false;

    private float mPreferredRefreshRate = 0;

    private int mPreferredModeId = 0;

    private boolean mTraversalScheduled;
    private int mDeferDepth = 0;

    private boolean mSustainedPerformanceModeEnabled = false;
    private boolean mSustainedPerformanceModeCurrent = false;

    // Following variables are for debugging screen wakelock only.
    // Last window that requires screen wakelock
    WindowState mHoldScreenWindow = null;
    // Last window that obscures all windows below
    WindowState mObsuringWindow = null;

    private static final class LayerAndToken {
        public int layer;
        public AppWindowToken token;
    }
    private final LayerAndToken mTmpLayerAndToken = new LayerAndToken();

    private final ArrayList<SurfaceControl> mPendingDestroyingSurfaces = new ArrayList<>();

    public WindowSurfacePlacer(WindowManagerService service) {
        mService = service;
        mWallpaperControllerLocked = mService.mWallpaperControllerLocked;
    }

    /**
     * See {@link WindowManagerService#deferSurfaceLayout()}
     */
    void deferLayout() {
        mDeferDepth++;
    }

    /**
     * See {@link WindowManagerService#continueSurfaceLayout()}
     */
    void continueLayout() {
        mDeferDepth--;
        if (mDeferDepth <= 0) {
            performSurfacePlacement();
        }
    }

    final void performSurfacePlacement() {
        if (mDeferDepth > 0) {
            return;
        }
        int loopCount = 6;
        do {
            mTraversalScheduled = false;
            performSurfacePlacementLoop();
            mService.mH.removeMessages(DO_TRAVERSAL);
            loopCount--;
        } while (mTraversalScheduled && loopCount > 0);
        mWallpaperActionPending = false;
    }

    private void performSurfacePlacementLoop() {
        if (mInLayout) {
            if (DEBUG) {
                throw new RuntimeException("Recursive call!");
            }
            Slog.w(TAG, "performLayoutAndPlaceSurfacesLocked called while in layout. Callers="
                    + Debug.getCallers(3));
            return;
        }

        if (mService.mWaitingForConfig) {
            // Our configuration has changed (most likely rotation), but we
            // don't yet have the complete configuration to report to
            // applications.  Don't do any window layout until we have it.
            return;
        }

        if (!mService.mDisplayReady) {
            // Not yet initialized, nothing to do.
            return;
        }

        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "wmLayout");
        mInLayout = true;

        boolean recoveringMemory = false;
        if (!mService.mForceRemoves.isEmpty()) {
            recoveringMemory = true;
            // Wait a little bit for things to settle down, and off we go.
            while (!mService.mForceRemoves.isEmpty()) {
                WindowState ws = mService.mForceRemoves.remove(0);
                Slog.i(TAG, "Force removing: " + ws);
                mService.removeWindowInnerLocked(ws);
            }
            Slog.w(TAG, "Due to memory failure, waiting a bit for next layout");
            Object tmp = new Object();
            synchronized (tmp) {
                try {
                    tmp.wait(250);
                } catch (InterruptedException e) {
                }
            }
        }

        try {
            performSurfacePlacementInner(recoveringMemory);

            mInLayout = false;

            if (mService.needsLayout()) {
                if (++mLayoutRepeatCount < 6) {
                    requestTraversal();
                } else {
                    Slog.e(TAG, "Performed 6 layouts in a row. Skipping");
                    mLayoutRepeatCount = 0;
                }
            } else {
                mLayoutRepeatCount = 0;
            }

            if (mService.mWindowsChanged && !mService.mWindowChangeListeners.isEmpty()) {
                mService.mH.removeMessages(REPORT_WINDOWS_CHANGE);
                mService.mH.sendEmptyMessage(REPORT_WINDOWS_CHANGE);
            }
        } catch (RuntimeException e) {
            mInLayout = false;
            Slog.wtf(TAG, "Unhandled exception while laying out windows", e);
        }

        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
    }

    void debugLayoutRepeats(final String msg, int pendingLayoutChanges) {
        if (mLayoutRepeatCount >= LAYOUT_REPEAT_THRESHOLD) {
            Slog.v(TAG, "Layouts looping: " + msg +
                    ", mPendingLayoutChanges = 0x" + Integer.toHexString(pendingLayoutChanges));
        }
    }

    // "Something has changed!  Let's make it correct now."
    private void performSurfacePlacementInner(boolean recoveringMemory) {
        if (DEBUG_WINDOW_TRACE) Slog.v(TAG, "performSurfacePlacementInner: entry. Called by "
                + Debug.getCallers(3));

        int i;
        boolean updateInputWindowsNeeded = false;

        if (mService.mFocusMayChange) {
            mService.mFocusMayChange = false;
            updateInputWindowsNeeded = mService.updateFocusedWindowLocked(
                    UPDATE_FOCUS_WILL_PLACE_SURFACES, false /*updateInputWindows*/);
        }

        // Initialize state of exiting tokens.
        final int numDisplays = mService.mDisplayContents.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mService.mDisplayContents.valueAt(displayNdx);
            for (i=displayContent.mExitingTokens.size()-1; i>=0; i--) {
                displayContent.mExitingTokens.get(i).hasVisible = false;
            }
        }

        for (int stackNdx = mService.mStackIdToStack.size() - 1; stackNdx >= 0; --stackNdx) {
            // Initialize state of exiting applications.
            final AppTokenList exitingAppTokens =
                    mService.mStackIdToStack.valueAt(stackNdx).mExitingAppTokens;
            for (int tokenNdx = exitingAppTokens.size() - 1; tokenNdx >= 0; --tokenNdx) {
                exitingAppTokens.get(tokenNdx).hasVisible = false;
            }
        }

        mHoldScreen = null;
        mHoldScreenWindow = null;
        mObsuringWindow = null;
        mScreenBrightness = -1;
        mButtonBrightness = -1;
        mUserActivityTimeout = -1;
        mObscureApplicationContentOnSecondaryDisplays = false;
        mSustainedPerformanceModeCurrent = false;
        mService.mTransactionSequence++;

        final DisplayContent defaultDisplay = mService.getDefaultDisplayContentLocked();
        final DisplayInfo defaultInfo = defaultDisplay.getDisplayInfo();
        final int defaultDw = defaultInfo.logicalWidth;
        final int defaultDh = defaultInfo.logicalHeight;

        if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                ">>> OPEN TRANSACTION performLayoutAndPlaceSurfaces");
        SurfaceControl.openTransaction();
        try {
            applySurfaceChangesTransaction(recoveringMemory, numDisplays, defaultDw, defaultDh);
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "Unhandled exception in Window Manager", e);
        } finally {
            SurfaceControl.closeTransaction();
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                    "<<< CLOSE TRANSACTION performLayoutAndPlaceSurfaces");
        }

        final WindowList defaultWindows = defaultDisplay.getWindowList();

        // If we are ready to perform an app transition, check through
        // all of the app tokens to be shown and see if they are ready
        // to go.
        if (mService.mAppTransition.isReady()) {
            defaultDisplay.pendingLayoutChanges |= handleAppTransitionReadyLocked(defaultWindows);
            if (DEBUG_LAYOUT_REPEATS)
                debugLayoutRepeats("after handleAppTransitionReadyLocked",
                        defaultDisplay.pendingLayoutChanges);
        }

        if (!mService.mAnimator.mAppWindowAnimating && mService.mAppTransition.isRunning()) {
            // We have finished the animation of an app transition.  To do
            // this, we have delayed a lot of operations like showing and
            // hiding apps, moving apps in Z-order, etc.  The app token list
            // reflects the correct Z-order, but the window list may now
            // be out of sync with it.  So here we will just rebuild the
            // entire app window list.  Fun!
            defaultDisplay.pendingLayoutChanges |=
                    mService.handleAnimatingStoppedAndTransitionLocked();
            if (DEBUG_LAYOUT_REPEATS)
                debugLayoutRepeats("after handleAnimStopAndXitionLock",
                        defaultDisplay.pendingLayoutChanges);
        }

        if (mWallpaperForceHidingChanged && defaultDisplay.pendingLayoutChanges == 0
                && !mService.mAppTransition.isReady()) {
            // At this point, there was a window with a wallpaper that
            // was force hiding other windows behind it, but now it
            // is going away.  This may be simple -- just animate
            // away the wallpaper and its window -- or it may be
            // hard -- the wallpaper now needs to be shown behind
            // something that was hidden.
            defaultDisplay.pendingLayoutChanges |= FINISH_LAYOUT_REDO_LAYOUT;
            if (DEBUG_LAYOUT_REPEATS)
                debugLayoutRepeats("after animateAwayWallpaperLocked",
                        defaultDisplay.pendingLayoutChanges);
        }
        mWallpaperForceHidingChanged = false;

        if (mWallpaperMayChange) {
            if (DEBUG_WALLPAPER_LIGHT)
                Slog.v(TAG, "Wallpaper may change!  Adjusting");
            defaultDisplay.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
            if (DEBUG_LAYOUT_REPEATS) debugLayoutRepeats("WallpaperMayChange",
                    defaultDisplay.pendingLayoutChanges);
        }

        if (mService.mFocusMayChange) {
            mService.mFocusMayChange = false;
            if (mService.updateFocusedWindowLocked(UPDATE_FOCUS_PLACING_SURFACES,
                    false /*updateInputWindows*/)) {
                updateInputWindowsNeeded = true;
                defaultDisplay.pendingLayoutChanges |= FINISH_LAYOUT_REDO_ANIM;
            }
        }

        if (mService.needsLayout()) {
            defaultDisplay.pendingLayoutChanges |= FINISH_LAYOUT_REDO_LAYOUT;
            if (DEBUG_LAYOUT_REPEATS) debugLayoutRepeats("mLayoutNeeded",
                    defaultDisplay.pendingLayoutChanges);
        }

        for (i = mService.mResizingWindows.size() - 1; i >= 0; i--) {
            WindowState win = mService.mResizingWindows.get(i);
            if (win.mAppFreezing) {
                // Don't remove this window until rotation has completed.
                continue;
            }
            // Discard the saved surface if window size is changed, it can't be reused.
            if (win.mAppToken != null) {
                win.mAppToken.destroySavedSurfaces();
            }
            win.reportResized();
            mService.mResizingWindows.remove(i);
        }

        if (DEBUG_ORIENTATION && mService.mDisplayFrozen) Slog.v(TAG,
                "With display frozen, orientationChangeComplete=" + mOrientationChangeComplete);
        if (mOrientationChangeComplete) {
            if (mService.mWindowsFreezingScreen != WINDOWS_FREEZING_SCREENS_NONE) {
                mService.mWindowsFreezingScreen = WINDOWS_FREEZING_SCREENS_NONE;
                mService.mLastFinishedFreezeSource = mLastWindowFreezeSource;
                mService.mH.removeMessages(WINDOW_FREEZE_TIMEOUT);
            }
            mService.stopFreezingDisplayLocked();
        }

        // Destroy the surface of any windows that are no longer visible.
        boolean wallpaperDestroyed = false;
        i = mService.mDestroySurface.size();
        if (i > 0) {
            do {
                i--;
                WindowState win = mService.mDestroySurface.get(i);
                win.mDestroying = false;
                if (mService.mInputMethodWindow == win) {
                    mService.mInputMethodWindow = null;
                }
                if (mWallpaperControllerLocked.isWallpaperTarget(win)) {
                    wallpaperDestroyed = true;
                }
                win.destroyOrSaveSurface();
            } while (i > 0);
            mService.mDestroySurface.clear();
        }

        // Time to remove any exiting tokens?
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mService.mDisplayContents.valueAt(displayNdx);
            ArrayList<WindowToken> exitingTokens = displayContent.mExitingTokens;
            for (i = exitingTokens.size() - 1; i >= 0; i--) {
                WindowToken token = exitingTokens.get(i);
                if (!token.hasVisible) {
                    exitingTokens.remove(i);
                    if (token.windowType == TYPE_WALLPAPER) {
                        mWallpaperControllerLocked.removeWallpaperToken(token);
                    }
                }
            }
        }

        // Time to remove any exiting applications?
        for (int stackNdx = mService.mStackIdToStack.size() - 1; stackNdx >= 0; --stackNdx) {
            // Initialize state of exiting applications.
            final AppTokenList exitingAppTokens =
                    mService.mStackIdToStack.valueAt(stackNdx).mExitingAppTokens;
            for (i = exitingAppTokens.size() - 1; i >= 0; i--) {
                AppWindowToken token = exitingAppTokens.get(i);
                if (!token.hasVisible && !mService.mClosingApps.contains(token) &&
                        (!token.mIsExiting || token.allAppWindows.isEmpty())) {
                    // Make sure there is no animation running on this token,
                    // so any windows associated with it will be removed as
                    // soon as their animations are complete
                    token.mAppAnimator.clearAnimation();
                    token.mAppAnimator.animating = false;
                    if (DEBUG_ADD_REMOVE || DEBUG_TOKEN_MOVEMENT) Slog.v(TAG,
                            "performLayout: App token exiting now removed" + token);
                    token.removeAppFromTaskLocked();
                }
            }
        }

        if (wallpaperDestroyed) {
            defaultDisplay.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
            defaultDisplay.layoutNeeded = true;
        }

        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mService.mDisplayContents.valueAt(displayNdx);
            if (displayContent.pendingLayoutChanges != 0) {
                displayContent.layoutNeeded = true;
            }
        }

        // Finally update all input windows now that the window changes have stabilized.
        mService.mInputMonitor.updateInputWindowsLw(true /*force*/);

        mService.setHoldScreenLocked(mHoldScreen);
        if (!mService.mDisplayFrozen) {
            if (mScreenBrightness < 0 || mScreenBrightness > 1.0f) {
                mService.mPowerManagerInternal.setScreenBrightnessOverrideFromWindowManager(-1);
            } else {
                mService.mPowerManagerInternal.setScreenBrightnessOverrideFromWindowManager(
                        toBrightnessOverride(mScreenBrightness));
            }
            if (mButtonBrightness < 0
                    || mButtonBrightness > 1.0f) {
                mService.mPowerManagerInternal.setButtonBrightnessOverrideFromWindowManager(-1);
            } else {
                mService.mPowerManagerInternal.setButtonBrightnessOverrideFromWindowManager(
                        toBrightnessOverride(mButtonBrightness));
            }
            mService.mPowerManagerInternal.setUserActivityTimeoutOverrideFromWindowManager(
                    mUserActivityTimeout);
        }

        if (mSustainedPerformanceModeCurrent != mSustainedPerformanceModeEnabled) {
            mSustainedPerformanceModeEnabled = mSustainedPerformanceModeCurrent;
            mService.mPowerManagerInternal.powerHint(
                    mService.mPowerManagerInternal.POWER_HINT_SUSTAINED_PERFORMANCE_MODE,
                    (mSustainedPerformanceModeEnabled ? 1 : 0));
        }

        if (mService.mTurnOnScreen) {
            if (mService.mAllowTheaterModeWakeFromLayout
                    || Settings.Global.getInt(mService.mContext.getContentResolver(),
                        Settings.Global.THEATER_MODE_ON, 0) == 0) {
                if (DEBUG_VISIBILITY || DEBUG_POWER) {
                    Slog.v(TAG, "Turning screen on after layout!");
                }
                mService.mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                        "android.server.wm:TURN_ON");
            }
            mService.mTurnOnScreen = false;
        }

        if (mUpdateRotation) {
            if (DEBUG_ORIENTATION) Slog.d(TAG, "Performing post-rotate rotation");
            if (mService.updateRotationUncheckedLocked(false)) {
                mService.mH.sendEmptyMessage(SEND_NEW_CONFIGURATION);
            } else {
                mUpdateRotation = false;
            }
        }

        if (mService.mWaitingForDrawnCallback != null ||
                (mOrientationChangeComplete && !defaultDisplay.layoutNeeded &&
                        !mUpdateRotation)) {
            mService.checkDrawnWindowsLocked();
        }

        final int N = mService.mPendingRemove.size();
        if (N > 0) {
            if (mService.mPendingRemoveTmp.length < N) {
                mService.mPendingRemoveTmp = new WindowState[N+10];
            }
            mService.mPendingRemove.toArray(mService.mPendingRemoveTmp);
            mService.mPendingRemove.clear();
            DisplayContentList displayList = new DisplayContentList();
            for (i = 0; i < N; i++) {
                WindowState w = mService.mPendingRemoveTmp[i];
                mService.removeWindowInnerLocked(w);
                final DisplayContent displayContent = w.getDisplayContent();
                if (displayContent != null && !displayList.contains(displayContent)) {
                    displayList.add(displayContent);
                }
            }

            for (DisplayContent displayContent : displayList) {
                mService.mLayersController.assignLayersLocked(displayContent.getWindowList());
                displayContent.layoutNeeded = true;
            }
        }

        // Remove all deferred displays stacks, tasks, and activities.
        for (int displayNdx = mService.mDisplayContents.size() - 1; displayNdx >= 0; --displayNdx) {
            mService.mDisplayContents.valueAt(displayNdx).checkForDeferredActions();
        }

        if (updateInputWindowsNeeded) {
            mService.mInputMonitor.updateInputWindowsLw(false /*force*/);
        }
        mService.setFocusTaskRegionLocked();

        // Check to see if we are now in a state where the screen should
        // be enabled, because the window obscured flags have changed.
        mService.enableScreenIfNeededLocked();

        mService.scheduleAnimationLocked();
        mService.mWindowPlacerLocked.destroyPendingSurfaces();

        if (DEBUG_WINDOW_TRACE) Slog.e(TAG,
                "performSurfacePlacementInner exit: animating=" + mService.mAnimator.isAnimating());
    }

    private void applySurfaceChangesTransaction(boolean recoveringMemory, int numDisplays,
            int defaultDw, int defaultDh) {
        if (mService.mWatermark != null) {
            mService.mWatermark.positionSurface(defaultDw, defaultDh);
        }
        if (mService.mStrictModeFlash != null) {
            mService.mStrictModeFlash.positionSurface(defaultDw, defaultDh);
        }
        if (mService.mCircularDisplayMask != null) {
            mService.mCircularDisplayMask.positionSurface(defaultDw, defaultDh,
                    mService.mRotation);
        }
        if (mService.mEmulatorDisplayOverlay != null) {
            mService.mEmulatorDisplayOverlay.positionSurface(defaultDw, defaultDh,
                    mService.mRotation);
        }

        boolean focusDisplayed = false;

        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mService.mDisplayContents.valueAt(displayNdx);
            boolean updateAllDrawn = false;
            WindowList windows = displayContent.getWindowList();
            DisplayInfo displayInfo = displayContent.getDisplayInfo();
            final int displayId = displayContent.getDisplayId();
            final int dw = displayInfo.logicalWidth;
            final int dh = displayInfo.logicalHeight;
            final int innerDw = displayInfo.appWidth;
            final int innerDh = displayInfo.appHeight;
            final boolean isDefaultDisplay = (displayId == Display.DEFAULT_DISPLAY);

            // Reset for each display.
            mDisplayHasContent = false;
            mPreferredRefreshRate = 0;
            mPreferredModeId = 0;

            int repeats = 0;
            do {
                repeats++;
                if (repeats > 6) {
                    Slog.w(TAG, "Animation repeat aborted after too many iterations");
                    displayContent.layoutNeeded = false;
                    break;
                }

                if (DEBUG_LAYOUT_REPEATS) debugLayoutRepeats(
                        "On entry to LockedInner", displayContent.pendingLayoutChanges);

                if ((displayContent.pendingLayoutChanges & FINISH_LAYOUT_REDO_WALLPAPER) != 0 &&
                        mWallpaperControllerLocked.adjustWallpaperWindows()) {
                    mService.mLayersController.assignLayersLocked(windows);
                    displayContent.layoutNeeded = true;
                }

                if (isDefaultDisplay
                        && (displayContent.pendingLayoutChanges & FINISH_LAYOUT_REDO_CONFIG) != 0) {
                    if (DEBUG_LAYOUT) Slog.v(TAG, "Computing new config from layout");
                    if (mService.updateOrientationFromAppTokensLocked(true)) {
                        displayContent.layoutNeeded = true;
                        mService.mH.sendEmptyMessage(SEND_NEW_CONFIGURATION);
                    }
                }

                if ((displayContent.pendingLayoutChanges & FINISH_LAYOUT_REDO_LAYOUT) != 0) {
                    displayContent.layoutNeeded = true;
                }

                // FIRST LOOP: Perform a layout, if needed.
                if (repeats < LAYOUT_REPEAT_THRESHOLD) {
                    performLayoutLockedInner(displayContent, repeats == 1,
                            false /* updateInputWindows */);
                } else {
                    Slog.w(TAG, "Layout repeat skipped after too many iterations");
                }

                // FIRST AND ONE HALF LOOP: Make WindowManagerPolicy think
                // it is animating.
                displayContent.pendingLayoutChanges = 0;

                if (isDefaultDisplay) {
                    mService.mPolicy.beginPostLayoutPolicyLw(dw, dh);
                    for (int i = windows.size() - 1; i >= 0; i--) {
                        WindowState w = windows.get(i);
                        if (w.mHasSurface) {
                            mService.mPolicy.applyPostLayoutPolicyLw(w, w.mAttrs,
                                    w.mAttachedWindow);
                        }
                    }
                    displayContent.pendingLayoutChanges |=
                            mService.mPolicy.finishPostLayoutPolicyLw();
                    if (DEBUG_LAYOUT_REPEATS) debugLayoutRepeats("after finishPostLayoutPolicyLw",
                            displayContent.pendingLayoutChanges);
                }
            } while (displayContent.pendingLayoutChanges != 0);

            mObscured = false;
            mSyswin = false;
            displayContent.resetDimming();

            // Only used if default window
            final boolean someoneLosingFocus = !mService.mLosingFocus.isEmpty();

            for (int i = windows.size() - 1; i >= 0; i--) {
                WindowState w = windows.get(i);
                final Task task = w.getTask();
                final boolean obscuredChanged = w.mObscured != mObscured;

                // Update effect.
                w.mObscured = mObscured;
                if (!mObscured) {
                    handleNotObscuredLocked(w, displayInfo);
                }

                w.applyDimLayerIfNeeded();

                if (isDefaultDisplay && obscuredChanged
                        && mWallpaperControllerLocked.isWallpaperTarget(w) && w.isVisibleLw()) {
                    // This is the wallpaper target and its obscured state
                    // changed... make sure the current wallaper's visibility
                    // has been updated accordingly.
                    mWallpaperControllerLocked.updateWallpaperVisibility();
                }

                final WindowStateAnimator winAnimator = w.mWinAnimator;

                // If the window has moved due to its containing content frame changing, then
                // notify the listeners and optionally animate it. Simply checking a change of
                // position is not enough, because being move due to dock divider is not a trigger
                // for animation.
                if (w.hasMoved()) {
                    // Frame has moved, containing content frame has also moved, and we're not
                    // currently animating... let's do something.
                    final int left = w.mFrame.left;
                    final int top = w.mFrame.top;
                    final boolean adjustedForMinimizedDockOrIme = task != null
                                && (task.mStack.isAdjustedForMinimizedDockedStack()
                                    || task.mStack.isAdjustedForIme());
                    if ((w.mAttrs.privateFlags & PRIVATE_FLAG_NO_MOVE_ANIMATION) == 0
                            && !w.isDragResizing() && !adjustedForMinimizedDockOrIme
                            && (task == null || w.getTask().mStack.hasMovementAnimations())
                            && !w.mWinAnimator.mLastHidden) {
                        winAnimator.setMoveAnimation(left, top);
                    }

                    //TODO (multidisplay): Accessibility supported only for the default display.
                    if (mService.mAccessibilityController != null
                            && displayId == Display.DEFAULT_DISPLAY) {
                        mService.mAccessibilityController.onSomeWindowResizedOrMovedLocked();
                    }

                    try {
                        w.mClient.moved(left, top);
                    } catch (RemoteException e) {
                    }
                    w.mMovedByResize = false;
                }

                //Slog.i(TAG, "Window " + this + " clearing mContentChanged - done placing");
                w.mContentChanged = false;

                // Moved from updateWindowsAndWallpaperLocked().
                if (w.mHasSurface) {
                    // If we have recently synchronized a previous transaction for this
                    // window ensure we don't push through an unsynchronized one now.
                    winAnimator.deferToPendingTransaction();

                    // Take care of the window being ready to display.
                    final boolean committed = winAnimator.commitFinishDrawingLocked();
                    if (isDefaultDisplay && committed) {
                        if (w.mAttrs.type == TYPE_DREAM) {
                            // HACK: When a dream is shown, it may at that
                            // point hide the lock screen.  So we need to
                            // redo the layout to let the phone window manager
                            // make this happen.
                            displayContent.pendingLayoutChanges |=
                                    FINISH_LAYOUT_REDO_LAYOUT;
                            if (DEBUG_LAYOUT_REPEATS) {
                                debugLayoutRepeats("dream and commitFinishDrawingLocked true",
                                        displayContent.pendingLayoutChanges);
                            }
                        }
                        if ((w.mAttrs.flags & FLAG_SHOW_WALLPAPER) != 0) {
                            if (DEBUG_WALLPAPER_LIGHT)
                                Slog.v(TAG, "First draw done in potential wallpaper target " + w);
                            mWallpaperMayChange = true;
                            displayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
                            if (DEBUG_LAYOUT_REPEATS) {
                                debugLayoutRepeats("wallpaper and commitFinishDrawingLocked true",
                                        displayContent.pendingLayoutChanges);
                            }
                        }
                    }
                    if (!winAnimator.isAnimationStarting() && !winAnimator.isWaitingForOpening()) {
                        // Updates the shown frame before we set up the surface. This is needed
                        // because the resizing could change the top-left position (in addition to
                        // size) of the window. setSurfaceBoundariesLocked uses mShownPosition to
                        // position the surface.
                        //
                        // If an animation is being started, we can't call this method because the
                        // animation hasn't processed its initial transformation yet, but in general
                        // we do want to update the position if the window is animating.
                        winAnimator.computeShownFrameLocked();
                    }
                    winAnimator.setSurfaceBoundariesLocked(recoveringMemory);
                }

                final AppWindowToken atoken = w.mAppToken;
                if (DEBUG_STARTING_WINDOW && atoken != null && w == atoken.startingWindow) {
                    Slog.d(TAG, "updateWindows: starting " + w
                            + " isOnScreen=" + w.isOnScreen() + " allDrawn=" + atoken.allDrawn
                            + " freezingScreen=" + atoken.mAppAnimator.freezingScreen);
                }
                if (atoken != null && (!atoken.allDrawn || !atoken.allDrawnExcludingSaved
                        || atoken.mAppAnimator.freezingScreen)) {
                    if (atoken.lastTransactionSequence != mService.mTransactionSequence) {
                        atoken.lastTransactionSequence = mService.mTransactionSequence;
                        atoken.numInterestingWindows = atoken.numDrawnWindows = 0;
                        atoken.numInterestingWindowsExcludingSaved = 0;
                        atoken.numDrawnWindowsExclusingSaved = 0;
                        atoken.startingDisplayed = false;
                    }
                    if (!atoken.allDrawn && w.mightAffectAllDrawn(false /* visibleOnly */)) {
                        if (DEBUG_VISIBILITY || DEBUG_ORIENTATION) {
                            Slog.v(TAG, "Eval win " + w + ": isDrawn="
                                    + w.isDrawnLw()
                                    + ", isAnimationSet=" + winAnimator.isAnimationSet());
                            if (!w.isDrawnLw()) {
                                Slog.v(TAG, "Not displayed: s="
                                        + winAnimator.mSurfaceController
                                        + " pv=" + w.mPolicyVisibility
                                        + " mDrawState=" + winAnimator.drawStateToString()
                                        + " ah=" + w.mAttachedHidden
                                        + " th=" + atoken.hiddenRequested
                                        + " a=" + winAnimator.mAnimating);
                            }
                        }
                        if (w != atoken.startingWindow) {
                            if (w.isInteresting()) {
                                atoken.numInterestingWindows++;
                                if (w.isDrawnLw()) {
                                    atoken.numDrawnWindows++;
                                    if (DEBUG_VISIBILITY || DEBUG_ORIENTATION)
                                        Slog.v(TAG, "tokenMayBeDrawn: " + atoken
                                                + " w=" + w + " numInteresting="
                                                + atoken.numInterestingWindows
                                                + " freezingScreen="
                                                + atoken.mAppAnimator.freezingScreen
                                                + " mAppFreezing=" + w.mAppFreezing);
                                    updateAllDrawn = true;
                                }
                            }
                        } else if (w.isDrawnLw()) {
                            mService.mH.sendEmptyMessage(NOTIFY_STARTING_WINDOW_DRAWN);
                            atoken.startingDisplayed = true;
                        }
                    }
                    if (!atoken.allDrawnExcludingSaved
                            && w.mightAffectAllDrawn(true /* visibleOnly */)) {
                        if (w != atoken.startingWindow && w.isInteresting()) {
                            atoken.numInterestingWindowsExcludingSaved++;
                            if (w.isDrawnLw() && !w.isAnimatingWithSavedSurface()) {
                                atoken.numDrawnWindowsExclusingSaved++;
                                if (DEBUG_VISIBILITY || DEBUG_ORIENTATION)
                                    Slog.v(TAG, "tokenMayBeDrawnExcludingSaved: " + atoken
                                            + " w=" + w + " numInteresting="
                                            + atoken.numInterestingWindowsExcludingSaved
                                            + " freezingScreen="
                                            + atoken.mAppAnimator.freezingScreen
                                            + " mAppFreezing=" + w.mAppFreezing);
                                updateAllDrawn = true;
                            }
                        }
                    }
                }

                if (isDefaultDisplay && someoneLosingFocus && w == mService.mCurrentFocus
                        && w.isDisplayedLw()) {
                    focusDisplayed = true;
                }

                mService.updateResizingWindows(w);
            }

            mService.mDisplayManagerInternal.setDisplayProperties(displayId,
                    mDisplayHasContent,
                    mPreferredRefreshRate,
                    mPreferredModeId,
                    true /* inTraversal, must call performTraversalInTrans... below */);

            mService.getDisplayContentLocked(displayId).stopDimmingIfNeeded();

            if (updateAllDrawn) {
                updateAllDrawnLocked(displayContent);
            }
        }

        if (focusDisplayed) {
            mService.mH.sendEmptyMessage(REPORT_LOSING_FOCUS);
        }

        // Give the display manager a chance to adjust properties
        // like display rotation if it needs to.
        mService.mDisplayManagerInternal.performTraversalInTransactionFromWindowManager();
    }

    boolean isInLayout() {
        return mInLayout;
    }

    final void performLayoutLockedInner(final DisplayContent displayContent,
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

        if (mService.mInputConsumer != null) {
            mService.mInputConsumer.layout(dw, dh);
        }

        if (mService.mWallpaperInputConsumer != null) {
            mService.mWallpaperInputConsumer.layout(dw, dh);
        }

        final int N = windows.size();
        int i;

        if (DEBUG_LAYOUT) {
            Slog.v(TAG, "-------------------------------------");
            Slog.v(TAG, "performLayout: needed="
                    + displayContent.layoutNeeded + " dw=" + dw + " dh=" + dh);
        }

        mService.mPolicy.beginLayoutLw(isDefaultDisplay, dw, dh, mService.mRotation,
                mService.mCurConfiguration.uiMode);
        if (isDefaultDisplay) {
            // Not needed on non-default displays.
            mService.mSystemDecorLayer = mService.mPolicy.getSystemDecorLayerLw();
            mService.mScreenRect.set(0, 0, dw, dh);
        }

        mService.mPolicy.getContentRectLw(mTmpContentRect);
        displayContent.resize(mTmpContentRect);

        int seq = mService.mLayoutSeq+1;
        if (seq < 0) seq = 0;
        mService.mLayoutSeq = seq;

        boolean behindDream = false;

        // First perform layout of any root windows (not attached
        // to another window).
        int topAttached = -1;
        for (i = N-1; i >= 0; i--) {
            final WindowState win = windows.get(i);

            // Don't do layout of a window if it is not visible, or
            // soon won't be visible, to avoid wasting time and funky
            // changes while a window is animating away.
            final boolean gone = (behindDream && mService.mPolicy.canBeForceHidden(win, win.mAttrs))
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
                    || ((win.isConfigChanged() || win.setInsetsChanged())
                            && !win.isGoneForLayoutLw() &&
                            ((win.mAttrs.privateFlags & PRIVATE_FLAG_KEYGUARD) != 0 ||
                            (win.mHasSurface && win.mAppToken != null &&
                            win.mAppToken.layoutConfigChanges)))) {
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
                    mService.mPolicy.layoutWindowLw(win, null);
                    win.mLayoutSeq = seq;

                    // Window frames may have changed. Update dim layer with the new bounds.
                    final Task task = win.getTask();
                    if (task != null) {
                        displayContent.mDimLayerController.updateDimLayer(task);
                    }

                    if (DEBUG_LAYOUT) Slog.v(TAG,
                            "  LAYOUT: mFrame="
                            + win.mFrame + " mContainingFrame="
                            + win.mContainingFrame + " mDisplayFrame="
                            + win.mDisplayFrame);
                } else {
                    if (topAttached < 0) topAttached = i;
                }
            }
        }

        boolean attachedBehindDream = false;

        // Now perform layout of attached windows, which usually
        // depend on the position of the window they are attached to.
        // XXX does not deal with windows that are attached to windows
        // that are themselves attached.
        for (i = topAttached; i >= 0; i--) {
            final WindowState win = windows.get(i);

            if (win.mLayoutAttached) {
                if (DEBUG_LAYOUT) Slog.v(TAG,
                        "2ND PASS " + win + " mHaveFrame=" + win.mHaveFrame + " mViewVisibility="
                        + win.mViewVisibility + " mRelayoutCalled=" + win.mRelayoutCalled);
                // If this view is GONE, then skip it -- keep the current
                // frame, and let the caller know so they can ignore it
                // if they want.  (We do the normal layout for INVISIBLE
                // windows, since that means "perform layout as normal,
                // just don't display").
                if (attachedBehindDream && mService.mPolicy.canBeForceHidden(win, win.mAttrs)) {
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
                    mService.mPolicy.layoutWindowLw(win, win.mAttachedWindow);
                    win.mLayoutSeq = seq;
                    if (DEBUG_LAYOUT) Slog.v(TAG,
                            "  LAYOUT: mFrame=" + win.mFrame + " mContainingFrame="
                            + win.mContainingFrame + " mDisplayFrame=" + win.mDisplayFrame);
                }
            } else if (win.mAttrs.type == TYPE_DREAM) {
                // Don't layout windows behind a dream, so that if it
                // does stuff like hide the status bar we won't get a
                // bad transition when it goes away.
                attachedBehindDream = behindDream;
            }
        }

        // Window frames may have changed. Tell the input dispatcher about it.
        mService.mInputMonitor.setUpdateInputWindowsNeededLw();
        if (updateInputWindows) {
            mService.mInputMonitor.updateInputWindowsLw(false /*force*/);
        }

        mService.mPolicy.finishLayoutLw();
        mService.mH.sendEmptyMessage(UPDATE_DOCKED_STACK_DIVIDER);
    }

    /**
     * @param windows List of windows on default display.
     * @return bitmap indicating if another pass through layout must be made.
     */
    private int handleAppTransitionReadyLocked(WindowList windows) {
        int appsCount = mService.mOpeningApps.size();
        if (!transitionGoodToGo(appsCount)) {
            return 0;
        }
        if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "**** GOOD TO GO");
        int transit = mService.mAppTransition.getAppTransition();
        if (mService.mSkipAppTransitionAnimation) {
            transit = AppTransition.TRANSIT_UNSET;
        }
        mService.mSkipAppTransitionAnimation = false;
        mService.mNoAnimationNotifyOnTransitionFinished.clear();

        mService.mH.removeMessages(H.APP_TRANSITION_TIMEOUT);

        mService.rebuildAppWindowListLocked();

        mWallpaperMayChange = false;

        // The top-most window will supply the layout params,
        // and we will determine it below.
        LayoutParams animLp = null;
        int bestAnimLayer = -1;
        boolean fullscreenAnim = false;
        boolean voiceInteraction = false;

        final WindowState lowerWallpaperTarget =
                mWallpaperControllerLocked.getLowerWallpaperTarget();
        final WindowState upperWallpaperTarget =
                mWallpaperControllerLocked.getUpperWallpaperTarget();

        boolean openingAppHasWallpaper = false;
        boolean closingAppHasWallpaper = false;
        final AppWindowToken lowerWallpaperAppToken;
        final AppWindowToken upperWallpaperAppToken;
        if (lowerWallpaperTarget == null) {
            lowerWallpaperAppToken = upperWallpaperAppToken = null;
        } else {
            lowerWallpaperAppToken = lowerWallpaperTarget.mAppToken;
            upperWallpaperAppToken = upperWallpaperTarget.mAppToken;
        }

        int i;
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
        final int closingAppsCount = mService.mClosingApps.size();
        appsCount = closingAppsCount + mService.mOpeningApps.size();
        for (i = 0; i < appsCount; i++) {
            final AppWindowToken wtoken;
            if (i < closingAppsCount) {
                wtoken = mService.mClosingApps.valueAt(i);
                if (wtoken == lowerWallpaperAppToken || wtoken == upperWallpaperAppToken) {
                    closingAppHasWallpaper = true;
                }
            } else {
                wtoken = mService.mOpeningApps.valueAt(i - closingAppsCount);
                if (wtoken == lowerWallpaperAppToken || wtoken == upperWallpaperAppToken) {
                    openingAppHasWallpaper = true;
                }
            }

            voiceInteraction |= wtoken.voiceInteraction;

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

        transit = maybeUpdateTransitToWallpaper(transit, openingAppHasWallpaper,
                closingAppHasWallpaper, lowerWallpaperTarget, upperWallpaperTarget);

        // If all closing windows are obscured, then there is
        // no need to do an animation.  This is the case, for
        // example, when this transition is being done behind
        // the lock screen.
        if (!mService.mPolicy.allowAppAnimationsLw()) {
            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                    "Animations disallowed by keyguard or dream.");
            animLp = null;
        }

        processApplicationsAnimatingInPlace(transit);

        mTmpLayerAndToken.token = null;
        handleClosingApps(transit, animLp, voiceInteraction, mTmpLayerAndToken);
        final AppWindowToken topClosingApp = mTmpLayerAndToken.token;
        final int topClosingLayer = mTmpLayerAndToken.layer;

        final AppWindowToken topOpeningApp = handleOpeningApps(transit,
                animLp, voiceInteraction, topClosingLayer);

        final AppWindowAnimator openingAppAnimator = (topOpeningApp == null) ?  null :
                topOpeningApp.mAppAnimator;
        final AppWindowAnimator closingAppAnimator = (topClosingApp == null) ? null :
                topClosingApp.mAppAnimator;

        mService.mAppTransition.goodToGo(openingAppAnimator, closingAppAnimator,
                mService.mOpeningApps, mService.mClosingApps);
        mService.mAppTransition.postAnimationCallback();
        mService.mAppTransition.clear();

        mService.mOpeningApps.clear();
        mService.mClosingApps.clear();

        // This has changed the visibility of windows, so perform
        // a new layout to get them all up-to-date.
        mService.getDefaultDisplayContentLocked().layoutNeeded = true;

        // TODO(multidisplay): IMEs are only supported on the default display.
        if (windows == mService.getDefaultWindowListLocked()
                && !mService.moveInputMethodWindowsIfNeededLocked(true)) {
            mService.mLayersController.assignLayersLocked(windows);
        }
        mService.updateFocusedWindowLocked(UPDATE_FOCUS_PLACING_SURFACES,
                true /*updateInputWindows*/);
        mService.mFocusMayChange = false;
        mService.notifyActivityDrawnForKeyguard();
        return FINISH_LAYOUT_REDO_LAYOUT | FINISH_LAYOUT_REDO_CONFIG;
    }

    private AppWindowToken handleOpeningApps(int transit, LayoutParams animLp,
            boolean voiceInteraction, int topClosingLayer) {
        AppWindowToken topOpeningApp = null;
        final int appsCount = mService.mOpeningApps.size();
        for (int i = 0; i < appsCount; i++) {
            AppWindowToken wtoken = mService.mOpeningApps.valueAt(i);
            final AppWindowAnimator appAnimator = wtoken.mAppAnimator;
            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "Now opening app" + wtoken);

            if (!appAnimator.usingTransferredAnimation) {
                appAnimator.clearThumbnail();
                appAnimator.setNullAnimation();
            }
            wtoken.inPendingTransaction = false;

            if (!mService.setTokenVisibilityLocked(
                    wtoken, animLp, true, transit, false, voiceInteraction)){
                // This token isn't going to be animating. Add it to the list of tokens to
                // be notified of app transition complete since the notification will not be
                // sent be the app window animator.
                mService.mNoAnimationNotifyOnTransitionFinished.add(wtoken.token);
            }
            wtoken.updateReportedVisibilityLocked();
            wtoken.waitingToShow = false;

            appAnimator.mAllAppWinAnimators.clear();
            final int windowsCount = wtoken.allAppWindows.size();
            for (int j = 0; j < windowsCount; j++) {
                appAnimator.mAllAppWinAnimators.add(wtoken.allAppWindows.get(j).mWinAnimator);
            }
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                    ">>> OPEN TRANSACTION handleAppTransitionReadyLocked()");
            SurfaceControl.openTransaction();
            try {
                mService.mAnimator.orAnimating(appAnimator.showAllWindowsLocked());
            } finally {
                SurfaceControl.closeTransaction();
                if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                        "<<< CLOSE TRANSACTION handleAppTransitionReadyLocked()");
            }
            mService.mAnimator.mAppWindowAnimating |= appAnimator.isAnimating();

            int topOpeningLayer = 0;
            if (animLp != null) {
                int layer = -1;
                for (int j = 0; j < wtoken.allAppWindows.size(); j++) {
                    final WindowState win = wtoken.allAppWindows.get(j);
                    // Clearing the mAnimatingExit flag before entering animation. It will be set to true
                    // if app window is removed, or window relayout to invisible. We don't want to
                    // clear it out for windows that get replaced, because the animation depends on
                    // the flag to remove the replaced window.
                    //
                    // We also don't clear the mAnimatingExit flag for windows which have the
                    // mRemoveOnExit flag. This indicates an explicit remove request has been issued
                    // by the client. We should let animation proceed and not clear this flag or
                    // they won't eventually be removed by WindowStateAnimator#finishExit.
                    if (!win.mWillReplaceWindow && !win.mRemoveOnExit) {
                        win.mAnimatingExit = false;
                        // Clear mAnimating flag together with mAnimatingExit. When animation
                        // changes from exiting to entering, we need to clear this flag until the
                        // new animation gets applied, so that isAnimationStarting() becomes true
                        // until then.
                        // Otherwise applySurfaceChangesTransaction will faill to skip surface
                        // placement for this window during this period, one or more frame will
                        // show up with wrong position or scale.
                        win.mWinAnimator.mAnimating = false;
                    }
                    if (win.mWinAnimator.mAnimLayer > layer) {
                        layer = win.mWinAnimator.mAnimLayer;
                    }
                }
                if (topOpeningApp == null || layer > topOpeningLayer) {
                    topOpeningApp = wtoken;
                    topOpeningLayer = layer;
                }
            }
            if (mService.mAppTransition.isNextAppTransitionThumbnailUp()) {
                createThumbnailAppAnimator(transit, wtoken, topOpeningLayer, topClosingLayer);
            }
        }
        return topOpeningApp;
    }

    private void handleClosingApps(int transit, LayoutParams animLp, boolean voiceInteraction,
            LayerAndToken layerAndToken) {
        final int appsCount;
        appsCount = mService.mClosingApps.size();
        for (int i = 0; i < appsCount; i++) {
            AppWindowToken wtoken = mService.mClosingApps.valueAt(i);

            // If we still have some windows animating with saved surfaces that's
            // either invisible or already removed, mark them exiting so that they
            // are disposed of after the exit animation. These are not supposed to
            // be shown, or are delayed removal until app is actually drawn (in which
            // case the window will be removed after the animation).
            wtoken.markSavedSurfaceExiting();

            final AppWindowAnimator appAnimator = wtoken.mAppAnimator;
            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "Now closing app " + wtoken);
            appAnimator.clearThumbnail();
            appAnimator.setNullAnimation();
            wtoken.inPendingTransaction = false;
            mService.setTokenVisibilityLocked(wtoken, animLp, false, transit, false,
                    voiceInteraction);
            wtoken.updateReportedVisibilityLocked();
            // Force the allDrawn flag, because we want to start
            // this guy's animations regardless of whether it's
            // gotten drawn.
            wtoken.allDrawn = true;
            wtoken.deferClearAllDrawn = false;
            // Ensure that apps that are mid-starting are also scheduled to have their
            // starting windows removed after the animation is complete
            if (wtoken.startingWindow != null && !wtoken.startingWindow.mAnimatingExit) {
                mService.scheduleRemoveStartingWindowLocked(wtoken);
            }
            mService.mAnimator.mAppWindowAnimating |= appAnimator.isAnimating();

            if (animLp != null) {
                int layer = -1;
                for (int j = 0; j < wtoken.windows.size(); j++) {
                    WindowState win = wtoken.windows.get(j);
                    if (win.mWinAnimator.mAnimLayer > layer) {
                        layer = win.mWinAnimator.mAnimLayer;
                    }
                }
                if (layerAndToken.token == null || layer > layerAndToken.layer) {
                    layerAndToken.token = wtoken;
                    layerAndToken.layer = layer;
                }
            }
            if (mService.mAppTransition.isNextAppTransitionThumbnailDown()) {
                createThumbnailAppAnimator(transit, wtoken, 0, layerAndToken.layer);
            }
        }
    }

    private boolean transitionGoodToGo(int appsCount) {
        if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                "Checking " + appsCount + " opening apps (frozen="
                        + mService.mDisplayFrozen + " timeout="
                        + mService.mAppTransition.isTimeout() + ")...");
        int reason = APP_TRANSITION_TIMEOUT;
        if (!mService.mAppTransition.isTimeout()) {
            for (int i = 0; i < appsCount; i++) {
                AppWindowToken wtoken = mService.mOpeningApps.valueAt(i);
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                        "Check opening app=" + wtoken + ": allDrawn="
                        + wtoken.allDrawn + " startingDisplayed="
                        + wtoken.startingDisplayed + " startingMoved="
                        + wtoken.startingMoved + " isRelaunching()="
                        + wtoken.isRelaunching());

                if (wtoken.isRelaunching()) {
                    return false;
                }

                final boolean drawnBeforeRestoring = wtoken.allDrawn;
                wtoken.restoreSavedSurfaces();

                if (!wtoken.allDrawn && !wtoken.startingDisplayed && !wtoken.startingMoved) {
                    return false;
                }
                if (wtoken.allDrawn) {
                    reason = drawnBeforeRestoring ? APP_TRANSITION_WINDOWS_DRAWN
                            : APP_TRANSITION_SAVED_SURFACE;
                } else {
                    reason = APP_TRANSITION_STARTING_WINDOW;
                }
            }

            // We also need to wait for the specs to be fetched, if needed.
            if (mService.mAppTransition.isFetchingAppTransitionsSpecs()) {
                if (DEBUG_APP_TRANSITIONS) Slog.v(TAG, "isFetchingAppTransitionSpecs=true");
                return false;
            }

            // If the wallpaper is visible, we need to check it's ready too.
            boolean wallpaperReady = !mWallpaperControllerLocked.isWallpaperVisible() ||
                    mWallpaperControllerLocked.wallpaperTransitionReady();
            if (wallpaperReady) {
                mService.mH.obtainMessage(NOTIFY_APP_TRANSITION_STARTING, reason, 0).sendToTarget();
                return true;
            }
            return false;
        }
        mService.mH.obtainMessage(NOTIFY_APP_TRANSITION_STARTING, reason, 0).sendToTarget();
        return true;
    }

    private int maybeUpdateTransitToWallpaper(int transit, boolean openingAppHasWallpaper,
            boolean closingAppHasWallpaper, WindowState lowerWallpaperTarget,
            WindowState upperWallpaperTarget) {
        // if wallpaper is animating in or out set oldWallpaper to null else to wallpaper
        final WindowState wallpaperTarget = mWallpaperControllerLocked.getWallpaperTarget();
        final WindowState oldWallpaper =
                mWallpaperControllerLocked.isWallpaperTargetAnimating()
                        ? null : wallpaperTarget;
        final ArraySet<AppWindowToken> openingApps = mService.mOpeningApps;
        final ArraySet<AppWindowToken> closingApps = mService.mClosingApps;
        if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                "New wallpaper target=" + wallpaperTarget
                        + ", oldWallpaper=" + oldWallpaper
                        + ", lower target=" + lowerWallpaperTarget
                        + ", upper target=" + upperWallpaperTarget
                        + ", openingApps=" + openingApps
                        + ", closingApps=" + closingApps);
        mService.mAnimateWallpaperWithTarget = false;
        if (closingAppHasWallpaper && openingAppHasWallpaper) {
            if (DEBUG_APP_TRANSITIONS)
                Slog.v(TAG, "Wallpaper animation!");
            switch (transit) {
                case AppTransition.TRANSIT_ACTIVITY_OPEN:
                case AppTransition.TRANSIT_TASK_OPEN:
                case AppTransition.TRANSIT_TASK_TO_FRONT:
                    transit = AppTransition.TRANSIT_WALLPAPER_INTRA_OPEN;
                    break;
                case AppTransition.TRANSIT_ACTIVITY_CLOSE:
                case AppTransition.TRANSIT_TASK_CLOSE:
                case AppTransition.TRANSIT_TASK_TO_BACK:
                    transit = AppTransition.TRANSIT_WALLPAPER_INTRA_CLOSE;
                    break;
            }
            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                    "New transit: " + AppTransition.appTransitionToString(transit));
        } else if (oldWallpaper != null && !mService.mOpeningApps.isEmpty()
                && !openingApps.contains(oldWallpaper.mAppToken)
                && closingApps.contains(oldWallpaper.mAppToken)) {
            // We are transitioning from an activity with a wallpaper to one without.
            transit = AppTransition.TRANSIT_WALLPAPER_CLOSE;
            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                    "New transit away from wallpaper: "
                    + AppTransition.appTransitionToString(transit));
        } else if (wallpaperTarget != null && wallpaperTarget.isVisibleLw() &&
                openingApps.contains(wallpaperTarget.mAppToken)) {
            // We are transitioning from an activity without
            // a wallpaper to now showing the wallpaper
            transit = AppTransition.TRANSIT_WALLPAPER_OPEN;
            if (DEBUG_APP_TRANSITIONS) Slog.v(TAG,
                    "New transit into wallpaper: "
                    + AppTransition.appTransitionToString(transit));
        } else {
            mService.mAnimateWallpaperWithTarget = true;
        }
        return transit;
    }

    /**
     * @param w WindowState this method is applied to.
     * @param dispInfo info of the display that the window's obscuring state is checked against.
     */
    private void handleNotObscuredLocked(final WindowState w, final DisplayInfo dispInfo) {
        final LayoutParams attrs = w.mAttrs;
        final int attrFlags = attrs.flags;
        final boolean canBeSeen = w.isDisplayedLw();
        final int privateflags = attrs.privateFlags;

        if (canBeSeen && w.isObscuringFullscreen(dispInfo)) {
            // This window completely covers everything behind it,
            // so we want to leave all of them as undimmed (for
            // performance reasons).
            if (!mObscured) {
                mObsuringWindow = w;
            }

            mObscured = true;
        }

        if (w.mHasSurface) {
            if ((attrFlags&FLAG_KEEP_SCREEN_ON) != 0) {
                mHoldScreen = w.mSession;
                mHoldScreenWindow = w;
            } else if (DEBUG_KEEP_SCREEN_ON && w == mService.mLastWakeLockHoldingWindow) {
                Slog.d(TAG_KEEP_SCREEN_ON, "handleNotObscuredLocked: " + w + " was holding "
                        + "screen wakelock but no longer has FLAG_KEEP_SCREEN_ON!!! called by"
                        + Debug.getCallers(10));
            }
            if (!mSyswin && w.mAttrs.screenBrightness >= 0
                    && mScreenBrightness < 0) {
                mScreenBrightness = w.mAttrs.screenBrightness;
            }
            if (!mSyswin && w.mAttrs.buttonBrightness >= 0
                    && mButtonBrightness < 0) {
                mButtonBrightness = w.mAttrs.buttonBrightness;
            }
            if (!mSyswin && w.mAttrs.userActivityTimeout >= 0
                    && mUserActivityTimeout < 0) {
                mUserActivityTimeout = w.mAttrs.userActivityTimeout;
            }

            final int type = attrs.type;
            if (canBeSeen
                    && (type == TYPE_SYSTEM_DIALOG
                     || type == TYPE_SYSTEM_ERROR
                     || (attrs.privateFlags & PRIVATE_FLAG_KEYGUARD) != 0)) {
                mSyswin = true;
            }

            if (canBeSeen) {
                // This function assumes that the contents of the default display are
                // processed first before secondary displays.
                final DisplayContent displayContent = w.getDisplayContent();
                if (displayContent != null && displayContent.isDefaultDisplay) {
                    // While a dream or keyguard is showing, obscure ordinary application
                    // content on secondary displays (by forcibly enabling mirroring unless
                    // there is other content we want to show) but still allow opaque
                    // keyguard dialogs to be shown.
                    if (type == TYPE_DREAM || (attrs.privateFlags & PRIVATE_FLAG_KEYGUARD) != 0) {
                        mObscureApplicationContentOnSecondaryDisplays = true;
                    }
                    mDisplayHasContent = true;
                } else if (displayContent != null &&
                        (!mObscureApplicationContentOnSecondaryDisplays
                        || (mObscured && type == TYPE_KEYGUARD_DIALOG))) {
                    // Allow full screen keyguard presentation dialogs to be seen.
                    mDisplayHasContent = true;
                }
                if (mPreferredRefreshRate == 0
                        && w.mAttrs.preferredRefreshRate != 0) {
                    mPreferredRefreshRate = w.mAttrs.preferredRefreshRate;
                }
                if (mPreferredModeId == 0
                        && w.mAttrs.preferredDisplayModeId != 0) {
                    mPreferredModeId = w.mAttrs.preferredDisplayModeId;
                }
                if ((privateflags & PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE) != 0) {
                    mSustainedPerformanceModeCurrent = true;
                }
            }
        }
    }

    private void updateAllDrawnLocked(DisplayContent displayContent) {
        // See if any windows have been drawn, so they (and others
        // associated with them) can now be shown.
        ArrayList<TaskStack> stacks = displayContent.getStacks();
        for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ArrayList<Task> tasks = stacks.get(stackNdx).getTasks();
            for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                final AppTokenList tokens = tasks.get(taskNdx).mAppTokens;
                for (int tokenNdx = tokens.size() - 1; tokenNdx >= 0; --tokenNdx) {
                    final AppWindowToken wtoken = tokens.get(tokenNdx);
                    if (!wtoken.allDrawn) {
                        int numInteresting = wtoken.numInterestingWindows;
                        if (numInteresting > 0 && wtoken.numDrawnWindows >= numInteresting) {
                            if (DEBUG_VISIBILITY)
                                Slog.v(TAG, "allDrawn: " + wtoken
                                    + " interesting=" + numInteresting
                                    + " drawn=" + wtoken.numDrawnWindows);
                            wtoken.allDrawn = true;
                            // Force an additional layout pass where WindowStateAnimator#
                            // commitFinishDrawingLocked() will call performShowLocked().
                            displayContent.layoutNeeded = true;
                            mService.mH.obtainMessage(NOTIFY_ACTIVITY_DRAWN,
                                    wtoken.token).sendToTarget();
                        }
                    }
                    if (!wtoken.allDrawnExcludingSaved) {
                        int numInteresting = wtoken.numInterestingWindowsExcludingSaved;
                        if (numInteresting > 0
                                && wtoken.numDrawnWindowsExclusingSaved >= numInteresting) {
                            if (DEBUG_VISIBILITY)
                                Slog.v(TAG, "allDrawnExcludingSaved: " + wtoken
                                    + " interesting=" + numInteresting
                                    + " drawn=" + wtoken.numDrawnWindowsExclusingSaved);
                            wtoken.allDrawnExcludingSaved = true;
                            displayContent.layoutNeeded = true;
                            if (wtoken.isAnimatingInvisibleWithSavedSurface()
                                    && !mService.mFinishedEarlyAnim.contains(wtoken)) {
                                mService.mFinishedEarlyAnim.add(wtoken);
                            }
                        }
                    }
                }
            }
        }
    }

    private static int toBrightnessOverride(float value) {
        return (int)(value * PowerManager.BRIGHTNESS_ON);
    }

    private void processApplicationsAnimatingInPlace(int transit) {
        if (transit == AppTransition.TRANSIT_TASK_IN_PLACE) {
            // Find the focused window
            final WindowState win = mService.findFocusedWindowLocked(
                    mService.getDefaultDisplayContentLocked());
            if (win != null) {
                final AppWindowToken wtoken = win.mAppToken;
                final AppWindowAnimator appAnimator = wtoken.mAppAnimator;
                if (DEBUG_APP_TRANSITIONS)
                    Slog.v(TAG, "Now animating app in place " + wtoken);
                appAnimator.clearThumbnail();
                appAnimator.setNullAnimation();
                mService.updateTokenInPlaceLocked(wtoken, transit);
                wtoken.updateReportedVisibilityLocked();

                appAnimator.mAllAppWinAnimators.clear();
                final int N = wtoken.allAppWindows.size();
                for (int j = 0; j < N; j++) {
                    appAnimator.mAllAppWinAnimators.add(wtoken.allAppWindows.get(j).mWinAnimator);
                }
                mService.mAnimator.mAppWindowAnimating |= appAnimator.isAnimating();
                mService.mAnimator.orAnimating(appAnimator.showAllWindowsLocked());
            }
        }
    }

    private void createThumbnailAppAnimator(int transit, AppWindowToken appToken,
            int openingLayer, int closingLayer) {
        AppWindowAnimator openingAppAnimator = (appToken == null) ? null : appToken.mAppAnimator;
        if (openingAppAnimator == null || openingAppAnimator.animation == null) {
            return;
        }
        final int taskId = appToken.mTask.mTaskId;
        Bitmap thumbnailHeader = mService.mAppTransition.getAppTransitionThumbnailHeader(taskId);
        if (thumbnailHeader == null || thumbnailHeader.getConfig() == Bitmap.Config.ALPHA_8) {
            if (DEBUG_APP_TRANSITIONS) Slog.d(TAG, "No thumbnail header bitmap for: " + taskId);
            return;
        }
        // This thumbnail animation is very special, we need to have
        // an extra surface with the thumbnail included with the animation.
        Rect dirty = new Rect(0, 0, thumbnailHeader.getWidth(), thumbnailHeader.getHeight());
        try {
            // TODO(multi-display): support other displays
            final DisplayContent displayContent = mService.getDefaultDisplayContentLocked();
            final Display display = displayContent.getDisplay();
            final DisplayInfo displayInfo = displayContent.getDisplayInfo();

            // Create a new surface for the thumbnail
            SurfaceControl surfaceControl = new SurfaceControl(mService.mFxSession,
                    "thumbnail anim", dirty.width(), dirty.height(),
                    PixelFormat.TRANSLUCENT, SurfaceControl.HIDDEN);
            surfaceControl.setLayerStack(display.getLayerStack());
            if (SHOW_TRANSACTIONS) {
                Slog.i(TAG, "  THUMBNAIL " + surfaceControl + ": CREATE");
            }

            // Draw the thumbnail onto the surface
            Surface drawSurface = new Surface();
            drawSurface.copyFrom(surfaceControl);
            Canvas c = drawSurface.lockCanvas(dirty);
            c.drawBitmap(thumbnailHeader, 0, 0, null);
            drawSurface.unlockCanvasAndPost(c);
            drawSurface.release();

            // Get the thumbnail animation
            Animation anim;
            if (mService.mAppTransition.isNextThumbnailTransitionAspectScaled()) {
                // If this is a multi-window scenario, we use the windows frame as
                // destination of the thumbnail header animation. If this is a full screen
                // window scenario, we use the whole display as the target.
                WindowState win = appToken.findMainWindow();
                Rect appRect = win != null ? win.getContentFrameLw() :
                        new Rect(0, 0, displayInfo.appWidth, displayInfo.appHeight);
                Rect insets = win != null ? win.mContentInsets : null;
                // For the new aspect-scaled transition, we want it to always show
                // above the animating opening/closing window, and we want to
                // synchronize its thumbnail surface with the surface for the
                // open/close animation (only on the way down)
                anim = mService.mAppTransition.createThumbnailAspectScaleAnimationLocked(appRect,
                        insets, thumbnailHeader, taskId, mService.mCurConfiguration.uiMode,
                        mService.mCurConfiguration.orientation);
                openingAppAnimator.thumbnailForceAboveLayer = Math.max(openingLayer, closingLayer);
                openingAppAnimator.deferThumbnailDestruction =
                        !mService.mAppTransition.isNextThumbnailTransitionScaleUp();
            } else {
                anim = mService.mAppTransition.createThumbnailScaleAnimationLocked(
                        displayInfo.appWidth, displayInfo.appHeight, transit, thumbnailHeader);
            }
            anim.restrictDuration(MAX_ANIMATION_DURATION);
            anim.scaleCurrentDuration(mService.getTransitionAnimationScaleLocked());

            openingAppAnimator.thumbnail = surfaceControl;
            openingAppAnimator.thumbnailLayer = openingLayer;
            openingAppAnimator.thumbnailAnimation = anim;
            mService.mAppTransition.getNextAppTransitionStartRect(taskId, mTmpStartRect);
        } catch (Surface.OutOfResourcesException e) {
            Slog.e(TAG, "Can't allocate thumbnail/Canvas surface w="
                    + dirty.width() + " h=" + dirty.height(), e);
            openingAppAnimator.clearThumbnail();
        }
    }

    boolean copyAnimToLayoutParamsLocked() {
        boolean doRequest = false;

        final int bulkUpdateParams = mService.mAnimator.mBulkUpdateParams;
        if ((bulkUpdateParams & SET_UPDATE_ROTATION) != 0) {
            mUpdateRotation = true;
            doRequest = true;
        }
        if ((bulkUpdateParams & SET_WALLPAPER_MAY_CHANGE) != 0) {
            mWallpaperMayChange = true;
            doRequest = true;
        }
        if ((bulkUpdateParams & SET_FORCE_HIDING_CHANGED) != 0) {
            mWallpaperForceHidingChanged = true;
            doRequest = true;
        }
        if ((bulkUpdateParams & SET_ORIENTATION_CHANGE_COMPLETE) == 0) {
            mOrientationChangeComplete = false;
        } else {
            mOrientationChangeComplete = true;
            mLastWindowFreezeSource = mService.mAnimator.mLastWindowFreezeSource;
            if (mService.mWindowsFreezingScreen != WINDOWS_FREEZING_SCREENS_NONE) {
                doRequest = true;
            }
        }
        if ((bulkUpdateParams & SET_TURN_ON_SCREEN) != 0) {
            mService.mTurnOnScreen = true;
        }
        if ((bulkUpdateParams & SET_WALLPAPER_ACTION_PENDING) != 0) {
            mWallpaperActionPending = true;
        }

        return doRequest;
    }

    void requestTraversal() {
        if (!mTraversalScheduled) {
            mTraversalScheduled = true;
            mService.mH.sendEmptyMessage(DO_TRAVERSAL);
        }
    }

    /**
     * Puts the {@param surface} into a pending list to be destroyed after the current transaction
     * has been committed.
     */
    void destroyAfterTransaction(SurfaceControl surface) {
        mPendingDestroyingSurfaces.add(surface);
    }

    /**
     * Destroys any surfaces that have been put into the pending list with
     * {@link #destroyAfterTransaction}.
     */
    void destroyPendingSurfaces() {
        for (int i = mPendingDestroyingSurfaces.size() - 1; i >= 0; i--) {
            mPendingDestroyingSurfaces.get(i).destroy();
        }
        mPendingDestroyingSurfaces.clear();
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mTraversalScheduled="); pw.println(mTraversalScheduled);
        pw.print(prefix); pw.print("mHoldScreenWindow="); pw.println(mHoldScreenWindow);
        pw.print(prefix); pw.print("mObsuringWindow="); pw.println(mObsuringWindow);
    }
}
