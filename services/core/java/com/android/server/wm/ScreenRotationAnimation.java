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
import android.graphics.Point;
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

/**
 * This class handles the rotation animation when the device is rotated.
 *
 * <p>
 * The screen rotation animation is composed of 4 different part:
 * <ul>
 * <li> The screenshot: <p>
 *     A screenshot of the whole screen prior the change of orientation is taken to hide the
 *     element resizing below. The screenshot is then animated to rotate and cross-fade to
 *     the new orientation with the content in the new orientation.
 *
 * <li> The windows on the display: <p>y
 *      Once the device is rotated, the screen and its content are in the new orientation. The
 *      animation first rotate the new content into the old orientation to then be able to
 *      animate to the new orientation
 *
 * <li> The exiting Blackframe: <p>
 *     Because the change of orientation might change the width and height of the content (i.e
 *     when rotating from portrait to landscape) we "crop" the new content using black frames
 *     around the screenshot so the new content does not go beyond the screenshot's bounds
 *
 * <li> The entering Blackframe: <p>
 *     The enter Blackframe is similar to the exit Blackframe but is only used when a custom
 *     rotation animation is used and matches the new content size instead of the screenshot.
 * </ul>
 *
 * Each part has its own Surface which are then animated by {@link SurfaceAnimator}s.
 */
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
    private SurfaceControl mEnterBlackFrameLayer;
    private SurfaceControl mRotationLayer;
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
    private Animation mRotateAlphaAnimation;
    private boolean mStarted;
    private boolean mAnimRunning;
    private boolean mFinishAnimReady;
    private long mFinishAnimStartTime;
    private boolean mForceDefaultOrientation;
    private BlackFrame mExitingBlackFrame;
    private SurfaceRotationAnimationController mSurfaceRotationAnimationController;

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
        mSurfaceRotationAnimationController = new SurfaceRotationAnimationController();

        final SurfaceControl.Transaction t = mService.mTransactionFactory.get();
        try {
            mRotationLayer = displayContent.makeOverlay()
                    .setName("RotationLayer")
                    .setContainerLayer()
                    .build();

            mEnterBlackFrameLayer = displayContent.makeOverlay()
                    .setName("EnterBlackFrameLayer")
                    .setContainerLayer()
                    .build();

            mSurfaceControl = displayContent.makeSurface(null)
                    .setName("ScreenshotSurface")
                    .setParent(mRotationLayer)
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
                t.setLayer(mRotationLayer, SCREEN_FREEZE_LAYER_BASE);
                t.setLayer(mSurfaceControl, SCREEN_FREEZE_LAYER_SCREENSHOT);
                t.setAlpha(mSurfaceControl, 0);
                t.show(mRotationLayer);
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

    private void setRotationTransform(SurfaceControl.Transaction t, Matrix matrix) {
        if (mRotationLayer == null) {
            return;
        }
        matrix.getValues(mTmpFloats);
        float x = mTmpFloats[Matrix.MTRANS_X];
        float y = mTmpFloats[Matrix.MTRANS_Y];
        if (mForceDefaultOrientation) {
            mDisplayContent.getBounds(mCurrentDisplayRect);
            x -= mCurrentDisplayRect.left;
            y -= mCurrentDisplayRect.top;
        }
        t.setPosition(mRotationLayer, x, y);
        t.setMatrix(mRotationLayer,
                mTmpFloats[Matrix.MSCALE_X], mTmpFloats[Matrix.MSKEW_Y],
                mTmpFloats[Matrix.MSKEW_X], mTmpFloats[Matrix.MSCALE_Y]);

        t.setAlpha(mSurfaceControl, (float) 1.0);
        t.setAlpha(mRotationLayer, (float) 1.0);
        t.show(mRotationLayer);
    }

    public void printTo(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mSurface="); pw.print(mSurfaceControl);
        pw.print(" mWidth="); pw.print(mWidth);
        pw.print(" mHeight="); pw.println(mHeight);
        pw.print(prefix); pw.print("mExitingBlackFrame="); pw.println(mExitingBlackFrame);
        if (mExitingBlackFrame != null) {
            mExitingBlackFrame.printTo(prefix + "  ", pw);
        }
        pw.print(prefix);
        pw.print("mEnteringBlackFrame=");
        pw.println(mEnteringBlackFrame);
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

        setRotationTransform(t, mSnapshotInitialMatrix);
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

        mRotateAlphaAnimation = AnimationUtils.loadAnimation(mContext,
                com.android.internal.R.anim.screen_rotate_alpha);

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
        mRotateAlphaAnimation.restrictDuration(maxAnimationDuration);
        mRotateAlphaAnimation.scaleCurrentDuration(animationScale);

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
                    outer = new Rect(-mWidth, -mHeight, mWidth * 2, mHeight * 2);
                    inner = new Rect(0, 0, mWidth, mHeight);
                }
                mExitingBlackFrame = new BlackFrame(mService.mTransactionFactory, t, outer, inner,
                        SCREEN_FREEZE_LAYER_EXIT, mDisplayContent, mForceDefaultOrientation,
                        mRotationLayer);
            } catch (OutOfResourcesException e) {
                Slog.w(TAG, "Unable to allocate black surface", e);
            }
        }

        if (customAnim && mEnteringBlackFrame == null) {
            try {
                Rect outer = new Rect(-finalWidth, -finalHeight,
                        finalWidth * 2, finalHeight * 2);
                Rect inner = new Rect(0, 0, finalWidth, finalHeight);
                mEnteringBlackFrame = new BlackFrame(mService.mTransactionFactory, t, outer, inner,
                        SCREEN_FREEZE_LAYER_ENTER, mDisplayContent, false, mEnterBlackFrameLayer);
            } catch (OutOfResourcesException e) {
                Slog.w(TAG, "Unable to allocate black surface", e);
            }
        }

        mSurfaceRotationAnimationController.startAnimation();
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
        if (mSurfaceRotationAnimationController != null) {
            mSurfaceRotationAnimationController.cancel();
            mSurfaceRotationAnimationController = null;
        }
        if (mSurfaceControl != null) {
            if (SHOW_TRANSACTIONS ||
                    SHOW_SURFACE_ALLOC) {
                Slog.i(TAG_WM,
                        "  FREEZE " + mSurfaceControl + ": DESTROY");
            }
            mSurfaceControl = null;
            SurfaceControl.Transaction t = mService.mTransactionFactory.get();
            if (mRotationLayer != null) {
                if (mRotationLayer.isValid()) {
                    t.remove(mRotationLayer);
                }
                mRotationLayer = null;
            }
            if (mEnterBlackFrameLayer != null) {
                if (mEnterBlackFrameLayer.isValid()) {
                    t.remove(mEnterBlackFrameLayer);
                }
                mEnterBlackFrameLayer = null;
            }
            t.apply();
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
        if (mRotateAlphaAnimation != null) {
            mRotateAlphaAnimation.cancel();
            mRotateAlphaAnimation = null;
        }
    }

    public boolean isAnimating() {
        return mSurfaceRotationAnimationController != null
                && mSurfaceRotationAnimationController.isAnimating();
    }

    public boolean isRotating() {
        return mCurRotation != mOriginalRotation;
    }

    public Transformation getEnterTransformation() {
        return mEnterTransformation;
    }

    /**
     * Utility class that runs a {@link ScreenRotationAnimation} on the {@link
     * SurfaceAnimationRunner}.
     * <p>
     * The rotation animation is divided into the following hierarchy:
     * <ul>
     * <li> A first rotation layer, containing the blackframes. This layer is animated by the
     * "screen_rotate_X_exit" that applies a scale and rotate and where X is value of the rotation.
     *     <ul>
     *         <li> A child layer containing the screenshot on which is added an animation of it's
     *     alpha channel ("screen_rotate_alpha") and that will rotate with his parent layer.</li>
     *     </ul>
     * <li> A second rotation layer used when custom animations are passed in
     * {@link ScreenRotationAnimation#startAnimation(
     *     SurfaceControl.Transaction, long, float, int, int, int, int)}.
     * </ul>
     * <p>
     * Thus an {@link LocalAnimationAdapter.AnimationSpec} is created for each of
     * this three {@link SurfaceControl}s which then delegates the animation to the
     * {@link ScreenRotationAnimation}.
     */
    class SurfaceRotationAnimationController {
        private SurfaceAnimator mDisplayAnimator;
        private SurfaceAnimator mEnterBlackFrameAnimator;
        private SurfaceAnimator mScreenshotRotationAnimator;
        private SurfaceAnimator mRotateScreenAnimator;
        private final Runnable mNoopCallback = () -> { // b/141177184
        };

        /**
         * Start the rotation animation of the display and the screenshot on the
         * {@link SurfaceAnimationRunner}.
         */
        void startAnimation() {
            mRotateScreenAnimator = startScreenshotAlphaAnimation();
            mDisplayAnimator = startDisplayRotation();
            if (mExitingBlackFrame != null) {
                mScreenshotRotationAnimator = startScreenshotRotationAnimation();
            }
            if (mEnteringBlackFrame != null) {
                mEnterBlackFrameAnimator = startEnterBlackFrameAnimation();
            }
        }

        private SimpleSurfaceAnimatable.Builder initializeBuilder() {
            return new SimpleSurfaceAnimatable.Builder()
                    .setPendingTransactionSupplier(mDisplayContent::getPendingTransaction)
                    .setCommitTransactionRunnable(mDisplayContent::commitPendingTransaction)
                    .setAnimationLeashSupplier(mDisplayContent::makeOverlay);
        }

        private SurfaceAnimator startDisplayRotation() {
            return startAnimation(initializeBuilder()
                            .setAnimationLeashParent(mDisplayContent.getSurfaceControl())
                            .setSurfaceControl(mDisplayContent.getWindowingLayer())
                            .setParentSurfaceControl(mDisplayContent.getSurfaceControl())
                            .setWidth(mDisplayContent.getSurfaceWidth())
                            .setHeight(mDisplayContent.getSurfaceHeight())
                            .build(),
                    createWindowAnimationSpec(mRotateEnterAnimation),
                    this::cancel);
        }

        private SurfaceAnimator startScreenshotAlphaAnimation() {
            return startAnimation(initializeBuilder()
                            .setSurfaceControl(mSurfaceControl)
                            .setAnimationLeashParent(mRotationLayer)
                            .setWidth(mWidth)
                            .setHeight(mHeight)
                            .build(),
                    createWindowAnimationSpec(mRotateAlphaAnimation),
                    mNoopCallback);
        }

        private SurfaceAnimator startEnterBlackFrameAnimation() {
            return startAnimation(initializeBuilder()
                            .setSurfaceControl(mEnterBlackFrameLayer)
                            .setAnimationLeashParent(mDisplayContent.getOverlayLayer())
                            .build(),
                    createWindowAnimationSpec(mRotateEnterAnimation),
                    mNoopCallback);
        }

        private SurfaceAnimator startScreenshotRotationAnimation() {
            return startAnimation(initializeBuilder()
                            .setSurfaceControl(mRotationLayer)
                            .setAnimationLeashParent(mDisplayContent.getOverlayLayer())
                            .build(),
                    createWindowAnimationSpec(mRotateExitAnimation),
                    this::onAnimationEnd);
        }

        private WindowAnimationSpec createWindowAnimationSpec(Animation mAnimation) {
            return new WindowAnimationSpec(mAnimation, new Point(0, 0) /* position */,
                    false /* canSkipFirstFrame */, 0 /* WindowCornerRadius */);
        }

        /**
         * Start an animation defined by animationSpec on a new {@link SurfaceAnimator}.
         *
         * @param animatable The animatable used for the animation.
         * @param animationSpec                The spec of the animation.
         * @param animationFinishedCallback    Callback passed to the {@link SurfaceAnimator} and
         *                                    called when the animation finishes.
         * @return The newly created {@link SurfaceAnimator} that as been started.
         */
        private SurfaceAnimator startAnimation(
                SurfaceAnimator.Animatable animatable,
                LocalAnimationAdapter.AnimationSpec animationSpec,
                Runnable animationFinishedCallback) {
            SurfaceAnimator animator = new SurfaceAnimator(
                    animatable, animationFinishedCallback, mService);

            LocalAnimationAdapter localAnimationAdapter = new LocalAnimationAdapter(
                    animationSpec, mService.mSurfaceAnimationRunner);

            animator.startAnimation(mDisplayContent.getPendingTransaction(),
                    localAnimationAdapter, false);
            return animator;
        }

        private void onAnimationEnd() {
            mEnterBlackFrameAnimator = null;
            mScreenshotRotationAnimator = null;
            mRotateScreenAnimator = null;
            mService.mAnimator.mBulkUpdateParams |= WindowSurfacePlacer.SET_UPDATE_ROTATION;
            kill();
            mService.updateRotation(false, false);
            AccessibilityController accessibilityController = mService.mAccessibilityController;

            if (accessibilityController != null) {
                // We just finished rotation animation which means we did not
                // announce the rotation and waited for it to end, announce now.
                accessibilityController.onRotationChangedLocked(mDisplayContent);
            }
        }

        public void cancel() {
            if (mEnterBlackFrameAnimator != null) {
                mEnterBlackFrameAnimator.cancelAnimation();
            }

            if (mScreenshotRotationAnimator != null) {
                mScreenshotRotationAnimator.cancelAnimation();
            }

            if (mRotateScreenAnimator != null) {
                mRotateScreenAnimator.cancelAnimation();
            }

            if (mDisplayAnimator != null) {
                mDisplayAnimator.cancelAnimation();
            }
        }

        public boolean isAnimating() {
            return mDisplayAnimator != null && mDisplayAnimator.isAnimating()
                    || mEnterBlackFrameAnimator != null && mEnterBlackFrameAnimator.isAnimating()
                    || mRotateScreenAnimator != null && mRotateScreenAnimator.isAnimating()
                    || mScreenshotRotationAnimator != null
                    && mScreenshotRotationAnimator.isAnimating();
        }
    }
}
