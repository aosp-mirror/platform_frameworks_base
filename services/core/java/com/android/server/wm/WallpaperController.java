/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.WallpaperManager.COMMAND_FREEZE;
import static android.app.WallpaperManager.COMMAND_UNFREEZE;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WALLPAPER;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.WindowContainer.AnimationFlags.PARENTS;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SCREENSHOT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WALLPAPER;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.WALLPAPER_DRAW_PENDING_TIMEOUT;

import android.annotation.Nullable;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.ArraySet;
import android.util.MathUtils;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.window.ScreenCapture;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLogImpl;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.ToBooleanFunction;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Controls wallpaper windows visibility, ordering, and so on.
 * NOTE: All methods in this class must be called with the window manager service lock held.
 */
class WallpaperController {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "WallpaperController" : TAG_WM;
    private WindowManagerService mService;
    private DisplayContent mDisplayContent;

    private final ArrayList<WallpaperWindowToken> mWallpaperTokens = new ArrayList<>();

    // If non-null, this is the currently visible window that is associated
    // with the wallpaper.
    private WindowState mWallpaperTarget = null;
    // If non-null, we are in the middle of animating from one wallpaper target
    // to another, and this is the previous wallpaper target.
    private WindowState mPrevWallpaperTarget = null;

    private float mLastWallpaperX = -1;
    private float mLastWallpaperY = -1;
    private float mLastWallpaperXStep = -1;
    private float mLastWallpaperYStep = -1;
    private float mLastWallpaperZoomOut = 0;
    private int mLastWallpaperDisplayOffsetX = Integer.MIN_VALUE;
    private int mLastWallpaperDisplayOffsetY = Integer.MIN_VALUE;
    private final float mMaxWallpaperScale;
    // Whether COMMAND_FREEZE was dispatched.
    private boolean mLastFrozen = false;

    // This is set when we are waiting for a wallpaper to tell us it is done
    // changing its scroll position.
    private WindowState mWaitingOnWallpaper;

    // The last time we had a timeout when waiting for a wallpaper.
    private long mLastWallpaperTimeoutTime;
    // We give a wallpaper up to 150ms to finish scrolling.
    private static final long WALLPAPER_TIMEOUT = 150;
    // Time we wait after a timeout before trying to wait again.
    private static final long WALLPAPER_TIMEOUT_RECOVERY = 10000;

    // We give a wallpaper up to 500ms to finish drawing before playing app transitions.
    private static final long WALLPAPER_DRAW_PENDING_TIMEOUT_DURATION = 500;
    private static final int WALLPAPER_DRAW_NORMAL = 0;
    private static final int WALLPAPER_DRAW_PENDING = 1;
    private static final int WALLPAPER_DRAW_TIMEOUT = 2;
    private int mWallpaperDrawState = WALLPAPER_DRAW_NORMAL;

    private boolean mShouldUpdateZoom;

    @Nullable private Point mLargestDisplaySize = null;

    private final FindWallpaperTargetResult mFindResults = new FindWallpaperTargetResult();

    private boolean mShouldOffsetWallpaperCenter;

    final boolean mIsLockscreenLiveWallpaperEnabled;

    private final Consumer<WindowState> mFindWallpapers = w -> {
        if (w.mAttrs.type == TYPE_WALLPAPER) {
            WallpaperWindowToken token = w.mToken.asWallpaperToken();
            if (token.canShowWhenLocked() && !mFindResults.hasTopShowWhenLockedWallpaper()) {
                mFindResults.setTopShowWhenLockedWallpaper(w);
            } else if (!token.canShowWhenLocked()
                    && !mFindResults.hasTopHideWhenLockedWallpaper()) {
                mFindResults.setTopHideWhenLockedWallpaper(w);
            }
        }
    };

    private final ToBooleanFunction<WindowState> mFindWallpaperTargetFunction = w -> {
        final boolean useShellTransition = w.mTransitionController.isShellTransitionsEnabled();
        if (!useShellTransition) {
            if (w.mActivityRecord != null && !w.mActivityRecord.isVisible()
                    && !w.mActivityRecord.isAnimating(TRANSITION | PARENTS)) {
                // If this window's app token is hidden and not animating, it is of no interest.
                if (DEBUG_WALLPAPER) Slog.v(TAG, "Skipping hidden and not animating token: " + w);
                return false;
            }
        } else {
            final ActivityRecord ar = w.mActivityRecord;
            // The animating window can still be visible on screen if it is in transition, so we
            // should check whether this window can be wallpaper target even when visibleRequested
            // is false.
            if (ar != null && !ar.isVisibleRequested() && !ar.isVisible()) {
                // An activity that is not going to remain visible shouldn't be the target.
                return false;
            }
        }
        if (DEBUG_WALLPAPER) Slog.v(TAG, "Win " + w + ": isOnScreen=" + w.isOnScreen()
                + " mDrawState=" + w.mWinAnimator.mDrawState);

        final WindowContainer animatingContainer = w.mActivityRecord != null
                ? w.mActivityRecord.getAnimatingContainer() : null;
        if (!useShellTransition && animatingContainer != null
                && animatingContainer.isAnimating(TRANSITION | PARENTS)
                && AppTransition.isKeyguardGoingAwayTransitOld(animatingContainer.mTransit)
                && (animatingContainer.mTransitFlags
                & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER) != 0) {
            // Keep the wallpaper visible when Keyguard is going away.
            mFindResults.setUseTopWallpaperAsTarget(true);
        }

        if (mService.mPolicy.isKeyguardLocked() && w.canShowWhenLocked()) {
            if (mService.mPolicy.isKeyguardOccluded() || (useShellTransition
                    ? w.inTransition() : mService.mPolicy.isKeyguardUnoccluding())) {
                // The lowest show when locked window decides whether we need to put the wallpaper
                // behind.
                mFindResults.mNeedsShowWhenLockedWallpaper = !isFullscreen(w.mAttrs)
                        || (w.mActivityRecord != null && !w.mActivityRecord.fillsParent());
            }
        }

        final boolean animationWallpaper = animatingContainer != null
                && animatingContainer.getAnimation() != null
                && animatingContainer.getAnimation().getShowWallpaper();
        final boolean hasWallpaper = w.hasWallpaper() || animationWallpaper;
        if (isRecentsTransitionTarget(w) || isBackNavigationTarget(w)) {
            if (DEBUG_WALLPAPER) Slog.v(TAG, "Found recents animation wallpaper target: " + w);
            mFindResults.setWallpaperTarget(w);
            return true;
        } else if (hasWallpaper && w.isOnScreen()
                && (mWallpaperTarget == w || w.isDrawFinishedLw())) {
            if (DEBUG_WALLPAPER) Slog.v(TAG, "Found wallpaper target: " + w);
            mFindResults.setWallpaperTarget(w);
            if (w == mWallpaperTarget && w.isAnimating(TRANSITION | PARENTS)) {
                // The current wallpaper target is animating, so we'll look behind it for
                // another possible target and figure out what is going on later.
                if (DEBUG_WALLPAPER) Slog.v(TAG,
                        "Win " + w + ": token animating, looking behind.");
            }
            mFindResults.setIsWallpaperTargetForLetterbox(w.hasWallpaperForLetterboxBackground());
            // While the keyguard is going away, both notification shade and a normal activity such
            // as a launcher can satisfy criteria for a wallpaper target. In this case, we should
            // chose the normal activity, otherwise wallpaper becomes invisible when a new animation
            // starts before the keyguard going away animation finishes.
            return w.mActivityRecord != null;
        }
        return false;
    };

    private boolean isRecentsTransitionTarget(WindowState w) {
        if (w.mTransitionController.isShellTransitionsEnabled()) {
            // Because the recents activity is invisible in background while keyguard is occluded
            // (the activity window is on screen while keyguard is locked) with recents animation,
            // the task animating by recents needs to be wallpaper target to make wallpaper visible.
            // While for unlocked case, because recents activity will be moved to top, it can be
            // the wallpaper target naturally.
            return w.mActivityRecord != null && w.mAttrs.type == TYPE_BASE_APPLICATION
                    && mDisplayContent.isKeyguardLocked()
                    && w.mTransitionController.isTransientHide(w.getTask());
        }
        // The window is either the recents activity or is in the task animating by the recents.
        final RecentsAnimationController controller = mService.getRecentsAnimationController();
        return controller != null && controller.isWallpaperVisible(w);
    }

    private boolean isBackNavigationTarget(WindowState w) {
        // The window is in animating by back navigation and set to show wallpaper.
        return mService.mAtmService.mBackNavigationController.isWallpaperVisible(w);
    }

    /**
     * @see #computeLastWallpaperZoomOut()
     */
    private Consumer<WindowState>  mComputeMaxZoomOutFunction = windowState -> {
        if (!windowState.mIsWallpaper
                && Float.compare(windowState.mWallpaperZoomOut, mLastWallpaperZoomOut) > 0) {
            mLastWallpaperZoomOut = windowState.mWallpaperZoomOut;
        }
    };

    WallpaperController(WindowManagerService service, DisplayContent displayContent) {
        mService = service;
        mDisplayContent = displayContent;
        Resources resources = service.mContext.getResources();
        mMaxWallpaperScale =
                resources.getFloat(com.android.internal.R.dimen.config_wallpaperMaxScale);
        mShouldOffsetWallpaperCenter =
                resources.getBoolean(
                        com.android.internal.R.bool.config_offsetWallpaperToCenterOfLargestDisplay);
        mIsLockscreenLiveWallpaperEnabled =
                SystemProperties.getBoolean("persist.wm.debug.lockscreen_live_wallpaper", true);
    }

    void resetLargestDisplay(Display display) {
        if (display != null && display.getType() == Display.TYPE_INTERNAL) {
            mLargestDisplaySize = null;
        }
    }

    @VisibleForTesting void setShouldOffsetWallpaperCenter(boolean shouldOffset) {
        mShouldOffsetWallpaperCenter = shouldOffset;
    }

    @Nullable private Point findLargestDisplaySize() {
        if (!mShouldOffsetWallpaperCenter) {
            return null;
        }
        Point largestDisplaySize = new Point();
        List<DisplayInfo> possibleDisplayInfo =
                mService.getPossibleDisplayInfoLocked(DEFAULT_DISPLAY);
        for (int i = 0; i < possibleDisplayInfo.size(); i++) {
            DisplayInfo displayInfo = possibleDisplayInfo.get(i);
            if (displayInfo.type == Display.TYPE_INTERNAL
                    && Math.max(displayInfo.logicalWidth, displayInfo.logicalHeight)
                    > Math.max(largestDisplaySize.x, largestDisplaySize.y)) {
                largestDisplaySize.set(displayInfo.logicalWidth,
                        displayInfo.logicalHeight);
            }
        }
        return largestDisplaySize;
    }

    WindowState getWallpaperTarget() {
        return mWallpaperTarget;
    }

    boolean isWallpaperTarget(WindowState win) {
        return win == mWallpaperTarget;
    }

    boolean isBelowWallpaperTarget(WindowState win) {
        return mWallpaperTarget != null && mWallpaperTarget.mLayer >= win.mBaseLayer;
    }

    boolean isWallpaperVisible() {
        for (int i = mWallpaperTokens.size() - 1; i >= 0; --i) {
            if (mWallpaperTokens.get(i).isVisible()) return true;
        }
        return false;
    }

    /**
     * Starts {@param a} on all wallpaper windows.
     */
    void startWallpaperAnimation(Animation a) {
        for (int curTokenNdx = mWallpaperTokens.size() - 1; curTokenNdx >= 0; curTokenNdx--) {
            final WallpaperWindowToken token = mWallpaperTokens.get(curTokenNdx);
            token.startAnimation(a);
        }
    }

    boolean isWallpaperTargetAnimating() {
        return mWallpaperTarget != null && mWallpaperTarget.isAnimating(TRANSITION | PARENTS)
                && (mWallpaperTarget.mActivityRecord == null
                        || !mWallpaperTarget.mActivityRecord.isWaitingForTransitionStart());
    }

    /**
     * Make one wallpaper visible, according to {@attr showHome}.
     * This is called during the keyguard unlocking transition
     * (see {@link KeyguardController#keyguardGoingAway(int, int)}),
     * or when a keyguard unlock is cancelled (see {@link KeyguardController})
     */
    public void showWallpaperInTransition(boolean showHome) {
        updateWallpaperWindowsTarget(mFindResults);

        if (!mFindResults.hasTopShowWhenLockedWallpaper()) {
            Slog.w(TAG, "There is no wallpaper for the lock screen");
            return;
        }
        WindowState hideWhenLocked = mFindResults.mTopWallpaper.mTopHideWhenLockedWallpaper;
        WindowState showWhenLocked = mFindResults.mTopWallpaper.mTopShowWhenLockedWallpaper;
        if (!mFindResults.hasTopHideWhenLockedWallpaper()) {
            // Shared wallpaper, ensure its visibility
            showWhenLocked.mToken.asWallpaperToken().updateWallpaperWindows(true);
        } else {
            // Separate lock and home wallpapers: show the correct wallpaper in transition
            hideWhenLocked.mToken.asWallpaperToken().updateWallpaperWindowsInTransition(showHome);
            showWhenLocked.mToken.asWallpaperToken().updateWallpaperWindowsInTransition(!showHome);
        }
    }

    void hideDeferredWallpapersIfNeededLegacy() {
        for (int i = mWallpaperTokens.size() - 1; i >= 0; i--) {
            final WallpaperWindowToken token = mWallpaperTokens.get(i);
            if (!token.isVisibleRequested()) {
                token.commitVisibility(false);
            }
        }
    }

    void hideWallpapers(final WindowState winGoingAway) {
        if (mWallpaperTarget != null
                && (mWallpaperTarget != winGoingAway || mPrevWallpaperTarget != null)) {
            return;
        }
        if (mFindResults.useTopWallpaperAsTarget) {
            // wallpaper target is going away but there has request to use top wallpaper
            // Keep wallpaper visible.
            return;
        }
        for (int i = mWallpaperTokens.size() - 1; i >= 0; i--) {
            final WallpaperWindowToken token = mWallpaperTokens.get(i);
            token.setVisibility(false);
            if (ProtoLogImpl.isEnabled(WM_DEBUG_WALLPAPER) && token.isVisible()) {
                ProtoLog.d(WM_DEBUG_WALLPAPER,
                        "Hiding wallpaper %s from %s target=%s prev=%s callers=%s",
                        token, winGoingAway, mWallpaperTarget, mPrevWallpaperTarget,
                        Debug.getCallers(5));
            }
        }
    }

    boolean updateWallpaperOffset(WindowState wallpaperWin, boolean sync) {
        // Size of the display the wallpaper is rendered on.
        final Rect lastWallpaperBounds = wallpaperWin.getParentFrame();
        // Full size of the wallpaper (usually larger than bounds above to parallax scroll when
        // swiping through Launcher pages).
        final Rect wallpaperFrame = wallpaperWin.getFrame();

        final int diffWidth = wallpaperFrame.width() - lastWallpaperBounds.width();
        final int diffHeight = wallpaperFrame.height() - lastWallpaperBounds.height();
        if ((wallpaperWin.mAttrs.flags & WindowManager.LayoutParams.FLAG_SCALED) != 0
                && Math.abs(diffWidth) > 1 && Math.abs(diffHeight) > 1) {
            Slog.d(TAG, "Skip wallpaper offset with inconsistent orientation, bounds="
                    + lastWallpaperBounds + " frame=" + wallpaperFrame);
            // With FLAG_SCALED, the requested size should at least make the frame match one of
            // side. If both sides contain differences, the client side may not have updated the
            // latest size according to the current orientation. So skip calculating the offset to
            // avoid the wallpaper not filling the screen.
            return false;
        }

        int newXOffset = 0;
        int newYOffset = 0;
        boolean rawChanged = false;
        // Set the default wallpaper x-offset to either edge of the screen (depending on RTL), to
        // match the behavior of most Launchers
        float defaultWallpaperX = wallpaperWin.isRtl() ? 1f : 0f;
        // "Wallpaper X" is the previous x-offset of the wallpaper (in a 0 to 1 scale).
        // The 0 to 1 scale is because the "length" varies depending on how many home screens you
        // have, so 0 is the left of the first home screen, and 1 is the right of the last one (for
        // LTR, and the opposite for RTL).
        float wpx = mLastWallpaperX >= 0 ? mLastWallpaperX : defaultWallpaperX;
        // "Wallpaper X step size" is how much of that 0-1 is one "page" of the home screen
        // when scrolling.
        float wpxs = mLastWallpaperXStep >= 0 ? mLastWallpaperXStep : -1.0f;
        // Difference between width of wallpaper image, and the last size of the wallpaper.
        // This is the horizontal surplus from the prior configuration.
        int availw = diffWidth;

        int displayOffset = getDisplayWidthOffset(availw, lastWallpaperBounds,
                wallpaperWin.isRtl());
        availw -= displayOffset;
        int offset = availw > 0 ? -(int)(availw * wpx + .5f) : 0;
        if (mLastWallpaperDisplayOffsetX != Integer.MIN_VALUE) {
            // if device is LTR, then offset wallpaper to the left (the wallpaper is drawn
            // always starting from the left of the screen).
            offset += mLastWallpaperDisplayOffsetX;
        } else if (!wallpaperWin.isRtl()) {
            // In RTL the offset is calculated so that the wallpaper ends up right aligned (see
            // offset above).
            offset -= displayOffset;
        }
        newXOffset = offset;

        if (wallpaperWin.mWallpaperX != wpx || wallpaperWin.mWallpaperXStep != wpxs) {
            wallpaperWin.mWallpaperX = wpx;
            wallpaperWin.mWallpaperXStep = wpxs;
            rawChanged = true;
        }

        float wpy = mLastWallpaperY >= 0 ? mLastWallpaperY : 0.5f;
        float wpys = mLastWallpaperYStep >= 0 ? mLastWallpaperYStep : -1.0f;
        offset = diffHeight > 0 ? -(int) (diffHeight * wpy + .5f) : 0;
        if (mLastWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
            offset += mLastWallpaperDisplayOffsetY;
        }
        newYOffset = offset;

        if (wallpaperWin.mWallpaperY != wpy || wallpaperWin.mWallpaperYStep != wpys) {
            wallpaperWin.mWallpaperY = wpy;
            wallpaperWin.mWallpaperYStep = wpys;
            rawChanged = true;
        }

        if (Float.compare(wallpaperWin.mWallpaperZoomOut, mLastWallpaperZoomOut) != 0) {
            wallpaperWin.mWallpaperZoomOut = mLastWallpaperZoomOut;
            rawChanged = true;
        }

        boolean changed = wallpaperWin.setWallpaperOffset(newXOffset, newYOffset,
                wallpaperWin.mShouldScaleWallpaper
                        ? zoomOutToScale(wallpaperWin.mWallpaperZoomOut) : 1);

        if (rawChanged && (wallpaperWin.mAttrs.privateFlags &
                WindowManager.LayoutParams.PRIVATE_FLAG_WANTS_OFFSET_NOTIFICATIONS) != 0) {
            try {
                if (DEBUG_WALLPAPER) Slog.v(TAG, "Report new wp offset "
                        + wallpaperWin + " x=" + wallpaperWin.mWallpaperX
                        + " y=" + wallpaperWin.mWallpaperY
                        + " zoom=" + wallpaperWin.mWallpaperZoomOut);
                if (sync) {
                    mWaitingOnWallpaper = wallpaperWin;
                }
                wallpaperWin.mClient.dispatchWallpaperOffsets(
                        wallpaperWin.mWallpaperX, wallpaperWin.mWallpaperY,
                        wallpaperWin.mWallpaperXStep, wallpaperWin.mWallpaperYStep,
                        wallpaperWin.mWallpaperZoomOut, sync);

                if (sync) {
                    if (mWaitingOnWallpaper != null) {
                        long start = SystemClock.uptimeMillis();
                        if ((mLastWallpaperTimeoutTime + WALLPAPER_TIMEOUT_RECOVERY)
                                < start) {
                            try {
                                if (DEBUG_WALLPAPER) Slog.v(TAG,
                                        "Waiting for offset complete...");
                                mService.mGlobalLock.wait(WALLPAPER_TIMEOUT);
                            } catch (InterruptedException e) {
                            }
                            if (DEBUG_WALLPAPER) Slog.v(TAG, "Offset complete!");
                            if ((start + WALLPAPER_TIMEOUT) < SystemClock.uptimeMillis()) {
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

    /**
     * Get an extra offset if needed ({@link #mShouldOffsetWallpaperCenter} = true, typically on
     * multiple display devices) so that the wallpaper in a smaller display ends up centered at the
     * same position as in the largest display of the device.
     *
     * Note that the wallpaper has already been cropped when set by the user, so these calculations
     * apply to the image size for the display the wallpaper was set for.
     *
     * @param availWidth   width available for the wallpaper offset in the current display
     * @param displayFrame size of the "display" (parent frame)
     * @param isRtl        whether we're in an RTL configuration
     * @return an offset to apply to the width, or 0 if the current configuration doesn't require
     * any adjustment (either @link #mShouldOffsetWallpaperCenter} is false or we're on the largest
     * display).
     */
    private int getDisplayWidthOffset(int availWidth, Rect displayFrame, boolean isRtl) {
        if (!mShouldOffsetWallpaperCenter) {
            return 0;
        }
        if (mLargestDisplaySize == null) {
            mLargestDisplaySize = findLargestDisplaySize();
        }
        if (mLargestDisplaySize == null) {
            return 0;
        }
        // Page width is the width of a Launcher "page", for pagination when swiping right.
        int pageWidth = displayFrame.width();
        // Only need offset if the current size is different from the largest display, and we're
        // in a portrait configuration
        if (mLargestDisplaySize.x != pageWidth && displayFrame.width() < displayFrame.height()) {
            // The wallpaper will be scaled to fit the height of the wallpaper, so if the height
            // of the displays are different, we need to account for that scaling when calculating
            // the offset to the center
            float sizeRatio = (float) displayFrame.height() / mLargestDisplaySize.y;
            // Scale the width of the largest display to match the scale of the wallpaper size in
            // the current display
            int adjustedLargestWidth = Math.round(mLargestDisplaySize.x * sizeRatio);
            // Finally, find the difference between the centers, taking into account that the
            // size of the wallpaper frame could be smaller than the screen
            return isRtl
                    ? adjustedLargestWidth - (adjustedLargestWidth + pageWidth) / 2
                    : Math.min(adjustedLargestWidth - pageWidth, availWidth) / 2;
        }
        return 0;
    }

    void setWindowWallpaperPosition(
            WindowState window, float x, float y, float xStep, float yStep) {
        if (window.mWallpaperX != x || window.mWallpaperY != y)  {
            window.mWallpaperX = x;
            window.mWallpaperY = y;
            window.mWallpaperXStep = xStep;
            window.mWallpaperYStep = yStep;
            updateWallpaperOffsetLocked(window, true);
        }
    }

    void setWallpaperZoomOut(WindowState window, float zoom) {
        if (Float.compare(window.mWallpaperZoomOut, zoom) != 0) {
            window.mWallpaperZoomOut = zoom;
            mShouldUpdateZoom = true;
            updateWallpaperOffsetLocked(window, false);
        }
    }

    void setShouldZoomOutWallpaper(WindowState window, boolean shouldZoom) {
        if (shouldZoom != window.mShouldScaleWallpaper) {
            window.mShouldScaleWallpaper = shouldZoom;
            updateWallpaperOffsetLocked(window, false);
        }
    }

    void setWindowWallpaperDisplayOffset(WindowState window, int x, int y) {
        if (window.mWallpaperDisplayOffsetX != x || window.mWallpaperDisplayOffsetY != y)  {
            window.mWallpaperDisplayOffsetX = x;
            window.mWallpaperDisplayOffsetY = y;
            updateWallpaperOffsetLocked(window, true);
        }
    }

    Bundle sendWindowWallpaperCommand(
            WindowState window, String action, int x, int y, int z, Bundle extras, boolean sync) {
        if (window == mWallpaperTarget || window == mPrevWallpaperTarget) {
            sendWindowWallpaperCommand(action, x, y, z, extras, sync);
        }

        return null;
    }

    private void sendWindowWallpaperCommand(
                String action, int x, int y, int z, Bundle extras, boolean sync) {
        boolean doWait = sync;
        for (int curTokenNdx = mWallpaperTokens.size() - 1; curTokenNdx >= 0; curTokenNdx--) {
            final WallpaperWindowToken token = mWallpaperTokens.get(curTokenNdx);
            token.sendWindowWallpaperCommand(action, x, y, z, extras, sync);
        }

        if (doWait) {
            // TODO: Need to wait for result.
        }
    }

    private void updateWallpaperOffsetLocked(WindowState changingTarget, boolean sync) {
        WindowState target = mWallpaperTarget;
        if (target == null && changingTarget.mToken.isVisible()
                && changingTarget.mTransitionController.inTransition()) {
            // If the wallpaper target was cleared during transition, still allows the visible
            // window which may have been requested to be invisible to update the offset, e.g.
            // zoom effect from home.
            target = changingTarget;
        }
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
            computeLastWallpaperZoomOut();
            if (target.mWallpaperDisplayOffsetX != Integer.MIN_VALUE) {
                mLastWallpaperDisplayOffsetX = target.mWallpaperDisplayOffsetX;
            } else if (changingTarget.mWallpaperDisplayOffsetX != Integer.MIN_VALUE) {
                mLastWallpaperDisplayOffsetX = changingTarget.mWallpaperDisplayOffsetX;
            }
            if (target.mWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
                mLastWallpaperDisplayOffsetY = target.mWallpaperDisplayOffsetY;
            } else if (changingTarget.mWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
                mLastWallpaperDisplayOffsetY = changingTarget.mWallpaperDisplayOffsetY;
            }
            if (target.mWallpaperXStep >= 0) {
                mLastWallpaperXStep = target.mWallpaperXStep;
            } else if (changingTarget.mWallpaperXStep >= 0) {
                mLastWallpaperXStep = changingTarget.mWallpaperXStep;
            }
            if (target.mWallpaperYStep >= 0) {
                mLastWallpaperYStep = target.mWallpaperYStep;
            } else if (changingTarget.mWallpaperYStep >= 0) {
                mLastWallpaperYStep = changingTarget.mWallpaperYStep;
            }
        }

        for (int curTokenNdx = mWallpaperTokens.size() - 1; curTokenNdx >= 0; curTokenNdx--) {
            mWallpaperTokens.get(curTokenNdx).updateWallpaperOffset(sync);
        }
    }

    void clearLastWallpaperTimeoutTime() {
        mLastWallpaperTimeoutTime = 0;
    }

    void wallpaperCommandComplete(IBinder window) {
        if (mWaitingOnWallpaper != null &&
                mWaitingOnWallpaper.mClient.asBinder() == window) {
            mWaitingOnWallpaper = null;
            mService.mGlobalLock.notifyAll();
        }
    }

    void wallpaperOffsetsComplete(IBinder window) {
        if (mWaitingOnWallpaper != null &&
                mWaitingOnWallpaper.mClient.asBinder() == window) {
            mWaitingOnWallpaper = null;
            mService.mGlobalLock.notifyAll();
        }
    }

    private void findWallpaperTarget() {
        mFindResults.reset();
        if (mService.mAtmService.mSupportsFreeformWindowManagement
                && mDisplayContent.getDefaultTaskDisplayArea()
                .isRootTaskVisible(WINDOWING_MODE_FREEFORM)) {
            // In freeform mode we set the wallpaper as its own target, so we don't need an
            // additional window to make it visible.
            mFindResults.setUseTopWallpaperAsTarget(true);
        }

        mDisplayContent.forAllWindows(mFindWallpapers, true /* traverseTopToBottom */);
        mDisplayContent.forAllWindows(mFindWallpaperTargetFunction, true /* traverseTopToBottom */);
        if (mFindResults.mNeedsShowWhenLockedWallpaper) {
            // Keep wallpaper visible if the show-when-locked activities doesn't fill screen.
            mFindResults.setUseTopWallpaperAsTarget(true);
        }

        if (mFindResults.wallpaperTarget == null && mFindResults.useTopWallpaperAsTarget) {
            mFindResults.setWallpaperTarget(
                    mFindResults.getTopWallpaper(mDisplayContent.isKeyguardLocked()));
        }
    }

    List<WindowState> getAllTopWallpapers() {
        ArrayList<WindowState> wallpapers = new ArrayList<>(2);
        if (mFindResults.hasTopShowWhenLockedWallpaper()) {
            wallpapers.add(mFindResults.mTopWallpaper.mTopShowWhenLockedWallpaper);
        }
        if (mFindResults.hasTopHideWhenLockedWallpaper()) {
            wallpapers.add(mFindResults.mTopWallpaper.mTopHideWhenLockedWallpaper);
        }
        return wallpapers;
    }

    private boolean isFullscreen(WindowManager.LayoutParams attrs) {
        return attrs.x == 0 && attrs.y == 0
                && attrs.width == MATCH_PARENT && attrs.height == MATCH_PARENT;
    }

    /** Updates the target wallpaper if needed and returns true if an update happened. */
    private void updateWallpaperWindowsTarget(FindWallpaperTargetResult result) {

        WindowState wallpaperTarget = result.wallpaperTarget;

        if (mWallpaperTarget == wallpaperTarget
                || (mPrevWallpaperTarget != null && mPrevWallpaperTarget == wallpaperTarget)) {

            if (mPrevWallpaperTarget == null) {
                return;
            }

            // Is it time to stop animating?
            if (!mPrevWallpaperTarget.isAnimatingLw()) {
                ProtoLog.v(WM_DEBUG_WALLPAPER, "No longer animating wallpaper targets!");
                mPrevWallpaperTarget = null;
                mWallpaperTarget = wallpaperTarget;
            }
            return;
        }

        ProtoLog.v(WM_DEBUG_WALLPAPER, "New wallpaper target: %s prevTarget: %s caller=%s",
                wallpaperTarget, mWallpaperTarget, Debug.getCallers(5));

        mPrevWallpaperTarget = null;

        final WindowState prevWallpaperTarget = mWallpaperTarget;
        mWallpaperTarget = wallpaperTarget;

        if (prevWallpaperTarget == null && wallpaperTarget != null) {
            updateWallpaperOffsetLocked(mWallpaperTarget, false);
        }
        if (wallpaperTarget == null || prevWallpaperTarget == null) {
            return;
        }

        // Now what is happening...  if the current and new targets are animating,
        // then we are in our super special mode!
        boolean oldAnim = prevWallpaperTarget.isAnimatingLw();
        boolean foundAnim = wallpaperTarget.isAnimatingLw();
        ProtoLog.v(WM_DEBUG_WALLPAPER, "New animation: %s old animation: %s",
                foundAnim, oldAnim);

        if (!foundAnim || !oldAnim) {
            return;
        }

        if (mDisplayContent.getWindow(w -> w == prevWallpaperTarget) == null) {
            return;
        }

        final boolean newTargetHidden = wallpaperTarget.mActivityRecord != null
                && !wallpaperTarget.mActivityRecord.isVisibleRequested();
        final boolean oldTargetHidden = prevWallpaperTarget.mActivityRecord != null
                && !prevWallpaperTarget.mActivityRecord.isVisibleRequested();

        ProtoLog.v(WM_DEBUG_WALLPAPER, "Animating wallpapers: "
                + "old: %s hidden=%b new: %s hidden=%b",
                prevWallpaperTarget, oldTargetHidden, wallpaperTarget, newTargetHidden);

        mPrevWallpaperTarget = prevWallpaperTarget;

        if (newTargetHidden && !oldTargetHidden) {
            ProtoLog.v(WM_DEBUG_WALLPAPER, "Old wallpaper still the target.");
            // Use the old target if new target is hidden but old target
            // is not. If they're both hidden, still use the new target.
            mWallpaperTarget = prevWallpaperTarget;
        } else if (newTargetHidden == oldTargetHidden
                && !mDisplayContent.mOpeningApps.contains(wallpaperTarget.mActivityRecord)
                && (mDisplayContent.mOpeningApps.contains(prevWallpaperTarget.mActivityRecord)
                || mDisplayContent.mClosingApps.contains(prevWallpaperTarget.mActivityRecord))) {
            // If they're both hidden (or both not hidden), prefer the one that's currently in
            // opening or closing app list, this allows transition selection logic to better
            // determine the wallpaper status of opening/closing apps.
            mWallpaperTarget = prevWallpaperTarget;
        }

        result.setWallpaperTarget(wallpaperTarget);
    }

    public void updateWallpaperTokens(boolean keyguardLocked) {
        if (DEBUG_WALLPAPER) {
            Slog.v(TAG, "Wallpaper vis: target " + mWallpaperTarget + " prev="
                    + mPrevWallpaperTarget);
        }
        updateWallpaperTokens(mWallpaperTarget != null || mPrevWallpaperTarget != null,
                keyguardLocked);
    }

    /**
     * Change the visibility of the top wallpaper to {@param visibility} and hide all the others.
     */
    private void updateWallpaperTokens(boolean visibility, boolean keyguardLocked) {
        WindowState topWallpaper = mFindResults.getTopWallpaper(keyguardLocked);
        WallpaperWindowToken topWallpaperToken =
                topWallpaper == null ? null : topWallpaper.mToken.asWallpaperToken();
        for (int curTokenNdx = mWallpaperTokens.size() - 1; curTokenNdx >= 0; curTokenNdx--) {
            final WallpaperWindowToken token = mWallpaperTokens.get(curTokenNdx);
            token.updateWallpaperWindows(visibility && (token == topWallpaperToken));
        }
    }

    void adjustWallpaperWindows() {
        mDisplayContent.mWallpaperMayChange = false;

        // First find top-most window that has asked to be on top of the wallpaper;
        // all wallpapers go behind it.
        findWallpaperTarget();
        updateWallpaperWindowsTarget(mFindResults);

        // The window is visible to the compositor...but is it visible to the user?
        // That is what the wallpaper cares about.
        final boolean visible = mWallpaperTarget != null;
        if (DEBUG_WALLPAPER) {
            Slog.v(TAG, "Wallpaper visibility: " + visible + " at display "
                    + mDisplayContent.getDisplayId());
        }

        if (visible) {
            if (mWallpaperTarget.mWallpaperX >= 0) {
                mLastWallpaperX = mWallpaperTarget.mWallpaperX;
                mLastWallpaperXStep = mWallpaperTarget.mWallpaperXStep;
            }
            computeLastWallpaperZoomOut();
            if (mWallpaperTarget.mWallpaperY >= 0) {
                mLastWallpaperY = mWallpaperTarget.mWallpaperY;
                mLastWallpaperYStep = mWallpaperTarget.mWallpaperYStep;
            }
            if (mWallpaperTarget.mWallpaperDisplayOffsetX != Integer.MIN_VALUE) {
                mLastWallpaperDisplayOffsetX = mWallpaperTarget.mWallpaperDisplayOffsetX;
            }
            if (mWallpaperTarget.mWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
                mLastWallpaperDisplayOffsetY = mWallpaperTarget.mWallpaperDisplayOffsetY;
            }
        }

        if (!mDisplayContent.isKeyguardGoingAway() || !mIsLockscreenLiveWallpaperEnabled) {
            // When keyguard goes away, KeyguardController handles the visibility
            updateWallpaperTokens(visible, mDisplayContent.isKeyguardLocked());
        }

        if (DEBUG_WALLPAPER) {
            Slog.v(TAG, "adjustWallpaperWindows: wallpaper visibility " + visible
                    + ", lock visibility " + mDisplayContent.isKeyguardLocked());
        }

        if (visible && mLastFrozen != mFindResults.isWallpaperTargetForLetterbox) {
            mLastFrozen = mFindResults.isWallpaperTargetForLetterbox;
            sendWindowWallpaperCommand(
                    mFindResults.isWallpaperTargetForLetterbox ? COMMAND_FREEZE : COMMAND_UNFREEZE,
                    /* x= */ 0, /* y= */ 0, /* z= */ 0, /* extras= */ null, /* sync= */ false);
        }

        ProtoLog.d(WM_DEBUG_WALLPAPER, "New wallpaper: target=%s prev=%s",
                mWallpaperTarget, mPrevWallpaperTarget);
    }

    boolean processWallpaperDrawPendingTimeout() {
        if (mWallpaperDrawState == WALLPAPER_DRAW_PENDING) {
            mWallpaperDrawState = WALLPAPER_DRAW_TIMEOUT;
            if (DEBUG_WALLPAPER) {
                Slog.v(TAG, "*** WALLPAPER DRAW TIMEOUT");
            }

            // If there was a pending recents animation, start the animation anyways (it's better
            // to not see the wallpaper than for the animation to not start)
            if (mService.getRecentsAnimationController() != null) {
                mService.getRecentsAnimationController().startAnimation();
            }

            // If there was a pending back navigation animation that would show wallpaper, start
            // the animation due to it was skipped in previous surface placement.
            mService.mAtmService.mBackNavigationController.startAnimation();
            return true;
        }
        return false;
    }

    boolean wallpaperTransitionReady() {
        boolean transitionReady = true;
        boolean wallpaperReady = true;
        for (int curTokenIndex = mWallpaperTokens.size() - 1;
                curTokenIndex >= 0 && wallpaperReady; curTokenIndex--) {
            final WallpaperWindowToken token = mWallpaperTokens.get(curTokenIndex);
            if (token.hasVisibleNotDrawnWallpaper()) {
                // We've told this wallpaper to be visible, but it is not drawn yet
                wallpaperReady = false;
                if (mWallpaperDrawState != WALLPAPER_DRAW_TIMEOUT) {
                    // wait for this wallpaper until it is drawn or timeout
                    transitionReady = false;
                }
                if (mWallpaperDrawState == WALLPAPER_DRAW_NORMAL) {
                    mWallpaperDrawState = WALLPAPER_DRAW_PENDING;
                    mService.mH.removeMessages(WALLPAPER_DRAW_PENDING_TIMEOUT, this);
                    mService.mH.sendMessageDelayed(
                                mService.mH.obtainMessage(WALLPAPER_DRAW_PENDING_TIMEOUT, this),
                                WALLPAPER_DRAW_PENDING_TIMEOUT_DURATION);

                }
                if (DEBUG_WALLPAPER) {
                    Slog.v(TAG,
                            "Wallpaper should be visible but has not been drawn yet. "
                                    + "mWallpaperDrawState=" + mWallpaperDrawState);
                }
                break;
            }
        }
        if (wallpaperReady) {
            mWallpaperDrawState = WALLPAPER_DRAW_NORMAL;
            mService.mH.removeMessages(WALLPAPER_DRAW_PENDING_TIMEOUT, this);
        }

        return transitionReady;
    }

    /**
     * Adjusts the wallpaper windows if the input display has a pending wallpaper layout or one of
     * the opening apps should be a wallpaper target.
     */
    void adjustWallpaperWindowsForAppTransitionIfNeeded(ArraySet<ActivityRecord> openingApps) {
        boolean adjust = false;
        if ((mDisplayContent.pendingLayoutChanges & FINISH_LAYOUT_REDO_WALLPAPER) != 0) {
            adjust = true;
        } else {
            for (int i = openingApps.size() - 1; i >= 0; --i) {
                final ActivityRecord activity = openingApps.valueAt(i);
                if (activity.windowsCanBeWallpaperTarget()) {
                    adjust = true;
                    break;
                }
            }
        }

        if (adjust) {
            adjustWallpaperWindows();
        }
    }

    void addWallpaperToken(WallpaperWindowToken token) {
        mWallpaperTokens.add(token);
    }

    void removeWallpaperToken(WallpaperWindowToken token) {
        mWallpaperTokens.remove(token);
    }

    @VisibleForTesting
    boolean canScreenshotWallpaper() {
        return canScreenshotWallpaper(getTopVisibleWallpaper());
    }

    private boolean canScreenshotWallpaper(WindowState wallpaperWindowState) {
        if (!mService.mPolicy.isScreenOn()) {
            if (DEBUG_SCREENSHOT) {
                Slog.i(TAG_WM, "Attempted to take screenshot while display was off.");
            }
            return false;
        }

        if (wallpaperWindowState == null) {
            if (DEBUG_SCREENSHOT) {
                Slog.i(TAG_WM, "No visible wallpaper to screenshot");
            }
            return false;
        }
        return true;
    }

    /**
     * Take a screenshot of the wallpaper if it's visible.
     *
     * @return Bitmap of the wallpaper
     */
    Bitmap screenshotWallpaperLocked() {
        final WindowState wallpaperWindowState = getTopVisibleWallpaper();
        if (!canScreenshotWallpaper(wallpaperWindowState)) {
            return null;
        }

        final Rect bounds = wallpaperWindowState.getBounds();
        bounds.offsetTo(0, 0);

        ScreenCapture.ScreenshotHardwareBuffer wallpaperBuffer = ScreenCapture.captureLayers(
                wallpaperWindowState.getSurfaceControl(), bounds, 1 /* frameScale */);

        if (wallpaperBuffer == null) {
            Slog.w(TAG_WM, "Failed to screenshot wallpaper");
            return null;
        }
        return Bitmap.wrapHardwareBuffer(
                wallpaperBuffer.getHardwareBuffer(), wallpaperBuffer.getColorSpace());
    }

    /**
     * Mirrors the visible wallpaper if it's available.
     *
     * @return A SurfaceControl for the parent of the mirrored wallpaper.
     */
    SurfaceControl mirrorWallpaperSurface() {
        final WindowState wallpaperWindowState = getTopVisibleWallpaper();
        return wallpaperWindowState != null
                ? SurfaceControl.mirrorSurface(wallpaperWindowState.getSurfaceControl())
                : null;
    }

    WindowState getTopVisibleWallpaper() {
        for (int curTokenNdx = mWallpaperTokens.size() - 1; curTokenNdx >= 0; curTokenNdx--) {
            final WallpaperWindowToken token = mWallpaperTokens.get(curTokenNdx);
            for (int i = token.getChildCount() - 1; i >= 0; i--) {
                final WindowState w = token.getChildAt(i);
                if (w.mWinAnimator.getShown() && w.mWinAnimator.mLastAlpha > 0f) {
                    return w;
                }
            }
        }
        return null;
    }

    /**
     * Each window can request a zoom, example:
     * - User is in overview, zoomed out.
     * - User also pulls down the shade.
     *
     * This means that we always have to choose the largest zoom out that we have, otherwise
     * we'll have conflicts and break the "depth system" mental model.
     */
    private void computeLastWallpaperZoomOut() {
        if (mShouldUpdateZoom) {
            mLastWallpaperZoomOut = 0;
            mDisplayContent.forAllWindows(mComputeMaxZoomOutFunction, true);
            mShouldUpdateZoom = false;
        }
    }

    private float zoomOutToScale(float zoom) {
        return MathUtils.lerp(1, mMaxWallpaperScale, 1 - zoom);
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("displayId="); pw.println(mDisplayContent.getDisplayId());
        pw.print(prefix); pw.print("mWallpaperTarget="); pw.println(mWallpaperTarget);
        if (mPrevWallpaperTarget != null) {
            pw.print(prefix); pw.print("mPrevWallpaperTarget="); pw.println(mPrevWallpaperTarget);
        }
        pw.print(prefix); pw.print("mLastWallpaperX="); pw.print(mLastWallpaperX);
        pw.print(" mLastWallpaperY="); pw.println(mLastWallpaperY);
        if (mLastWallpaperDisplayOffsetX != Integer.MIN_VALUE
                || mLastWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
            pw.print(prefix);
            pw.print("mLastWallpaperDisplayOffsetX="); pw.print(mLastWallpaperDisplayOffsetX);
            pw.print(" mLastWallpaperDisplayOffsetY="); pw.println(mLastWallpaperDisplayOffsetY);
        }
    }

    /** Helper class for storing the results of a wallpaper target find operation. */
    final private static class FindWallpaperTargetResult {

        static final class TopWallpaper {
            // A wp that can be visible on home screen only
            WindowState mTopHideWhenLockedWallpaper = null;
            // A wallpaper that has permission to be visible on lock screen (lock or shared wp)
            WindowState mTopShowWhenLockedWallpaper = null;

            void reset() {
                mTopHideWhenLockedWallpaper = null;
                mTopShowWhenLockedWallpaper = null;
            }
        }

        TopWallpaper mTopWallpaper = new TopWallpaper();
        boolean mNeedsShowWhenLockedWallpaper;
        boolean useTopWallpaperAsTarget = false;
        WindowState wallpaperTarget = null;
        boolean isWallpaperTargetForLetterbox = false;

        void setTopHideWhenLockedWallpaper(WindowState win) {
            if (DEBUG_WALLPAPER) {
                Slog.v(TAG, "setTopHideWhenLockedWallpaper " + win);
            }
            mTopWallpaper.mTopHideWhenLockedWallpaper = win;
        }

        void setTopShowWhenLockedWallpaper(WindowState win) {
            if (DEBUG_WALLPAPER) {
                Slog.v(TAG, "setTopShowWhenLockedWallpaper " + win);
            }
            mTopWallpaper.mTopShowWhenLockedWallpaper = win;
        }

        boolean hasTopHideWhenLockedWallpaper() {
            return mTopWallpaper.mTopHideWhenLockedWallpaper != null;
        }

        boolean hasTopShowWhenLockedWallpaper() {
            return mTopWallpaper.mTopShowWhenLockedWallpaper != null;
        }

        WindowState getTopWallpaper(boolean isKeyguardLocked) {
            if (!isKeyguardLocked && hasTopHideWhenLockedWallpaper()) {
                return mTopWallpaper.mTopHideWhenLockedWallpaper;
            } else {
                return mTopWallpaper.mTopShowWhenLockedWallpaper;
            }
        }

        void setWallpaperTarget(WindowState win) {
            wallpaperTarget = win;
        }

        void setUseTopWallpaperAsTarget(boolean topWallpaperAsTarget) {
            useTopWallpaperAsTarget = topWallpaperAsTarget;
        }

        void setIsWallpaperTargetForLetterbox(boolean isWallpaperTargetForLetterbox) {
            this.isWallpaperTargetForLetterbox = isWallpaperTargetForLetterbox;
        }

        void reset() {
            mTopWallpaper.reset();
            mNeedsShowWhenLockedWallpaper = false;
            wallpaperTarget = null;
            useTopWallpaperAsTarget = false;
            isWallpaperTargetForLetterbox = false;
        }
    }
}
