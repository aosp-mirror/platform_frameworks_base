/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.graphics.Matrix.MSCALE_X;
import static android.graphics.Matrix.MSCALE_Y;
import static android.graphics.Matrix.MSKEW_X;
import static android.graphics.Matrix.MSKEW_Y;
import static android.graphics.Matrix.MTRANS_X;
import static android.graphics.Matrix.MTRANS_Y;
import static android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.TRANSIT_OLD_NONE;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_DRAW;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_STARTING_WINDOW;
import static com.android.internal.protolog.ProtoLogGroup.WM_SHOW_SURFACE_ALLOC;
import static com.android.internal.protolog.ProtoLogGroup.WM_SHOW_TRANSACTIONS;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION;
import static com.android.server.wm.WindowContainer.AnimationFlags.PARENTS;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW_VERBOSE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.TYPE_LAYER_MULTIPLIER;
import static com.android.server.wm.WindowManagerService.logWithStack;
import static com.android.server.wm.WindowStateAnimatorProto.DRAW_STATE;
import static com.android.server.wm.WindowStateAnimatorProto.SURFACE;
import static com.android.server.wm.WindowStateAnimatorProto.SYSTEM_DECOR_RECT;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Debug;
import android.os.Trace;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import com.android.internal.protolog.common.ProtoLog;
import com.android.server.policy.WindowManagerPolicy;

import java.io.PrintWriter;

/**
 * Keep track of animations and surface operations for a single WindowState.
 **/
class WindowStateAnimator {
    static final String TAG = TAG_WITH_CLASS_NAME ? "WindowStateAnimator" : TAG_WM;
    static final int WINDOW_FREEZE_LAYER = TYPE_LAYER_MULTIPLIER * 200;
    static final int PRESERVED_SURFACE_LAYER = 1;

    /**
     * Mode how the window gets clipped by the root task bounds during an animation: The clipping
     * should be applied after applying the animation transformation, i.e. the root task bounds
     * don't move during the animation.
     */
    static final int ROOT_TASK_CLIP_AFTER_ANIM = 0;

    /**
     * Mode how the window gets clipped by the root task bounds: The clipping should be applied
     * before applying the animation transformation, i.e. the root task bounds move with the window.
     */
    static final int ROOT_TASK_CLIP_BEFORE_ANIM = 1;

    /**
     * Mode how window gets clipped by the root task bounds during an animation: Don't clip the
     * window by the root task bounds.
     */
    static final int ROOT_TASK_CLIP_NONE = 2;

    // Unchanging local convenience fields.
    final WindowManagerService mService;
    final WindowState mWin;
    final WindowAnimator mAnimator;
    final Session mSession;
    final WindowManagerPolicy mPolicy;
    final Context mContext;
    final boolean mIsWallpaper;
    private final WallpaperController mWallpaperControllerLocked;

    boolean mAnimationIsEntrance;

    WindowSurfaceController mSurfaceController;

    float mShownAlpha = 0;
    float mAlpha = 0;
    float mLastAlpha = 0;

    /**
     * This is rectangle of the window's surface that is not covered by
     * system decorations.
     */
    private final Rect mSystemDecorRect = new Rect();

    float mDsDx=1, mDtDx=0, mDsDy=0, mDtDy=1;
    private float mLastDsDx=1, mLastDtDx=0, mLastDsDy=0, mLastDtDy=1;

    boolean mHaveMatrix;

    // Set to true if, when the window gets displayed, it should perform
    // an enter animation.
    boolean mEnterAnimationPending;

    /** Used to indicate that this window is undergoing an enter animation. Used for system
     * windows to make the callback to View.dispatchOnWindowShownCallback(). Set when the
     * window is first added or shown, cleared when the callback has been made. */
    boolean mEnteringAnimation;

    /** The pixel format of the underlying SurfaceControl */
    int mSurfaceFormat;

    /** This is set when there is no Surface */
    static final int NO_SURFACE = 0;
    /** This is set after the Surface has been created but before the window has been drawn. During
     * this time the surface is hidden. */
    static final int DRAW_PENDING = 1;
    /** This is set after the window has finished drawing for the first time but before its surface
     * is shown.  The surface will be displayed when the next layout is run. */
    static final int COMMIT_DRAW_PENDING = 2;
    /** This is set during the time after the window's drawing has been committed, and before its
     * surface is actually shown.  It is used to delay showing the surface until all windows in a
     * token are ready to be shown. */
    static final int READY_TO_SHOW = 3;
    /** Set when the window has been shown in the screen the first time. */
    static final int HAS_DRAWN = 4;

    String drawStateToString() {
        switch (mDrawState) {
            case NO_SURFACE: return "NO_SURFACE";
            case DRAW_PENDING: return "DRAW_PENDING";
            case COMMIT_DRAW_PENDING: return "COMMIT_DRAW_PENDING";
            case READY_TO_SHOW: return "READY_TO_SHOW";
            case HAS_DRAWN: return "HAS_DRAWN";
            default: return Integer.toString(mDrawState);
        }
    }
    int mDrawState;

    /** Was this window last hidden? */
    boolean mLastHidden;

    int mAttrType;

    // An offset in pixel of the surface contents from the window position. Used for Wallpaper
    // to provide the effect of scrolling within a large surface. We just use these values as
    // a cache.
    int mXOffset = 0;
    int mYOffset = 0;

    // A scale factor for the surface contents, that will be applied from the center of the visible
    // region.
    float mWallpaperScale = 1f;

    private final Rect mTmpSize = new Rect();

    /**
     * Handles surface changes synchronized to after the client has drawn the surface. This
     * transaction is currently used to reparent the old surface children to the new surface once
     * the client has completed drawing to the new surface.
     * This transaction is also used to merge transactions parceled in by the client. The client
     * uses the transaction to update the relative z of its children from the old parent surface
     * to the new parent surface once window manager reparents its children.
     */
    private final SurfaceControl.Transaction mPostDrawTransaction =
            new SurfaceControl.Transaction();

    WindowStateAnimator(final WindowState win) {
        final WindowManagerService service = win.mWmService;

        mService = service;
        mAnimator = service.mAnimator;
        mPolicy = service.mPolicy;
        mContext = service.mContext;

        mWin = win;
        mSession = win.mSession;
        mAttrType = win.mAttrs.type;
        mIsWallpaper = win.mIsWallpaper;
        mWallpaperControllerLocked = win.getDisplayContent().mWallpaperController;
    }

    void onAnimationFinished() {
        // Done animating, clean up.
        if (DEBUG_ANIM) Slog.v(
                TAG, "Animation done in " + this + ": exiting=" + mWin.mAnimatingExit
                        + ", reportedVisible="
                        + (mWin.mActivityRecord != null && mWin.mActivityRecord.reportedVisible));

        mWin.checkPolicyVisibilityChange();
        final DisplayContent displayContent = mWin.getDisplayContent();
        if ((mAttrType == LayoutParams.TYPE_STATUS_BAR
                || mAttrType == LayoutParams.TYPE_NOTIFICATION_SHADE) && mWin.isVisibleByPolicy()) {
            // Upon completion of a not-visible to visible status bar animation a relayout is
            // required.
            displayContent.setLayoutNeeded();
        }
        mWin.onExitAnimationDone();
        displayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_ANIM;
        if (displayContent.mWallpaperController.isWallpaperTarget(mWin)) {
            displayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
        }
        if (DEBUG_LAYOUT_REPEATS) {
            mService.mWindowPlacerLocked.debugLayoutRepeats(
                    "WindowStateAnimator", displayContent.pendingLayoutChanges);
        }

        if (mWin.mActivityRecord != null) {
            mWin.mActivityRecord.updateReportedVisibilityLocked();
        }
    }

    void hide(SurfaceControl.Transaction transaction, String reason) {
        if (!mLastHidden) {
            //dump();
            mLastHidden = true;

            if (mSurfaceController != null) {
                mSurfaceController.hide(transaction, reason);
            }
        }
    }

    boolean finishDrawingLocked(SurfaceControl.Transaction postDrawTransaction) {
        final boolean startingWindow =
                mWin.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
        if (startingWindow) {
            ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Finishing drawing window %s: mDrawState=%s",
                    mWin, drawStateToString());
        }

        boolean layoutNeeded = false;

        if (mDrawState == DRAW_PENDING) {
            ProtoLog.v(WM_DEBUG_DRAW,
                    "finishDrawingLocked: mDrawState=COMMIT_DRAW_PENDING %s in %s", mWin,
                    mSurfaceController);
            if (startingWindow) {
                ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Draw state now committed in %s", mWin);
            }
            mDrawState = COMMIT_DRAW_PENDING;
            layoutNeeded = true;

            if (postDrawTransaction != null) {
                mPostDrawTransaction.merge(postDrawTransaction);
            }
        } else if (postDrawTransaction != null) {
            // If draw state is not pending we may delay applying this transaction from the client,
            // so apply it now.
            postDrawTransaction.apply();
        }

        return layoutNeeded;
    }

    // This must be called while inside a transaction.
    boolean commitFinishDrawingLocked() {
        if (DEBUG_STARTING_WINDOW_VERBOSE &&
                mWin.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING) {
            Slog.i(TAG, "commitFinishDrawingLocked: " + mWin + " cur mDrawState="
                    + drawStateToString());
        }
        if (mDrawState != COMMIT_DRAW_PENDING && mDrawState != READY_TO_SHOW) {
            return false;
        }
        if (DEBUG_ANIM) {
            Slog.i(TAG, "commitFinishDrawingLocked: mDrawState=READY_TO_SHOW " + mSurfaceController);
        }
        mDrawState = READY_TO_SHOW;
        boolean result = false;
        final ActivityRecord activity = mWin.mActivityRecord;
        if (activity == null || activity.canShowWindows()
                || mWin.mAttrs.type == TYPE_APPLICATION_STARTING) {
            result = mWin.performShowLocked();
        }
        return result;
    }

    void resetDrawState() {
        mDrawState = DRAW_PENDING;

        if (mWin.mActivityRecord == null) {
            return;
        }

        if (!mWin.mActivityRecord.isAnimating(TRANSITION)) {
            mWin.mActivityRecord.clearAllDrawn();
        }
    }

    WindowSurfaceController createSurfaceLocked(int windowType) {
        final WindowState w = mWin;

        if (mSurfaceController != null) {
            return mSurfaceController;
        }

        w.setHasSurface(false);

        if (DEBUG_ANIM) {
            Slog.i(TAG, "createSurface " + this + ": mDrawState=DRAW_PENDING");
        }

        resetDrawState();

        mService.makeWindowFreezingScreenIfNeededLocked(w);

        int flags = SurfaceControl.HIDDEN;
        final WindowManager.LayoutParams attrs = w.mAttrs;

        if (w.isSecureLocked()) {
            flags |= SurfaceControl.SECURE;
        }

        if ((mWin.mAttrs.privateFlags & PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY) != 0) {
            flags |= SurfaceControl.SKIP_SCREENSHOT;
        }

        w.calculateSurfaceBounds(attrs, mTmpSize);

        final int width = mTmpSize.width();
        final int height = mTmpSize.height();

        if (DEBUG_VISIBILITY) {
            Slog.v(TAG, "Creating surface in session "
                    + mSession.mSurfaceSession + " window " + this
                    + " w=" + width + " h=" + height
                    + " x=" + mTmpSize.left + " y=" + mTmpSize.top
                    + " format=" + attrs.format + " flags=" + flags);
        }

        // Set up surface control with initial size.
        try {

            // This can be removed once we move all Buffer Layers to use BLAST.
            final boolean isHwAccelerated = (attrs.flags & FLAG_HARDWARE_ACCELERATED) != 0;
            final int format = isHwAccelerated ? PixelFormat.TRANSLUCENT : attrs.format;

            mSurfaceController = new WindowSurfaceController(attrs.getTitle().toString(), width,
                    height, format, flags, this, windowType);
            mSurfaceController.setColorSpaceAgnostic((attrs.privateFlags
                    & WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC) != 0);

            mSurfaceFormat = format;

            w.setHasSurface(true);
            // The surface instance is changed. Make sure the input info can be applied to the
            // new surface, e.g. relaunch activity.
            w.mInputWindowHandle.forceChange();

            ProtoLog.i(WM_SHOW_SURFACE_ALLOC,
                        "  CREATE SURFACE %s IN SESSION %s: pid=%d format=%d flags=0x%x / %s",
                        mSurfaceController, mSession.mSurfaceSession, mSession.mPid, attrs.format,
                        flags, this);
        } catch (OutOfResourcesException e) {
            Slog.w(TAG, "OutOfResourcesException creating surface");
            mService.mRoot.reclaimSomeSurfaceMemory(this, "create", true);
            mDrawState = NO_SURFACE;
            return null;
        } catch (Exception e) {
            Slog.e(TAG, "Exception creating surface (parent dead?)", e);
            mDrawState = NO_SURFACE;
            return null;
        }

        if (DEBUG) {
            Slog.v(TAG, "Got surface: " + mSurfaceController
                    + ", set left=" + w.getFrame().left + " top=" + w.getFrame().top);
        }

        if (SHOW_LIGHT_TRANSACTIONS) {
            Slog.i(TAG, ">>> OPEN TRANSACTION createSurfaceLocked");
            WindowManagerService.logSurface(w, "CREATE pos=("
                    + w.getFrame().left + "," + w.getFrame().top + ") ("
                    + width + "x" + height + ")" + " HIDE", false);
        }

        mLastHidden = true;

        if (DEBUG) Slog.v(TAG, "Created surface " + this);
        return mSurfaceController;
    }

    boolean hasSurface() {
        return mSurfaceController != null && mSurfaceController.hasSurface();
    }

    void destroySurfaceLocked(SurfaceControl.Transaction t) {
        final ActivityRecord activity = mWin.mActivityRecord;
        if (activity != null) {
            if (mWin == activity.mStartingWindow) {
                activity.startingDisplayed = false;
            }
        }

        if (mSurfaceController == null) {
            return;
        }

        mWin.mHidden = true;

        try {
            if (DEBUG_VISIBILITY) {
                logWithStack(TAG, "Window " + this + " destroying surface "
                        + mSurfaceController + ", session " + mSession);
            }
            ProtoLog.i(WM_SHOW_SURFACE_ALLOC, "SURFACE DESTROY: %s. %s",
                    mWin, new RuntimeException().fillInStackTrace());
            destroySurface(t);
            // Don't hide wallpaper if we're deferring the surface destroy
            // because of a surface change.
            mWallpaperControllerLocked.hideWallpapers(mWin);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Exception thrown when destroying Window " + this
                    + " surface " + mSurfaceController + " session " + mSession + ": "
                    + e.toString());
        }

        // Whether the surface was preserved (and copied to mPendingDestroySurface) or not, it
        // needs to be cleared to match the WindowState.mHasSurface state. It is also necessary
        // so it can be recreated successfully in mPendingDestroySurface case.
        mWin.setHasSurface(false);
        if (mSurfaceController != null) {
            mSurfaceController.setShown(false);
        }
        mSurfaceController = null;
        mDrawState = NO_SURFACE;
    }

    void computeShownFrameLocked() {
        if (mIsWallpaper && mService.mRoot.mWallpaperActionPending) {
            return;
        } else if (mWin.isDragResizeChanged()) {
            // This window is awaiting a relayout because user just started (or ended)
            // drag-resizing. The shown frame (which affects surface size and pos)
            // should not be updated until we get next finished draw with the new surface.
            // Otherwise one or two frames rendered with old settings would be displayed
            // with new geometry.
            return;
        }

        if (DEBUG) {
            Slog.v(TAG, "computeShownFrameLocked: " + this
                    + " not attached, mAlpha=" + mAlpha);
        }

        mShownAlpha = mAlpha;
        mHaveMatrix = false;
        mDsDx = 1;
        mDtDx = 0;
        mDtDy = 0;
        mDsDy = 1;
    }

    private boolean isInBlastSync() {
        return mService.useBLASTSync() && mWin.useBLASTSync();
    }

    private boolean shouldConsumeMainWindowSizeTransaction() {
        // We only consume the transaction when the client is calling relayout
        // because this is the only time we know the frameNumber will be valid
        // due to the client renderer being paused. Put otherwise, only when
        // mInRelayout is true can we guarantee the next frame will contain
        // the most recent configuration.
        if (!mWin.mInRelayout) return false;
        // Since we can only do this for one window, we focus on the main application window
        if (mAttrType != TYPE_BASE_APPLICATION) return false;
        final Task task = mWin.getTask();
        if (task == null) return false;
        if (task.getMainWindowSizeChangeTransaction() == null) return false;
        // Likewise we only focus on the task root, since we can only use one window
        if (!mWin.mActivityRecord.isRootOfTask()) return false;
        return true;
    }

    void setSurfaceBoundariesLocked(SurfaceControl.Transaction t) {
        if (mSurfaceController == null) {
            return;
        }

        final WindowState w = mWin;

        if (!w.mSeamlesslyRotated) {
            // Used to offset the WSA when stack position changes before a resize.
            int xOffset = mXOffset;
            int yOffset = mYOffset;
            if (!mIsWallpaper) {
                mSurfaceController.setPosition(t, xOffset, yOffset);
                // Wallpaper is already updated above when calling setWallpaperPositionAndScale so
                // we only need to consider the non-wallpaper case here.
                mSurfaceController.setMatrix(t,
                        mDsDx * w.mHScale,
                        mDtDx * w.mVScale,
                        mDtDy * w.mHScale,
                        mDsDy * w.mVScale);
            } else {
                setWallpaperPositionAndScale(t, xOffset, yOffset, mWallpaperScale);
            }
        }

        final Task task = w.getTask();
        if (shouldConsumeMainWindowSizeTransaction()) {
            if (isInBlastSync()) {
                // If we're in a sync transaction, there's no need to call defer transaction.
                // The sync transaction will contain the buffer so the bounds change transaction
                // will only be applied with the buffer.
                t.merge(task.getMainWindowSizeChangeTransaction());
            } else {
                // Use pending transaction here instead of the transaction passed in because we
                // want to ensure the defer transaction is applied on the main transaction and
                // not on the sync  transaction. This is because the sync transaction could
                // contain the buffer and we'd defer the transaction that contains the buffer
                // we're deferring on.
                SurfaceControl.Transaction pendingTransaction = mWin.getPendingTransaction();
                pendingTransaction.deferTransactionUntil(
                        task.getMainWindowSizeChangeTask().getSurfaceControl(),
                        mWin.getClientViewRootSurface(), mWin.getFrameNumber());
                pendingTransaction.deferTransactionUntil(mSurfaceController.mSurfaceControl,
                        mWin.getClientViewRootSurface(), mWin.getFrameNumber());
                pendingTransaction.merge(task.getMainWindowSizeChangeTransaction());
            }
            task.setMainWindowSizeChangeTransaction(null);
        }
    }

    void prepareSurfaceLocked(SurfaceControl.Transaction t) {
        final WindowState w = mWin;
        if (!hasSurface()) {

            // There is no need to wait for an animation change if our window is gone for layout
            // already as we'll never be visible.
            if (w.getOrientationChanging() && w.isGoneForLayout()) {
                ProtoLog.v(WM_DEBUG_ORIENTATION, "Orientation change skips hidden %s", w);
                w.setOrientationChanging(false);
            }
            return;
        }

        boolean displayed = false;

        computeShownFrameLocked();

        setSurfaceBoundariesLocked(t);

        if (w.isParentWindowHidden() || !w.isOnScreen()) {
            hide(t, "prepareSurfaceLocked");
            mWallpaperControllerLocked.hideWallpapers(w);

            // If we are waiting for this window to handle an orientation change. If this window is
            // really hidden (gone for layout), there is no point in still waiting for it.
            // Note that this does introduce a potential glitch if the window becomes unhidden
            // before it has drawn for the new orientation.
            if (w.getOrientationChanging() && w.isGoneForLayout()) {
                w.setOrientationChanging(false);
                ProtoLog.v(WM_DEBUG_ORIENTATION,
                        "Orientation change skips hidden %s", w);
            }
        } else if (mLastAlpha != mShownAlpha
                || mLastDsDx != mDsDx
                || mLastDtDx != mDtDx
                || mLastDsDy != mDsDy
                || mLastDtDy != mDtDy
                || w.mLastHScale != w.mHScale
                || w.mLastVScale != w.mVScale
                || mLastHidden) {
            displayed = true;
            mLastAlpha = mShownAlpha;
            mLastDsDx = mDsDx;
            mLastDtDx = mDtDx;
            mLastDsDy = mDsDy;
            mLastDtDy = mDtDy;
            w.mLastHScale = w.mHScale;
            w.mLastVScale = w.mVScale;
            ProtoLog.i(WM_SHOW_TRANSACTIONS,
                    "SURFACE controller=%s alpha=%f matrix=[%f*%f,%f*%f][%f*%f,%f*%f]: %s",
                            mSurfaceController, mShownAlpha, mDsDx, w.mHScale, mDtDx, w.mVScale,
                            mDtDy, w.mHScale, mDsDy, w.mVScale, w);

            boolean prepared = true;

            if (mIsWallpaper) {
                setWallpaperPositionAndScale(t, mXOffset, mYOffset, mWallpaperScale);
            } else {
                prepared =
                    mSurfaceController.prepareToShowInTransaction(t, mShownAlpha,
                        mDsDx * w.mHScale,
                        mDtDx * w.mVScale,
                        mDtDy * w.mHScale,
                        mDsDy * w.mVScale
                    );
            }

            if (prepared && mDrawState == HAS_DRAWN) {
                if (mLastHidden) {
                    if (showSurfaceRobustlyLocked(t)) {
                        mAnimator.requestRemovalOfReplacedWindows(w);
                        mLastHidden = false;
                        final DisplayContent displayContent = w.getDisplayContent();
                        if (!displayContent.getLastHasContent()) {
                            // This draw means the difference between unique content and mirroring.
                            // Run another pass through performLayout to set mHasContent in the
                            // LogicalDisplay.
                            displayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_ANIM;
                            if (DEBUG_LAYOUT_REPEATS) {
                                mService.mWindowPlacerLocked.debugLayoutRepeats(
                                        "showSurfaceRobustlyLocked " + w,
                                        displayContent.pendingLayoutChanges);
                            }
                        }
                    } else {
                        w.setOrientationChanging(false);
                    }
                }
            }
            if (hasSurface()) {
                w.mToken.hasVisible = true;
            }
        } else {
            if (DEBUG_ANIM && mWin.isAnimating(TRANSITION | PARENTS)) {
                Slog.v(TAG, "prepareSurface: No changes in animation for " + this);
            }
            displayed = true;
        }

        if (w.getOrientationChanging()) {
            if (!w.isDrawn()) {
                if (w.mDisplayContent.waitForUnfreeze(w)) {
                    w.mWmService.mRoot.mOrientationChangeComplete = false;
                    mAnimator.mLastWindowFreezeSource = w;
                }
                ProtoLog.v(WM_DEBUG_ORIENTATION,
                        "Orientation continue waiting for draw in %s", w);
            } else {
                w.setOrientationChanging(false);
                ProtoLog.v(WM_DEBUG_ORIENTATION, "Orientation change complete in %s", w);
            }
        }

        if (displayed) {
            w.mToken.hasVisible = true;
        }
    }

    boolean setWallpaperOffset(int dx, int dy, float scale) {
        if (mXOffset == dx && mYOffset == dy && Float.compare(mWallpaperScale, scale) == 0) {
            return false;
        }
        mXOffset = dx;
        mYOffset = dy;
        mWallpaperScale = scale;

        if (mSurfaceController != null) {
            try {
                if (SHOW_LIGHT_TRANSACTIONS) {
                    Slog.i(TAG, ">>> OPEN TRANSACTION setWallpaperOffset");
                }
                mService.openSurfaceTransaction();
                setWallpaperPositionAndScale(SurfaceControl.getGlobalTransaction(), dx, dy, scale);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Error positioning surface of " + mWin
                        + " pos=(" + dx + "," + dy + ")", e);
            } finally {
                mService.closeSurfaceTransaction("setWallpaperOffset");
                if (SHOW_LIGHT_TRANSACTIONS) {
                    Slog.i(TAG, "<<< CLOSE TRANSACTION setWallpaperOffset");
                }
            }
        }

        return true;
    }

    private void setWallpaperPositionAndScale(SurfaceControl.Transaction t, int dx, int dy,
            float scale) {
        DisplayInfo displayInfo = mWin.getDisplayInfo();
        Matrix matrix = mWin.mTmpMatrix;
        matrix.setTranslate(dx, dy);
        matrix.postScale(scale, scale, displayInfo.logicalWidth / 2f,
                displayInfo.logicalHeight / 2f);
        matrix.getValues(mWin.mTmpMatrixArray);
        matrix.reset();

        mSurfaceController.setPosition(t,mWin.mTmpMatrixArray[MTRANS_X],
                mWin.mTmpMatrixArray[MTRANS_Y]);
        mSurfaceController.setMatrix(t,
                mDsDx * mWin.mTmpMatrixArray[MSCALE_X] * mWin.mHScale,
                mDtDx * mWin.mTmpMatrixArray[MSKEW_Y] * mWin.mVScale,
                mDtDy * mWin.mTmpMatrixArray[MSKEW_X] * mWin.mHScale,
                mDsDy * mWin.mTmpMatrixArray[MSCALE_Y] * mWin.mVScale);
    }

    /**
     * Try to change the pixel format without recreating the surface. This
     * will be common in the case of changing from PixelFormat.OPAQUE to
     * PixelFormat.TRANSLUCENT in the hardware-accelerated case as both
     * requested formats resolve to the same underlying SurfaceControl format
     * @return True if format was succesfully changed, false otherwise
     */
    boolean tryChangeFormatInPlaceLocked() {
        if (mSurfaceController == null) {
            return false;
        }
        final LayoutParams attrs = mWin.getAttrs();
        final boolean isHwAccelerated = (attrs.flags & FLAG_HARDWARE_ACCELERATED) != 0;
        final int format = isHwAccelerated ? PixelFormat.TRANSLUCENT : attrs.format;
        if (format == mSurfaceFormat) {
            setOpaqueLocked(!PixelFormat.formatHasAlpha(attrs.format));
            return true;
        }
        return false;
    }

    void setOpaqueLocked(boolean isOpaque) {
        if (mSurfaceController == null) {
            return;
        }
        mSurfaceController.setOpaque(isOpaque);
    }

    void setSecureLocked(boolean isSecure) {
        if (mSurfaceController == null) {
            return;
        }
        mSurfaceController.setSecure(isSecure);
    }

    void setColorSpaceAgnosticLocked(boolean agnostic) {
        if (mSurfaceController == null) {
            return;
        }
        mSurfaceController.setColorSpaceAgnostic(agnostic);
    }

    /**
     * Have the surface flinger show a surface, robustly dealing with
     * error conditions.  In particular, if there is not enough memory
     * to show the surface, then we will try to get rid of other surfaces
     * in order to succeed.
     *
     * @return Returns true if the surface was successfully shown.
     */
    private boolean showSurfaceRobustlyLocked(SurfaceControl.Transaction t) {
        boolean shown = mSurfaceController.showRobustly(t);
        if (!shown)
            return false;

        t.merge(mPostDrawTransaction);
        return true;
    }

    void applyEnterAnimationLocked() {
        // If we are the new part of a window replacement transition and we have requested
        // not to animate, we instead want to make it seamless, so we don't want to apply
        // an enter transition.
        if (mWin.mSkipEnterAnimationForSeamlessReplacement) {
            return;
        }

        final int transit;
        if (mEnterAnimationPending) {
            mEnterAnimationPending = false;
            transit = WindowManagerPolicy.TRANSIT_ENTER;
        } else {
            transit = WindowManagerPolicy.TRANSIT_SHOW;
        }

        // We don't apply animation for application main window here since this window type
        // should be controlled by AppWindowToken in general.
        if (mAttrType != TYPE_BASE_APPLICATION) {
            applyAnimationLocked(transit, true);
        }

        if (mService.mAccessibilityController != null) {
            mService.mAccessibilityController.onWindowTransition(mWin, transit);
        }
    }

    /**
     * Choose the correct animation and set it to the passed WindowState.
     * @param transit If AppTransition.TRANSIT_PREVIEW_DONE and the app window has been drawn
     *      then the animation will be app_starting_exit. Any other value loads the animation from
     *      the switch statement below.
     * @param isEntrance The animation type the last time this was called. Used to keep from
     *      loading the same animation twice.
     * @return true if an animation has been loaded.
     */
    boolean applyAnimationLocked(int transit, boolean isEntrance) {
        if (mWin.isAnimating() && mAnimationIsEntrance == isEntrance) {
            // If we are trying to apply an animation, but already running
            // an animation of the same type, then just leave that one alone.
            return true;
        }

        final boolean isImeWindow = mWin.mAttrs.type == TYPE_INPUT_METHOD;
        if (isEntrance && isImeWindow) {
            mWin.getDisplayContent().adjustForImeIfNeeded();
            mWin.setDisplayLayoutNeeded();
            mService.mWindowPlacerLocked.requestTraversal();
        }

        // Only apply an animation if the display isn't frozen.  If it is
        // frozen, there is no reason to animate and it can cause strange
        // artifacts when we unfreeze the display if some different animation
        // is running.
        if (mWin.mToken.okToAnimate()) {
            int anim = mWin.getDisplayContent().getDisplayPolicy().selectAnimation(mWin, transit);
            int attr = -1;
            Animation a = null;
            if (anim != DisplayPolicy.ANIMATION_STYLEABLE) {
                if (anim != DisplayPolicy.ANIMATION_NONE) {
                    Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "WSA#loadAnimation");
                    a = AnimationUtils.loadAnimation(mContext, anim);
                    Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
                }
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
                    a = mWin.getDisplayContent().mAppTransition.loadAnimationAttr(
                            mWin.mAttrs, attr, TRANSIT_OLD_NONE);
                }
            }
            if (DEBUG_ANIM) Slog.v(TAG,
                    "applyAnimation: win=" + this
                    + " anim=" + anim + " attr=0x" + Integer.toHexString(attr)
                    + " a=" + a
                    + " transit=" + transit
                    + " type=" + mAttrType
                    + " isEntrance=" + isEntrance + " Callers " + Debug.getCallers(3));
            if (a != null) {
                if (DEBUG_ANIM) logWithStack(TAG, "Loaded animation " + a + " for " + this);
                Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "WSA#startAnimation");
                mWin.startAnimation(a);
                Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
                mAnimationIsEntrance = isEntrance;
            }
        } else if (!isImeWindow) {
            mWin.cancelAnimation();
        }

        if (!isEntrance && isImeWindow) {
            mWin.getDisplayContent().adjustForImeIfNeeded();
        }

        return mWin.isAnimating(0 /* flags */, ANIMATION_TYPE_WINDOW_ANIMATION);
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        if (mSurfaceController != null) {
            mSurfaceController.dumpDebug(proto, SURFACE);
        }
        proto.write(DRAW_STATE, mDrawState);
        mSystemDecorRect.dumpDebug(proto, SYSTEM_DECOR_RECT);
        proto.end(token);
    }

    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        if (mAnimationIsEntrance) {
            pw.print(prefix); pw.print(" mAnimationIsEntrance="); pw.print(mAnimationIsEntrance);
        }
        if (mSurfaceController != null) {
            mSurfaceController.dump(pw, prefix, dumpAll);
        }
        if (dumpAll) {
            pw.print(prefix); pw.print("mDrawState="); pw.print(drawStateToString());
            pw.print(prefix); pw.print(" mLastHidden="); pw.println(mLastHidden);
            pw.print(prefix); pw.print("mEnterAnimationPending=" + mEnterAnimationPending);
            pw.print(prefix); pw.print("mSystemDecorRect="); mSystemDecorRect.printShortString(pw);

            pw.println();
        }

        if (mShownAlpha != 1 || mAlpha != 1 || mLastAlpha != 1) {
            pw.print(prefix); pw.print("mShownAlpha="); pw.print(mShownAlpha);
                    pw.print(" mAlpha="); pw.print(mAlpha);
                    pw.print(" mLastAlpha="); pw.println(mLastAlpha);
        }
        if (mHaveMatrix || mWin.mGlobalScale != 1) {
            pw.print(prefix); pw.print("mGlobalScale="); pw.print(mWin.mGlobalScale);
                    pw.print(" mDsDx="); pw.print(mDsDx);
                    pw.print(" mDtDx="); pw.print(mDtDx);
                    pw.print(" mDtDy="); pw.print(mDtDy);
                    pw.print(" mDsDy="); pw.println(mDsDy);
        }
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer("WindowStateAnimator{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        sb.append(mWin.mAttrs.getTitle());
        sb.append('}');
        return sb.toString();
    }

    boolean getShown() {
        if (mSurfaceController != null) {
            return mSurfaceController.getShown();
        }
        return false;
    }

    void destroySurface(SurfaceControl.Transaction t) {
        try {
            if (mSurfaceController != null) {
                mSurfaceController.destroy(t);
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Exception thrown when destroying surface " + this
                    + " surface " + mSurfaceController + " session " + mSession + ": " + e);
        } finally {
            mWin.setHasSurface(false);
            mSurfaceController = null;
            mDrawState = NO_SURFACE;
        }
    }

    SurfaceControl getSurfaceControl() {
        if (!hasSurface()) {
            return null;
        }
        return mSurfaceController.mSurfaceControl;
    }
}
