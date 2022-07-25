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

import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.util.RotationUtils.deltaRotation;
import static android.view.WindowManagerPolicyConstants.SCREEN_FREEZE_LAYER_BASE;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.internal.protolog.ProtoLogGroup.WM_SHOW_SURFACE_ALLOC;
import static com.android.server.wm.AnimationSpecProto.ROTATE;
import static com.android.server.wm.RotationAnimationSpecProto.DURATION_MS;
import static com.android.server.wm.RotationAnimationSpecProto.END_LUMA;
import static com.android.server.wm.RotationAnimationSpecProto.START_LUMA;
import static com.android.server.wm.ScreenRotationAnimationProto.ANIMATION_RUNNING;
import static com.android.server.wm.ScreenRotationAnimationProto.STARTED;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_SCREEN_ROTATION;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.GraphicBuffer;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.IBinder;
import android.os.Trace;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayAddress;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Transformation;

import com.android.internal.R;
import com.android.internal.protolog.common.ProtoLog;
import com.android.server.wm.SurfaceAnimator.AnimationType;
import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;
import com.android.server.wm.utils.RotationAnimationUtils;

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
 * <li> The Background color frame: <p>
 *      To have the animation seem more seamless, we add a color transitioning background behind the
 *      exiting and entering layouts. We compute the brightness of the start and end
 *      layouts and transition from the two brightness values as grayscale underneath the animation
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

    private final Context mContext;
    private final DisplayContent mDisplayContent;
    private final float[] mTmpFloats = new float[9];
    private final Transformation mRotateExitTransformation = new Transformation();
    private final Transformation mRotateEnterTransformation = new Transformation();
    // Complete transformations being applied.
    private final Matrix mSnapshotInitialMatrix = new Matrix();
    private final WindowManagerService mService;
    /** Only used for custom animations and not screen rotation. */
    private SurfaceControl mEnterBlackFrameLayer;
    /** This layer contains the actual screenshot that is to be faded out. */
    private SurfaceControl mScreenshotLayer;
    private SurfaceControl[] mRoundedCornerOverlay;
    /**
     * Only used for screen rotation and not custom animations. Layered behind all other layers
     * to avoid showing any "empty" spots
     */
    private SurfaceControl mBackColorSurface;
    private BlackFrame mEnteringBlackFrame;

    private final int mOriginalRotation;
    private final int mOriginalWidth;
    private final int mOriginalHeight;
    private int mCurRotation;

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
    private SurfaceRotationAnimationController mSurfaceRotationAnimationController;
    /** Intensity of light/whiteness of the layout before rotation occurs. */
    private float mStartLuma;
    /** Intensity of light/whiteness of the layout after rotation occurs. */
    private float mEndLuma;

    ScreenRotationAnimation(DisplayContent displayContent, @Surface.Rotation int originalRotation) {
        mService = displayContent.mWmService;
        mContext = mService.mContext;
        mDisplayContent = displayContent;
        final Rect currentBounds = displayContent.getBounds();
        final int width = currentBounds.width();
        final int height = currentBounds.height();

        // Screenshot does NOT include rotation!
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final int realOriginalRotation = displayInfo.rotation;

        mOriginalRotation = originalRotation;
        // If the delta is not zero, the rotation of display may not change, but we still want to
        // apply rotation animation because there should be a top app shown as rotated. So the
        // specified original rotation customizes the direction of animation to have better look
        // when restoring the rotated app to the same rotation as current display.
        final int delta = deltaRotation(originalRotation, realOriginalRotation);
        final boolean flipped = delta == Surface.ROTATION_90 || delta == Surface.ROTATION_270;
        mOriginalWidth = flipped ? height : width;
        mOriginalHeight = flipped ? width : height;
        final int logicalWidth = displayInfo.logicalWidth;
        final int logicalHeight = displayInfo.logicalHeight;
        final boolean isSizeChanged =
                logicalWidth > mOriginalWidth == logicalHeight > mOriginalHeight
                && (logicalWidth != mOriginalWidth || logicalHeight != mOriginalHeight);
        mSurfaceRotationAnimationController = new SurfaceRotationAnimationController();

        // Check whether the current screen contains any secure content.
        boolean isSecure = displayContent.hasSecureWindowOnScreen();
        final int displayId = displayContent.getDisplayId();
        final SurfaceControl.Transaction t = mService.mTransactionFactory.get();

        try {
            final SurfaceControl.ScreenshotHardwareBuffer screenshotBuffer;
            if (isSizeChanged) {
                final DisplayAddress address = displayInfo.address;
                if (!(address instanceof DisplayAddress.Physical)) {
                    Slog.e(TAG, "Display does not have a physical address: " + displayId);
                    return;
                }
                final DisplayAddress.Physical physicalAddress =
                        (DisplayAddress.Physical) address;
                final IBinder displayToken = SurfaceControl.getPhysicalDisplayToken(
                        physicalAddress.getPhysicalDisplayId());
                if (displayToken == null) {
                    Slog.e(TAG, "Display token is null.");
                    return;
                }
                // Temporarily not skip screenshot for the rounded corner overlays and screenshot
                // the whole display to include the rounded corner overlays.
                setSkipScreenshotForRoundedCornerOverlays(false, t);
                mRoundedCornerOverlay = displayContent.findRoundedCornerOverlays();
                final SurfaceControl.DisplayCaptureArgs captureArgs =
                        new SurfaceControl.DisplayCaptureArgs.Builder(displayToken)
                                .setSourceCrop(new Rect(0, 0, width, height))
                                .setAllowProtected(true)
                                .setCaptureSecureLayers(true)
                                .build();
                screenshotBuffer = SurfaceControl.captureDisplay(captureArgs);
            } else {
                SurfaceControl.LayerCaptureArgs captureArgs =
                        new SurfaceControl.LayerCaptureArgs.Builder(
                                displayContent.getSurfaceControl())
                                .setCaptureSecureLayers(true)
                                .setAllowProtected(true)
                                .setSourceCrop(new Rect(0, 0, width, height))
                                .build();
                screenshotBuffer = SurfaceControl.captureLayers(captureArgs);
            }

            if (screenshotBuffer == null) {
                Slog.w(TAG, "Unable to take screenshot of display " + displayId);
                return;
            }

            // If the screenshot contains secure layers, we have to make sure the
            // screenshot surface we display it in also has FLAG_SECURE so that
            // the user can not screenshot secure layers via the screenshot surface.
            if (screenshotBuffer.containsSecureLayers()) {
                isSecure = true;
            }

            mBackColorSurface = displayContent.makeChildSurface(null)
                    .setName("BackColorSurface")
                    .setColorLayer()
                    .setCallsite("ScreenRotationAnimation")
                    .build();

            String name = "RotationLayer";
            mScreenshotLayer = displayContent.makeOverlay()
                    .setName(name)
                    .setOpaque(true)
                    .setSecure(isSecure)
                    .setCallsite("ScreenRotationAnimation")
                    .setBLASTLayer()
                    .build();
            // This is the way to tell the input system to exclude this surface from occlusion
            // detection since we don't have a window for it. We do this because this window is
            // generated by the system as well as its content.
            InputMonitor.setTrustedOverlayInputInfo(mScreenshotLayer, t, displayId, name);

            mEnterBlackFrameLayer = displayContent.makeOverlay()
                    .setName("EnterBlackFrameLayer")
                    .setContainerLayer()
                    .setCallsite("ScreenRotationAnimation")
                    .build();

            HardwareBuffer hardwareBuffer = screenshotBuffer.getHardwareBuffer();
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER,
                    "ScreenRotationAnimation#getMedianBorderLuma");
            mStartLuma = RotationAnimationUtils.getMedianBorderLuma(hardwareBuffer,
                    screenshotBuffer.getColorSpace());
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);

            GraphicBuffer buffer = GraphicBuffer.createFromHardwareBuffer(
                    screenshotBuffer.getHardwareBuffer());

            t.setLayer(mScreenshotLayer, SCREEN_FREEZE_LAYER_BASE);
            t.reparent(mBackColorSurface, displayContent.getSurfaceControl());
            // If hdr layers are on-screen, e.g. picture-in-picture mode, the screenshot of
            // rotation animation is an sdr image containing tone-mapping hdr content, then
            // disable dimming effect to get avoid of hdr content being dimmed during animation.
            t.setDimmingEnabled(mScreenshotLayer, !screenshotBuffer.containsHdrLayers());
            t.setLayer(mBackColorSurface, -1);
            t.setColor(mBackColorSurface, new float[]{mStartLuma, mStartLuma, mStartLuma});
            t.setAlpha(mBackColorSurface, 1);
            t.setBuffer(mScreenshotLayer, buffer);
            t.setColorSpace(mScreenshotLayer, screenshotBuffer.getColorSpace());
            t.show(mScreenshotLayer);
            t.show(mBackColorSurface);

            if (mRoundedCornerOverlay != null) {
                for (SurfaceControl sc : mRoundedCornerOverlay) {
                    if (sc.isValid()) {
                        t.hide(sc);
                    }
                }
            }

        } catch (OutOfResourcesException e) {
            Slog.w(TAG, "Unable to allocate freeze surface", e);
        }

        // If display size is changed with the same orientation, map the bounds of screenshot to
        // the new logical display size. Currently pending transaction and RWC#mDisplayTransaction
        // are merged to global transaction, so it can be synced with display change when calling
        // DisplayManagerInternal#performTraversal(transaction).
        if (mScreenshotLayer != null && isSizeChanged) {
            displayContent.getPendingTransaction().setGeometry(mScreenshotLayer,
                    new Rect(0, 0, mOriginalWidth, mOriginalHeight),
                    new Rect(0, 0, logicalWidth, logicalHeight), Surface.ROTATION_0);
        }

        ProtoLog.i(WM_SHOW_SURFACE_ALLOC,
                "  FREEZE %s: CREATE", mScreenshotLayer);
        if (originalRotation == realOriginalRotation) {
            setRotation(t, realOriginalRotation);
        } else {
            // If the given original rotation is different from real original display rotation,
            // this is playing non-zero degree rotation animation without display rotation change,
            // so the snapshot doesn't need to be transformed.
            mCurRotation = realOriginalRotation;
            mSnapshotInitialMatrix.reset();
            setRotationTransform(t, mSnapshotInitialMatrix);
        }
        t.apply();
    }

    void setSkipScreenshotForRoundedCornerOverlays(
            boolean skipScreenshot, SurfaceControl.Transaction t) {
        mDisplayContent.forAllWindows(w -> {
            if (!w.mToken.mRoundedCornerOverlay || !w.isVisible() || !w.mWinAnimator.hasSurface()) {
                return;
            }
            t.setSkipScreenshot(w.mWinAnimator.mSurfaceController.mSurfaceControl, skipScreenshot);
        }, false);
        if (!skipScreenshot) {
            // Use sync apply to apply the change immediately, so that the next
            // SC.captureDisplay can capture the screen decor layers.
            t.apply(true /* sync */);
        }
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(STARTED, mStarted);
        proto.write(ANIMATION_RUNNING, mAnimRunning);
        proto.end(token);
    }

    boolean hasScreenshot() {
        return mScreenshotLayer != null;
    }

    private void setRotationTransform(SurfaceControl.Transaction t, Matrix matrix) {
        if (mScreenshotLayer == null) {
            return;
        }
        matrix.getValues(mTmpFloats);
        float x = mTmpFloats[Matrix.MTRANS_X];
        float y = mTmpFloats[Matrix.MTRANS_Y];
        t.setPosition(mScreenshotLayer, x, y);
        t.setMatrix(mScreenshotLayer,
                mTmpFloats[Matrix.MSCALE_X], mTmpFloats[Matrix.MSKEW_Y],
                mTmpFloats[Matrix.MSKEW_X], mTmpFloats[Matrix.MSCALE_Y]);

        t.setAlpha(mScreenshotLayer, (float) 1.0);
        t.show(mScreenshotLayer);
    }

    public void printTo(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mSurface="); pw.print(mScreenshotLayer);
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
        pw.print(prefix); pw.print("mSnapshotInitialMatrix=");
        mSnapshotInitialMatrix.dump(pw); pw.println();
    }

    public void setRotation(SurfaceControl.Transaction t, int rotation) {
        mCurRotation = rotation;

        // Compute the transformation matrix that must be applied
        // to the snapshot to make it stay in the same original position
        // with the current screen rotation.
        int delta = deltaRotation(rotation, mOriginalRotation);
        RotationAnimationUtils.createRotationMatrix(delta, mOriginalWidth, mOriginalHeight,
                mSnapshotInitialMatrix);
        setRotationTransform(t, mSnapshotInitialMatrix);
    }

    /**
     * Returns true if animating.
     */
    private boolean startAnimation(SurfaceControl.Transaction t, long maxAnimationDuration,
            float animationScale, int finalWidth, int finalHeight, int exitAnim, int enterAnim) {
        if (mScreenshotLayer == null) {
            // Can't do animation.
            return false;
        }
        if (mStarted) {
            return true;
        }

        mStarted = true;

        // Figure out how the screen has moved from the original rotation.
        int delta = deltaRotation(mCurRotation, mOriginalRotation);

        final boolean customAnim;
        if (exitAnim != 0 && enterAnim != 0) {
            customAnim = true;
            mRotateExitAnimation = AnimationUtils.loadAnimation(mContext, exitAnim);
            mRotateEnterAnimation = AnimationUtils.loadAnimation(mContext, enterAnim);
            mRotateAlphaAnimation = AnimationUtils.loadAnimation(mContext,
                    R.anim.screen_rotate_alpha);
        } else {
            customAnim = false;
            switch (delta) { /* Counter-Clockwise Rotations */
                case Surface.ROTATION_0:
                    mRotateExitAnimation = AnimationUtils.loadAnimation(mContext,
                            R.anim.screen_rotate_0_exit);
                    mRotateEnterAnimation = AnimationUtils.loadAnimation(mContext,
                            R.anim.rotation_animation_enter);
                    break;
                case Surface.ROTATION_90:
                    mRotateExitAnimation = AnimationUtils.loadAnimation(mContext,
                            R.anim.screen_rotate_plus_90_exit);
                    mRotateEnterAnimation = AnimationUtils.loadAnimation(mContext,
                            R.anim.screen_rotate_plus_90_enter);
                    break;
                case Surface.ROTATION_180:
                    mRotateExitAnimation = AnimationUtils.loadAnimation(mContext,
                            R.anim.screen_rotate_180_exit);
                    mRotateEnterAnimation = AnimationUtils.loadAnimation(mContext,
                            R.anim.screen_rotate_180_enter);
                    break;
                case Surface.ROTATION_270:
                    mRotateExitAnimation = AnimationUtils.loadAnimation(mContext,
                            R.anim.screen_rotate_minus_90_exit);
                    mRotateEnterAnimation = AnimationUtils.loadAnimation(mContext,
                            R.anim.screen_rotate_minus_90_enter);
                    break;
            }
        }

        ProtoLog.d(WM_DEBUG_ORIENTATION, "Start rotation animation. customAnim=%s, "
                        + "mCurRotation=%s, mOriginalRotation=%s",
                customAnim, Surface.rotationToString(mCurRotation),
                Surface.rotationToString(mOriginalRotation));

        mRotateExitAnimation.initialize(finalWidth, finalHeight, mOriginalWidth, mOriginalHeight);
        mRotateExitAnimation.restrictDuration(maxAnimationDuration);
        mRotateExitAnimation.scaleCurrentDuration(animationScale);
        mRotateEnterAnimation.initialize(finalWidth, finalHeight, mOriginalWidth, mOriginalHeight);
        mRotateEnterAnimation.restrictDuration(maxAnimationDuration);
        mRotateEnterAnimation.scaleCurrentDuration(animationScale);

        mAnimRunning = false;
        mFinishAnimReady = false;
        mFinishAnimStartTime = -1;

        if (customAnim) {
            mRotateAlphaAnimation.restrictDuration(maxAnimationDuration);
            mRotateAlphaAnimation.scaleCurrentDuration(animationScale);
        }

        if (customAnim && mEnteringBlackFrame == null) {
            try {
                Rect outer = new Rect(-finalWidth, -finalHeight,
                        finalWidth * 2, finalHeight * 2);
                Rect inner = new Rect(0, 0, finalWidth, finalHeight);
                mEnteringBlackFrame = new BlackFrame(mService.mTransactionFactory, t, outer, inner,
                        SCREEN_FREEZE_LAYER_BASE, mDisplayContent, false, mEnterBlackFrameLayer);
            } catch (OutOfResourcesException e) {
                Slog.w(TAG, "Unable to allocate black surface", e);
            }
        }

        if (customAnim) {
            mSurfaceRotationAnimationController.startCustomAnimation();
        } else {
            mSurfaceRotationAnimationController.startScreenRotationAnimation();
        }

        return true;
    }

    /**
     * Returns true if animating.
     */
    public boolean dismiss(SurfaceControl.Transaction t, long maxAnimationDuration,
            float animationScale, int finalWidth, int finalHeight, int exitAnim, int enterAnim) {
        if (mScreenshotLayer == null) {
            // Can't do animation.
            return false;
        }
        if (!mStarted) {
            mEndLuma = RotationAnimationUtils.getLumaOfSurfaceControl(mDisplayContent.getDisplay(),
                    mDisplayContent.getWindowingLayer());
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

        if (mScreenshotLayer != null) {
            ProtoLog.i(WM_SHOW_SURFACE_ALLOC, "  FREEZE %s: DESTROY", mScreenshotLayer);
            SurfaceControl.Transaction t = mService.mTransactionFactory.get();
            if (mScreenshotLayer.isValid()) {
                t.remove(mScreenshotLayer);
            }
            mScreenshotLayer = null;

            if (mEnterBlackFrameLayer != null) {
                if (mEnterBlackFrameLayer.isValid()) {
                    t.remove(mEnterBlackFrameLayer);
                }
                mEnterBlackFrameLayer = null;
            }
            if (mBackColorSurface != null) {
                if (mBackColorSurface.isValid()) {
                    t.remove(mBackColorSurface);
                }
                mBackColorSurface = null;
            }

            if (mRoundedCornerOverlay != null) {
                if (mDisplayContent.getRotationAnimation() == null
                        || mDisplayContent.getRotationAnimation() == this) {
                    setSkipScreenshotForRoundedCornerOverlays(true, t);
                    for (SurfaceControl sc : mRoundedCornerOverlay) {
                        if (sc.isValid()) {
                            t.show(sc);
                        }
                    }
                }
                mRoundedCornerOverlay = null;
            }
            t.apply();
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

    /**
     * Utility class that runs a {@link ScreenRotationAnimation} on the {@link
     * SurfaceAnimationRunner}.
     * <p>
     * The rotation animation supports both screen rotation and custom animations
     *
     * For custom animations:
     * <ul>
     *   <li>
     *     The screenshot layer which has an added animation of it's alpha channel
     *     ("screen_rotate_alpha") and that will be applied along with the custom animation.
     *   </li>
     *   <li> A device layer that is animated with the provided custom animation </li>
     * </ul>
     *
     * For screen rotation:
     * <ul>
     *   <li> A rotation layer that is both rotated and faded out during a single animation </li>
     *   <li> A device layer that is both rotated and faded in during a single animation </li>
     *   <li> A background color layer that transitions colors behind the first two layers </li>
     * </ul>
     *
     * {@link ScreenRotationAnimation#startAnimation(
     *     SurfaceControl.Transaction, long, float, int, int, int, int)}.
     * </ul>
     *
     * <p>
     * Thus an {@link LocalAnimationAdapter.AnimationSpec} is created for each of
     * this three {@link SurfaceControl}s which then delegates the animation to the
     * {@link ScreenRotationAnimation}.
     */
    class SurfaceRotationAnimationController {
        private SurfaceAnimator mDisplayAnimator;
        private SurfaceAnimator mScreenshotRotationAnimator;
        private SurfaceAnimator mRotateScreenAnimator;
        private SurfaceAnimator mEnterBlackFrameAnimator;

        void startCustomAnimation() {
            try {
                mService.mSurfaceAnimationRunner.deferStartingAnimations();
                mRotateScreenAnimator = startScreenshotAlphaAnimation();
                mDisplayAnimator = startDisplayRotation();
                if (mEnteringBlackFrame != null) {
                    mEnterBlackFrameAnimator = startEnterBlackFrameAnimation();
                }
            } finally {
                mService.mSurfaceAnimationRunner.continueStartingAnimations();
            }
        }

        /**
         * Start the rotation animation of the display and the screenshot on the
         * {@link SurfaceAnimationRunner}.
         */
        void startScreenRotationAnimation() {
            try {
                mService.mSurfaceAnimationRunner.deferStartingAnimations();
                mDisplayAnimator = startDisplayRotation();
                mScreenshotRotationAnimator = startScreenshotRotationAnimation();
                startColorAnimation();
            } finally {
                mService.mSurfaceAnimationRunner.continueStartingAnimations();
            }
        }

        private SimpleSurfaceAnimatable.Builder initializeBuilder() {
            return new SimpleSurfaceAnimatable.Builder()
                    .setSyncTransactionSupplier(mDisplayContent::getSyncTransaction)
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
                    this::onAnimationEnd);
        }

        private SurfaceAnimator startScreenshotAlphaAnimation() {
            return startAnimation(initializeBuilder()
                            .setSurfaceControl(mScreenshotLayer)
                            .setAnimationLeashParent(mDisplayContent.getOverlayLayer())
                            .setWidth(mDisplayContent.getSurfaceWidth())
                            .setHeight(mDisplayContent.getSurfaceHeight())
                            .build(),
                    createWindowAnimationSpec(mRotateAlphaAnimation),
                    this::onAnimationEnd);
        }

        private SurfaceAnimator startEnterBlackFrameAnimation() {
            return startAnimation(initializeBuilder()
                            .setSurfaceControl(mEnterBlackFrameLayer)
                            .setAnimationLeashParent(mDisplayContent.getOverlayLayer())
                            .build(),
                    createWindowAnimationSpec(mRotateEnterAnimation),
                    this::onAnimationEnd);
        }

        private SurfaceAnimator startScreenshotRotationAnimation() {
            return startAnimation(initializeBuilder()
                            .setSurfaceControl(mScreenshotLayer)
                            .setAnimationLeashParent(mDisplayContent.getOverlayLayer())
                            .build(),
                    createWindowAnimationSpec(mRotateExitAnimation),
                    this::onAnimationEnd);
        }


        /**
         * Applies the color change from {@link #mStartLuma} to {@link #mEndLuma} as a
         * grayscale color
         */
        private void startColorAnimation() {
            int colorTransitionMs = mContext.getResources().getInteger(
                    R.integer.config_screen_rotation_color_transition);
            final SurfaceAnimationRunner runner = mService.mSurfaceAnimationRunner;
            final float[] rgbTmpFloat = new float[3];
            final int startColor = Color.rgb(mStartLuma, mStartLuma, mStartLuma);
            final int endColor = Color.rgb(mEndLuma, mEndLuma, mEndLuma);
            final long duration = colorTransitionMs * (long) mService.getCurrentAnimatorScale();
            final ArgbEvaluator va = ArgbEvaluator.getInstance();
            runner.startAnimation(
                new LocalAnimationAdapter.AnimationSpec() {
                    @Override
                    public long getDuration() {
                        return duration;
                    }

                    @Override
                    public void apply(SurfaceControl.Transaction t, SurfaceControl leash,
                        long currentPlayTime) {
                        final float fraction = getFraction(currentPlayTime);
                        final int color = (Integer) va.evaluate(fraction, startColor, endColor);
                        Color middleColor = Color.valueOf(color);
                        rgbTmpFloat[0] = middleColor.red();
                        rgbTmpFloat[1] = middleColor.green();
                        rgbTmpFloat[2] = middleColor.blue();
                        if (leash.isValid()) {
                            t.setColor(leash, rgbTmpFloat);
                        }
                    }

                    @Override
                    public void dump(PrintWriter pw, String prefix) {
                        pw.println(prefix + "startLuma=" + mStartLuma
                                + " endLuma=" + mEndLuma
                                + " durationMs=" + colorTransitionMs);
                    }

                    @Override
                    public void dumpDebugInner(ProtoOutputStream proto) {
                        final long token = proto.start(ROTATE);
                        proto.write(START_LUMA, mStartLuma);
                        proto.write(END_LUMA, mEndLuma);
                        proto.write(DURATION_MS, colorTransitionMs);
                        proto.end(token);
                    }
                },
                mBackColorSurface, mDisplayContent.getPendingTransaction(), null);
        }

        private WindowAnimationSpec createWindowAnimationSpec(Animation mAnimation) {
            return new WindowAnimationSpec(mAnimation, new Point(0, 0) /* position */,
                    false /* canSkipFirstFrame */, 0 /* WindowCornerRadius */);
        }

        /**
         * Start an animation defined by animationSpec on a new {@link SurfaceAnimator}.
         *
         * @param animatable The animatable used for the animation.
         * @param animationSpec The spec of the animation.
         * @param animationFinishedCallback Callback passed to the {@link SurfaceAnimator}
         *                                    and called when the animation finishes.
         * @return The newly created {@link SurfaceAnimator} that as been started.
         */
        private SurfaceAnimator startAnimation(
                SurfaceAnimator.Animatable animatable,
                LocalAnimationAdapter.AnimationSpec animationSpec,
                OnAnimationFinishedCallback animationFinishedCallback) {
            SurfaceAnimator animator = new SurfaceAnimator(
                    animatable, animationFinishedCallback, mService);

            LocalAnimationAdapter localAnimationAdapter = new LocalAnimationAdapter(
                    animationSpec, mService.mSurfaceAnimationRunner);
            animator.startAnimation(mDisplayContent.getPendingTransaction(),
                    localAnimationAdapter, false, ANIMATION_TYPE_SCREEN_ROTATION);
            return animator;
        }

        private void onAnimationEnd(@AnimationType int type, AnimationAdapter anim) {
            synchronized (mService.mGlobalLock) {
                if (isAnimating()) {
                    ProtoLog.v(WM_DEBUG_ORIENTATION,
                            "ScreenRotation still animating: type: %d\n"
                                    + "mDisplayAnimator: %s\n"
                                    + "mEnterBlackFrameAnimator: %s\n"
                                    + "mRotateScreenAnimator: %s\n"
                                    + "mScreenshotRotationAnimator: %s",
                            type,
                            mDisplayAnimator != null
                                    ? mDisplayAnimator.isAnimating() : null,
                            mEnterBlackFrameAnimator != null
                                    ? mEnterBlackFrameAnimator.isAnimating() : null,
                            mRotateScreenAnimator != null
                                    ? mRotateScreenAnimator.isAnimating() : null,
                            mScreenshotRotationAnimator != null
                                    ? mScreenshotRotationAnimator.isAnimating() : null
                    );
                    return;
                }
                ProtoLog.d(WM_DEBUG_ORIENTATION, "ScreenRotationAnimation onAnimationEnd");
                mEnterBlackFrameAnimator = null;
                mScreenshotRotationAnimator = null;
                mRotateScreenAnimator = null;
                mService.mAnimator.mBulkUpdateParams |= WindowSurfacePlacer.SET_UPDATE_ROTATION;
                if (mDisplayContent.getRotationAnimation() == ScreenRotationAnimation.this) {
                    // It also invokes kill().
                    mDisplayContent.setRotationAnimation(null);
                } else {
                    kill();
                }
                mService.updateRotation(false, false);
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

            if (mBackColorSurface != null) {
                mService.mSurfaceAnimationRunner.onAnimationCancelled(mBackColorSurface);
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
