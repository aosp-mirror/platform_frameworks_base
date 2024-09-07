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

import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.SurfaceControl.METADATA_OWNER_PID;
import static android.view.SurfaceControl.METADATA_OWNER_UID;
import static android.view.SurfaceControl.METADATA_WINDOW_TYPE;
import static android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.TRANSIT_OLD_NONE;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ANIM;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_DRAW;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_STARTING_WINDOW;
import static com.android.internal.protolog.ProtoLogGroup.WM_SHOW_SURFACE_ALLOC;
import static com.android.internal.protolog.ProtoLogGroup.WM_SHOW_TRANSACTIONS;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW_VERBOSE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.logWithStack;
import static com.android.server.wm.WindowStateAnimatorProto.DRAW_STATE;
import static com.android.server.wm.WindowStateAnimatorProto.SURFACE;
import static com.android.server.wm.WindowStateAnimatorProto.SYSTEM_DECOR_RECT;
import static com.android.server.wm.WindowSurfaceControllerProto.SHOWN;
import static com.android.window.flags.Flags.secureWindowState;
import static com.android.window.flags.Flags.setScPropertiesInClient;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Debug;
import android.os.Trace;
import android.util.EventLog;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import com.android.internal.protolog.ProtoLog;
import com.android.internal.protolog.common.LogLevel;
import com.android.server.policy.WindowManagerPolicy;

import java.io.PrintWriter;

/**
 * Keep track of animations and surface operations for a single WindowState.
 **/
class WindowStateAnimator {
    static final String TAG = TAG_WITH_CLASS_NAME ? "WindowStateAnimator" : TAG_WM;
    static final int PRESERVED_SURFACE_LAYER = 1;

    /**
     * Mode how the window gets clipped by the root task bounds during an animation: The clipping
     * should be applied after applying the animation transformation, i.e. the root task bounds
     * don't move during the animation.
     */
    static final int ROOT_TASK_CLIP_AFTER_ANIM = 0;

    /**
     * Mode how window gets clipped by the root task bounds during an animation: Don't clip the
     * window by the root task bounds.
     */
    static final int ROOT_TASK_CLIP_NONE = 1;

    // Unchanging local convenience fields.
    final WindowManagerService mService;
    final WindowState mWin;
    final WindowAnimator mAnimator;
    final Session mSession;
    final WindowManagerPolicy mPolicy;
    final Context mContext;
    private final WallpaperController mWallpaperControllerLocked;

    boolean mAnimationIsEntrance;

    SurfaceControl mSurfaceControl;
    private boolean mSurfaceShown;
    private String mTitle;

    float mShownAlpha = 0;
    float mAlpha = 0;
    float mLastAlpha = 0;

    /**
     * This is rectangle of the window's surface that is not covered by
     * system decorations.
     */
    private final Rect mSystemDecorRect = new Rect();

    // Set to true if, when the window gets displayed, it should perform
    // an enter animation.
    boolean mEnterAnimationPending;

    /** Used to indicate that this window is undergoing an enter animation. Used for system
     * windows to make the callback to View.dispatchOnWindowShownCallback(). Set when the
     * window is first added or shown, cleared when the callback has been made. */
    boolean mEnteringAnimation;

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

    WindowStateAnimator(final WindowState win) {
        final WindowManagerService service = win.mWmService;

        mService = service;
        mAnimator = service.mAnimator;
        mPolicy = service.mPolicy;
        mContext = service.mContext;

        mWin = win;
        mSession = win.mSession;
        mAttrType = win.mAttrs.type;
        mWallpaperControllerLocked = win.getDisplayContent().mWallpaperController;
    }

    void onAnimationFinished() {
        // Done animating, clean up.
        ProtoLog.v(WM_DEBUG_ANIM, "Animation done in %s: exiting=%b, reportedVisible=%b",
                this, mWin.mAnimatingExit,
                (mWin.mActivityRecord != null && mWin.mActivityRecord.reportedVisible));

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
        if (mLastHidden) {
            return;
        }
        mLastHidden = true;
        if (mSurfaceControl == null || !mSurfaceShown) {
            return;
        }
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE HIDE ( %s ): %s", reason, mTitle);

        setShown(false);
        transaction.hide(mSurfaceControl);
        if (mWin.mIsWallpaper) {
            final DisplayContent dc = mWin.getDisplayContent();
            EventLog.writeEvent(EventLogTags.WM_WALLPAPER_SURFACE,
                    dc.mDisplayId, 0 /* request hidden */,
                    String.valueOf(dc.mWallpaperController.getWallpaperTarget()));
        }
    }

    private void setShown(boolean surfaceShown) {
        mSurfaceShown = surfaceShown;
        mService.updateNonSystemOverlayWindowsVisibilityIfNeeded(mWin, surfaceShown);
        mWin.onSurfaceShownChanged(surfaceShown);
        mSession.onWindowSurfaceVisibilityChanged(mWin, mSurfaceShown);
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
                    mSurfaceControl);
            if (startingWindow) {
                ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Draw state now committed in %s", mWin);
            }
            mDrawState = COMMIT_DRAW_PENDING;
            layoutNeeded = true;
        }

        if (postDrawTransaction != null) {
            mWin.getSyncTransaction().merge(postDrawTransaction);
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
        ProtoLog.i(WM_DEBUG_ANIM, "commitFinishDrawingLocked: mDrawState=READY_TO_SHOW %s",
                mSurfaceControl);
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

    SurfaceControl createSurfaceLocked() {
        final WindowState w = mWin;

        if (mSurfaceControl != null) {
            return mSurfaceControl;
        }

        w.setHasSurface(false);

        ProtoLog.i(WM_DEBUG_ANIM, "createSurface %s: mDrawState=DRAW_PENDING", this);

        resetDrawState();

        mService.makeWindowFreezingScreenIfNeededLocked(w);

        int flags = SurfaceControl.HIDDEN;
        final WindowManager.LayoutParams attrs = w.mAttrs;

        if (!secureWindowState()) {
            if (w.isSecureLocked()) {
                flags |= SurfaceControl.SECURE;
            }
        }

        if ((mWin.mAttrs.privateFlags & PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY) != 0) {
            flags |= SurfaceControl.SKIP_SCREENSHOT;
        }

        if (DEBUG_VISIBILITY) {
            Slog.v(TAG, "Creating surface " + this
                    + " format=" + attrs.format + " flags=" + flags);
        }

        // Set up surface control with initial size.
        try {

            // This can be removed once we move all Buffer Layers to use BLAST.
            final boolean isHwAccelerated = (attrs.flags & FLAG_HARDWARE_ACCELERATED) != 0;
            final int format = isHwAccelerated ? PixelFormat.TRANSLUCENT : attrs.format;

            mTitle = attrs.getTitle().toString();
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "new SurfaceControl");
            mSurfaceControl = mWin.makeSurface()
                    .setParent(mWin.mSurfaceControl)
                    .setName(mTitle)
                    .setFormat(format)
                    .setFlags(flags)
                    .setMetadata(METADATA_WINDOW_TYPE, attrs.type)
                    .setMetadata(METADATA_OWNER_UID, mSession.mUid)
                    .setMetadata(METADATA_OWNER_PID, mSession.mPid)
                    .setCallsite("WindowSurfaceController")
                    .setBLASTLayer().build();
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);

            if (!setScPropertiesInClient()) {
                setColorSpaceAgnosticLocked(
                        (attrs.privateFlags & LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC) != 0);
            }

            w.setHasSurface(true);
            // The surface instance is changed. Make sure the input info can be applied to the
            // new surface, e.g. relaunch activity.
            w.mInputWindowHandle.forceChange();

            ProtoLog.i(WM_SHOW_SURFACE_ALLOC,
                    "  CREATE SURFACE %s: pid=%d format=%d flags=0x%x / %s",
                    mSurfaceControl, mSession.mPid, attrs.format, flags, this);
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
            Slog.v(TAG, "Got surface: " + mSurfaceControl
                    + ", set left=" + w.getFrame().left + " top=" + w.getFrame().top);
        }

        if (SHOW_LIGHT_TRANSACTIONS) {
            Slog.i(TAG, ">>> OPEN TRANSACTION createSurfaceLocked");
            WindowManagerService.logSurface(w, "CREATE pos=("
                    + w.getFrame().left + "," + w.getFrame().top + ") HIDE", false);
        }

        mLastHidden = true;

        if (DEBUG) Slog.v(TAG, "Created surface " + this);
        return mSurfaceControl;
    }

    boolean hasSurface() {
        return mSurfaceControl != null;
    }

    void getSurfaceControl(SurfaceControl outSurfaceControl) {
        outSurfaceControl.copyFrom(mSurfaceControl, "WindowStateAnimator.getSurfaceControl");
    }

    void destroySurfaceLocked(SurfaceControl.Transaction t) {
        if (mSurfaceControl == null) {
            return;
        }

        mWin.mHidden = true;

        try {
            if (DEBUG_VISIBILITY) {
                logWithStack(TAG, "Window " + this + " destroying surface "
                        + mSurfaceControl + ", session " + mSession);
            }
            ProtoLog.i(WM_SHOW_SURFACE_ALLOC, "SURFACE DESTROY: %s. %s",
                    mWin, new RuntimeException().fillInStackTrace());
            destroySurface(t);
            if (mService.mFlags.mEnsureWallpaperInTransitions) {
                if (mWallpaperControllerLocked.isWallpaperTarget(mWin)) {
                    mWin.requestUpdateWallpaperIfNeeded();
                }
            } else {
                mWallpaperControllerLocked.hideWallpapers(mWin);
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Exception thrown when destroying Window " + this
                    + " surface " + mSurfaceControl + " session " + mSession + ": "
                    + e.toString());
        }
    }

    void computeShownFrameLocked() {
        if (mWin.mIsWallpaper && mService.mRoot.mWallpaperActionPending) {
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

        computeShownFrameLocked();

        if (!w.isOnScreen()) {
            hide(t, "prepareSurfaceLocked");
            if (!w.mIsWallpaper || !mService.mFlags.mEnsureWallpaperInTransitions) {
                mWallpaperControllerLocked.hideWallpapers(w);
            }

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
                || mLastHidden) {
            mLastAlpha = mShownAlpha;
            ProtoLog.i(WM_SHOW_TRANSACTIONS,
                    "SURFACE controller=%s alpha=%f HScale=%f, VScale=%f: %s",
                    mSurfaceControl, mShownAlpha, w.mHScale, w.mVScale, w);

            t.setAlpha(mSurfaceControl, mShownAlpha);

            if (mDrawState == HAS_DRAWN) {
                if (mLastHidden) {
                    showRobustly(t);
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
                }
            }
        }

        if (w.getOrientationChanging()) {
            if (!w.isDrawn()) {
                if (w.mDisplayContent.shouldSyncRotationChange(w)) {
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
    }

    private void showRobustly(SurfaceControl.Transaction t) {
        if (mSurfaceShown) {
            return;
        }

        ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE SHOW (performLayout): %s", mTitle);
        if (DEBUG_VISIBILITY) Slog.v(TAG, "Showing " + this + " during relayout");
        setShown(true);
        t.show(mSurfaceControl);
        if (mWin.mIsWallpaper) {
            final DisplayContent dc = mWin.mDisplayContent;
            EventLog.writeEvent(EventLogTags.WM_WALLPAPER_SURFACE,
                    dc.mDisplayId, 1 /* request shown */,
                    String.valueOf(dc.mWallpaperController.getWallpaperTarget()));
        }
    }

    void setOpaqueLocked(boolean isOpaque) {
        if (mSurfaceControl == null) {
            return;
        }
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE isOpaque=%b: %s", isOpaque, mTitle);
        mWin.getPendingTransaction().setOpaque(mSurfaceControl, isOpaque);
        mService.scheduleAnimationLocked();
    }

    void setColorSpaceAgnosticLocked(boolean agnostic) {
        if (mSurfaceControl == null) {
            return;
        }
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "SURFACE isColorSpaceAgnostic=%b: %s", agnostic, mTitle);
        mWin.getPendingTransaction().setColorSpaceAgnostic(mSurfaceControl, agnostic);
    }

    void applyEnterAnimationLocked() {
        final int transit;
        if (mEnterAnimationPending) {
            mEnterAnimationPending = false;
            transit = WindowManagerPolicy.TRANSIT_ENTER;
        } else {
            transit = WindowManagerPolicy.TRANSIT_SHOW;
        }

        // We don't apply animation for application main window here since this window type
        // should be controlled by ActivityRecord in general. Wallpaper is also excluded because
        // WallpaperController should handle it. Also skip play enter animation for the window
        // below starting window.
        if (mAttrType != TYPE_BASE_APPLICATION && !mWin.mIsWallpaper
                && !(mWin.mActivityRecord != null && mWin.mActivityRecord.hasStartingWindow())) {
            applyAnimationLocked(transit, true);
        }

        if (mService.mAccessibilityController.hasCallbacks()) {
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

        if (mWin.mAttrs.type == TYPE_INPUT_METHOD) {
            mWin.getDisplayContent().adjustForImeIfNeeded();
            if (isEntrance) {
                mWin.setDisplayLayoutNeeded();
                mService.mWindowPlacerLocked.requestTraversal();
            }
        }

        if (mWin.mControllableInsetProvider != null) {
            // All our animations should be driven by the insets control target.
            return false;
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
            if (ProtoLog.isEnabled(WM_DEBUG_ANIM, LogLevel.VERBOSE)) {
                ProtoLog.v(WM_DEBUG_ANIM, "applyAnimation: win=%s"
                        + " anim=%d attr=0x%x a=%s transit=%d type=%d isEntrance=%b Callers %s",
                        this, anim, attr, a, transit, mAttrType, isEntrance, Debug.getCallers(20));
            }
            if (a != null) {
                Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "WSA#startAnimation");
                mWin.startAnimation(a);
                Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
                mAnimationIsEntrance = isEntrance;
            }
        } else {
            mWin.cancelAnimation();
        }

        return mWin.isAnimating(0 /* flags */, ANIMATION_TYPE_WINDOW_ANIMATION);
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        if (mSurfaceControl != null) {
            final long dumpToken = proto.start(SURFACE);
            proto.write(SHOWN, mSurfaceShown);
            proto.end(dumpToken);
        }
        proto.write(DRAW_STATE, mDrawState);
        mSystemDecorRect.dumpDebug(proto, SYSTEM_DECOR_RECT);
        proto.end(token);
    }

    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        if (mAnimationIsEntrance) {
            pw.print(prefix); pw.print(" mAnimationIsEntrance="); pw.print(mAnimationIsEntrance);
        }
        if (mSurfaceControl != null) {
            if (dumpAll) {
                pw.print(prefix); pw.print("mSurface="); pw.println(mSurfaceControl);
            }
            pw.print(prefix); pw.print("Surface: shown="); pw.print(mSurfaceShown);
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
        if (mWin.mGlobalScale != 1) {
            pw.print(prefix); pw.print("mGlobalScale="); pw.print(mWin.mGlobalScale);
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
        return mSurfaceControl != null && mSurfaceShown;
    }

    void destroySurface(SurfaceControl.Transaction t) {
        if (mSurfaceControl == null) {
            return;
        }
        ProtoLog.i(WM_SHOW_SURFACE_ALLOC,
                "Destroying surface %s called by %s", this, Debug.getCallers(8));
        if (mWin.mIsWallpaper && !mWin.mWindowRemovalAllowed && !mWin.mRemoveOnExit) {
            // The wallpaper surface should have the same lifetime as its window.
            Slog.e(TAG, "Unexpected removing wallpaper surface of " + mWin
                    + " by " + Debug.getCallers(8));
        }
        t.remove(mSurfaceControl);
        setShown(false);
        mSurfaceControl = null;
        mWin.setHasSurface(false);
        mDrawState = NO_SURFACE;
    }
}
