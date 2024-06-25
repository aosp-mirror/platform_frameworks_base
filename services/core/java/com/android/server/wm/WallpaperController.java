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

import static android.app.WallpaperManager.COMMAND_DISPLAY_SWITCH;
import static android.app.WallpaperManager.COMMAND_FREEZE;
import static android.app.WallpaperManager.COMMAND_UNFREEZE;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
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
import static com.android.window.flags.Flags.multiCrop;

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
import android.util.ArraySet;
import android.util.MathUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.ScreenCapture;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.ToBooleanFunction;
import com.android.server.wallpaper.WallpaperCropper.WallpaperCropUtils;
import com.android.window.flags.Flags;

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
    private WallpaperCropUtils mWallpaperCropUtils = null;
    private DisplayContent mDisplayContent;

    // Larger index has higher z-order.
    private final ArrayList<WallpaperWindowToken> mWallpaperTokens = new ArrayList<>();

    // If non-null, this is the currently visible window that is associated
    // with the wallpaper.
    private WindowState mWallpaperTarget = null;
    // If non-null, we are in the middle of animating from one wallpaper target
    // to another, and this is the previous wallpaper target.
    private WindowState mPrevWallpaperTarget = null;

    private float mLastWallpaperZoomOut = 0;

    // Whether COMMAND_FREEZE was dispatched.
    private boolean mLastFrozen = false;

    private float mMinWallpaperScale;
    private float mMaxWallpaperScale;

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

    @Nullable private Point mLargestDisplaySize = null;

    private final FindWallpaperTargetResult mFindResults = new FindWallpaperTargetResult();

    private boolean mShouldOffsetWallpaperCenter;

    /**
     * Whether the wallpaper has been notified about a physical display switch event is started.
     */
    private volatile boolean mIsWallpaperNotifiedOnDisplaySwitch;

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

        if (mService.mPolicy.isKeyguardLocked()) {
            if (w.canShowWhenLocked()) {
                if (mService.mPolicy.isKeyguardOccluded() || (useShellTransition
                        ? w.inTransition() : mService.mPolicy.isKeyguardUnoccluding())) {
                    // The lowest show-when-locked window decides whether to show wallpaper.
                    mFindResults.mNeedsShowWhenLockedWallpaper = !isFullscreen(w.mAttrs)
                            || (w.mActivityRecord != null && !w.mActivityRecord.fillsParent());
                }
            } else if (w.hasWallpaper() && mService.mPolicy.isKeyguardHostWindow(w.mAttrs)
                    && w.mTransitionController.hasTransientLaunch(mDisplayContent)) {
                // If we have no candidates at all, notification shade is allowed to be the target
                // of last resort even if it has not been made visible yet.
                if (DEBUG_WALLPAPER) Slog.v(TAG, "Found keyguard as wallpaper target: " + w);
                mFindResults.setWallpaperTarget(w);
                return false;
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
            mFindResults.setIsWallpaperTargetForLetterbox(w.hasWallpaperForLetterboxBackground());
            if (w == mWallpaperTarget && w.isAnimating(TRANSITION | PARENTS)) {
                // The current wallpaper target is animating, so we'll look behind it for
                // another possible target and figure out what is going on later.
                if (DEBUG_WALLPAPER) Slog.v(TAG,
                        "Win " + w + ": token animating, looking behind.");
            }
            // While the keyguard is going away, both notification shade and a normal activity such
            // as a launcher can satisfy criteria for a wallpaper target. In this case, we should
            // chose the normal activity, otherwise wallpaper becomes invisible when a new animation
            // starts before the keyguard going away animation finishes.
            if (w.mActivityRecord == null && mDisplayContent.isKeyguardGoingAway()) {
                return false;
            }
            return true;
        }
        return false;
    };

    private boolean isRecentsTransitionTarget(WindowState w) {
        if (w.mTransitionController.isShellTransitionsEnabled()) {
            return false;
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
        mMinWallpaperScale =
                resources.getFloat(com.android.internal.R.dimen.config_wallpaperMinScale);
        mMaxWallpaperScale = resources.getFloat(R.dimen.config_wallpaperMaxScale);
        mShouldOffsetWallpaperCenter = resources.getBoolean(
                com.android.internal.R.bool.config_offsetWallpaperToCenterOfLargestDisplay);
    }

    void resetLargestDisplay(Display display) {
        if (display != null && display.getType() == Display.TYPE_INTERNAL) {
            mLargestDisplaySize = null;
        }
    }

    @VisibleForTesting
    void setMinWallpaperScale(float minScale) {
        mMinWallpaperScale = minScale;
    }

    @VisibleForTesting
    void setMaxWallpaperScale(float maxScale) {
        mMaxWallpaperScale = maxScale;
    }

    @VisibleForTesting void setShouldOffsetWallpaperCenter(boolean shouldOffset) {
        mShouldOffsetWallpaperCenter = shouldOffset;
    }

    @Nullable private Point findLargestDisplaySize() {
        if (!mShouldOffsetWallpaperCenter || multiCrop()) {
            return null;
        }
        Point largestDisplaySize = new Point();
        float largestWidth = 0;
        List<DisplayInfo> possibleDisplayInfo =
                mService.getPossibleDisplayInfoLocked(DEFAULT_DISPLAY);
        for (int i = 0; i < possibleDisplayInfo.size(); i++) {
            DisplayInfo displayInfo = possibleDisplayInfo.get(i);
            float width = (float) displayInfo.logicalWidth / displayInfo.physicalXDpi;
            if (displayInfo.type == Display.TYPE_INTERNAL && width > largestWidth) {
                largestWidth = width;
                largestDisplaySize.set(displayInfo.logicalWidth,
                        displayInfo.logicalHeight);
            }
        }
        return largestDisplaySize;
    }

    void setWallpaperCropUtils(WallpaperCropUtils wallpaperCropUtils) {
        mWallpaperCropUtils = wallpaperCropUtils;
    }

    WindowState getWallpaperTarget() {
        return mWallpaperTarget;
    }

    WindowState getPrevWallpaperTarget() {
        return mPrevWallpaperTarget;
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

    boolean isWallpaperTargetAnimating() {
        return mWallpaperTarget != null && mWallpaperTarget.isAnimating(TRANSITION | PARENTS)
                && (mWallpaperTarget.mActivityRecord == null
                        || !mWallpaperTarget.mActivityRecord.isWaitingForTransitionStart());
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
            if (token.isVisible()) {
                ProtoLog.d(WM_DEBUG_WALLPAPER,
                        "Hiding wallpaper %s from %s target=%s prev=%s callers=%s",
                        token, winGoingAway, mWallpaperTarget, mPrevWallpaperTarget,
                        Debug.getCallers(5));
            }
            token.setVisibility(false);
        }
    }

    boolean updateWallpaperOffset(WindowState wallpaperWin, boolean sync) {
        // Size of the display the wallpaper is rendered on.
        final Rect lastWallpaperBounds = wallpaperWin.getParentFrame();
        int screenWidth = lastWallpaperBounds.width();
        int screenHeight = lastWallpaperBounds.height();
        float screenRatio = (float) screenWidth / screenHeight;
        Point screenSize = new Point(screenWidth, screenHeight);

        WallpaperWindowToken token = wallpaperWin.mToken.asWallpaperToken();

        /*
         * TODO(b/270726737) adapt comments once flag gets removed and multiCrop is always true
         * Size of the wallpaper. May have more width/height ratio than the screen for parallax.
         *
         * If multiCrop is true, we use a map, cropHints, defining which sub-area of the wallpaper
         * to show for a given screen orientation. In this case, wallpaperFrame represents the
         * sub-area of WallpaperWin to show for the current screen size.
         *
         * If multiCrop is false, don't show a custom sub-area of the wallpaper. Just show the
         * whole wallpaperWin if possible, and center and zoom if necessary.
         */
        final Rect wallpaperFrame;

        /*
         * The values cropZoom, cropOffsetX and cropOffsetY are only used if multiCrop is true.
         * Zoom and offsets to be applied in order to show wallpaperFrame on screen.
         */
        final float cropZoom;
        final int cropOffsetX;
        final int cropOffsetY;

        /*
         * Difference of width/height between the wallpaper and the screen.
         * This is the additional room that we have to apply offsets (i.e. parallax).
         */
        final int diffWidth;
        final int diffHeight;

        /*
         * zoom, offsetX and offsetY are not related to cropping the wallpaper:
         *  - zoom is used to apply an additional zoom (e.g. for launcher animations).
         *  - offsetX, offsetY are used to apply an offset to the wallpaper (e.g. parallax effect).
         */
        final float zoom;
        int offsetX;
        int offsetY;

        if (multiCrop()) {
            if (mWallpaperCropUtils == null) {
                Slog.e(TAG, "Update wallpaper offsets before the system is ready. Aborting");
                return false;
            }
            Point bitmapSize = new Point(
                    wallpaperWin.mRequestedWidth, wallpaperWin.mRequestedHeight);
            SparseArray<Rect> cropHints = token.getCropHints();
            wallpaperFrame = bitmapSize.x <= 0 || bitmapSize.y <= 0 ? wallpaperWin.getFrame()
                    : mWallpaperCropUtils.getCrop(screenSize, bitmapSize, cropHints,
                            wallpaperWin.isRtl());
            int frameWidth = wallpaperFrame.width();
            int frameHeight = wallpaperFrame.height();
            float frameRatio = (float) frameWidth / frameHeight;

            // If the crop is proportionally wider/taller than the screen, scale it so that its
            // height/width matches the screen height/width, and use the additional width/height
            // for parallax (respectively).
            boolean scaleHeight = frameRatio >= screenRatio;
            cropZoom = wallpaperFrame.isEmpty() ? 1f : scaleHeight
                    ? (float) screenHeight / frameHeight / wallpaperWin.mVScale
                    : (float) screenWidth / frameWidth / wallpaperWin.mHScale;

            // The dimensions of the frame, without the additional width or height for parallax.
            float w = scaleHeight ? frameHeight * screenRatio : frameWidth;
            float h = scaleHeight ? frameHeight : frameWidth / screenRatio;

            // Note: a positive x/y offset shifts the wallpaper to the right/bottom respectively.
            cropOffsetX = -wallpaperFrame.left + (int) ((cropZoom - 1f) * w / 2f);
            cropOffsetY = -wallpaperFrame.top + (int) ((cropZoom - 1f) * h / 2f);

            // Available width or height for parallax
            diffWidth = (int) ((frameWidth - w) * wallpaperWin.mHScale);
            diffHeight = (int) ((frameHeight - h) * wallpaperWin.mVScale);
        } else {
            wallpaperFrame = wallpaperWin.getFrame();
            cropZoom = 1f;
            cropOffsetX = 0;
            cropOffsetY = 0;
            diffWidth = wallpaperFrame.width() - screenWidth;
            diffHeight = wallpaperFrame.height() - screenHeight;

            if ((wallpaperWin.mAttrs.flags & WindowManager.LayoutParams.FLAG_SCALED) != 0
                    && Math.abs(diffWidth) > 1 && Math.abs(diffHeight) > 1) {
                Slog.d(TAG, "Skip wallpaper offset with inconsistent orientation, bounds="
                        + lastWallpaperBounds + " frame=" + wallpaperFrame);
                // With FLAG_SCALED, the requested size should at least make the frame match one of
                // side. If both sides contain differences, the client side may not have updated the
                // latest size according to the current orientation. So skip calculating the offset
                // to avoid the wallpaper not filling the screen.
                return false;
            }
        }

        boolean rawChanged = false;
        // Set the default wallpaper x-offset to either edge of the screen (depending on RTL), to
        // match the behavior of most Launchers
        float defaultWallpaperX = wallpaperWin.isRtl() ? 1f : 0f;
        // "Wallpaper X" is the previous x-offset of the wallpaper (in a 0 to 1 scale).
        // The 0 to 1 scale is because the "length" varies depending on how many home screens you
        // have, so 0 is the left of the first home screen, and 1 is the right of the last one (for
        // LTR, and the opposite for RTL).
        float wpx = token.mWallpaperX >= 0 ? token.mWallpaperX : defaultWallpaperX;
        // "Wallpaper X step size" is how much of that 0-1 is one "page" of the home screen
        // when scrolling.
        float wpxs = token.mWallpaperXStep >= 0 ? token.mWallpaperXStep : -1.0f;
        // Difference between width of wallpaper image, and the last size of the wallpaper.
        // This is the horizontal surplus from the prior configuration.
        int availw = diffWidth;

        int displayOffset = getDisplayWidthOffset(availw, lastWallpaperBounds,
                wallpaperWin.isRtl());
        availw -= displayOffset;
        offsetX = availw > 0 ? -(int) (availw * wpx + .5f) : 0;
        if (token.mWallpaperDisplayOffsetX != Integer.MIN_VALUE) {
            // if device is LTR, then offset wallpaper to the left (the wallpaper is drawn
            // always starting from the left of the screen).
            offsetX += token.mWallpaperDisplayOffsetX;
        } else if (!wallpaperWin.isRtl()) {
            // In RTL the offset is calculated so that the wallpaper ends up right aligned (see
            // offset above).
            offsetX -= displayOffset;
        }
        offsetX += cropOffsetX * wallpaperWin.mHScale;

        if (wallpaperWin.mWallpaperX != wpx || wallpaperWin.mWallpaperXStep != wpxs) {
            wallpaperWin.mWallpaperX = wpx;
            wallpaperWin.mWallpaperXStep = wpxs;
            rawChanged = true;
        }

        float wpy = token.mWallpaperY >= 0 ? token.mWallpaperY : 0.5f;
        float wpys = token.mWallpaperYStep >= 0 ? token.mWallpaperYStep : -1.0f;
        offsetY = diffHeight > 0 ? -(int) (diffHeight * wpy + .5f) : 0;
        if (token.mWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
            offsetY += token.mWallpaperDisplayOffsetY;
        }
        offsetY += cropOffsetY * wallpaperWin.mVScale;

        if (wallpaperWin.mWallpaperY != wpy || wallpaperWin.mWallpaperYStep != wpys) {
            wallpaperWin.mWallpaperY = wpy;
            wallpaperWin.mWallpaperYStep = wpys;
            rawChanged = true;
        }

        if (Float.compare(wallpaperWin.mWallpaperZoomOut, mLastWallpaperZoomOut) != 0) {
            wallpaperWin.mWallpaperZoomOut = mLastWallpaperZoomOut;
            rawChanged = true;
        }
        zoom = wallpaperWin.mShouldScaleWallpaper
                ? zoomOutToScale(wallpaperWin.mWallpaperZoomOut) : 1f;
        final float totalZoom = zoom * cropZoom;
        boolean changed = wallpaperWin.setWallpaperOffset(offsetX, offsetY, totalZoom);

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
                                ProtoLog.v(WM_DEBUG_WALLPAPER, "Waiting for offset complete...");
                                mService.mGlobalLock.wait(WALLPAPER_TIMEOUT);
                            } catch (InterruptedException e) {
                            }
                            ProtoLog.v(WM_DEBUG_WALLPAPER, "Offset complete!");
                            if ((start + WALLPAPER_TIMEOUT) < SystemClock.uptimeMillis()) {
                                ProtoLog.v(WM_DEBUG_WALLPAPER,
                                        "Timeout waiting for wallpaper to offset: %s",
                                        wallpaperWin);
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
        if (!mShouldOffsetWallpaperCenter || multiCrop()) {
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
            updateWallpaperOffsetLocked(window, !mService.mFlags.mWallpaperOffsetAsync);
        }
    }

    void setWallpaperZoomOut(WindowState window, float zoom) {
        if (Float.compare(window.mWallpaperZoomOut, zoom) != 0) {
            window.mWallpaperZoomOut = zoom;
            computeLastWallpaperZoomOut();
            for (int i = mWallpaperTokens.size() - 1; i >= 0; i--) {
                final WallpaperWindowToken token = mWallpaperTokens.get(i);
                token.updateWallpaperOffset(false);
            }
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
            updateWallpaperOffsetLocked(window, !mService.mFlags.mWallpaperOffsetAsync);
        }
    }

    void sendWindowWallpaperCommandUnchecked(
            WindowState window, String action, int x, int y, int z,
            Bundle extras, boolean sync) {
        sendWindowWallpaperCommand(action, x, y, z, extras, sync);
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

        WallpaperWindowToken token = getTokenForTarget(target);
        if (token == null) return;

        if (target.mWallpaperX >= 0) {
            token.mWallpaperX = target.mWallpaperX;
        } else if (changingTarget.mWallpaperX >= 0) {
            token.mWallpaperX = changingTarget.mWallpaperX;
        }
        if (target.mWallpaperY >= 0) {
            token.mWallpaperY = target.mWallpaperY;
        } else if (changingTarget.mWallpaperY >= 0) {
            token.mWallpaperY = changingTarget.mWallpaperY;
        }
        if (target.mWallpaperDisplayOffsetX != Integer.MIN_VALUE) {
            token.mWallpaperDisplayOffsetX = target.mWallpaperDisplayOffsetX;
        } else if (changingTarget.mWallpaperDisplayOffsetX != Integer.MIN_VALUE) {
            token.mWallpaperDisplayOffsetX = changingTarget.mWallpaperDisplayOffsetX;
        }
        if (target.mWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
            token.mWallpaperDisplayOffsetY = target.mWallpaperDisplayOffsetY;
        } else if (changingTarget.mWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
            token.mWallpaperDisplayOffsetY = changingTarget.mWallpaperDisplayOffsetY;
        }
        if (target.mWallpaperXStep >= 0) {
            token.mWallpaperXStep = target.mWallpaperXStep;
        } else if (changingTarget.mWallpaperXStep >= 0) {
            token.mWallpaperXStep = changingTarget.mWallpaperXStep;
        }
        if (target.mWallpaperYStep >= 0) {
            token.mWallpaperYStep = target.mWallpaperYStep;
        } else if (changingTarget.mWallpaperYStep >= 0) {
            token.mWallpaperYStep = changingTarget.mWallpaperYStep;
        }
        token.updateWallpaperOffset(sync);
    }

    private WallpaperWindowToken getTokenForTarget(WindowState target) {
        if (target == null) return null;
        WindowState window = mFindResults.getTopWallpaper(
                target.canShowWhenLocked() && mService.isKeyguardLocked());
        return window == null ? null : window.mToken.asWallpaperToken();
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

        findWallpapers();
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

    private void findWallpapers() {
        for (int i = mWallpaperTokens.size() - 1; i >= 0; i--) {
            final WallpaperWindowToken token = mWallpaperTokens.get(i);
            final boolean canShowWhenLocked = token.canShowWhenLocked();
            for (int j = token.getChildCount() - 1; j >= 0; j--) {
                final WindowState w = token.getChildAt(j);
                if (!w.mIsWallpaper) continue;
                if (canShowWhenLocked && !mFindResults.hasTopShowWhenLockedWallpaper()) {
                    mFindResults.setTopShowWhenLockedWallpaper(w);
                } else if (!canShowWhenLocked && !mFindResults.hasTopHideWhenLockedWallpaper()) {
                    mFindResults.setTopHideWhenLockedWallpaper(w);
                }
            }
        }
    }

    void collectTopWallpapers(Transition transition) {
        if (mFindResults.hasTopShowWhenLockedWallpaper()) {
            if (Flags.ensureWallpaperInTransitions()) {
                transition.collect(mFindResults.mTopWallpaper.mTopShowWhenLockedWallpaper.mToken);
            } else {
                transition.collect(mFindResults.mTopWallpaper.mTopShowWhenLockedWallpaper);
            }

        }
        if (mFindResults.hasTopHideWhenLockedWallpaper()) {
            if (Flags.ensureWallpaperInTransitions()) {
                transition.collect(mFindResults.mTopWallpaper.mTopHideWhenLockedWallpaper.mToken);
            } else {
                transition.collect(mFindResults.mTopWallpaper.mTopHideWhenLockedWallpaper);
            }
        }
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

    /**
     * Change the visibility of the top wallpaper to {@param visibility} and hide all the others.
     */
    private void updateWallpaperTokens(boolean visibility, boolean keyguardLocked) {
        ProtoLog.v(WM_DEBUG_WALLPAPER, "updateWallpaperTokens requestedVisibility=%b on"
                + " keyguardLocked=%b", visibility, keyguardLocked);
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
        WallpaperWindowToken token = getTokenForTarget(mWallpaperTarget);

        // The window is visible to the compositor...but is it visible to the user?
        // That is what the wallpaper cares about.
        final boolean visible = token != null;

        if (visible) {
            if (mWallpaperTarget.mWallpaperX >= 0) {
                token.mWallpaperX = mWallpaperTarget.mWallpaperX;
                token.mWallpaperXStep = mWallpaperTarget.mWallpaperXStep;
            }
            if (mWallpaperTarget.mWallpaperY >= 0) {
                token.mWallpaperY = mWallpaperTarget.mWallpaperY;
                token.mWallpaperYStep = mWallpaperTarget.mWallpaperYStep;
            }
            if (mWallpaperTarget.mWallpaperDisplayOffsetX != Integer.MIN_VALUE) {
                token.mWallpaperDisplayOffsetX = mWallpaperTarget.mWallpaperDisplayOffsetX;
            }
            if (mWallpaperTarget.mWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
                token.mWallpaperDisplayOffsetY = mWallpaperTarget.mWallpaperDisplayOffsetY;
            }
        }

        updateWallpaperTokens(visible, mDisplayContent.isKeyguardLocked());

        ProtoLog.v(WM_DEBUG_WALLPAPER,
                "Wallpaper at display %d - visibility: %b, keyguardLocked: %b",
                mDisplayContent.getDisplayId(), visible, mDisplayContent.isKeyguardLocked());

        if (visible && mLastFrozen != mFindResults.isWallpaperTargetForLetterbox) {
            mLastFrozen = mFindResults.isWallpaperTargetForLetterbox;
            sendWindowWallpaperCommand(
                    mFindResults.isWallpaperTargetForLetterbox ? COMMAND_FREEZE : COMMAND_UNFREEZE,
                    /* x= */ 0, /* y= */ 0, /* z= */ 0, /* extras= */ null, /* sync= */ false);
        }

        ProtoLog.d(WM_DEBUG_WALLPAPER, "Wallpaper target=%s prev=%s",
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
                ProtoLog.v(WM_DEBUG_WALLPAPER,
                        "Wallpaper should be visible but has not been drawn yet. "
                                + "mWallpaperDrawState=%d", mWallpaperDrawState);
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

    void onWallpaperTokenReordered() {
        if (mWallpaperTokens.size() > 1) {
            mWallpaperTokens.sort(null /* by WindowContainer#compareTo */);
        }
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
     * Notifies the wallpaper that the display turns off when switching physical device. If the
     * wallpaper is currently visible, its client visibility will be preserved until the display is
     * confirmed to be off or on.
     */
    void onDisplaySwitchStarted() {
        mIsWallpaperNotifiedOnDisplaySwitch = notifyDisplaySwitch(true /* start */);
    }

    /**
     * Called when the screen has finished turning on or the device goes to sleep. This is no-op if
     * the operation is not part of a display switch.
     */
    void onDisplaySwitchFinished() {
        // The method can be called outside WM lock (turned on), so only acquire lock if needed.
        // This is to optimize the common cases that regular devices don't have display switch.
        if (mIsWallpaperNotifiedOnDisplaySwitch) {
            synchronized (mService.mGlobalLock) {
                mIsWallpaperNotifiedOnDisplaySwitch = false;
                notifyDisplaySwitch(false /* start */);
            }
        }
    }

    private boolean notifyDisplaySwitch(boolean start) {
        boolean notified = false;
        for (int curTokenNdx = mWallpaperTokens.size() - 1; curTokenNdx >= 0; curTokenNdx--) {
            final WallpaperWindowToken token = mWallpaperTokens.get(curTokenNdx);
            for (int i = token.getChildCount() - 1; i >= 0; i--) {
                final WindowState w = token.getChildAt(i);
                if (start && !w.mWinAnimator.getShown()) {
                    continue;
                }
                try {
                    w.mClient.dispatchWallpaperCommand(COMMAND_DISPLAY_SWITCH, 0 /* x */, 0 /* y */,
                            start ? 1 : 0 /* use z as start or finish */,
                            null /* bundle */, false /* sync */);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to dispatch COMMAND_DISPLAY_SWITCH " + e);
                }
                notified = true;
            }
        }
        return notified;
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
        mLastWallpaperZoomOut = 0;
        mDisplayContent.forAllWindows(mComputeMaxZoomOutFunction, true);
    }


    private float zoomOutToScale(float zoomOut) {
        return MathUtils.lerp(mMinWallpaperScale, mMaxWallpaperScale, 1 - zoomOut);
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("displayId="); pw.println(mDisplayContent.getDisplayId());
        pw.print(prefix); pw.print("mWallpaperTarget="); pw.println(mWallpaperTarget);
        pw.print(prefix); pw.print("mLastWallpaperZoomOut="); pw.println(mLastWallpaperZoomOut);
        if (mPrevWallpaperTarget != null) {
            pw.print(prefix); pw.print("mPrevWallpaperTarget="); pw.println(mPrevWallpaperTarget);
        }

        for (int i = mWallpaperTokens.size() - 1; i >= 0; i--) {
            final WallpaperWindowToken t = mWallpaperTokens.get(i);
            pw.print(prefix); pw.println("token " + t + ":");
            pw.print(prefix); pw.print("  canShowWhenLocked="); pw.println(t.canShowWhenLocked());
            dumpValue(pw, prefix, "mWallpaperX", t.mWallpaperX);
            dumpValue(pw, prefix, "mWallpaperY", t.mWallpaperY);
            dumpValue(pw, prefix, "mWallpaperXStep", t.mWallpaperXStep);
            dumpValue(pw, prefix, "mWallpaperYStep", t.mWallpaperYStep);
            dumpValue(pw, prefix, "mWallpaperDisplayOffsetX", t.mWallpaperDisplayOffsetX);
            dumpValue(pw, prefix, "mWallpaperDisplayOffsetY", t.mWallpaperDisplayOffsetY);
        }
    }

    private void dumpValue(PrintWriter pw, String prefix, String valueName, float value) {
        pw.print(prefix); pw.print("  " + valueName + "=");
        pw.println(value >= 0 ? value : "NA");
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
            if (mTopWallpaper.mTopHideWhenLockedWallpaper != win) {
                ProtoLog.d(WM_DEBUG_WALLPAPER, "New home screen wallpaper: %s, prev: %s",
                        win, mTopWallpaper.mTopHideWhenLockedWallpaper);
            }
            mTopWallpaper.mTopHideWhenLockedWallpaper = win;
        }

        void setTopShowWhenLockedWallpaper(WindowState win) {
            if (mTopWallpaper.mTopShowWhenLockedWallpaper != win) {
                ProtoLog.d(WM_DEBUG_WALLPAPER, "New lock/shared screen wallpaper: %s, prev: %s",
                        win, mTopWallpaper.mTopShowWhenLockedWallpaper);
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
