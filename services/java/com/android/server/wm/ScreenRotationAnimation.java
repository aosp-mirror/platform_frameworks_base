/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.util.Slog;
import android.view.Surface;
import android.view.SurfaceSession;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Transformation;

class ScreenRotationAnimation {
    static final String TAG = "ScreenRotationAnimation";
    static final boolean DEBUG_STATE = false;
    static final boolean DEBUG_TRANSFORMS = false;

    static final int FREEZE_LAYER = WindowManagerService.TYPE_LAYER_MULTIPLIER * 200;

    final Context mContext;
    Surface mSurface;
    BlackFrame mBlackFrame;
    int mWidth, mHeight;

    int mSnapshotRotation;
    int mSnapshotDeltaRotation;
    int mOriginalRotation;
    int mOriginalWidth, mOriginalHeight;
    int mCurRotation;

    // For all animations, "exit" is for the UI elements that are going
    // away (that is the snapshot of the old screen), and "enter" is for
    // the new UI elements that are appearing (that is the active windows
    // in their final orientation).

    // The starting animation for the exiting and entering elements.  This
    // animation applies a transformation while the rotation is in progress.
    // It is started immediately, before the new entering UI is ready.
    Animation mStartExitAnimation;
    final Transformation mStartExitTransformation = new Transformation();
    Animation mStartEnterAnimation;
    final Transformation mStartEnterTransformation = new Transformation();

    // The finishing animation for the exiting and entering elements.  This
    // animation needs to undo the transformation of the starting animation.
    // It starts running once the new rotation UI elements are ready to be
    // displayed.
    Animation mFinishExitAnimation;
    final Transformation mFinishExitTransformation = new Transformation();
    Animation mFinishEnterAnimation;
    final Transformation mFinishEnterTransformation = new Transformation();

    // The current active animation to move from the old to the new rotated
    // state.  Which animation is run here will depend on the old and new
    // rotations.
    Animation mRotateExitAnimation;
    final Transformation mRotateExitTransformation = new Transformation();
    Animation mRotateEnterAnimation;
    final Transformation mRotateEnterTransformation = new Transformation();

    // A previously running rotate animation.  This will be used if we need
    // to switch to a new rotation before finishing the previous one.
    Animation mLastRotateExitAnimation;
    final Transformation mLastRotateExitTransformation = new Transformation();
    Animation mLastRotateEnterAnimation;
    final Transformation mLastRotateEnterTransformation = new Transformation();

    // Complete transformations being applied.
    final Transformation mExitTransformation = new Transformation();
    final Transformation mEnterTransformation = new Transformation();

    boolean mStarted;
    boolean mAnimRunning;
    boolean mFinishAnimReady;
    long mFinishAnimStartTime;

    final Matrix mSnapshotInitialMatrix = new Matrix();
    final Matrix mSnapshotFinalMatrix = new Matrix();
    final Matrix mTmpMatrix = new Matrix();
    final float[] mTmpFloats = new float[9];

    public ScreenRotationAnimation(Context context, SurfaceSession session,
            boolean inTransaction, int originalWidth, int originalHeight, int originalRotation) {
        mContext = context;

        // Screenshot does NOT include rotation!
        mSnapshotRotation = 0;
        if (originalRotation == Surface.ROTATION_90
                || originalRotation == Surface.ROTATION_270) {
            mWidth = originalHeight;
            mHeight = originalWidth;
        } else {
            mWidth = originalWidth;
            mHeight = originalHeight;
        }

        mOriginalRotation = originalRotation;
        mOriginalWidth = originalWidth;
        mOriginalHeight = originalHeight;

        if (!inTransaction) {
            if (WindowManagerService.SHOW_LIGHT_TRANSACTIONS) Slog.i(WindowManagerService.TAG,
                    ">>> OPEN TRANSACTION ScreenRotationAnimation");
            Surface.openTransaction();
        }
        
        try {
            try {
                mSurface = new Surface(session, 0, "FreezeSurface",
                        -1, mWidth, mHeight, PixelFormat.OPAQUE, Surface.FX_SURFACE_SCREENSHOT | Surface.HIDDEN);
                if (mSurface == null || !mSurface.isValid()) {
                    // Screenshot failed, punt.
                    mSurface = null;
                    return;
                }
                mSurface.setLayer(FREEZE_LAYER + 1);
                mSurface.show();
            } catch (Surface.OutOfResourcesException e) {
                Slog.w(TAG, "Unable to allocate freeze surface", e);
            }

            if (WindowManagerService.SHOW_TRANSACTIONS ||
                    WindowManagerService.SHOW_SURFACE_ALLOC) Slog.i(WindowManagerService.TAG,
                            "  FREEZE " + mSurface + ": CREATE");

            setRotation(originalRotation);
        } finally {
            if (!inTransaction) {
                Surface.closeTransaction();
                if (WindowManagerService.SHOW_LIGHT_TRANSACTIONS) Slog.i(WindowManagerService.TAG,
                        "<<< CLOSE TRANSACTION ScreenRotationAnimation");
            }
        }
    }

    boolean hasScreenshot() {
        return mSurface != null;
    }

    static int deltaRotation(int oldRotation, int newRotation) {
        int delta = newRotation - oldRotation;
        if (delta < 0) delta += 4;
        return delta;
    }

    void setSnapshotTransform(Matrix matrix, float alpha) {
        if (mSurface != null) {
            matrix.getValues(mTmpFloats);
            mSurface.setPosition(mTmpFloats[Matrix.MTRANS_X],
                    mTmpFloats[Matrix.MTRANS_Y]);
            mSurface.setMatrix(
                    mTmpFloats[Matrix.MSCALE_X], mTmpFloats[Matrix.MSKEW_Y],
                    mTmpFloats[Matrix.MSKEW_X], mTmpFloats[Matrix.MSCALE_Y]);
            mSurface.setAlpha(alpha);
            if (DEBUG_TRANSFORMS) {
                float[] srcPnts = new float[] { 0, 0, mWidth, mHeight };
                float[] dstPnts = new float[4];
                matrix.mapPoints(dstPnts, srcPnts);
                Slog.i(TAG, "Original  : (" + srcPnts[0] + "," + srcPnts[1]
                        + ")-(" + srcPnts[2] + "," + srcPnts[3] + ")");
                Slog.i(TAG, "Transformed: (" + dstPnts[0] + "," + dstPnts[1]
                        + ")-(" + dstPnts[2] + "," + dstPnts[3] + ")");
            }
        }
    }

    public static void createRotationMatrix(int rotation, int width, int height,
            Matrix outMatrix) {
        switch (rotation) {
            case Surface.ROTATION_0:
                outMatrix.reset();
                break;
            case Surface.ROTATION_90:
                outMatrix.setRotate(90, 0, 0);
                outMatrix.postTranslate(height, 0);
                break;
            case Surface.ROTATION_180:
                outMatrix.setRotate(180, 0, 0);
                outMatrix.postTranslate(width, height);
                break;
            case Surface.ROTATION_270:
                outMatrix.setRotate(270, 0, 0);
                outMatrix.postTranslate(0, width);
                break;
        }
    }

    // Must be called while in a transaction.
    private void setRotation(int rotation) {
        mCurRotation = rotation;

        // Compute the transformation matrix that must be applied
        // to the snapshot to make it stay in the same original position
        // with the current screen rotation.
        int delta = deltaRotation(rotation, mSnapshotRotation);
        createRotationMatrix(delta, mWidth, mHeight, mSnapshotInitialMatrix);

        if (DEBUG_STATE) Slog.v(TAG, "**** ROTATION: " + delta);
        setSnapshotTransform(mSnapshotInitialMatrix, 1.0f);
    }

    // Must be called while in a transaction.
    public boolean setRotation(int rotation, SurfaceSession session,
            long maxAnimationDuration, float animationScale, int finalWidth, int finalHeight) {
        setRotation(rotation);
        return startAnimation(session, maxAnimationDuration, animationScale,
                finalWidth, finalHeight, false);
    }

    /**
     * Returns true if animating.
     */
    private boolean startAnimation(SurfaceSession session, long maxAnimationDuration,
            float animationScale, int finalWidth, int finalHeight, boolean dismissing) {
        if (mSurface == null) {
            // Can't do animation.
            return false;
        }
        if (mStarted) {
            return true;
        }

        mStarted = true;

        boolean firstStart = false;

        // Figure out how the screen has moved from the original rotation.
        int delta = deltaRotation(mCurRotation, mOriginalRotation);

        if (mFinishExitAnimation == null && (!dismissing || delta != Surface.ROTATION_0)) {
            if (DEBUG_STATE) Slog.v(TAG, "Creating start and finish animations");
            firstStart = true;
            mStartExitAnimation = AnimationUtils.loadAnimation(mContext,
                    com.android.internal.R.anim.screen_rotate_start_exit);
            mStartEnterAnimation = AnimationUtils.loadAnimation(mContext,
                    com.android.internal.R.anim.screen_rotate_start_enter);
            mFinishExitAnimation = AnimationUtils.loadAnimation(mContext,
                    com.android.internal.R.anim.screen_rotate_finish_exit);
            mFinishEnterAnimation = AnimationUtils.loadAnimation(mContext,
                    com.android.internal.R.anim.screen_rotate_finish_enter);
        }

        if (DEBUG_STATE) Slog.v(TAG, "Rotation delta: " + delta + " finalWidth="
                + finalWidth + " finalHeight=" + finalHeight
                + " origWidth=" + mOriginalWidth + " origHeight=" + mOriginalHeight);

        switch (delta) {
            case Surface.ROTATION_0:
                mRotateExitAnimation = AnimationUtils.loadAnimation(mContext,
                        com.android.internal.R.anim.screen_rotate_0_exit);
                mRotateEnterAnimation = AnimationUtils.loadAnimation(mContext,
                        com.android.internal.R.anim.screen_rotate_0_enter);
                break;
            case Surface.ROTATION_90:
                mRotateExitAnimation = AnimationUtils.loadAnimation(mContext,
                        com.android.internal.R.anim.screen_rotate_plus_90_exit);
                mRotateEnterAnimation = AnimationUtils.loadAnimation(mContext,
                        com.android.internal.R.anim.screen_rotate_plus_90_enter);
                break;
            case Surface.ROTATION_180:
                mRotateExitAnimation = AnimationUtils.loadAnimation(mContext,
                        com.android.internal.R.anim.screen_rotate_180_exit);
                mRotateEnterAnimation = AnimationUtils.loadAnimation(mContext,
                        com.android.internal.R.anim.screen_rotate_180_enter);
                break;
            case Surface.ROTATION_270:
                mRotateExitAnimation = AnimationUtils.loadAnimation(mContext,
                        com.android.internal.R.anim.screen_rotate_minus_90_exit);
                mRotateEnterAnimation = AnimationUtils.loadAnimation(mContext,
                        com.android.internal.R.anim.screen_rotate_minus_90_enter);
                break;
        }

        // Initialize the animations.  This is a hack, redefining what "parent"
        // means to allow supplying the last and next size.  In this definition
        // "%p" is the original (let's call it "previous") size, and "%" is the
        // screen's current/new size.
        if (firstStart) {
            if (DEBUG_STATE) Slog.v(TAG, "Initializing start and finish animations");
            mStartEnterAnimation.initialize(finalWidth, finalHeight,
                    mOriginalWidth, mOriginalHeight);
            mStartExitAnimation.initialize(finalWidth, finalHeight,
                    mOriginalWidth, mOriginalHeight);
            mFinishEnterAnimation.initialize(finalWidth, finalHeight,
                    mOriginalWidth, mOriginalHeight);
            mFinishExitAnimation.initialize(finalWidth, finalHeight,
                    mOriginalWidth, mOriginalHeight);
        }
        mRotateEnterAnimation.initialize(finalWidth, finalHeight, mOriginalWidth, mOriginalHeight);
        mRotateExitAnimation.initialize(finalWidth, finalHeight, mOriginalWidth, mOriginalHeight);
        mAnimRunning = false;
        mFinishAnimReady = false;
        mFinishAnimStartTime = -1;

        if (firstStart) {
            mStartExitAnimation.restrictDuration(maxAnimationDuration);
            mStartExitAnimation.scaleCurrentDuration(animationScale);
            mStartEnterAnimation.restrictDuration(maxAnimationDuration);
            mStartEnterAnimation.scaleCurrentDuration(animationScale);
            mFinishExitAnimation.restrictDuration(maxAnimationDuration);
            mFinishExitAnimation.scaleCurrentDuration(animationScale);
            mFinishEnterAnimation.restrictDuration(maxAnimationDuration);
            mFinishEnterAnimation.scaleCurrentDuration(animationScale);
        }
        mRotateExitAnimation.restrictDuration(maxAnimationDuration);
        mRotateExitAnimation.scaleCurrentDuration(animationScale);
        mRotateEnterAnimation.restrictDuration(maxAnimationDuration);
        mRotateEnterAnimation.scaleCurrentDuration(animationScale);

        if (mBlackFrame == null) {
            if (WindowManagerService.SHOW_LIGHT_TRANSACTIONS || DEBUG_STATE) Slog.i(
                    WindowManagerService.TAG,
                    ">>> OPEN TRANSACTION ScreenRotationAnimation.startAnimation");
            Surface.openTransaction();

            try {
                Rect outer = new Rect(-finalWidth*1, -finalHeight*1, finalWidth*2, finalHeight*2);
                Rect inner = new Rect(0, 0, finalWidth, finalHeight);
                mBlackFrame = new BlackFrame(session, outer, inner, FREEZE_LAYER);
            } catch (Surface.OutOfResourcesException e) {
                Slog.w(TAG, "Unable to allocate black surface", e);
            } finally {
                Surface.closeTransaction();
                if (WindowManagerService.SHOW_LIGHT_TRANSACTIONS || DEBUG_STATE) Slog.i(
                        WindowManagerService.TAG,
                        "<<< CLOSE TRANSACTION ScreenRotationAnimation.startAnimation");
            }
        }

        return true;
    }

    /**
     * Returns true if animating.
     */
    public boolean dismiss(SurfaceSession session, long maxAnimationDuration,
            float animationScale, int finalWidth, int finalHeight) {
        if (DEBUG_STATE) Slog.v(TAG, "Dismiss!");
        if (mSurface == null) {
            // Can't do animation.
            return false;
        }
        if (!mStarted) {
            startAnimation(session, maxAnimationDuration, animationScale, finalWidth, finalHeight,
                    true);
        }
        if (!mStarted) {
            return false;
        }
        if (DEBUG_STATE) Slog.v(TAG, "Setting mFinishAnimReady = true");
        mFinishAnimReady = true;
        return true;
    }

    public void kill() {
        if (DEBUG_STATE) Slog.v(TAG, "Kill!");
        if (mSurface != null) {
            if (WindowManagerService.SHOW_TRANSACTIONS ||
                    WindowManagerService.SHOW_SURFACE_ALLOC) Slog.i(WindowManagerService.TAG,
                            "  FREEZE " + mSurface + ": DESTROY");
            mSurface.destroy();
            mSurface = null;
        }
        if (mBlackFrame != null) {
            mBlackFrame.kill();
            mBlackFrame = null;
        }
        if (mStartExitAnimation != null) {
            mStartExitAnimation.cancel();
            mStartExitAnimation = null;
        }
        if (mStartEnterAnimation != null) {
            mStartEnterAnimation.cancel();
            mStartEnterAnimation = null;
        }
        if (mFinishExitAnimation != null) {
            mFinishExitAnimation.cancel();
            mFinishExitAnimation = null;
        }
        if (mStartEnterAnimation != null) {
            mStartEnterAnimation.cancel();
            mStartEnterAnimation = null;
        }
        if (mRotateExitAnimation != null) {
            mRotateExitAnimation.cancel();
            mRotateExitAnimation = null;
        }
        if (mRotateEnterAnimation != null) {
            mRotateEnterAnimation.cancel();
            mRotateEnterAnimation = null;
        }
    }

    public boolean isAnimating() {
        return mStartEnterAnimation != null || mStartExitAnimation != null
                && mFinishEnterAnimation != null || mFinishExitAnimation != null
                && mRotateEnterAnimation != null || mRotateExitAnimation != null;
    }

    public boolean stepAnimation(long now) {
        if (!isAnimating()) {
            if (DEBUG_STATE) Slog.v(TAG, "Step: no animations running");
            return false;
        }

        if (!mAnimRunning) {
            if (DEBUG_STATE) Slog.v(TAG, "Step: starting start, finish, rotate");
            if (mStartEnterAnimation != null) {
                mStartEnterAnimation.setStartTime(now);
            }
            if (mStartExitAnimation != null) {
                mStartExitAnimation.setStartTime(now);
            }
            if (mFinishEnterAnimation != null) {
                mFinishEnterAnimation.setStartTime(0);
            }
            if (mFinishExitAnimation != null) {
                mFinishExitAnimation.setStartTime(0);
            }
            if (mRotateEnterAnimation != null) {
                mRotateEnterAnimation.setStartTime(now);
            }
            if (mRotateExitAnimation != null) {
                mRotateExitAnimation.setStartTime(now);
            }
            mAnimRunning = true;
        }

        if (mFinishAnimReady && mFinishAnimStartTime < 0) {
            if (DEBUG_STATE) Slog.v(TAG, "Step: finish anim now ready");
            mFinishAnimStartTime = now;
        }

        // If the start animation is no longer running, we want to keep its
        // transformation intact until the finish animation also completes.

        boolean moreStartExit = false;
        if (mStartExitAnimation != null) {
            mStartExitTransformation.clear();
            moreStartExit = mStartExitAnimation.getTransformation(now, mStartExitTransformation);
            if (DEBUG_TRANSFORMS) Slog.v(TAG, "Stepped start exit: " + mStartExitTransformation);
            if (!moreStartExit) {
                if (DEBUG_STATE) Slog.v(TAG, "Start exit animation done!");
                mStartExitAnimation.cancel();
                mStartExitAnimation = null;
            }
        }

        boolean moreStartEnter = false;
        if (mStartEnterAnimation != null) {
            mStartEnterTransformation.clear();
            moreStartEnter = mStartEnterAnimation.getTransformation(now, mStartEnterTransformation);
            if (DEBUG_TRANSFORMS) Slog.v(TAG, "Stepped start enter: " + mStartEnterTransformation);
            if (!moreStartEnter) {
                if (DEBUG_STATE) Slog.v(TAG, "Start enter animation done!");
                mStartEnterAnimation.cancel();
                mStartEnterAnimation = null;
            }
        }

        long finishNow = mFinishAnimReady ? (now - mFinishAnimStartTime) : 0;
        if (DEBUG_STATE) Slog.v(TAG, "Step: finishNow=" + finishNow);

        mFinishExitTransformation.clear();
        boolean moreFinishExit = false;
        if (mFinishExitAnimation != null) {
            moreFinishExit = mFinishExitAnimation.getTransformation(finishNow, mFinishExitTransformation);
            if (DEBUG_TRANSFORMS) Slog.v(TAG, "Stepped finish exit: " + mFinishExitTransformation);
            if (!moreStartExit && !moreFinishExit) {
                if (DEBUG_STATE) Slog.v(TAG, "Finish exit animation done, clearing start/finish anims!");
                mStartExitTransformation.clear();
                mFinishExitAnimation.cancel();
                mFinishExitAnimation = null;
                mFinishExitTransformation.clear();
            }
        }

        mFinishEnterTransformation.clear();
        boolean moreFinishEnter = false;
        if (mFinishEnterAnimation != null) {
            moreFinishEnter = mFinishEnterAnimation.getTransformation(finishNow, mFinishEnterTransformation);
            if (DEBUG_TRANSFORMS) Slog.v(TAG, "Stepped finish enter: " + mFinishEnterTransformation);
            if (!moreStartEnter && !moreFinishEnter) {
                if (DEBUG_STATE) Slog.v(TAG, "Finish enter animation done, clearing start/finish anims!");
                mStartEnterTransformation.clear();
                mFinishEnterAnimation.cancel();
                mFinishEnterAnimation = null;
                mFinishEnterTransformation.clear();
            }
        }

        mRotateExitTransformation.clear();
        boolean moreRotateExit = false;
        if (mRotateExitAnimation != null) {
            moreRotateExit = mRotateExitAnimation.getTransformation(now, mRotateExitTransformation);
            if (DEBUG_TRANSFORMS) Slog.v(TAG, "Stepped rotate exit: " + mRotateExitTransformation);
        }

        if (!moreFinishExit && !moreRotateExit) {
            if (DEBUG_STATE) Slog.v(TAG, "Rotate exit animation done!");
            mRotateExitAnimation.cancel();
            mRotateExitAnimation = null;
            mRotateExitTransformation.clear();
        }

        mRotateEnterTransformation.clear();
        boolean moreRotateEnter = false;
        if (mRotateEnterAnimation != null) {
            moreRotateEnter = mRotateEnterAnimation.getTransformation(now, mRotateEnterTransformation);
            if (DEBUG_TRANSFORMS) Slog.v(TAG, "Stepped rotate enter: " + mRotateEnterTransformation);
        }

        if (!moreFinishEnter && !moreRotateEnter) {
            if (DEBUG_STATE) Slog.v(TAG, "Rotate enter animation done!");
            mRotateEnterAnimation.cancel();
            mRotateEnterAnimation = null;
            mRotateEnterTransformation.clear();
        }

        mExitTransformation.set(mRotateExitTransformation);
        mExitTransformation.compose(mStartExitTransformation);
        mExitTransformation.compose(mFinishExitTransformation);

        mEnterTransformation.set(mRotateEnterTransformation);
        mEnterTransformation.compose(mStartEnterTransformation);
        mEnterTransformation.compose(mFinishEnterTransformation);

        if (DEBUG_TRANSFORMS) Slog.v(TAG, "Final exit: " + mExitTransformation);
        if (DEBUG_TRANSFORMS) Slog.v(TAG, "Final enter: " + mEnterTransformation);

        if (!moreStartExit && !moreFinishExit && !moreRotateExit) {
            if (mSurface != null) {
                if (DEBUG_STATE) Slog.v(TAG, "Exit animations done, hiding screenshot surface");
                mSurface.hide();
            }
        }

        if (!moreStartEnter && !moreFinishEnter && !moreRotateEnter) {
            if (mBlackFrame != null) {
                if (DEBUG_STATE) Slog.v(TAG, "Enter animations done, hiding black frame");
                mBlackFrame.hide();
            }
        } else {
            if (mBlackFrame != null) {
                mBlackFrame.setMatrix(mEnterTransformation.getMatrix());
            }
        }

        mSnapshotFinalMatrix.setConcat(mExitTransformation.getMatrix(), mSnapshotInitialMatrix);
        setSnapshotTransform(mSnapshotFinalMatrix, mExitTransformation.getAlpha());

        final boolean more = moreStartEnter || moreStartExit || moreFinishEnter || moreFinishExit
                || moreRotateEnter || moreRotateExit || !mFinishAnimReady;

        if (DEBUG_STATE) Slog.v(TAG, "Step: more=" + more);

        return more;
    }

    public Transformation getEnterTransformation() {
        return mEnterTransformation;
    }
}
