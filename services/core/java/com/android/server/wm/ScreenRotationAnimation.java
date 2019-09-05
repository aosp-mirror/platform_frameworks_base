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

import static com.android.server.wm.ScreenRotationAnimationProto.ANIMATION_RUNNING;
import static com.android.server.wm.ScreenRotationAnimationProto.STARTED;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_SURFACE_ALLOC;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.TYPE_LAYER_MULTIPLIER;
import static com.android.server.wm.WindowStateAnimator.WINDOW_FREEZE_LAYER;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Transformation;

import java.io.PrintWriter;

class ScreenRotationAnimation {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ScreenRotationAnimation" : TAG_WM;

    /*
     * Layers for screen rotation animation. We put these layers above
     * WINDOW_FREEZE_LAYER so that screen freeze will cover all windows.
     */
    private static final int SCREEN_FREEZE_LAYER_BASE = WINDOW_FREEZE_LAYER + TYPE_LAYER_MULTIPLIER;
    private static final int SCREEN_FREEZE_LAYER_ENTER = SCREEN_FREEZE_LAYER_BASE;
    private static final int SCREEN_FREEZE_LAYER_SCREENSHOT = SCREEN_FREEZE_LAYER_BASE + 1;
    private static final int SCREEN_FREEZE_LAYER_EXIT = SCREEN_FREEZE_LAYER_BASE + 2;

    private final Context mContext;
    private final DisplayContent mDisplayContent;
    private final float[] mTmpFloats = new float[9];
    private final Transformation mRotateExitTransformation = new Transformation();
    private final Transformation mRotateEnterTransformation = new Transformation();
    // Complete transformations being applied.
    private final Transformation mExitTransformation = new Transformation();
    private final Transformation mEnterTransformation = new Transformation();
    private final Matrix mFrameInitialMatrix = new Matrix();
    private final Matrix mSnapshotInitialMatrix = new Matrix();
    private final Matrix mSnapshotFinalMatrix = new Matrix();
    private final Matrix mExitFrameFinalMatrix = new Matrix();
    private final WindowManagerService mService;
    private SurfaceControl mSurfaceControl;
    private BlackFrame mEnteringBlackFrame;
    private int mWidth, mHeight;

    private int mOriginalRotation;
    private int mOriginalWidth, mOriginalHeight;
    private int mCurRotation;

    private Rect mOriginalDisplayRect = new Rect();
    private Rect mCurrentDisplayRect = new Rect();
    // The current active animation to move from the old to the new rotated
    // state.  Which animation is run here will depend on the old and new
    // rotations.
    private Animation mRotateExitAnimation;
    private Animation mRotateEnterAnimation;
    private boolean mStarted;
    private boolean mAnimRunning;
    private boolean mFinishAnimReady;
    private long mFinishAnimStartTime;
    private boolean mForceDefaultOrientation;
    private BlackFrame mExitingBlackFrame;
    private boolean mMoreRotateEnter;
    private boolean mMoreRotateExit;
    private long mHalfwayPoint;

    public ScreenRotationAnimation(Context context, DisplayContent displayContent,
            boolean fixedToUserRotation, boolean isSecure, WindowManagerService service) {
        mService = service;
        mContext = context;
        mDisplayContent = displayContent;
        displayContent.getBounds(mOriginalDisplayRect);

        // Screenshot does NOT include rotation!
        final Display display = displayContent.getDisplay();
        int originalRotation = display.getRotation();
        final int originalWidth;
        final int originalHeight;
        DisplayInfo displayInfo = displayContent.getDisplayInfo();
        if (fixedToUserRotation) {
            // Emulated orientation.
            mForceDefaultOrientation = true;
            originalWidth = displayContent.mBaseDisplayWidth;
            originalHeight = displayContent.mBaseDisplayHeight;
        } else {
            // Normal situation
            originalWidth = displayInfo.logicalWidth;
            originalHeight = displayInfo.logicalHeight;
        }
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

        final SurfaceControl.Transaction t = mService.mTransactionFactory.get();
        try {
            mSurfaceControl = displayContent.makeOverlay()
                    .setName("ScreenshotSurface")
                    .setBufferSize(mWidth, mHeight)
                    .setSecure(isSecure)
                    .build();

            // In case display bounds change, screenshot buffer and surface may mismatch so set a
            // scaling mode.
            SurfaceControl.Transaction t2 = mService.mTransactionFactory.get();
            t2.setOverrideScalingMode(mSurfaceControl, Surface.SCALING_MODE_SCALE_TO_WINDOW);
            t2.apply(true /* sync */);

            // Capture a screenshot into the surface we just created.
            final int displayId = display.getDisplayId();
            final Surface surface = mService.mSurfaceFactory.get();
            surface.copyFrom(mSurfaceControl);
            SurfaceControl.ScreenshotGraphicBuffer gb =
                    mService.mDisplayManagerInternal.screenshot(displayId);
            if (gb != null) {
                try {
                    surface.attachAndQueueBufferWithColorSpace(gb.getGraphicBuffer(),
                            gb.getColorSpace());
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Failed to attach screenshot - " + e.getMessage());
                }
                // If the screenshot contains secure layers, we have to make sure the
                // screenshot surface we display it in also has FLAG_SECURE so that
                // the user can not screenshot secure layers via the screenshot surface.
                if (gb.containsSecureLayers()) {
                    t.setSecure(mSurfaceControl, true);
                }
                t.setLayer(mSurfaceControl, SCREEN_FREEZE_LAYER_SCREENSHOT);
                t.setAlpha(mSurfaceControl, 0);
                t.show(mSurfaceControl);
            } else {
                Slog.w(TAG, "Unable to take screenshot of display " + displayId);
            }
            surface.destroy();
        } catch (OutOfResourcesException e) {
            Slog.w(TAG, "Unable to allocate freeze surface", e);
        }

        if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) {
            Slog.i(TAG_WM,
                    "  FREEZE " + mSurfaceControl + ": CREATE");
        }
        setRotation(t, originalRotation);
        t.apply();
    }

    private static void createRotationMatrix(int rotation, int width, int height,
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

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(STARTED, mStarted);
        proto.write(ANIMATION_RUNNING, mAnimRunning);
        proto.end(token);
    }

    boolean hasScreenshot() {
        return mSurfaceControl != null;
    }

    private void setSnapshotTransform(SurfaceControl.Transaction t, Matrix matrix, float alpha) {
        if (mSurfaceControl != null) {
            matrix.getValues(mTmpFloats);
            float x = mTmpFloats[Matrix.MTRANS_X];
            float y = mTmpFloats[Matrix.MTRANS_Y];
            if (mForceDefaultOrientation) {
                mDisplayContent.getBounds(mCurrentDisplayRect);
                x -= mCurrentDisplayRect.left;
                y -= mCurrentDisplayRect.top;
            }
            t.setPosition(mSurfaceControl, x, y);
            t.setMatrix(mSurfaceControl,
                    mTmpFloats[Matrix.MSCALE_X], mTmpFloats[Matrix.MSKEW_Y],
                    mTmpFloats[Matrix.MSKEW_X], mTmpFloats[Matrix.MSCALE_Y]);
            t.setAlpha(mSurfaceControl, alpha);
        }
    }

    public void printTo(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mSurface="); pw.print(mSurfaceControl);
        pw.print(" mWidth="); pw.print(mWidth);
        pw.print(" mHeight="); pw.println(mHeight);
        pw.print(prefix); pw.print("mExitingBlackFrame="); pw.println(mExitingBlackFrame);
        if (mExitingBlackFrame != null) {
            mExitingBlackFrame.printTo(prefix + "  ", pw);
        }
        pw.print(prefix); pw.print("mEnteringBlackFrame="); pw.println(mEnteringBlackFrame);
        if (mEnteringBlackFrame != null) {
            mEnteringBlackFrame.printTo(prefix + "  ", pw);
        }
        pw.print(prefix); pw.print("mCurRotation="); pw.print(mCurRotation);
        pw.print(" mOriginalRotation="); pw.println(mOriginalRotation);
        pw.print(prefix); pw.print("mOriginalWidth="); pw.print(mOriginalWidth);
        pw.print(" mOriginalHeight="); pw.println(mOriginalHeight);
        pw.print(prefix); pw.print("mStarted="); pw.print(mStarted);
        pw.print(" mAnimRunning="); pw.print(mAnimRunning);
        pw.print(" mFinishAnimReady="); pw.print(mFinishAnimReady);
        pw.print(" mFinishAnimStartTime="); pw.println(mFinishAnimStartTime);
        pw.print(prefix); pw.print("mRotateExitAnimation="); pw.print(mRotateExitAnimation);
        pw.print(" "); mRotateExitTransformation.printShortString(pw); pw.println();
        pw.print(prefix); pw.print("mRotateEnterAnimation="); pw.print(mRotateEnterAnimation);
        pw.print(" "); mRotateEnterTransformation.printShortString(pw); pw.println();
        pw.print(prefix); pw.print("mExitTransformation=");
        mExitTransformation.printShortString(pw); pw.println();
        pw.print(prefix); pw.print("mEnterTransformation=");
        mEnterTransformation.printShortString(pw); pw.println();
        pw.print(prefix); pw.print("mFrameInitialMatrix=");
        mFrameInitialMatrix.printShortString(pw);
        pw.println();
        pw.print(prefix); pw.print("mSnapshotInitialMatrix=");
        mSnapshotInitialMatrix.printShortString(pw);
        pw.print(" mSnapshotFinalMatrix="); mSnapshotFinalMatrix.printShortString(pw);
        pw.println();
        pw.print(prefix); pw.print("mExitFrameFinalMatrix=");
        mExitFrameFinalMatrix.printShortString(pw);
        pw.println();
        pw.print(prefix); pw.print("mForceDefaultOrientation="); pw.print(mForceDefaultOrientation);
        if (mForceDefaultOrientation) {
            pw.print(" mOriginalDisplayRect="); pw.print(mOriginalDisplayRect.toShortString());
            pw.print(" mCurrentDisplayRect="); pw.println(mCurrentDisplayRect.toShortString());
        }
    }

    public void setRotation(SurfaceControl.Transaction t, int rotation) {
        mCurRotation = rotation;

        // Compute the transformation matrix that must be applied
        // to the snapshot to make it stay in the same original position
        // with the current screen rotation.
        int delta = DisplayContent.deltaRotation(rotation, Surface.ROTATION_0);
        createRotationMatrix(delta, mWidth, mHeight, mSnapshotInitialMatrix);

        setSnapshotTransform(t, mSnapshotInitialMatrix, 1.0f);
    }

    /**
     * Returns true if animating.
     */
    private boolean startAnimation(SurfaceControl.Transaction t, long maxAnimationDuration,
            float animationScale, int finalWidth, int finalHeight, int exitAnim, int enterAnim) {
        if (mSurfaceControl == null) {
            // Can't do animation.
            return false;
        }
        if (mStarted) {
            return true;
        }

        mStarted = true;

        // Figure out how the screen has moved from the original rotation.
        int delta = DisplayContent.deltaRotation(mCurRotation, mOriginalRotation);

        final boolean customAnim;
        if (exitAnim != 0 && enterAnim != 0) {
            customAnim = true;
            mRotateExitAnimation = AnimationUtils.loadAnimation(mContext, exitAnim);
            mRotateEnterAnimation = AnimationUtils.loadAnimation(mContext, enterAnim);
        } else {
            customAnim = false;
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
        }

        // Initialize the animations.  This is a hack, redefining what "parent"
        // means to allow supplying the last and next size.  In this definition
        // "%p" is the original (let's call it "previous") size, and "%" is the
        // screen's current/new size.
        mRotateEnterAnimation.initialize(finalWidth, finalHeight, mOriginalWidth, mOriginalHeight);
        mRotateExitAnimation.initialize(finalWidth, finalHeight, mOriginalWidth, mOriginalHeight);
        mAnimRunning = false;
        mFinishAnimReady = false;
        mFinishAnimStartTime = -1;

        mRotateExitAnimation.restrictDuration(maxAnimationDuration);
        mRotateExitAnimation.scaleCurrentDuration(animationScale);
        mRotateEnterAnimation.restrictDuration(maxAnimationDuration);
        mRotateEnterAnimation.scaleCurrentDuration(animationScale);

        if (!customAnim && mExitingBlackFrame == null) {
            try {
                // Compute the transformation matrix that must be applied
                // the the black frame to make it stay in the initial position
                // before the new screen rotation.  This is different than the
                // snapshot transformation because the snapshot is always based
                // of the native orientation of the screen, not the orientation
                // we were last in.
                createRotationMatrix(delta, mOriginalWidth, mOriginalHeight, mFrameInitialMatrix);

                final Rect outer;
                final Rect inner;
                if (mForceDefaultOrientation) {
                    // Going from a smaller Display to a larger Display, add curtains to sides
                    // or top and bottom. Going from a larger to smaller display will result in
                    // no BlackSurfaces being constructed.
                    outer = mCurrentDisplayRect;
                    inner = mOriginalDisplayRect;
                } else {
                    outer = new Rect(-mOriginalWidth * 1, -mOriginalHeight * 1,
                            mOriginalWidth * 2, mOriginalHeight * 2);
                    inner = new Rect(0, 0, mOriginalWidth, mOriginalHeight);
                }
                mExitingBlackFrame = new BlackFrame(mService.mTransactionFactory, t, outer, inner,
                        SCREEN_FREEZE_LAYER_EXIT, mDisplayContent, mForceDefaultOrientation);
                mExitingBlackFrame.setMatrix(t, mFrameInitialMatrix);
            } catch (OutOfResourcesException e) {
                Slog.w(TAG, "Unable to allocate black surface", e);
            }
        }

        if (customAnim && mEnteringBlackFrame == null) {
            try {
                Rect outer = new Rect(-finalWidth * 1, -finalHeight * 1,
                        finalWidth * 2, finalHeight * 2);
                Rect inner = new Rect(0, 0, finalWidth, finalHeight);
                mEnteringBlackFrame = new BlackFrame(mService.mTransactionFactory, t, outer, inner,
                        SCREEN_FREEZE_LAYER_ENTER, mDisplayContent, false);
            } catch (OutOfResourcesException e) {
                Slog.w(TAG, "Unable to allocate black surface", e);
            }
        }

        return true;
    }

    /**
     * Returns true if animating.
     */
    public boolean dismiss(SurfaceControl.Transaction t, long maxAnimationDuration,
            float animationScale, int finalWidth, int finalHeight, int exitAnim, int enterAnim) {
        if (mSurfaceControl == null) {
            // Can't do animation.
            return false;
        }
        if (!mStarted) {
            startAnimation(t, maxAnimationDuration, animationScale, finalWidth, finalHeight,
                    exitAnim, enterAnim);
        }
        if (!mStarted) {
            return false;
        }
        mFinishAnimReady = true;
        return true;
    }

    public void kill() {
        if (mSurfaceControl != null) {
            if (SHOW_TRANSACTIONS ||
                    SHOW_SURFACE_ALLOC) {
                Slog.i(TAG_WM,
                        "  FREEZE " + mSurfaceControl + ": DESTROY");
            }
            mService.mTransactionFactory.get().remove(mSurfaceControl).apply();
            mSurfaceControl = null;
        }
        if (mExitingBlackFrame != null) {
            mExitingBlackFrame.kill();
            mExitingBlackFrame = null;
        }
        if (mEnteringBlackFrame != null) {
            mEnteringBlackFrame.kill();
            mEnteringBlackFrame = null;
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
        return hasAnimations();
    }

    public boolean isRotating() {
        return mCurRotation != mOriginalRotation;
    }

    private boolean hasAnimations() {
        return mRotateEnterAnimation != null || mRotateExitAnimation != null;
    }

    private boolean stepAnimation(long now) {
        if (now > mHalfwayPoint) {
            mHalfwayPoint = Long.MAX_VALUE;
        }
        if (mFinishAnimReady && mFinishAnimStartTime < 0) {
            mFinishAnimStartTime = now;
        }

        mMoreRotateExit = false;
        if (mRotateExitAnimation != null) {
            mMoreRotateExit = mRotateExitAnimation.getTransformation(now,
                    mRotateExitTransformation);
        }

        mMoreRotateEnter = false;
        if (mRotateEnterAnimation != null) {
            mMoreRotateEnter = mRotateEnterAnimation.getTransformation(now,
                    mRotateEnterTransformation);
        }

        if (!mMoreRotateExit) {
            if (mRotateExitAnimation != null) {
                mRotateExitAnimation.cancel();
                mRotateExitAnimation = null;
                mRotateExitTransformation.clear();
            }
        }

        if (!mMoreRotateEnter) {
            if (mRotateEnterAnimation != null) {
                mRotateEnterAnimation.cancel();
                mRotateEnterAnimation = null;
                mRotateEnterTransformation.clear();
            }
        }

        mExitTransformation.set(mRotateExitTransformation);
        mEnterTransformation.set(mRotateEnterTransformation);

        final boolean more = mMoreRotateEnter || mMoreRotateExit
                || !mFinishAnimReady;

        mSnapshotFinalMatrix.setConcat(mExitTransformation.getMatrix(), mSnapshotInitialMatrix);

        return more;
    }

    void updateSurfaces(SurfaceControl.Transaction t) {
        if (!mStarted) {
            return;
        }

        if (mSurfaceControl != null) {
            if (!mMoreRotateExit) {
                t.hide(mSurfaceControl);
            }
        }

        if (mExitingBlackFrame != null) {
            if (!mMoreRotateExit) {
                mExitingBlackFrame.hide(t);
            } else {
                mExitFrameFinalMatrix.setConcat(mExitTransformation.getMatrix(),
                        mFrameInitialMatrix);
                mExitingBlackFrame.setMatrix(t, mExitFrameFinalMatrix);
                if (mForceDefaultOrientation) {
                    mExitingBlackFrame.setAlpha(t, mExitTransformation.getAlpha());
                }
            }
        }

        if (mEnteringBlackFrame != null) {
            if (!mMoreRotateEnter) {
                mEnteringBlackFrame.hide(t);
            } else {
                mEnteringBlackFrame.setMatrix(t, mEnterTransformation.getMatrix());
            }
        }

        t.setEarlyWakeup();
        setSnapshotTransform(t, mSnapshotFinalMatrix, mExitTransformation.getAlpha());
    }

    public boolean stepAnimationLocked(long now) {
        if (!hasAnimations()) {
            mFinishAnimReady = false;
            return false;
        }

        if (!mAnimRunning) {
            if (mRotateEnterAnimation != null) {
                mRotateEnterAnimation.setStartTime(now);
            }
            if (mRotateExitAnimation != null) {
                mRotateExitAnimation.setStartTime(now);
            }
            mAnimRunning = true;
            mHalfwayPoint = now + mRotateEnterAnimation.getDuration() / 2;
        }

        return stepAnimation(now);
    }

    public Transformation getEnterTransformation() {
        return mEnterTransformation;
    }
}
