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

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
import static android.view.WindowManager.LayoutParams.FLAG_SCALED;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManager.TRANSIT_NONE;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.DragResizeMode.DRAG_RESIZE_MODE_FREEFORM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW_VERBOSE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_CROP;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_SURFACE_ALLOC;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.TYPE_LAYER_MULTIPLIER;
import static com.android.server.wm.WindowManagerService.logWithStack;
import static com.android.server.wm.WindowStateAnimatorProto.DRAW_STATE;
import static com.android.server.wm.WindowStateAnimatorProto.LAST_CLIP_RECT;
import static com.android.server.wm.WindowStateAnimatorProto.SURFACE;
import static com.android.server.wm.WindowStateAnimatorProto.SYSTEM_DECOR_RECT;
import static com.android.server.wm.WindowSurfacePlacer.SET_ORIENTATION_CHANGE_COMPLETE;
import static com.android.server.wm.utils.CoordinateTransforms.transformToRotation;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
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

import com.android.server.policy.WindowManagerPolicy;

import java.io.PrintWriter;

/**
 * Keep track of animations and surface operations for a single WindowState.
 **/
class WindowStateAnimator {
    static final String TAG = TAG_WITH_CLASS_NAME ? "WindowStateAnimator" : TAG_WM;
    static final int WINDOW_FREEZE_LAYER = TYPE_LAYER_MULTIPLIER * 200;

    /**
     * Mode how the window gets clipped by the stack bounds during an animation: The clipping should
     * be applied after applying the animation transformation, i.e. the stack bounds don't move
     * during the animation.
     */
    static final int STACK_CLIP_AFTER_ANIM = 0;

    /**
     * Mode how the window gets clipped by the stack bounds: The clipping should be applied before
     * applying the animation transformation, i.e. the stack bounds move with the window.
     */
    static final int STACK_CLIP_BEFORE_ANIM = 1;

    /**
     * Mode how window gets clipped by the stack bounds during an animation: Don't clip the window
     * by the stack bounds.
     */
    static final int STACK_CLIP_NONE = 2;

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
    int mAnimLayer;
    int mLastLayer;

    /**
     * Set when we have changed the size of the surface, to know that
     * we must tell them application to resize (and thus redraw itself).
     */
    boolean mSurfaceResized;
    /**
     * Whether we should inform the client on next relayoutWindow that
     * the surface has been resized since last time.
     */
    boolean mReportSurfaceResized;
    WindowSurfaceController mSurfaceController;
    private WindowSurfaceController mPendingDestroySurface;

    /**
     * Set if the client has asked that the destroy of its surface be delayed
     * until it explicitly says it is okay.
     */
    boolean mSurfaceDestroyDeferred;

    private boolean mDestroyPreservedSurfaceUponRedraw;
    float mShownAlpha = 0;
    float mAlpha = 0;
    float mLastAlpha = 0;

    Rect mTmpClipRect = new Rect();
    Rect mTmpFinalClipRect = new Rect();
    Rect mLastClipRect = new Rect();
    Rect mLastFinalClipRect = new Rect();
    Rect mTmpStackBounds = new Rect();
    private Rect mTmpAnimatingBounds = new Rect();
    private Rect mTmpSourceBounds = new Rect();

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

    private boolean mAnimationStartDelayed;

    private final SurfaceControl.Transaction mTmpTransaction = new SurfaceControl.Transaction();

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

    boolean mForceScaleUntilResize;

    // WindowState.mHScale and WindowState.mVScale contain the
    // scale according to client specified layout parameters (e.g.
    // one layout size, with another surface size, creates such scaling).
    // Here we track an additional scaling factor used to follow stack
    // scaling (as in the case of the Pinned stack animation).
    float mExtraHScale = (float) 1.0;
    float mExtraVScale = (float) 1.0;

    // An offset in pixel of the surface contents from the window position. Used for Wallpaper
    // to provide the effect of scrolling within a large surface. We just use these values as
    // a cache.
    int mXOffset = 0;
    int mYOffset = 0;

    /**
     * A flag to determine if the WSA needs to offset its position to compensate for the stack's
     * position update before the WSA surface has resized.
     */
    private boolean mOffsetPositionForStackResize;

    private final Rect mTmpSize = new Rect();

    private final SurfaceControl.Transaction mReparentTransaction = new SurfaceControl.Transaction();

    // Used to track whether we have called detach children on the way to invisibility, in which
    // case we need to give the client a new Surface if it lays back out to a visible state.
    boolean mChildrenDetached = false;

    // Set to true after the first frame of the Pinned stack animation
    // and reset after the last to ensure we only reset mForceScaleUntilResize
    // once per animation.
    boolean mPipAnimationStarted = false;

    private final Point mTmpPos = new Point();

    WindowStateAnimator(final WindowState win) {
        final WindowManagerService service = win.mService;

        mService = service;
        mAnimator = service.mAnimator;
        mPolicy = service.mPolicy;
        mContext = service.mContext;

        mWin = win;
        mSession = win.mSession;
        mAttrType = win.mAttrs.type;
        mIsWallpaper = win.mIsWallpaper;
        mWallpaperControllerLocked = mService.mRoot.mWallpaperController;
    }

    /**
     * Is the window or its container currently set to animate or currently animating?
     */
    boolean isAnimationSet() {
        return mWin.isAnimating();
    }

    void cancelExitAnimationForNextAnimationLocked() {
        if (DEBUG_ANIM) Slog.d(TAG,
                "cancelExitAnimationForNextAnimationLocked: " + mWin);

        mWin.cancelAnimation();
        mWin.destroySurfaceUnchecked();
    }

    void onAnimationFinished() {
        // Done animating, clean up.
        if (DEBUG_ANIM) Slog.v(
                TAG, "Animation done in " + this + ": exiting=" + mWin.mAnimatingExit
                        + ", reportedVisible="
                        + (mWin.mAppToken != null ? mWin.mAppToken.reportedVisible : false));

        if (mAnimator.mWindowDetachedWallpaper == mWin) {
            mAnimator.mWindowDetachedWallpaper = null;
        }

        mWin.checkPolicyVisibilityChange();
        final DisplayContent displayContent = mWin.getDisplayContent();
        if (mAttrType == LayoutParams.TYPE_STATUS_BAR && mWin.mPolicyVisibility) {
            // Upon completion of a not-visible to visible status bar animation a relayout is
            // required.
            if (displayContent != null) {
                displayContent.setLayoutNeeded();
            }
        }

        mWin.onExitAnimationDone();
        final int displayId = mWin.getDisplayId();
        int pendingLayoutChanges = FINISH_LAYOUT_REDO_ANIM;
        if (displayContent.mWallpaperController.isWallpaperTarget(mWin)) {
            pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
        }
        mAnimator.setPendingLayoutChanges(displayId, pendingLayoutChanges);
        if (DEBUG_LAYOUT_REPEATS)
            mService.mWindowPlacerLocked.debugLayoutRepeats(
                    "WindowStateAnimator", mAnimator.getPendingLayoutChanges(displayId));

        if (mWin.mAppToken != null) {
            mWin.mAppToken.updateReportedVisibilityLocked();
        }
    }

    void hide(SurfaceControl.Transaction transaction, String reason) {
        if (!mLastHidden) {
            //dump();
            mLastHidden = true;

            // We may have a preserved surface which we no longer need. If there was a quick
            // VISIBLE, GONE, VISIBLE, GONE sequence, the surface may never draw, so we don't mark
            // it to be destroyed in prepareSurfaceLocked.
            markPreservedSurfaceForDestroy();

            if (mSurfaceController != null) {
                mSurfaceController.hide(transaction, reason);
            }
        }
    }

    void hide(String reason) {
        hide(mTmpTransaction, reason);
        SurfaceControl.mergeToGlobalTransaction(mTmpTransaction);
    }

    boolean finishDrawingLocked() {
        final boolean startingWindow =
                mWin.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
        if (DEBUG_STARTING_WINDOW && startingWindow) {
            Slog.v(TAG, "Finishing drawing window " + mWin + ": mDrawState="
                    + drawStateToString());
        }

        boolean layoutNeeded = false;

        if (mDrawState == DRAW_PENDING) {
            if (DEBUG_ANIM || SHOW_TRANSACTIONS || DEBUG_ORIENTATION)
                Slog.v(TAG, "finishDrawingLocked: mDrawState=COMMIT_DRAW_PENDING " + mWin + " in "
                        + mSurfaceController);
            if (DEBUG_STARTING_WINDOW && startingWindow) {
                Slog.v(TAG, "Draw state now committed in " + mWin);
            }
            mDrawState = COMMIT_DRAW_PENDING;
            layoutNeeded = true;
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
        final AppWindowToken atoken = mWin.mAppToken;
        if (atoken == null || atoken.canShowWindows()
                || mWin.mAttrs.type == TYPE_APPLICATION_STARTING) {
            result = mWin.performShowLocked();
        }
        return result;
    }

    void preserveSurfaceLocked() {
        if (mDestroyPreservedSurfaceUponRedraw) {
            // This could happen when switching the surface mode very fast. For example,
            // we preserved a surface when dragResizing changed to true. Then before the
            // preserved surface is removed, dragResizing changed to false again.
            // In this case, we need to leave the preserved surface alone, and destroy
            // the actual surface, so that the createSurface call could create a surface
            // of the proper size. The preserved surface will still be removed when client
            // finishes drawing to the new surface.
            mSurfaceDestroyDeferred = false;
            destroySurfaceLocked();
            mSurfaceDestroyDeferred = true;
            return;
        }
        if (SHOW_TRANSACTIONS) WindowManagerService.logSurface(mWin, "SET FREEZE LAYER", false);
        if (mSurfaceController != null) {
            // Our SurfaceControl is always at layer 0 within the parent Surface managed by
            // window-state. We want this old Surface to stay on top of the new one
            // until we do the swap, so we place it at layer 1.
            mSurfaceController.mSurfaceControl.setLayer(1);
        }
        mDestroyPreservedSurfaceUponRedraw = true;
        mSurfaceDestroyDeferred = true;
        destroySurfaceLocked();
    }

    void destroyPreservedSurfaceLocked() {
        if (!mDestroyPreservedSurfaceUponRedraw) {
            return;
        }
        if (mSurfaceController != null) {
            if (mPendingDestroySurface != null) {
                // If we are preserving a surface but we aren't relaunching that means
                // we are just doing an in-place switch. In that case any SurfaceFlinger side
                // child layers need to be reparented to the new surface to make this
                // transparent to the app.
                if (mWin.mAppToken == null || mWin.mAppToken.isRelaunching() == false) {
                    mReparentTransaction.reparentChildren(mPendingDestroySurface.mSurfaceControl,
                            mSurfaceController.mSurfaceControl.getHandle())
                            .apply();
                }
            }
        }

        destroyDeferredSurfaceLocked();
        mDestroyPreservedSurfaceUponRedraw = false;
    }

    void markPreservedSurfaceForDestroy() {
        if (mDestroyPreservedSurfaceUponRedraw
                && !mService.mDestroyPreservedSurface.contains(mWin)) {
            mService.mDestroyPreservedSurface.add(mWin);
        }
    }

    private int getLayerStack() {
        return mWin.getDisplayContent().getDisplay().getLayerStack();
    }

    void resetDrawState() {
        mDrawState = DRAW_PENDING;

        if (mWin.mAppToken == null) {
            return;
        }

        if (!mWin.mAppToken.isSelfAnimating()) {
            mWin.mAppToken.clearAllDrawn();
        } else {
            // Currently animating, persist current state of allDrawn until animation
            // is complete.
            mWin.mAppToken.deferClearAllDrawn = true;
        }
    }

    WindowSurfaceController createSurfaceLocked(int windowType, int ownerUid) {
        final WindowState w = mWin;

        if (mSurfaceController != null) {
            return mSurfaceController;
        }
        mChildrenDetached = false;

        if ((mWin.mAttrs.privateFlags & PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY) != 0) {
            windowType = SurfaceControl.WINDOW_TYPE_DONT_SCREENSHOT;
        }

        w.setHasSurface(false);

        if (DEBUG_ANIM || DEBUG_ORIENTATION) Slog.i(TAG,
                "createSurface " + this + ": mDrawState=DRAW_PENDING");

        resetDrawState();

        mService.makeWindowFreezingScreenIfNeededLocked(w);

        int flags = SurfaceControl.HIDDEN;
        final WindowManager.LayoutParams attrs = w.mAttrs;

        if (mService.isSecureLocked(w)) {
            flags |= SurfaceControl.SECURE;
        }

        mTmpSize.set(0, 0, 0, 0);
        calculateSurfaceBounds(w, attrs);
        final int width = mTmpSize.width();
        final int height = mTmpSize.height();

        if (DEBUG_VISIBILITY) {
            Slog.v(TAG, "Creating surface in session "
                    + mSession.mSurfaceSession + " window " + this
                    + " w=" + width + " h=" + height
                    + " x=" + mTmpSize.left + " y=" + mTmpSize.top
                    + " format=" + attrs.format + " flags=" + flags);
        }

        // We may abort, so initialize to defaults.
        mLastClipRect.set(0, 0, 0, 0);

        // Set up surface control with initial size.
        try {

            final boolean isHwAccelerated = (attrs.flags & FLAG_HARDWARE_ACCELERATED) != 0;
            final int format = isHwAccelerated ? PixelFormat.TRANSLUCENT : attrs.format;
            if (!PixelFormat.formatHasAlpha(attrs.format)
                    // Don't make surface with surfaceInsets opaque as they display a
                    // translucent shadow.
                    && attrs.surfaceInsets.left == 0
                    && attrs.surfaceInsets.top == 0
                    && attrs.surfaceInsets.right == 0
                    && attrs.surfaceInsets.bottom == 0
                    // Don't make surface opaque when resizing to reduce the amount of
                    // artifacts shown in areas the app isn't drawing content to.
                    && !w.isDragResizing()) {
                flags |= SurfaceControl.OPAQUE;
            }

            mSurfaceController = new WindowSurfaceController(mSession.mSurfaceSession,
                    attrs.getTitle().toString(), width, height, format, flags, this,
                    windowType, ownerUid);

            setOffsetPositionForStackResize(false);
            mSurfaceFormat = format;

            w.setHasSurface(true);

            if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) {
                Slog.i(TAG, "  CREATE SURFACE "
                        + mSurfaceController + " IN SESSION "
                        + mSession.mSurfaceSession
                        + ": pid=" + mSession.mPid + " format="
                        + attrs.format + " flags=0x"
                        + Integer.toHexString(flags)
                        + " / " + this);
            }
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

        if (WindowManagerService.localLOGV) Slog.v(TAG, "Got surface: " + mSurfaceController
                + ", set left=" + w.mFrame.left + " top=" + w.mFrame.top
                + ", animLayer=" + mAnimLayer);

        if (SHOW_LIGHT_TRANSACTIONS) {
            Slog.i(TAG, ">>> OPEN TRANSACTION createSurfaceLocked");
            WindowManagerService.logSurface(w, "CREATE pos=("
                    + w.mFrame.left + "," + w.mFrame.top + ") ("
                    + width + "x" + height + "), layer=" + mAnimLayer + " HIDE", false);
        }

        mLastHidden = true;

        if (WindowManagerService.localLOGV) Slog.v(TAG, "Created surface " + this);
        return mSurfaceController;
    }

    private void calculateSurfaceBounds(WindowState w, LayoutParams attrs) {
        if ((attrs.flags & FLAG_SCALED) != 0) {
            // For a scaled surface, we always want the requested size.
            mTmpSize.right = mTmpSize.left + w.mRequestedWidth;
            mTmpSize.bottom = mTmpSize.top + w.mRequestedHeight;
        } else {
            // When we're doing a drag-resizing, request a surface that's fullscreen size,
            // so that we don't need to reallocate during the process. This also prevents
            // buffer drops due to size mismatch.
            if (w.isDragResizing()) {
                if (w.getResizeMode() == DRAG_RESIZE_MODE_FREEFORM) {
                    mTmpSize.left = 0;
                    mTmpSize.top = 0;
                }
                final DisplayInfo displayInfo = w.getDisplayInfo();
                mTmpSize.right = mTmpSize.left + displayInfo.logicalWidth;
                mTmpSize.bottom = mTmpSize.top + displayInfo.logicalHeight;
            } else {
                mTmpSize.right = mTmpSize.left + w.mCompatFrame.width();
                mTmpSize.bottom = mTmpSize.top + w.mCompatFrame.height();
            }
        }

        // Something is wrong and SurfaceFlinger will not like this, try to revert to sane values.
        // This doesn't necessarily mean that there is an error in the system. The sizes might be
        // incorrect, because it is before the first layout or draw.
        if (mTmpSize.width() < 1) {
            mTmpSize.right = mTmpSize.left + 1;
        }
        if (mTmpSize.height() < 1) {
            mTmpSize.bottom = mTmpSize.top + 1;
        }

        // Adjust for surface insets.
        mTmpSize.left -= attrs.surfaceInsets.left;
        mTmpSize.top -= attrs.surfaceInsets.top;
        mTmpSize.right += attrs.surfaceInsets.right;
        mTmpSize.bottom += attrs.surfaceInsets.bottom;
    }

    boolean hasSurface() {
        return mSurfaceController != null && mSurfaceController.hasSurface();
    }

    void destroySurfaceLocked() {
        final AppWindowToken wtoken = mWin.mAppToken;
        if (wtoken != null) {
            if (mWin == wtoken.startingWindow) {
                wtoken.startingDisplayed = false;
            }
        }

        if (mSurfaceController == null) {
            return;
        }

        // When destroying a surface we want to make sure child windows are hidden. If we are
        // preserving the surface until redraw though we intend to swap it out with another surface
        // for resizing. In this case the window always remains visible to the user and the child
        // windows should likewise remain visible.
        if (!mDestroyPreservedSurfaceUponRedraw) {
            mWin.mHidden = true;
        }

        try {
            if (DEBUG_VISIBILITY) logWithStack(TAG, "Window " + this + " destroying surface "
                    + mSurfaceController + ", session " + mSession);
            if (mSurfaceDestroyDeferred) {
                if (mSurfaceController != null && mPendingDestroySurface != mSurfaceController) {
                    if (mPendingDestroySurface != null) {
                        if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) {
                            WindowManagerService.logSurface(mWin, "DESTROY PENDING", true);
                        }
                        mPendingDestroySurface.destroyNotInTransaction();
                    }
                    mPendingDestroySurface = mSurfaceController;
                }
            } else {
                if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) {
                    WindowManagerService.logSurface(mWin, "DESTROY", true);
                }
                destroySurface();
            }
            // Don't hide wallpaper if we're deferring the surface destroy
            // because of a surface change.
            if (!mDestroyPreservedSurfaceUponRedraw) {
                mWallpaperControllerLocked.hideWallpapers(mWin);
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Exception thrown when destroying Window " + this
                + " surface " + mSurfaceController + " session " + mSession + ": " + e.toString());
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

    void destroyDeferredSurfaceLocked() {
        try {
            if (mPendingDestroySurface != null) {
                if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) {
                    WindowManagerService.logSurface(mWin, "DESTROY PENDING", true);
                }
                mPendingDestroySurface.destroyNotInTransaction();
                // Don't hide wallpaper if we're destroying a deferred surface
                // after a surface mode change.
                if (!mDestroyPreservedSurfaceUponRedraw) {
                    mWallpaperControllerLocked.hideWallpapers(mWin);
                }
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Exception thrown when destroying Window "
                    + this + " surface " + mPendingDestroySurface
                    + " session " + mSession + ": " + e.toString());
        }
        mSurfaceDestroyDeferred = false;
        mPendingDestroySurface = null;
    }

    void computeShownFrameLocked() {
        final int displayId = mWin.getDisplayId();
        final ScreenRotationAnimation screenRotationAnimation =
                mAnimator.getScreenRotationAnimationLocked(displayId);
        final boolean windowParticipatesInScreenRotationAnimation =
                !mWin.mForceSeamlesslyRotate;
        final boolean screenAnimation = screenRotationAnimation != null
                && screenRotationAnimation.isAnimating()
                && windowParticipatesInScreenRotationAnimation;

        if (screenAnimation) {
            // cache often used attributes locally
            final Rect frame = mWin.mFrame;
            final float tmpFloats[] = mService.mTmpFloats;
            final Matrix tmpMatrix = mWin.mTmpMatrix;

            // Compute the desired transformation.
            if (screenRotationAnimation.isRotating()) {
                // If we are doing a screen animation, the global rotation
                // applied to windows can result in windows that are carefully
                // aligned with each other to slightly separate, allowing you
                // to see what is behind them.  An unsightly mess.  This...
                // thing...  magically makes it call good: scale each window
                // slightly (two pixels larger in each dimension, from the
                // window's center).
                final float w = frame.width();
                final float h = frame.height();
                if (w>=1 && h>=1) {
                    tmpMatrix.setScale(1 + 2/w, 1 + 2/h, w/2, h/2);
                } else {
                    tmpMatrix.reset();
                }
            } else {
                tmpMatrix.reset();
            }

            tmpMatrix.postScale(mWin.mGlobalScale, mWin.mGlobalScale);

            // WindowState.prepareSurfaces expands for surface insets (in order they don't get
            // clipped by the WindowState surface), so we need to go into the other direction here.
            tmpMatrix.postTranslate(mWin.mAttrs.surfaceInsets.left,
                    mWin.mAttrs.surfaceInsets.top);


            // "convert" it into SurfaceFlinger's format
            // (a 2x2 matrix + an offset)
            // Here we must not transform the position of the surface
            // since it is already included in the transformation.
            //Slog.i(TAG_WM, "Transform: " + matrix);

            mHaveMatrix = true;
            tmpMatrix.getValues(tmpFloats);
            mDsDx = tmpFloats[Matrix.MSCALE_X];
            mDtDx = tmpFloats[Matrix.MSKEW_Y];
            mDtDy = tmpFloats[Matrix.MSKEW_X];
            mDsDy = tmpFloats[Matrix.MSCALE_Y];

            // Now set the alpha...  but because our current hardware
            // can't do alpha transformation on a non-opaque surface,
            // turn it off if we are running an animation that is also
            // transforming since it is more important to have that
            // animation be smooth.
            mShownAlpha = mAlpha;
            if (!mService.mLimitedAlphaCompositing
                    || (!PixelFormat.formatHasAlpha(mWin.mAttrs.format)
                    || (mWin.isIdentityMatrix(mDsDx, mDtDx, mDtDy, mDsDy)))) {
                //Slog.i(TAG_WM, "Applying alpha transform");
                if (screenAnimation) {
                    mShownAlpha *= screenRotationAnimation.getEnterTransformation().getAlpha();
                }
            } else {
                //Slog.i(TAG_WM, "Not applying alpha transform");
            }

            if ((DEBUG_ANIM || WindowManagerService.localLOGV)
                    && (mShownAlpha == 1.0 || mShownAlpha == 0.0)) Slog.v(
                    TAG, "computeShownFrameLocked: Animating " + this + " mAlpha=" + mAlpha
                    + " screen=" + (screenAnimation ?
                            screenRotationAnimation.getEnterTransformation().getAlpha() : "null"));
            return;
        } else if (mIsWallpaper && mService.mRoot.mWallpaperActionPending) {
            return;
        } else if (mWin.isDragResizeChanged()) {
            // This window is awaiting a relayout because user just started (or ended)
            // drag-resizing. The shown frame (which affects surface size and pos)
            // should not be updated until we get next finished draw with the new surface.
            // Otherwise one or two frames rendered with old settings would be displayed
            // with new geometry.
            return;
        }

        if (WindowManagerService.localLOGV) Slog.v(
                TAG, "computeShownFrameLocked: " + this +
                " not attached, mAlpha=" + mAlpha);

        mShownAlpha = mAlpha;
        mHaveMatrix = false;
        mDsDx = mWin.mGlobalScale;
        mDtDx = 0;
        mDtDy = 0;
        mDsDy = mWin.mGlobalScale;
    }

    /**
     * Calculate the window-space crop rect and fill clipRect.
     * @return true if clipRect has been filled otherwise, no window space crop should be applied.
     */
    private boolean calculateCrop(Rect clipRect) {
        final WindowState w = mWin;
        final DisplayContent displayContent = w.getDisplayContent();
        clipRect.setEmpty();

        if (displayContent == null) {
            return false;
        }

        if (w.inPinnedWindowingMode()) {
            return false;
        }

        // During forced seamless rotation, the surface bounds get updated with the crop in the
        // new rotation, which is not compatible with showing the surface in the old rotation.
        // To work around that we disable cropping for such windows, as it is not necessary anyways.
        if (w.mForceSeamlesslyRotate) {
            return false;
        }

        // If we're animating, the wallpaper should only
        // be updated at the end of the animation.
        if (w.mAttrs.type == TYPE_WALLPAPER) {
            return false;
        }

        if (DEBUG_WINDOW_CROP) Slog.d(TAG,
                "Updating crop win=" + w + " mLastCrop=" + mLastClipRect);

        w.calculatePolicyCrop(mSystemDecorRect);

        if (DEBUG_WINDOW_CROP) Slog.d(TAG, "Applying decor to crop win=" + w + " mDecorFrame="
                + w.mDecorFrame + " mSystemDecorRect=" + mSystemDecorRect);

        final Task task = w.getTask();
        final boolean fullscreen = w.fillsDisplay() || (task != null && task.isFullscreen());
        final boolean isFreeformResizing =
                w.isDragResizing() && w.getResizeMode() == DRAG_RESIZE_MODE_FREEFORM;

        // We use the clip rect as provided by the tranformation for non-fullscreen windows to
        // avoid premature clipping with the system decor rect.
        clipRect.set(mSystemDecorRect);
        if (DEBUG_WINDOW_CROP) Slog.d(TAG, "win=" + w + " Initial clip rect: " + clipRect
                + " fullscreen=" + fullscreen);

        w.expandForSurfaceInsets(clipRect);

        // The clip rect was generated assuming (0,0) as the window origin,
        // so we need to translate to match the actual surface coordinates.
        clipRect.offset(w.mAttrs.surfaceInsets.left, w.mAttrs.surfaceInsets.top);

        if (DEBUG_WINDOW_CROP) Slog.d(TAG,
                "win=" + w + " Clip rect after stack adjustment=" + clipRect);

        w.transformClipRectFromScreenToSurfaceSpace(clipRect);

        return true;
    }

    private void applyCrop(Rect clipRect, boolean recoveringMemory) {
        if (DEBUG_WINDOW_CROP) Slog.d(TAG, "applyCrop: win=" + mWin
                + " clipRect=" + clipRect);
        if (clipRect != null) {
            if (!clipRect.equals(mLastClipRect)) {
                mLastClipRect.set(clipRect);
                mSurfaceController.setCropInTransaction(clipRect, recoveringMemory);
            }
        } else {
            mSurfaceController.clearCropInTransaction(recoveringMemory);
        }
    }

    void setSurfaceBoundariesLocked(final boolean recoveringMemory) {
        if (mSurfaceController == null) {
            return;
        }

        final WindowState w = mWin;
        final LayoutParams attrs = mWin.getAttrs();
        final Task task = w.getTask();

        mTmpSize.set(0, 0, 0, 0);
        calculateSurfaceBounds(w, attrs);

        mExtraHScale = (float) 1.0;
        mExtraVScale = (float) 1.0;

        boolean wasForceScaled = mForceScaleUntilResize;
        boolean wasSeamlesslyRotated = w.mSeamlesslyRotated;

        // Once relayout has been called at least once, we need to make sure
        // we only resize the client surface during calls to relayout. For
        // clients which use indeterminate measure specs (MATCH_PARENT),
        // we may try and change their window size without a call to relayout.
        // However, this would be unsafe, as the client may be in the middle
        // of producing a frame at the old size, having just completed layout
        // to find the surface size changed underneath it.
        final boolean relayout = !w.mRelayoutCalled || w.mInRelayout;
        if (relayout) {
            mSurfaceResized = mSurfaceController.setSizeInTransaction(
                    mTmpSize.width(), mTmpSize.height(), recoveringMemory);
        } else {
            mSurfaceResized = false;
        }
        mForceScaleUntilResize = mForceScaleUntilResize && !mSurfaceResized;
        // If we are undergoing seamless rotation, the surface has already
        // been set up to persist at it's old location. We need to freeze
        // updates until a resize occurs.
        mService.markForSeamlessRotation(w, w.mSeamlesslyRotated && !mSurfaceResized);

        Rect clipRect = null;
        if (calculateCrop(mTmpClipRect)) {
            clipRect = mTmpClipRect;
        }

        float surfaceWidth = mSurfaceController.getWidth();
        float surfaceHeight = mSurfaceController.getHeight();

        final Rect insets = attrs.surfaceInsets;

        if (isForceScaled()) {
            int hInsets = insets.left + insets.right;
            int vInsets = insets.top + insets.bottom;
            float surfaceContentWidth = surfaceWidth - hInsets;
            float surfaceContentHeight = surfaceHeight - vInsets;
            if (!mForceScaleUntilResize) {
                mSurfaceController.forceScaleableInTransaction(true);
            }

            int posX = 0;
            int posY = 0;
            task.mStack.getDimBounds(mTmpStackBounds);

            boolean allowStretching = false;
            task.mStack.getFinalAnimationSourceHintBounds(mTmpSourceBounds);
            // If we don't have source bounds, we can attempt to use the content insets
            // in the following scenario:
            //    1. We have content insets.
            //    2. We are not transitioning to full screen
            // We have to be careful to check "lastAnimatingBoundsWasToFullscreen" rather than
            // the mBoundsAnimating state, as we may have already left it and only be here
            // because of the force-scale until resize state.
            if (mTmpSourceBounds.isEmpty() && (mWin.mLastRelayoutContentInsets.width() > 0
                    || mWin.mLastRelayoutContentInsets.height() > 0)
                        && !task.mStack.lastAnimatingBoundsWasToFullscreen()) {
                mTmpSourceBounds.set(task.mStack.mPreAnimationBounds);
                mTmpSourceBounds.inset(mWin.mLastRelayoutContentInsets);
                allowStretching = true;
            }

            // Make sure that what we're animating to and from is actually the right size in case
            // the window cannot take up the full screen.
            mTmpStackBounds.intersectUnchecked(w.mParentFrame);
            mTmpSourceBounds.intersectUnchecked(w.mParentFrame);
            mTmpAnimatingBounds.intersectUnchecked(w.mParentFrame);

            if (!mTmpSourceBounds.isEmpty()) {
                // Get the final target stack bounds, if we are not animating, this is just the
                // current stack bounds
                task.mStack.getFinalAnimationBounds(mTmpAnimatingBounds);

                // Calculate the current progress and interpolate the difference between the target
                // and source bounds
                float finalWidth = mTmpAnimatingBounds.width();
                float initialWidth = mTmpSourceBounds.width();
                float tw = (surfaceContentWidth - mTmpStackBounds.width())
                        / (surfaceContentWidth - mTmpAnimatingBounds.width());
                float th = tw;
                mExtraHScale = (initialWidth + tw * (finalWidth - initialWidth)) / initialWidth;
                if (allowStretching) {
                    float finalHeight = mTmpAnimatingBounds.height();
                    float initialHeight = mTmpSourceBounds.height();
                    th = (surfaceContentHeight - mTmpStackBounds.height())
                        / (surfaceContentHeight - mTmpAnimatingBounds.height());
                    mExtraVScale = (initialHeight + tw * (finalHeight - initialHeight))
                            / initialHeight;
                } else {
                    mExtraVScale = mExtraHScale;
                }

                // Adjust the position to account for the inset bounds
                posX -= (int) (tw * mExtraHScale * mTmpSourceBounds.left);
                posY -= (int) (th * mExtraVScale * mTmpSourceBounds.top);

                // In pinned mode the clip rectangle applied to us by our stack has been
                // expanded outwards to allow for shadows. However in case of source bounds set
                // we need to crop to within the surface. The code above has scaled and positioned
                // the surface to fit the unexpanded stack bounds, but now we need to reapply
                // the cropping that the stack would have applied if it weren't expanded. This
                // can be different in each direction based on the source bounds.
                clipRect = mTmpClipRect;
                clipRect.set((int)((insets.left + mTmpSourceBounds.left) * tw),
                        (int)((insets.top + mTmpSourceBounds.top) * th),
                        insets.left + (int)(surfaceWidth
                                - (tw* (surfaceWidth - mTmpSourceBounds.right))),
                        insets.top + (int)(surfaceHeight
                                - (th * (surfaceHeight - mTmpSourceBounds.bottom))));
            } else {
                // We want to calculate the scaling based on the content area, not based on
                // the entire surface, so that we scale in sync with windows that don't have insets.
                mExtraHScale = mTmpStackBounds.width() / surfaceContentWidth;
                mExtraVScale = mTmpStackBounds.height() / surfaceContentHeight;

                // Since we are scaled to fit in our previously desired crop, we can now
                // expose the whole window in buffer space, and not risk extending
                // past where the system would have cropped us
                clipRect = null;
            }

            // In the case of ForceScaleToStack we scale entire tasks together,
            // and so we need to scale our offsets relative to the task bounds
            // or parent and child windows would fall out of alignment.
            posX -= (int) (attrs.x * (1 - mExtraHScale));
            posY -= (int) (attrs.y * (1 - mExtraVScale));

            // Imagine we are scaling down. As we scale the buffer down, we decrease the
            // distance between the surface top left, and the start of the surface contents
            // (previously it was surfaceInsets.left pixels in screen space but now it
            // will be surfaceInsets.left*mExtraHScale). This means in order to keep the
            // non inset content at the same position, we have to shift the whole window
            // forward. Likewise for scaling up, we've increased this distance, and we need
            // to shift by a negative number to compensate.
            posX += insets.left * (1 - mExtraHScale);
            posY += insets.top * (1 - mExtraVScale);

            mSurfaceController.setPositionInTransaction((float) Math.floor(posX),
                    (float) Math.floor(posY), recoveringMemory);

            // Various surfaces in the scaled stack may resize at different times.
            // We need to ensure for each surface, that we disable transformation matrix
            // scaling in the same transaction which we resize the surface in.
            // As we are in SCALING_MODE_SCALE_TO_WINDOW, SurfaceFlinger will
            // then take over the scaling until the new buffer arrives, and things
            // will be seamless.
            if (mPipAnimationStarted == false) {
                mForceScaleUntilResize = true;
                mPipAnimationStarted = true;
            }
        } else {
            mPipAnimationStarted = false;

            if (!w.mSeamlesslyRotated) {
                // Used to offset the WSA when stack position changes before a resize.
                int xOffset = mXOffset;
                int yOffset = mYOffset;
                if (mOffsetPositionForStackResize) {
                    if (relayout) {
                        // Once a relayout is called, reset the offset back to 0 and defer
                        // setting it until a new frame with the updated size. This ensures that
                        // the WS position is reset (so the stack position is shown) at the same
                        // time that the buffer size changes.
                        setOffsetPositionForStackResize(false);
                        mSurfaceController.deferTransactionUntil(mSurfaceController.getHandle(),
                                mWin.getFrameNumber());
                    } else {
                        final TaskStack stack = mWin.getStack();
                        mTmpPos.x = 0;
                        mTmpPos.y = 0;
                        if (stack != null) {
                            stack.getRelativePosition(mTmpPos);
                        }

                        xOffset = -mTmpPos.x;
                        yOffset = -mTmpPos.y;

                        // Crop also needs to be extended so the bottom isn't cut off when the WSA
                        // position is moved.
                        if (clipRect != null) {
                            clipRect.right += mTmpPos.x;
                            clipRect.bottom += mTmpPos.y;
                        }
                    }
                }
                mSurfaceController.setPositionInTransaction(xOffset, yOffset, recoveringMemory);
            }
        }

        // If we are ending the scaling mode. We switch to SCALING_MODE_FREEZE
        // to prevent further updates until buffer latch.
        // When ending both force scaling, and seamless rotation, we need to freeze
        // the Surface geometry until a buffer comes in at the new size (normally position and crop
        // are unfrozen). setGeometryAppliesWithResizeInTransaction accomplishes this for us.
        if ((wasForceScaled && !mForceScaleUntilResize) ||
                (wasSeamlesslyRotated && !w.mSeamlesslyRotated)) {
            mSurfaceController.setGeometryAppliesWithResizeInTransaction(true);
            mSurfaceController.forceScaleableInTransaction(false);
        }

        if (!w.mSeamlesslyRotated) {
            applyCrop(clipRect, recoveringMemory);
            mSurfaceController.setMatrixInTransaction(mDsDx * w.mHScale * mExtraHScale,
                    mDtDx * w.mVScale * mExtraVScale,
                    mDtDy * w.mHScale * mExtraHScale,
                    mDsDy * w.mVScale * mExtraVScale, recoveringMemory);
        }

        if (mSurfaceResized) {
            mReportSurfaceResized = true;
            mAnimator.setPendingLayoutChanges(w.getDisplayId(),
                    FINISH_LAYOUT_REDO_WALLPAPER);
        }
    }

    /**
     * Get rect of the task this window is currently in. If there is no task, rect will be set to
     * empty.
     */
    void getContainerRect(Rect rect) {
        final Task task = mWin.getTask();
        if (task != null) {
            task.getDimBounds(rect);
        } else {
            rect.left = rect.top = rect.right = rect.bottom = 0;
        }
    }

    void prepareSurfaceLocked(final boolean recoveringMemory) {
        final WindowState w = mWin;
        if (!hasSurface()) {

            // There is no need to wait for an animation change if our window is gone for layout
            // already as we'll never be visible.
            if (w.getOrientationChanging() && w.isGoneForLayoutLw()) {
                if (DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Orientation change skips hidden " + w);
                }
                w.setOrientationChanging(false);
            }
            return;
        }

        boolean displayed = false;

        computeShownFrameLocked();

        setSurfaceBoundariesLocked(recoveringMemory);

        if (mIsWallpaper && !w.mWallpaperVisible) {
            // Wallpaper is no longer visible and there is no wp target => hide it.
            hide("prepareSurfaceLocked");
        } else if (w.isParentWindowHidden() || !w.isOnScreen()) {
            hide("prepareSurfaceLocked");
            mWallpaperControllerLocked.hideWallpapers(w);

            // If we are waiting for this window to handle an orientation change. If this window is
            // really hidden (gone for layout), there is no point in still waiting for it.
            // Note that this does introduce a potential glitch if the window becomes unhidden
            // before it has drawn for the new orientation.
            if (w.getOrientationChanging() && w.isGoneForLayoutLw()) {
                w.setOrientationChanging(false);
                if (DEBUG_ORIENTATION) Slog.v(TAG,
                        "Orientation change skips hidden " + w);
            }
        } else if (mLastLayer != mAnimLayer
                || mLastAlpha != mShownAlpha
                || mLastDsDx != mDsDx
                || mLastDtDx != mDtDx
                || mLastDsDy != mDsDy
                || mLastDtDy != mDtDy
                || w.mLastHScale != w.mHScale
                || w.mLastVScale != w.mVScale
                || mLastHidden) {
            displayed = true;
            mLastAlpha = mShownAlpha;
            mLastLayer = mAnimLayer;
            mLastDsDx = mDsDx;
            mLastDtDx = mDtDx;
            mLastDsDy = mDsDy;
            mLastDtDy = mDtDy;
            w.mLastHScale = w.mHScale;
            w.mLastVScale = w.mVScale;
            if (SHOW_TRANSACTIONS) WindowManagerService.logSurface(w,
                    "controller=" + mSurfaceController +
                    "alpha=" + mShownAlpha + " layer=" + mAnimLayer
                    + " matrix=[" + mDsDx + "*" + w.mHScale
                    + "," + mDtDx + "*" + w.mVScale
                    + "][" + mDtDy + "*" + w.mHScale
                    + "," + mDsDy + "*" + w.mVScale + "]", false);

            boolean prepared =
                mSurfaceController.prepareToShowInTransaction(mShownAlpha,
                        mDsDx * w.mHScale * mExtraHScale,
                        mDtDx * w.mVScale * mExtraVScale,
                        mDtDy * w.mHScale * mExtraHScale,
                        mDsDy * w.mVScale * mExtraVScale,
                        recoveringMemory);

            if (prepared && mDrawState == HAS_DRAWN) {
                if (mLastHidden) {
                    if (showSurfaceRobustlyLocked()) {
                        markPreservedSurfaceForDestroy();
                        mAnimator.requestRemovalOfReplacedWindows(w);
                        mLastHidden = false;
                        if (mIsWallpaper) {
                            w.dispatchWallpaperVisibility(true);
                        }
                        if (!w.getDisplayContent().getLastHasContent()) {
                            // This draw means the difference between unique content and mirroring.
                            // Run another pass through performLayout to set mHasContent in the
                            // LogicalDisplay.
                            mAnimator.setPendingLayoutChanges(w.getDisplayId(),
                                    FINISH_LAYOUT_REDO_ANIM);
                            if (DEBUG_LAYOUT_REPEATS) {
                                mService.mWindowPlacerLocked.debugLayoutRepeats(
                                        "showSurfaceRobustlyLocked " + w,
                                        mAnimator.getPendingLayoutChanges(w.getDisplayId()));
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
            if (DEBUG_ANIM && isAnimationSet()) {
                Slog.v(TAG, "prepareSurface: No changes in animation for " + this);
            }
            displayed = true;
        }

        if (w.getOrientationChanging()) {
            if (!w.isDrawnLw()) {
                mAnimator.mBulkUpdateParams &= ~SET_ORIENTATION_CHANGE_COMPLETE;
                mAnimator.mLastWindowFreezeSource = w;
                if (DEBUG_ORIENTATION) Slog.v(TAG,
                        "Orientation continue waiting for draw in " + w);
            } else {
                w.setOrientationChanging(false);
                if (DEBUG_ORIENTATION) Slog.v(TAG, "Orientation change complete in " + w);
            }
        }

        if (displayed) {
            w.mToken.hasVisible = true;
        }
    }

    void setTransparentRegionHintLocked(final Region region) {
        if (mSurfaceController == null) {
            Slog.w(TAG, "setTransparentRegionHint: null mSurface after mHasSurface true");
            return;
        }
        mSurfaceController.setTransparentRegionHint(region);
    }

    boolean setWallpaperOffset(int dx, int dy) {
        if (mXOffset == dx && mYOffset == dy) {
            return false;
        }
        mXOffset = dx;
        mYOffset = dy;

        try {
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG, ">>> OPEN TRANSACTION setWallpaperOffset");
            mService.openSurfaceTransaction();
            mSurfaceController.setPositionInTransaction(dx, dy, false);
            applyCrop(null, false);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error positioning surface of " + mWin
                    + " pos=(" + dx + "," + dy + ")", e);
        } finally {
            mService.closeSurfaceTransaction("setWallpaperOffset");
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                    "<<< CLOSE TRANSACTION setWallpaperOffset");
            return true;
        }
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

    /**
     * Have the surface flinger show a surface, robustly dealing with
     * error conditions.  In particular, if there is not enough memory
     * to show the surface, then we will try to get rid of other surfaces
     * in order to succeed.
     *
     * @return Returns true if the surface was successfully shown.
     */
    private boolean showSurfaceRobustlyLocked() {
        if (mWin.getWindowConfiguration().windowsAreScaleable()) {
            mSurfaceController.forceScaleableInTransaction(true);
        }

        boolean shown = mSurfaceController.showRobustlyInTransaction();
        if (!shown)
            return false;

        // If we had a preserved surface it's no longer needed, and it may be harmful
        // if we are transparent.
        if (mPendingDestroySurface != null && mDestroyPreservedSurfaceUponRedraw) {
            mPendingDestroySurface.mSurfaceControl.hide();
            mPendingDestroySurface.reparentChildrenInTransaction(mSurfaceController);
        }

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
        applyAnimationLocked(transit, true);
        //TODO (multidisplay): Magnification is supported only for the default display.
        if (mService.mAccessibilityController != null
                && mWin.getDisplayId() == DEFAULT_DISPLAY) {
            mService.mAccessibilityController.onWindowTransitionLocked(mWin, transit);
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
        if (mWin.isSelfAnimating() && mAnimationIsEntrance == isEntrance) {
            // If we are trying to apply an animation, but already running
            // an animation of the same type, then just leave that one alone.
            return true;
        }

        if (isEntrance && mWin.mAttrs.type == TYPE_INPUT_METHOD) {
            mWin.getDisplayContent().adjustForImeIfNeeded();
            mWin.setDisplayLayoutNeeded();
            mService.mWindowPlacerLocked.requestTraversal();
        }

        // Only apply an animation if the display isn't frozen.  If it is
        // frozen, there is no reason to animate and it can cause strange
        // artifacts when we unfreeze the display if some different animation
        // is running.
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "WSA#applyAnimationLocked");
        if (mWin.mToken.okToAnimate()) {
            int anim = mPolicy.selectAnimationLw(mWin, transit);
            int attr = -1;
            Animation a = null;
            if (anim != 0) {
                a = anim != -1 ? AnimationUtils.loadAnimation(mContext, anim) : null;
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
                    a = mService.mAppTransition.loadAnimationAttr(mWin.mAttrs, attr, TRANSIT_NONE);
                }
            }
            if (DEBUG_ANIM) Slog.v(TAG,
                    "applyAnimation: win=" + this
                    + " anim=" + anim + " attr=0x" + Integer.toHexString(attr)
                    + " a=" + a
                    + " transit=" + transit
                    + " isEntrance=" + isEntrance + " Callers " + Debug.getCallers(3));
            if (a != null) {
                if (DEBUG_ANIM) logWithStack(TAG, "Loaded animation " + a + " for " + this);
                mWin.startAnimation(a);
                mAnimationIsEntrance = isEntrance;
            }
        } else {
            mWin.cancelAnimation();
        }

        if (!isEntrance && mWin.mAttrs.type == TYPE_INPUT_METHOD) {
            mWin.getDisplayContent().adjustForImeIfNeeded();
        }

        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
        return isAnimationSet();
    }

    void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        mLastClipRect.writeToProto(proto, LAST_CLIP_RECT);
        if (mSurfaceController != null) {
            mSurfaceController.writeToProto(proto, SURFACE);
        }
        proto.write(DRAW_STATE, mDrawState);
        mSystemDecorRect.writeToProto(proto, SYSTEM_DECOR_RECT);
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
            pw.print(prefix); pw.print("mSystemDecorRect="); mSystemDecorRect.printShortString(pw);
            pw.print(" mLastClipRect="); mLastClipRect.printShortString(pw);

            if (!mLastFinalClipRect.isEmpty()) {
                pw.print(" mLastFinalClipRect="); mLastFinalClipRect.printShortString(pw);
            }
            pw.println();
        }

        if (mPendingDestroySurface != null) {
            pw.print(prefix); pw.print("mPendingDestroySurface=");
                    pw.println(mPendingDestroySurface);
        }
        if (mSurfaceResized || mSurfaceDestroyDeferred) {
            pw.print(prefix); pw.print("mSurfaceResized="); pw.print(mSurfaceResized);
                    pw.print(" mSurfaceDestroyDeferred="); pw.println(mSurfaceDestroyDeferred);
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
        if (mAnimationStartDelayed) {
            pw.print(prefix); pw.print("mAnimationStartDelayed="); pw.print(mAnimationStartDelayed);
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

    void reclaimSomeSurfaceMemory(String operation, boolean secure) {
        mService.mRoot.reclaimSomeSurfaceMemory(this, operation, secure);
    }

    boolean getShown() {
        if (mSurfaceController != null) {
            return mSurfaceController.getShown();
        }
        return false;
    }

    void destroySurface() {
        try {
            if (mSurfaceController != null) {
                mSurfaceController.destroyNotInTransaction();
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

    void seamlesslyRotate(SurfaceControl.Transaction t, int oldRotation, int newRotation) {
        final WindowState w = mWin;

        // We rotated the screen, but have not received a new buffer with the correct size yet. In
        // the mean time, we rotate the buffer we have to the new orientation.
        final Matrix transform = mService.mTmpTransform;
        transformToRotation(oldRotation, newRotation, w.mFrame.width(), w.mFrame.height(),
                transform);
        transform.getValues(mService.mTmpFloats);

        float DsDx = mService.mTmpFloats[Matrix.MSCALE_X];
        float DtDx = mService.mTmpFloats[Matrix.MSKEW_Y];
        float DtDy = mService.mTmpFloats[Matrix.MSKEW_X];
        float DsDy = mService.mTmpFloats[Matrix.MSCALE_Y];
        float nx = mService.mTmpFloats[Matrix.MTRANS_X];
        float ny = mService.mTmpFloats[Matrix.MTRANS_Y];
        mSurfaceController.setPosition(t, nx, ny, false);
        mSurfaceController.setMatrix(t, DsDx * w.mHScale, DtDx * w.mVScale, DtDy
                * w.mHScale, DsDy * w.mVScale, false);
    }

    /** The force-scaled state for a given window can persist past
     * the state for it's stack as the windows complete resizing
     * independently of one another.
     */
    boolean isForceScaled() {
        final Task task = mWin.getTask();
        if (task != null && task.mStack.isForceScaled()) {
            return true;
        }
        return mForceScaleUntilResize;
    }

    void detachChildren() {
        if (mSurfaceController != null) {
            mSurfaceController.detachChildren();
        }
        mChildrenDetached = true;
    }

    int getLayer() {
        return mLastLayer;
    }

    void setOffsetPositionForStackResize(boolean offsetPositionForStackResize) {
        mOffsetPositionForStackResize = offsetPositionForStackResize;
    }
}
