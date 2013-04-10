// Copyright 2012 Google Inc. All Rights Reserved.

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static com.android.server.wm.WindowManagerService.LayoutFields.SET_ORIENTATION_CHANGE_COMPLETE;
import static com.android.server.wm.WindowManagerService.LayoutFields.SET_TURN_ON_SCREEN;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Debug;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.MagnificationSpec;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;
import android.view.WindowManager.LayoutParams;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Transformation;

import com.android.server.wm.WindowManagerService.H;

import java.io.PrintWriter;
import java.util.ArrayList;

class WinAnimatorList extends ArrayList<WindowStateAnimator> {
    public WinAnimatorList() {
        super();
    }

    public WinAnimatorList(WinAnimatorList other) {
        super(other);
    }
}

/**
 * Keep track of animations and surface operations for a single WindowState.
 **/
class WindowStateAnimator {
    static final boolean DEBUG_VISIBILITY = WindowManagerService.DEBUG_VISIBILITY;
    static final boolean DEBUG_ANIM = WindowManagerService.DEBUG_ANIM;
    static final boolean DEBUG_LAYERS = WindowManagerService.DEBUG_LAYERS;
    static final boolean DEBUG_STARTING_WINDOW = WindowManagerService.DEBUG_STARTING_WINDOW;
    static final boolean SHOW_TRANSACTIONS = WindowManagerService.SHOW_TRANSACTIONS;
    static final boolean SHOW_LIGHT_TRANSACTIONS = WindowManagerService.SHOW_LIGHT_TRANSACTIONS;
    static final boolean SHOW_SURFACE_ALLOC = WindowManagerService.SHOW_SURFACE_ALLOC;
    static final boolean localLOGV = WindowManagerService.localLOGV;
    static final boolean DEBUG_ORIENTATION = WindowManagerService.DEBUG_ORIENTATION;
    static final boolean DEBUG_SURFACE_TRACE = WindowManagerService.DEBUG_SURFACE_TRACE;

    static final String TAG = "WindowStateAnimator";

    // Unchanging local convenience fields.
    final WindowManagerService mService;
    final WindowState mWin;
    final WindowStateAnimator mAttachedWinAnimator;
    final WindowAnimator mAnimator;
    AppWindowAnimator mAppAnimator;
    final Session mSession;
    final WindowManagerPolicy mPolicy;
    final Context mContext;
    final boolean mIsWallpaper;

    // If this is a universe background window, this is the transformation
    // it is applying to the rest of the universe.
    final Transformation mUniverseTransform = new Transformation();

    // Currently running animation.
    boolean mAnimating;
    boolean mLocalAnimating;
    Animation mAnimation;
    boolean mAnimationIsEntrance;
    boolean mHasTransformation;
    boolean mHasLocalTransformation;
    final Transformation mTransformation = new Transformation();
    boolean mWasAnimating;      // Were we animating going into the most recent animation step?
    int mAnimLayer;
    int mLastLayer;

    SurfaceControl mSurfaceControl;
    SurfaceControl mPendingDestroySurface;

    /**
     * Set when we have changed the size of the surface, to know that
     * we must tell them application to resize (and thus redraw itself).
     */
    boolean mSurfaceResized;

    /**
     * Set if the client has asked that the destroy of its surface be delayed
     * until it explicitly says it is okay.
     */
    boolean mSurfaceDestroyDeferred;

    float mShownAlpha = 0;
    float mAlpha = 0;
    float mLastAlpha = 0;

    // Used to save animation distances between the time they are calculated and when they are
    // used.
    int mAnimDw;
    int mAnimDh;
    float mDsDx=1, mDtDx=0, mDsDy=0, mDtDy=1;
    float mLastDsDx=1, mLastDtDx=0, mLastDsDy=0, mLastDtDy=1;

    boolean mHaveMatrix;

    // For debugging, this is the last information given to the surface flinger.
    boolean mSurfaceShown;
    float mSurfaceX, mSurfaceY, mSurfaceW, mSurfaceH;
    int mSurfaceLayer;
    float mSurfaceAlpha;

    // Set to true if, when the window gets displayed, it should perform
    // an enter animation.
    boolean mEnterAnimationPending;

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
    static String drawStateToString(int state) {
        switch (state) {
            case NO_SURFACE: return "NO_SURFACE";
            case DRAW_PENDING: return "DRAW_PENDING";
            case COMMIT_DRAW_PENDING: return "COMMIT_DRAW_PENDING";
            case READY_TO_SHOW: return "READY_TO_SHOW";
            case HAS_DRAWN: return "HAS_DRAWN";
            default: return Integer.toString(state);
        }
    }
    int mDrawState;

    /** Was this window last hidden? */
    boolean mLastHidden;

    int mAttrFlags;
    int mAttrType;

    final int mLayerStack;

    public WindowStateAnimator(final WindowState win) {
        final WindowManagerService service = win.mService;

        mService = service;
        mAnimator = service.mAnimator;
        mPolicy = service.mPolicy;
        mContext = service.mContext;
        final DisplayInfo displayInfo = win.mDisplayContent.getDisplayInfo();
        mAnimDw = displayInfo.appWidth;
        mAnimDh = displayInfo.appHeight;

        mWin = win;
        mAttachedWinAnimator = win.mAttachedWindow == null
                ? null : win.mAttachedWindow.mWinAnimator;
        mAppAnimator = win.mAppToken == null ? null : win.mAppToken.mAppAnimator;
        mSession = win.mSession;
        mAttrFlags = win.mAttrs.flags;
        mAttrType = win.mAttrs.type;
        mIsWallpaper = win.mIsWallpaper;
        mLayerStack = win.mDisplayContent.getDisplay().getLayerStack();
    }

    public void setAnimation(Animation anim) {
        if (localLOGV) Slog.v(TAG, "Setting animation in " + this + ": " + anim);
        mAnimating = false;
        mLocalAnimating = false;
        mAnimation = anim;
        mAnimation.restrictDuration(WindowManagerService.MAX_ANIMATION_DURATION);
        mAnimation.scaleCurrentDuration(mService.mWindowAnimationScale);
        // Start out animation gone if window is gone, or visible if window is visible.
        mTransformation.clear();
        mTransformation.setAlpha(mLastHidden ? 0 : 1);
        mHasLocalTransformation = true;
    }

    public void clearAnimation() {
        if (mAnimation != null) {
            mAnimating = true;
            mLocalAnimating = false;
            mAnimation.cancel();
            mAnimation = null;
        }
    }

    /** Is the window or its container currently animating? */
    boolean isAnimating() {
        return mAnimation != null
                || (mAttachedWinAnimator != null && mAttachedWinAnimator.mAnimation != null)
                || (mAppAnimator != null &&
                        (mAppAnimator.animation != null
                                || mAppAnimator.mAppToken.inPendingTransaction));
    }

    /** Is the window animating the DummyAnimation? */
    boolean isDummyAnimation() {
        return mAppAnimator != null
                && mAppAnimator.animation == AppWindowAnimator.sDummyAnimation;
    }

    /** Is this window currently animating? */
    boolean isWindowAnimating() {
        return mAnimation != null;
    }

    void cancelExitAnimationForNextAnimationLocked() {
        if (mAnimation != null) {
            mAnimation.cancel();
            mAnimation = null;
            mLocalAnimating = false;
            destroySurfaceLocked();
        }
    }

    private boolean stepAnimation(long currentTime) {
        if ((mAnimation == null) || !mLocalAnimating) {
            return false;
        }
        mTransformation.clear();
        final boolean more = mAnimation.getTransformation(currentTime, mTransformation);
        if (false && DEBUG_ANIM) Slog.v(
            TAG, "Stepped animation in " + this +
            ": more=" + more + ", xform=" + mTransformation);
        return more;
    }

    // This must be called while inside a transaction.  Returns true if
    // there is more animation to run.
    boolean stepAnimationLocked(long currentTime) {
        // Save the animation state as it was before this step so WindowManagerService can tell if
        // we just started or just stopped animating by comparing mWasAnimating with isAnimating().
        mWasAnimating = mAnimating;
        if (mService.okToDisplay()) {
            // We will run animations as long as the display isn't frozen.

            if (mWin.isDrawnLw() && mAnimation != null) {
                mHasTransformation = true;
                mHasLocalTransformation = true;
                if (!mLocalAnimating) {
                    if (DEBUG_ANIM) Slog.v(
                        TAG, "Starting animation in " + this +
                        " @ " + currentTime + ": ww=" + mWin.mFrame.width() +
                        " wh=" + mWin.mFrame.height() +
                        " dw=" + mAnimDw + " dh=" + mAnimDh +
                        " scale=" + mService.mWindowAnimationScale);
                    mAnimation.initialize(mWin.mFrame.width(), mWin.mFrame.height(),
                            mAnimDw, mAnimDh);
                    final DisplayInfo displayInfo = mWin.mDisplayContent.getDisplayInfo();
                    mAnimDw = displayInfo.appWidth;
                    mAnimDh = displayInfo.appHeight;
                    mAnimation.setStartTime(currentTime);
                    mLocalAnimating = true;
                    mAnimating = true;
                }
                if ((mAnimation != null) && mLocalAnimating) {
                    if (stepAnimation(currentTime)) {
                        return true;
                    }
                }
                if (DEBUG_ANIM) Slog.v(
                    TAG, "Finished animation in " + this +
                    " @ " + currentTime);
                //WindowManagerService.this.dump();
            }
            mHasLocalTransformation = false;
            if ((!mLocalAnimating || mAnimationIsEntrance) && mAppAnimator != null
                    && mAppAnimator.animation != null) {
                // When our app token is animating, we kind-of pretend like
                // we are as well.  Note the mLocalAnimating mAnimationIsEntrance
                // part of this check means that we will only do this if
                // our window is not currently exiting, or it is not
                // locally animating itself.  The idea being that one that
                // is exiting and doing a local animation should be removed
                // once that animation is done.
                mAnimating = true;
                mHasTransformation = true;
                mTransformation.clear();
                return false;
            } else if (mHasTransformation) {
                // Little trick to get through the path below to act like
                // we have finished an animation.
                mAnimating = true;
            } else if (isAnimating()) {
                mAnimating = true;
            }
        } else if (mAnimation != null) {
            // If the display is frozen, and there is a pending animation,
            // clear it and make sure we run the cleanup code.
            mAnimating = true;
        }

        if (!mAnimating && !mLocalAnimating) {
            return false;
        }

        // Done animating, clean up.
        if (DEBUG_ANIM) Slog.v(
            TAG, "Animation done in " + this + ": exiting=" + mWin.mExiting
            + ", reportedVisible="
            + (mWin.mAppToken != null ? mWin.mAppToken.reportedVisible : false));

        mAnimating = false;
        mLocalAnimating = false;
        if (mAnimation != null) {
            mAnimation.cancel();
            mAnimation = null;
        }
        if (mAnimator.mWindowDetachedWallpaper == mWin) {
            mAnimator.mWindowDetachedWallpaper = null;
        }
        mAnimLayer = mWin.mLayer;
        if (mWin.mIsImWindow) {
            mAnimLayer += mService.mInputMethodAnimLayerAdjustment;
        } else if (mIsWallpaper) {
            mAnimLayer += mService.mWallpaperAnimLayerAdjustment;
        }
        if (DEBUG_LAYERS) Slog.v(TAG, "Stepping win " + this
                + " anim layer: " + mAnimLayer);
        mHasTransformation = false;
        mHasLocalTransformation = false;
        if (mWin.mPolicyVisibility != mWin.mPolicyVisibilityAfterAnim) {
            if (WindowState.DEBUG_VISIBILITY) {
                Slog.v(TAG, "Policy visibility changing after anim in " + this + ": "
                        + mWin.mPolicyVisibilityAfterAnim);
            }
            mWin.mPolicyVisibility = mWin.mPolicyVisibilityAfterAnim;
            mWin.mDisplayContent.layoutNeeded = true;
            if (!mWin.mPolicyVisibility) {
                if (mService.mCurrentFocus == mWin) {
                    mService.mFocusMayChange = true;
                }
                // Window is no longer visible -- make sure if we were waiting
                // for it to be displayed before enabling the display, that
                // we allow the display to be enabled now.
                mService.enableScreenIfNeededLocked();
            }
        }
        mTransformation.clear();
        if (mDrawState == HAS_DRAWN
                && mWin.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING
                && mWin.mAppToken != null
                && mWin.mAppToken.firstWindowDrawn
                && mWin.mAppToken.startingData != null) {
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG, "Finish starting "
                    + mWin.mToken + ": first real window done animating");
            mService.mFinishedStarting.add(mWin.mAppToken);
            mService.mH.sendEmptyMessage(H.FINISHED_STARTING);
        }

        finishExit();
        final int displayId = mWin.mDisplayContent.getDisplayId();
        mAnimator.setPendingLayoutChanges(displayId, WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM);
        if (WindowManagerService.DEBUG_LAYOUT_REPEATS) mService.debugLayoutRepeats(
                "WindowStateAnimator", mAnimator.getPendingLayoutChanges(displayId));

        if (mWin.mAppToken != null) {
            mWin.mAppToken.updateReportedVisibilityLocked();
        }

        return false;
    }

    void finishExit() {
        if (WindowManagerService.DEBUG_ANIM) Slog.v(
                TAG, "finishExit in " + this
                + ": exiting=" + mWin.mExiting
                + " remove=" + mWin.mRemoveOnExit
                + " windowAnimating=" + isWindowAnimating());

        final int N = mWin.mChildWindows.size();
        for (int i=0; i<N; i++) {
            mWin.mChildWindows.get(i).mWinAnimator.finishExit();
        }

        if (!mWin.mExiting) {
            return;
        }

        if (isWindowAnimating()) {
            return;
        }

        if (WindowManagerService.localLOGV) Slog.v(
                TAG, "Exit animation finished in " + this
                + ": remove=" + mWin.mRemoveOnExit);
        if (mSurfaceControl != null) {
            mService.mDestroySurface.add(mWin);
            mWin.mDestroying = true;
            if (WindowState.SHOW_TRANSACTIONS) WindowManagerService.logSurface(
                mWin, "HIDE (finishExit)", null);
            hide();
        }
        mWin.mExiting = false;
        if (mWin.mRemoveOnExit) {
            mService.mPendingRemove.add(mWin);
            mWin.mRemoveOnExit = false;
        }
        mAnimator.hideWallpapersLocked(mWin);
    }

    void hide() {
        if (!mLastHidden) {
            //dump();
            mLastHidden = true;
            if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(mWin,
                    "HIDE (performLayout)", null);
            if (mSurfaceControl != null) {
                mSurfaceShown = false;
                try {
                    mSurfaceControl.hide();
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Exception hiding surface in " + mWin);
                }
            }
        }
    }

    boolean finishDrawingLocked() {
        if (DEBUG_STARTING_WINDOW &&
                mWin.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING) {
            Slog.v(TAG, "Finishing drawing window " + mWin + ": mDrawState="
                    + drawStateToString(mDrawState));
        }
        if (mDrawState == DRAW_PENDING) {
            if (DEBUG_SURFACE_TRACE || DEBUG_ANIM || SHOW_TRANSACTIONS || DEBUG_ORIENTATION)
                Slog.v(TAG, "finishDrawingLocked: mDrawState=COMMIT_DRAW_PENDING " + this + " in "
                        + mSurfaceControl);
            if (DEBUG_STARTING_WINDOW &&
                    mWin.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING) {
                Slog.v(TAG, "Draw state now committed in " + mWin);
            }
            mDrawState = COMMIT_DRAW_PENDING;
            return true;
        }
        return false;
    }

    // This must be called while inside a transaction.
    boolean commitFinishDrawingLocked(long currentTime) {
        if (DEBUG_STARTING_WINDOW &&
                mWin.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING) {
            Slog.i(TAG, "commitFinishDrawingLocked: " + mWin + " cur mDrawState="
                    + drawStateToString(mDrawState));
        }
        if (mDrawState != COMMIT_DRAW_PENDING) {
            return false;
        }
        if (DEBUG_SURFACE_TRACE || DEBUG_ANIM) {
            Slog.i(TAG, "commitFinishDrawingLocked: mDrawState=READY_TO_SHOW " + mSurfaceControl);
        }
        mDrawState = READY_TO_SHOW;
        final boolean starting = mWin.mAttrs.type == TYPE_APPLICATION_STARTING;
        final AppWindowToken atoken = mWin.mAppToken;
        if (atoken == null || atoken.allDrawn || starting) {
            performShowLocked();
        }
        return true;
    }

    static class SurfaceTrace extends SurfaceControl {
        private final static String SURFACE_TAG = "SurfaceTrace";
        final static ArrayList<SurfaceTrace> sSurfaces = new ArrayList<SurfaceTrace>();

        private float mSurfaceTraceAlpha = 0;
        private int mLayer;
        private final PointF mPosition = new PointF();
        private final Point mSize = new Point();
        private final Rect mWindowCrop = new Rect();
        private boolean mShown = false;
        private int mLayerStack;
        private String mName;

        public SurfaceTrace(SurfaceSession s,
                       String name, int w, int h, int format, int flags)
                   throws OutOfResourcesException {
            super(s, name, w, h, format, flags);
            mName = name != null ? name : "Not named";
            mSize.set(w, h);
            Slog.v(SURFACE_TAG, "ctor: " + this + ". Called by "
                    + Debug.getCallers(3));
        }

        @Override
        public void setAlpha(float alpha) {
            super.setAlpha(alpha);
            if (alpha != mSurfaceTraceAlpha) {
                mSurfaceTraceAlpha = alpha;
                Slog.v(SURFACE_TAG, "setAlpha: " + this + ". Called by "
                        + Debug.getCallers(3));
            }
        }

        @Override
        public void setLayer(int zorder) {
            super.setLayer(zorder);
            if (zorder != mLayer) {
                mLayer = zorder;
                Slog.v(SURFACE_TAG, "setLayer: " + this + ". Called by "
                        + Debug.getCallers(3));
            }

            sSurfaces.remove(this);
            int i;
            for (i = sSurfaces.size() - 1; i >= 0; i--) {
                SurfaceTrace s = sSurfaces.get(i);
                if (s.mLayer < zorder) {
                    break;
                }
            }
            sSurfaces.add(i + 1, this);
        }

        @Override
        public void setPosition(float x, float y) {
            super.setPosition(x, y);
            if (x != mPosition.x || y != mPosition.y) {
                mPosition.set(x, y);
                Slog.v(SURFACE_TAG, "setPosition: " + this + ". Called by "
                        + Debug.getCallers(3));
            }
        }

        @Override
        public void setSize(int w, int h) {
            super.setSize(w, h);
            if (w != mSize.x || h != mSize.y) {
                mSize.set(w, h);
                Slog.v(SURFACE_TAG, "setSize: " + this + ". Called by "
                        + Debug.getCallers(3));
            }
        }

        @Override
        public void setWindowCrop(Rect crop) {
            super.setWindowCrop(crop);
            if (crop != null) {
                if (!crop.equals(mWindowCrop)) {
                    mWindowCrop.set(crop);
                    Slog.v(SURFACE_TAG, "setWindowCrop: " + this + ". Called by "
                            + Debug.getCallers(3));
                }
            }
        }

        @Override
        public void setLayerStack(int layerStack) {
            super.setLayerStack(layerStack);
            if (layerStack != mLayerStack) {
                mLayerStack = layerStack;
                Slog.v(SURFACE_TAG, "setLayerStack: " + this + ". Called by " + Debug.getCallers(3));
            }
        }

        @Override
        public void hide() {
            super.hide();
            if (mShown) {
                mShown = false;
                Slog.v(SURFACE_TAG, "hide: " + this + ". Called by "
                        + Debug.getCallers(3));
            }
        }
        @Override
        public void show() {
            super.show();
            if (!mShown) {
                mShown = true;
                Slog.v(SURFACE_TAG, "show: " + this + ". Called by "
                        + Debug.getCallers(3));
            }
        }

        @Override
        public void destroy() {
            super.destroy();
            Slog.v(SURFACE_TAG, "destroy: " + this + ". Called by "
                    + Debug.getCallers(3));
            sSurfaces.remove(this);
        }

        @Override
        public void release() {
            super.release();
            Slog.v(SURFACE_TAG, "release: " + this + ". Called by "
                    + Debug.getCallers(3));
            sSurfaces.remove(this);
        }

        static void dumpAllSurfaces() {
            final int N = sSurfaces.size();
            for (int i = 0; i < N; i++) {
                Slog.i(TAG, "SurfaceDump: " + sSurfaces.get(i));
            }
        }

        @Override
        public String toString() {
            return "Surface " + Integer.toHexString(System.identityHashCode(this)) + " "
                    + mName + " (" + mLayerStack + "): shown=" + mShown + " layer=" + mLayer
                    + " alpha=" + mSurfaceTraceAlpha + " " + mPosition.x + "," + mPosition.y
                    + " " + mSize.x + "x" + mSize.y
                    + " crop=" + mWindowCrop.toShortString();
        }
    }

    SurfaceControl createSurfaceLocked() {
        if (mSurfaceControl == null) {
            if (DEBUG_ANIM || DEBUG_ORIENTATION) Slog.i(TAG,
                    "createSurface " + this + ": mDrawState=DRAW_PENDING");
            mDrawState = DRAW_PENDING;
            if (mWin.mAppToken != null) {
                if (mWin.mAppToken.mAppAnimator.animation == null) {
                    mWin.mAppToken.allDrawn = false;
                    mWin.mAppToken.deferClearAllDrawn = false;
                } else {
                    // Currently animating, persist current state of allDrawn until animation
                    // is complete.
                    mWin.mAppToken.deferClearAllDrawn = true;
                }
            }

            mService.makeWindowFreezingScreenIfNeededLocked(mWin);

            int flags = SurfaceControl.HIDDEN;
            final WindowManager.LayoutParams attrs = mWin.mAttrs;

            if ((attrs.flags&WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                flags |= SurfaceControl.SECURE;
            }
            if (WindowState.DEBUG_VISIBILITY) Slog.v(
                TAG, "Creating surface in session "
                + mSession.mSurfaceSession + " window " + this
                + " w=" + mWin.mCompatFrame.width()
                + " h=" + mWin.mCompatFrame.height() + " format="
                + attrs.format + " flags=" + flags);

            int w = mWin.mCompatFrame.width();
            int h = mWin.mCompatFrame.height();
            if ((attrs.flags & LayoutParams.FLAG_SCALED) != 0) {
                // for a scaled surface, we always want the requested
                // size.
                w = mWin.mRequestedWidth;
                h = mWin.mRequestedHeight;
            }

            // Something is wrong and SurfaceFlinger will not like this,
            // try to revert to sane values
            if (w <= 0) w = 1;
            if (h <= 0) h = 1;

            mSurfaceShown = false;
            mSurfaceLayer = 0;
            mSurfaceAlpha = 0;
            mSurfaceX = 0;
            mSurfaceY = 0;
            mSurfaceW = w;
            mSurfaceH = h;
            mWin.mLastSystemDecorRect.set(0, 0, 0, 0);
            try {
                final boolean isHwAccelerated = (attrs.flags &
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) != 0;
                final int format = isHwAccelerated ? PixelFormat.TRANSLUCENT : attrs.format;
                if (!PixelFormat.formatHasAlpha(attrs.format)) {
                    flags |= SurfaceControl.OPAQUE;
                }
                if (DEBUG_SURFACE_TRACE) {
                    mSurfaceControl = new SurfaceTrace(
                            mSession.mSurfaceSession,
                            attrs.getTitle().toString(),
                            w, h, format, flags);
                } else {
                    mSurfaceControl = new SurfaceControl(
                        mSession.mSurfaceSession,
                        attrs.getTitle().toString(),
                        w, h, format, flags);
                }
                mWin.mHasSurface = true;
                if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) Slog.i(TAG,
                        "  CREATE SURFACE "
                        + mSurfaceControl + " IN SESSION "
                        + mSession.mSurfaceSession
                        + ": pid=" + mSession.mPid + " format="
                        + attrs.format + " flags=0x"
                        + Integer.toHexString(flags)
                        + " / " + this);
            } catch (SurfaceControl.OutOfResourcesException e) {
                mWin.mHasSurface = false;
                Slog.w(TAG, "OutOfResourcesException creating surface");
                mService.reclaimSomeSurfaceMemoryLocked(this, "create", true);
                mDrawState = NO_SURFACE;
                return null;
            } catch (Exception e) {
                mWin.mHasSurface = false;
                Slog.e(TAG, "Exception creating surface", e);
                mDrawState = NO_SURFACE;
                return null;
            }

            if (WindowManagerService.localLOGV) Slog.v(
                TAG, "Got surface: " + mSurfaceControl
                + ", set left=" + mWin.mFrame.left + " top=" + mWin.mFrame.top
                + ", animLayer=" + mAnimLayer);
            if (SHOW_LIGHT_TRANSACTIONS) {
                Slog.i(TAG, ">>> OPEN TRANSACTION createSurfaceLocked");
                WindowManagerService.logSurface(mWin, "CREATE pos=("
                        + mWin.mFrame.left + "," + mWin.mFrame.top + ") ("
                        + mWin.mCompatFrame.width() + "x" + mWin.mCompatFrame.height()
                        + "), layer=" + mAnimLayer + " HIDE", null);
            }
            SurfaceControl.openTransaction();
            try {
                try {
                    mSurfaceX = mWin.mFrame.left + mWin.mXOffset;
                    mSurfaceY = mWin.mFrame.top + mWin.mYOffset;
                    mSurfaceControl.setPosition(mSurfaceX, mSurfaceY);
                    mSurfaceLayer = mAnimLayer;
                    mSurfaceControl.setLayerStack(mLayerStack);
                    mSurfaceControl.setLayer(mAnimLayer);
                    mSurfaceControl.setAlpha(0);
                    mSurfaceShown = false;
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Error creating surface in " + w, e);
                    mService.reclaimSomeSurfaceMemoryLocked(this, "create-init", true);
                }
                mLastHidden = true;
            } finally {
                SurfaceControl.closeTransaction();
                if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                        "<<< CLOSE TRANSACTION createSurfaceLocked");
            }
            if (WindowManagerService.localLOGV) Slog.v(
                    TAG, "Created surface " + this);
        }
        return mSurfaceControl;
    }

    void destroySurfaceLocked() {
        if (mWin.mAppToken != null && mWin == mWin.mAppToken.startingWindow) {
            mWin.mAppToken.startingDisplayed = false;
        }

        if (mSurfaceControl != null) {

            int i = mWin.mChildWindows.size();
            while (i > 0) {
                i--;
                WindowState c = mWin.mChildWindows.get(i);
                c.mAttachedHidden = true;
            }

            try {
                if (DEBUG_VISIBILITY) {
                    RuntimeException e = null;
                    if (!WindowManagerService.HIDE_STACK_CRAWLS) {
                        e = new RuntimeException();
                        e.fillInStackTrace();
                    }
                    Slog.w(TAG, "Window " + this + " destroying surface "
                            + mSurfaceControl + ", session " + mSession, e);
                }
                if (mSurfaceDestroyDeferred) {
                    if (mSurfaceControl != null && mPendingDestroySurface != mSurfaceControl) {
                        if (mPendingDestroySurface != null) {
                            if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) {
                                RuntimeException e = null;
                                if (!WindowManagerService.HIDE_STACK_CRAWLS) {
                                    e = new RuntimeException();
                                    e.fillInStackTrace();
                                }
                                WindowManagerService.logSurface(mWin, "DESTROY PENDING", e);
                            }
                            mPendingDestroySurface.destroy();
                        }
                        mPendingDestroySurface = mSurfaceControl;
                    }
                } else {
                    if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) {
                        RuntimeException e = null;
                        if (!WindowManagerService.HIDE_STACK_CRAWLS) {
                            e = new RuntimeException();
                            e.fillInStackTrace();
                        }
                        WindowManagerService.logSurface(mWin, "DESTROY", e);
                    }
                    mSurfaceControl.destroy();
                }
                mAnimator.hideWallpapersLocked(mWin);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Exception thrown when destroying Window " + this
                    + " surface " + mSurfaceControl + " session " + mSession
                    + ": " + e.toString());
            }

            mSurfaceShown = false;
            mSurfaceControl = null;
            mWin.mHasSurface = false;
            mDrawState = NO_SURFACE;
        }
    }

    void destroyDeferredSurfaceLocked() {
        try {
            if (mPendingDestroySurface != null) {
                if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) {
                    RuntimeException e = null;
                    if (!WindowManagerService.HIDE_STACK_CRAWLS) {
                        e = new RuntimeException();
                        e.fillInStackTrace();
                    }
                    WindowManagerService.logSurface(mWin, "DESTROY PENDING", e);
                }
                mPendingDestroySurface.destroy();
                mAnimator.hideWallpapersLocked(mWin);
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
        final boolean selfTransformation = mHasLocalTransformation;
        Transformation attachedTransformation =
                (mAttachedWinAnimator != null && mAttachedWinAnimator.mHasLocalTransformation)
                ? mAttachedWinAnimator.mTransformation : null;
        Transformation appTransformation = (mAppAnimator != null && mAppAnimator.hasTransformation)
                ? mAppAnimator.transformation : null;

        // Wallpapers are animated based on the "real" window they
        // are currently targeting.
        if (mIsWallpaper && mService.mLowerWallpaperTarget == null
                && mService.mWallpaperTarget != null) {
            final WindowStateAnimator wallpaperAnimator = mService.mWallpaperTarget.mWinAnimator;
            if (wallpaperAnimator.mHasLocalTransformation &&
                    wallpaperAnimator.mAnimation != null &&
                    !wallpaperAnimator.mAnimation.getDetachWallpaper()) {
                attachedTransformation = wallpaperAnimator.mTransformation;
                if (WindowManagerService.DEBUG_WALLPAPER && attachedTransformation != null) {
                    Slog.v(TAG, "WP target attached xform: " + attachedTransformation);
                }
            }
            final AppWindowAnimator wpAppAnimator = mAnimator.getWallpaperAppAnimator();
            if (wpAppAnimator != null && wpAppAnimator.hasTransformation
                    && wpAppAnimator.animation != null
                    && !wpAppAnimator.animation.getDetachWallpaper()) {
                appTransformation = wpAppAnimator.transformation;
                if (WindowManagerService.DEBUG_WALLPAPER && appTransformation != null) {
                    Slog.v(TAG, "WP target app xform: " + appTransformation);
                }
            }
        }

        final int displayId = mWin.getDisplayId();
        final ScreenRotationAnimation screenRotationAnimation =
                mAnimator.getScreenRotationAnimationLocked(displayId);
        final boolean screenAnimation =
                screenRotationAnimation != null && screenRotationAnimation.isAnimating();
        if (selfTransformation || attachedTransformation != null
                || appTransformation != null || screenAnimation) {
            // cache often used attributes locally
            final Rect frame = mWin.mFrame;
            final float tmpFloats[] = mService.mTmpFloats;
            final Matrix tmpMatrix = mWin.mTmpMatrix;

            // Compute the desired transformation.
            if (screenAnimation && screenRotationAnimation.isRotating()) {
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
            if (selfTransformation) {
                tmpMatrix.postConcat(mTransformation.getMatrix());
            }
            tmpMatrix.postTranslate(frame.left + mWin.mXOffset, frame.top + mWin.mYOffset);
            if (attachedTransformation != null) {
                tmpMatrix.postConcat(attachedTransformation.getMatrix());
            }
            if (appTransformation != null) {
                tmpMatrix.postConcat(appTransformation.getMatrix());
            }
            if (mAnimator.mUniverseBackground != null) {
                tmpMatrix.postConcat(mAnimator.mUniverseBackground.mUniverseTransform.getMatrix());
            }
            if (screenAnimation) {
                tmpMatrix.postConcat(screenRotationAnimation.getEnterTransformation().getMatrix());
            }
            //TODO (multidisplay): Magnification is supported only for the default display.
            if (mService.mDisplayMagnifier != null
                    && mWin.getDisplayId() == Display.DEFAULT_DISPLAY) {
                MagnificationSpec spec = mService.mDisplayMagnifier
                        .getMagnificationSpecForWindowLocked(mWin);
                if (spec != null && !spec.isNop()) {
                    tmpMatrix.postScale(spec.scale, spec.scale);
                    tmpMatrix.postTranslate(spec.offsetX, spec.offsetY);
                }
            }

            // "convert" it into SurfaceFlinger's format
            // (a 2x2 matrix + an offset)
            // Here we must not transform the position of the surface
            // since it is already included in the transformation.
            //Slog.i(TAG, "Transform: " + matrix);

            mHaveMatrix = true;
            tmpMatrix.getValues(tmpFloats);
            mDsDx = tmpFloats[Matrix.MSCALE_X];
            mDtDx = tmpFloats[Matrix.MSKEW_Y];
            mDsDy = tmpFloats[Matrix.MSKEW_X];
            mDtDy = tmpFloats[Matrix.MSCALE_Y];
            float x = tmpFloats[Matrix.MTRANS_X];
            float y = tmpFloats[Matrix.MTRANS_Y];
            int w = frame.width();
            int h = frame.height();
            mWin.mShownFrame.set(x, y, x+w, y+h);

            // Now set the alpha...  but because our current hardware
            // can't do alpha transformation on a non-opaque surface,
            // turn it off if we are running an animation that is also
            // transforming since it is more important to have that
            // animation be smooth.
            mShownAlpha = mAlpha;
            if (!mService.mLimitedAlphaCompositing
                    || (!PixelFormat.formatHasAlpha(mWin.mAttrs.format)
                    || (mWin.isIdentityMatrix(mDsDx, mDtDx, mDsDy, mDtDy)
                            && x == frame.left && y == frame.top))) {
                //Slog.i(TAG, "Applying alpha transform");
                if (selfTransformation) {
                    mShownAlpha *= mTransformation.getAlpha();
                }
                if (attachedTransformation != null) {
                    mShownAlpha *= attachedTransformation.getAlpha();
                }
                if (appTransformation != null) {
                    mShownAlpha *= appTransformation.getAlpha();
                }
                if (mAnimator.mUniverseBackground != null) {
                    mShownAlpha *= mAnimator.mUniverseBackground.mUniverseTransform.getAlpha();
                }
                if (screenAnimation) {
                    mShownAlpha *= screenRotationAnimation.getEnterTransformation().getAlpha();
                }
            } else {
                //Slog.i(TAG, "Not applying alpha transform");
            }

            if ((DEBUG_SURFACE_TRACE || WindowManagerService.localLOGV)
                    && (mShownAlpha == 1.0 || mShownAlpha == 0.0)) Slog.v(
                    TAG, "computeShownFrameLocked: Animating " + this + " mAlpha=" + mAlpha
                    + " self=" + (selfTransformation ? mTransformation.getAlpha() : "null")
                    + " attached=" + (attachedTransformation == null ?
                            "null" : attachedTransformation.getAlpha())
                    + " app=" + (appTransformation == null ? "null" : appTransformation.getAlpha())
                    + " screen=" + (screenAnimation ?
                            screenRotationAnimation.getEnterTransformation().getAlpha() : "null"));
            return;
        } else if (mIsWallpaper && mService.mInnerFields.mWallpaperActionPending) {
            return;
        }

        if (WindowManagerService.localLOGV) Slog.v(
                TAG, "computeShownFrameLocked: " + this +
                " not attached, mAlpha=" + mAlpha);

        final boolean applyUniverseTransformation = (mAnimator.mUniverseBackground != null
                && mWin.mAttrs.type != WindowManager.LayoutParams.TYPE_UNIVERSE_BACKGROUND
                && mWin.mBaseLayer < mAnimator.mAboveUniverseLayer);
        MagnificationSpec spec = null;
        //TODO (multidisplay): Magnification is supported only for the default display.
        if (mService.mDisplayMagnifier != null && mWin.getDisplayId() == Display.DEFAULT_DISPLAY) {
            spec = mService.mDisplayMagnifier.getMagnificationSpecForWindowLocked(mWin);
        }
        if (applyUniverseTransformation || spec != null) {
            final Rect frame = mWin.mFrame;
            final float tmpFloats[] = mService.mTmpFloats;
            final Matrix tmpMatrix = mWin.mTmpMatrix;

            tmpMatrix.setScale(mWin.mGlobalScale, mWin.mGlobalScale);
            tmpMatrix.postTranslate(frame.left + mWin.mXOffset, frame.top + mWin.mYOffset);

            if (applyUniverseTransformation) {
                tmpMatrix.postConcat(mAnimator.mUniverseBackground.mUniverseTransform.getMatrix());
            }

            if (spec != null && !spec.isNop()) {
                tmpMatrix.postScale(spec.scale, spec.scale);
                tmpMatrix.postTranslate(spec.offsetX, spec.offsetY);
            }

            tmpMatrix.getValues(tmpFloats);

            mHaveMatrix = true;
            mDsDx = tmpFloats[Matrix.MSCALE_X];
            mDtDx = tmpFloats[Matrix.MSKEW_Y];
            mDsDy = tmpFloats[Matrix.MSKEW_X];
            mDtDy = tmpFloats[Matrix.MSCALE_Y];
            float x = tmpFloats[Matrix.MTRANS_X];
            float y = tmpFloats[Matrix.MTRANS_Y];
            int w = frame.width();
            int h = frame.height();
            mWin.mShownFrame.set(x, y, x + w, y + h);

            mShownAlpha = mAlpha;
            if (applyUniverseTransformation) {
                mShownAlpha *= mAnimator.mUniverseBackground.mUniverseTransform.getAlpha();
            }
        } else {
            mWin.mShownFrame.set(mWin.mFrame);
            if (mWin.mXOffset != 0 || mWin.mYOffset != 0) {
                mWin.mShownFrame.offset(mWin.mXOffset, mWin.mYOffset);
            }
            mShownAlpha = mAlpha;
            mHaveMatrix = false;
            mDsDx = mWin.mGlobalScale;
            mDtDx = 0;
            mDsDy = 0;
            mDtDy = mWin.mGlobalScale;
        }
    }

    void applyDecorRect(final Rect decorRect) {
        final WindowState w = mWin;
        // Compute the offset of the window in relation to the decor rect.
        final int offX = w.mXOffset + w.mFrame.left;
        final int offY = w.mYOffset + w.mFrame.top;
        // Initialize the decor rect to the entire frame.
        w.mSystemDecorRect.set(0, 0, w.mFrame.width(), w.mFrame.height());
        // Intersect with the decor rect, offsetted by window position.
        w.mSystemDecorRect.intersect(decorRect.left-offX, decorRect.top-offY,
                decorRect.right-offX, decorRect.bottom-offY);
        // If size compatibility is being applied to the window, the
        // surface is scaled relative to the screen.  Also apply this
        // scaling to the crop rect.  We aren't using the standard rect
        // scale function because we want to round things to make the crop
        // always round to a larger rect to ensure we don't crop too
        // much and hide part of the window that should be seen.
        if (w.mEnforceSizeCompat && w.mInvGlobalScale != 1.0f) {
            final float scale = w.mInvGlobalScale;
            w.mSystemDecorRect.left = (int) (w.mSystemDecorRect.left * scale - 0.5f);
            w.mSystemDecorRect.top = (int) (w.mSystemDecorRect.top * scale - 0.5f);
            w.mSystemDecorRect.right = (int) ((w.mSystemDecorRect.right+1) * scale - 0.5f);
            w.mSystemDecorRect.bottom = (int) ((w.mSystemDecorRect.bottom+1) * scale - 0.5f);
        }
    }

    void updateSurfaceWindowCrop(final boolean recoveringMemory) {
        final WindowState w = mWin;
        DisplayInfo displayInfo = w.mDisplayContent.getDisplayInfo();

        // Need to recompute a new system decor rect each time.
        if ((w.mAttrs.flags & LayoutParams.FLAG_SCALED) != 0) {
            // Currently can't do this cropping for scaled windows.  We'll
            // just keep the crop rect the same as the source surface.
            w.mSystemDecorRect.set(0, 0, w.mRequestedWidth, w.mRequestedHeight);
        } else if (!w.isDefaultDisplay()) {
            // On a different display there is no system decor.  Crop the window
            // by the screen boundaries.
            w.mSystemDecorRect.set(0, 0, w.mCompatFrame.width(), w.mCompatFrame.height());
            w.mSystemDecorRect.intersect(-w.mCompatFrame.left, -w.mCompatFrame.top,
                    displayInfo.logicalWidth - w.mCompatFrame.left,
                    displayInfo.logicalHeight - w.mCompatFrame.top);
        } else if (w.mLayer >= mService.mSystemDecorLayer) {
            // Above the decor layer is easy, just use the entire window.
            // Unless we have a universe background...  in which case all the
            // windows need to be cropped by the screen, so they don't cover
            // the universe background.
            if (mAnimator.mUniverseBackground == null) {
                w.mSystemDecorRect.set(0, 0, w.mCompatFrame.width(),
                        w.mCompatFrame.height());
            } else {
                applyDecorRect(mService.mScreenRect);
            }
        } else if (w.mAttrs.type == WindowManager.LayoutParams.TYPE_UNIVERSE_BACKGROUND) {
            // The universe background isn't cropped.
            w.mSystemDecorRect.set(0, 0, w.mCompatFrame.width(),
                    w.mCompatFrame.height());
        } else {
            applyDecorRect(mService.mSystemDecorRect);
        }

        if (!w.mSystemDecorRect.equals(w.mLastSystemDecorRect)) {
            w.mLastSystemDecorRect.set(w.mSystemDecorRect);
            try {
                if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(w,
                        "CROP " + w.mSystemDecorRect.toShortString(), null);
                mSurfaceControl.setWindowCrop(w.mSystemDecorRect);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Error setting crop surface of " + w
                        + " crop=" + w.mSystemDecorRect.toShortString(), e);
                if (!recoveringMemory) {
                    mService.reclaimSomeSurfaceMemoryLocked(this, "crop", true);
                }
            }
        }
    }

    void setSurfaceBoundariesLocked(final boolean recoveringMemory) {
        final WindowState w = mWin;
        int width, height;
        if ((w.mAttrs.flags & LayoutParams.FLAG_SCALED) != 0) {
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
        final boolean surfaceResized = mSurfaceW != width || mSurfaceH != height;
        if (surfaceResized) {
            mSurfaceW = width;
            mSurfaceH = height;
        }

        final float left = w.mShownFrame.left;
        final float top = w.mShownFrame.top;
        if (mSurfaceX != left || mSurfaceY != top) {
            try {
                if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(w,
                        "POS " + left + ", " + top, null);
                mSurfaceX = left;
                mSurfaceY = top;
                mSurfaceControl.setPosition(left, top);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Error positioning surface of " + w
                        + " pos=(" + left
                        + "," + top + ")", e);
                if (!recoveringMemory) {
                    mService.reclaimSomeSurfaceMemoryLocked(this, "position", true);
                }
            }
        }

        if (surfaceResized) {
            try {
                if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(w,
                        "SIZE " + width + "x" + height, null);
                mSurfaceResized = true;
                mSurfaceControl.setSize(width, height);
                final int displayId = w.mDisplayContent.getDisplayId();
                mAnimator.setPendingLayoutChanges(displayId,
                        WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER);
                if ((w.mAttrs.flags & LayoutParams.FLAG_DIM_BEHIND) != 0) {
                    mService.startDimmingLocked(this, w.mExiting ? 0 : w.mAttrs.dimAmount);
                }
            } catch (RuntimeException e) {
                // If something goes wrong with the surface (such
                // as running out of memory), don't take down the
                // entire system.
                Slog.e(TAG, "Error resizing surface of " + w
                        + " size=(" + width + "x" + height + ")", e);
                if (!recoveringMemory) {
                    mService.reclaimSomeSurfaceMemoryLocked(this, "size", true);
                }
            }
        }

        updateSurfaceWindowCrop(recoveringMemory);
    }

    public void prepareSurfaceLocked(final boolean recoveringMemory) {
        final WindowState w = mWin;
        if (mSurfaceControl == null) {
            if (w.mOrientationChanging) {
                if (DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Orientation change skips hidden " + w);
                }
                w.mOrientationChanging = false;
            }
            return;
        }

        boolean displayed = false;

        computeShownFrameLocked();

        setSurfaceBoundariesLocked(recoveringMemory);

        if (mIsWallpaper && !mWin.mWallpaperVisible) {
            // Wallpaper is no longer visible and there is no wp target => hide it.
            hide();
        } else if (w.mAttachedHidden || !w.isOnScreen()) {
            hide();
            mAnimator.hideWallpapersLocked(w);

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
            if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(w,
                    "alpha=" + mShownAlpha + " layer=" + mAnimLayer
                    + " matrix=[" + (mDsDx*w.mHScale)
                    + "," + (mDtDx*w.mVScale)
                    + "][" + (mDsDy*w.mHScale)
                    + "," + (mDtDy*w.mVScale) + "]", null);
            if (mSurfaceControl != null) {
                try {
                    mSurfaceAlpha = mShownAlpha;
                    mSurfaceControl.setAlpha(mShownAlpha);
                    mSurfaceLayer = mAnimLayer;
                    mSurfaceControl.setLayer(mAnimLayer);
                    mSurfaceControl.setMatrix(
                        mDsDx*w.mHScale, mDtDx*w.mVScale,
                        mDsDy*w.mHScale, mDtDy*w.mVScale);

                    if (mLastHidden && mDrawState == HAS_DRAWN) {
                        if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(w,
                                "SHOW (performLayout)", null);
                        if (WindowManagerService.DEBUG_VISIBILITY) Slog.v(TAG, "Showing " + w
                                + " during relayout");
                        if (showSurfaceRobustlyLocked()) {
                            mLastHidden = false;
                            if (mIsWallpaper) {
                                mService.dispatchWallpaperVisibility(w, true);
                            }
                        } else {
                            w.mOrientationChanging = false;
                        }
                    }
                    if (mSurfaceControl != null) {
                        w.mToken.hasVisible = true;
                    }
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Error updating surface in " + w, e);
                    if (!recoveringMemory) {
                        mService.reclaimSomeSurfaceMemoryLocked(this, "update", true);
                    }
                }
            }
        } else {
            if (DEBUG_ANIM && isAnimating()) {
                Slog.v(TAG, "prepareSurface: No changes in animation for " + this);
            }
            displayed = true;
        }

        if (displayed) {
            if (w.mOrientationChanging) {
                if (!w.isDrawnLw()) {
                    mAnimator.mBulkUpdateParams &= ~SET_ORIENTATION_CHANGE_COMPLETE;
                    mAnimator.mLastWindowFreezeSource = w;
                    if (DEBUG_ORIENTATION) Slog.v(TAG,
                            "Orientation continue waiting for draw in " + w);
                } else {
                    w.mOrientationChanging = false;
                    if (DEBUG_ORIENTATION) Slog.v(TAG, "Orientation change complete in " + w);
                }
            }
            w.mToken.hasVisible = true;
        }
    }

    void setTransparentRegionHintLocked(final Region region) {
        if (mSurfaceControl == null) {
            Slog.w(TAG, "setTransparentRegionHint: null mSurface after mHasSurface true");
            return;
        }
        if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
            ">>> OPEN TRANSACTION setTransparentRegion");
        SurfaceControl.openTransaction();
        try {
            if (SHOW_TRANSACTIONS) WindowManagerService.logSurface(mWin,
                    "transparentRegionHint=" + region, null);
            mSurfaceControl.setTransparentRegionHint(region);
        } finally {
            SurfaceControl.closeTransaction();
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                    "<<< CLOSE TRANSACTION setTransparentRegion");
        }
    }

    void setWallpaperOffset(RectF shownFrame) {
        final int left = (int) shownFrame.left;
        final int top = (int) shownFrame.top;
        if (mSurfaceX != left || mSurfaceY != top) {
            mSurfaceX = left;
            mSurfaceY = top;
            if (mAnimating) {
                // If this window (or its app token) is animating, then the position
                // of the surface will be re-computed on the next animation frame.
                // We can't poke it directly here because it depends on whatever
                // transformation is being applied by the animation.
                return;
            }
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                    ">>> OPEN TRANSACTION setWallpaperOffset");
            SurfaceControl.openTransaction();
            try {
                if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(mWin,
                        "POS " + left + ", " + top, null);
                mSurfaceControl.setPosition(mWin.mFrame.left + left, mWin.mFrame.top + top);
                updateSurfaceWindowCrop(false);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Error positioning surface of " + mWin
                        + " pos=(" + left + "," + top + ")", e);
            } finally {
                SurfaceControl.closeTransaction();
                if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                        "<<< CLOSE TRANSACTION setWallpaperOffset");
            }
        }
    }

    // This must be called while inside a transaction.
    boolean performShowLocked() {
        if (mWin.isHiddenFromUserLocked()) {
            Slog.w(TAG, "current user violation " + mService.mCurrentUserId + " trying to display "
                    + this + ", type " + mWin.mAttrs.type + ", belonging to " + mWin.mOwnerUid);
            return false;
        }
        if (DEBUG_VISIBILITY || (DEBUG_STARTING_WINDOW &&
                mWin.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING)) {
            RuntimeException e = null;
            if (!WindowManagerService.HIDE_STACK_CRAWLS) {
                e = new RuntimeException();
                e.fillInStackTrace();
            }
            Slog.v(TAG, "performShow on " + this
                    + ": mDrawState=" + mDrawState + " readyForDisplay="
                    + mWin.isReadyForDisplayIgnoringKeyguard()
                    + " starting=" + (mWin.mAttrs.type == TYPE_APPLICATION_STARTING)
                    + " during animation: policyVis=" + mWin.mPolicyVisibility
                    + " attHidden=" + mWin.mAttachedHidden
                    + " tok.hiddenRequested="
                    + (mWin.mAppToken != null ? mWin.mAppToken.hiddenRequested : false)
                    + " tok.hidden="
                    + (mWin.mAppToken != null ? mWin.mAppToken.hidden : false)
                    + " animating=" + mAnimating
                    + " tok animating="
                    + (mAppAnimator != null ? mAppAnimator.animating : false), e);
        }
        if (mDrawState == READY_TO_SHOW && mWin.isReadyForDisplayIgnoringKeyguard()) {
            if (SHOW_TRANSACTIONS || DEBUG_ORIENTATION)
                WindowManagerService.logSurface(mWin, "SHOW (performShowLocked)", null);
            if (DEBUG_VISIBILITY || (DEBUG_STARTING_WINDOW &&
                    mWin.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING)) {
                Slog.v(TAG, "Showing " + this
                        + " during animation: policyVis=" + mWin.mPolicyVisibility
                        + " attHidden=" + mWin.mAttachedHidden
                        + " tok.hiddenRequested="
                        + (mWin.mAppToken != null ? mWin.mAppToken.hiddenRequested : false)
                        + " tok.hidden="
                        + (mWin.mAppToken != null ? mWin.mAppToken.hidden : false)
                        + " animating=" + mAnimating
                        + " tok animating="
                        + (mAppAnimator != null ? mAppAnimator.animating : false));
            }

            mService.enableScreenIfNeededLocked();

            applyEnterAnimationLocked();

            // Force the show in the next prepareSurfaceLocked() call.
            mLastAlpha = -1;
            if (DEBUG_SURFACE_TRACE || DEBUG_ANIM)
                Slog.v(TAG, "performShowLocked: mDrawState=HAS_DRAWN in " + this);
            mDrawState = HAS_DRAWN;
            mService.scheduleAnimationLocked();

            int i = mWin.mChildWindows.size();
            while (i > 0) {
                i--;
                WindowState c = mWin.mChildWindows.get(i);
                if (c.mAttachedHidden) {
                    c.mAttachedHidden = false;
                    if (c.mWinAnimator.mSurfaceControl != null) {
                        c.mWinAnimator.performShowLocked();
                        // It hadn't been shown, which means layout not
                        // performed on it, so now we want to make sure to
                        // do a layout.  If called from within the transaction
                        // loop, this will cause it to restart with a new
                        // layout.
                        c.mDisplayContent.layoutNeeded = true;
                    }
                }
            }

            if (mWin.mAttrs.type != TYPE_APPLICATION_STARTING
                    && mWin.mAppToken != null) {
                mWin.mAppToken.firstWindowDrawn = true;

                if (mWin.mAppToken.startingData != null) {
                    if (WindowManagerService.DEBUG_STARTING_WINDOW ||
                            WindowManagerService.DEBUG_ANIM) Slog.v(TAG,
                            "Finish starting " + mWin.mToken
                            + ": first real window is shown, no animation");
                    // If this initial window is animating, stop it -- we
                    // will do an animation to reveal it from behind the
                    // starting window, so there is no need for it to also
                    // be doing its own stuff.
                    clearAnimation();
                    mService.mFinishedStarting.add(mWin.mAppToken);
                    mService.mH.sendEmptyMessage(H.FINISHED_STARTING);
                }
                mWin.mAppToken.updateReportedVisibilityLocked();
            }

            return true;
        }

        return false;
    }

    /**
     * Have the surface flinger show a surface, robustly dealing with
     * error conditions.  In particular, if there is not enough memory
     * to show the surface, then we will try to get rid of other surfaces
     * in order to succeed.
     *
     * @return Returns true if the surface was successfully shown.
     */
    boolean showSurfaceRobustlyLocked() {
        try {
            if (mSurfaceControl != null) {
                mSurfaceShown = true;
                mSurfaceControl.show();
                if (mWin.mTurnOnScreen) {
                    if (DEBUG_VISIBILITY) Slog.v(TAG,
                            "Show surface turning screen on: " + mWin);
                    mWin.mTurnOnScreen = false;
                    mAnimator.mBulkUpdateParams |= SET_TURN_ON_SCREEN;
                }
            }
            return true;
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failure showing surface " + mSurfaceControl + " in " + mWin, e);
        }

        mService.reclaimSomeSurfaceMemoryLocked(this, "show", true);

        return false;
    }

    void applyEnterAnimationLocked() {
        final int transit;
        if (mEnterAnimationPending) {
            mEnterAnimationPending = false;
            transit = WindowManagerPolicy.TRANSIT_ENTER;
        } else {
            transit = WindowManagerPolicy.TRANSIT_SHOW;
        }
        applyAnimationLocked(transit, true);
        //TODO (multidisplay): Magnification is supported only for the default display.
        if (mService.mDisplayMagnifier != null
                && mWin.getDisplayId() == Display.DEFAULT_DISPLAY) {
            mService.mDisplayMagnifier.onWindowTransitionLocked(mWin, transit);
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
        if (mLocalAnimating && mAnimationIsEntrance == isEntrance) {
            // If we are trying to apply an animation, but already running
            // an animation of the same type, then just leave that one alone.
            return true;
        }

        // Only apply an animation if the display isn't frozen.  If it is
        // frozen, there is no reason to animate and it can cause strange
        // artifacts when we unfreeze the display if some different animation
        // is running.
        if (mService.okToDisplay()) {
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
                    a = mService.mAppTransition.loadAnimation(mWin.mAttrs, attr);
                }
            }
            if (WindowManagerService.DEBUG_ANIM) Slog.v(TAG,
                    "applyAnimation: win=" + this
                    + " anim=" + anim + " attr=0x" + Integer.toHexString(attr)
                    + " a=" + a
                    + " transit=" + transit
                    + " isEntrance=" + isEntrance + " Callers " + Debug.getCallers(3));
            if (a != null) {
                if (WindowManagerService.DEBUG_ANIM) {
                    RuntimeException e = null;
                    if (!WindowManagerService.HIDE_STACK_CRAWLS) {
                        e = new RuntimeException();
                        e.fillInStackTrace();
                    }
                    Slog.v(TAG, "Loaded animation " + a + " for " + this, e);
                }
                setAnimation(a);
                mAnimationIsEntrance = isEntrance;
            }
        } else {
            clearAnimation();
        }

        return mAnimation != null;
    }

    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        if (mAnimating || mLocalAnimating || mAnimationIsEntrance
                || mAnimation != null) {
            pw.print(prefix); pw.print("mAnimating="); pw.print(mAnimating);
                    pw.print(" mLocalAnimating="); pw.print(mLocalAnimating);
                    pw.print(" mAnimationIsEntrance="); pw.print(mAnimationIsEntrance);
                    pw.print(" mAnimation="); pw.println(mAnimation);
        }
        if (mHasTransformation || mHasLocalTransformation) {
            pw.print(prefix); pw.print("XForm: has=");
                    pw.print(mHasTransformation);
                    pw.print(" hasLocal="); pw.print(mHasLocalTransformation);
                    pw.print(" "); mTransformation.printShortString(pw);
                    pw.println();
        }
        if (mSurfaceControl != null) {
            if (dumpAll) {
                pw.print(prefix); pw.print("mSurface="); pw.println(mSurfaceControl);
                pw.print(prefix); pw.print("mDrawState=");
                pw.print(drawStateToString(mDrawState));
                pw.print(" mLastHidden="); pw.println(mLastHidden);
            }
            pw.print(prefix); pw.print("Surface: shown="); pw.print(mSurfaceShown);
                    pw.print(" layer="); pw.print(mSurfaceLayer);
                    pw.print(" alpha="); pw.print(mSurfaceAlpha);
                    pw.print(" rect=("); pw.print(mSurfaceX);
                    pw.print(","); pw.print(mSurfaceY);
                    pw.print(") "); pw.print(mSurfaceW);
                    pw.print(" x "); pw.println(mSurfaceH);
        }
        if (mPendingDestroySurface != null) {
            pw.print(prefix); pw.print("mPendingDestroySurface=");
                    pw.println(mPendingDestroySurface);
        }
        if (mSurfaceResized || mSurfaceDestroyDeferred) {
            pw.print(prefix); pw.print("mSurfaceResized="); pw.print(mSurfaceResized);
                    pw.print(" mSurfaceDestroyDeferred="); pw.println(mSurfaceDestroyDeferred);
        }
        if (mWin.mAttrs.type == WindowManager.LayoutParams.TYPE_UNIVERSE_BACKGROUND) {
            pw.print(prefix); pw.print("mUniverseTransform=");
                    mUniverseTransform.printShortString(pw);
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
                    pw.print(" mDsDy="); pw.print(mDsDy);
                    pw.print(" mDtDy="); pw.println(mDtDy);
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
}
