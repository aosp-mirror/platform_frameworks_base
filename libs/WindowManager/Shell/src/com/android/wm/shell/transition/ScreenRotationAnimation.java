/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.transition;

import static android.util.RotationUtils.deltaRotation;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_CROSSFADE;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT;
import static android.view.WindowManagerPolicyConstants.SCREEN_FREEZE_LAYER_BASE;

import static com.android.wm.shell.transition.DefaultSurfaceAnimator.buildSurfaceAnimation;
import static com.android.wm.shell.transition.Transitions.TAG;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.util.Slog;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.window.ScreenCapture;
import android.window.TransitionInfo;

import com.android.internal.R;
import com.android.internal.policy.TransitionAnimation;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.shared.TransactionPool;

import java.util.ArrayList;

/**
 * This class handles the rotation animation when the device is rotated.
 *
 * <p>
 * The screen rotation animation is composed of 3 different part:
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
 * </ul>
 */
class ScreenRotationAnimation {
    static final int MAX_ANIMATION_DURATION = 10 * 1000;
    static final int FLAG_HAS_WALLPAPER = 1;

    private final Context mContext;
    private final TransactionPool mTransactionPool;
    private final float[] mTmpFloats = new float[9];
    /** The leash of the changing window container. */
    private final SurfaceControl mSurfaceControl;

    private final int mAnimHint;
    private final int mStartWidth;
    private final int mStartHeight;
    private final int mEndWidth;
    private final int mEndHeight;
    private final int mStartRotation;
    private final int mEndRotation;

    /** This layer contains the actual screenshot that is to be faded out. */
    private SurfaceControl mScreenshotLayer;
    /**
     * Only used for screen rotation and not custom animations. Layered behind all other layers
     * to avoid showing any "empty" spots
     */
    private SurfaceControl mBackColorSurface;
    /** The leash using to animate screenshot layer. */
    private final SurfaceControl mAnimLeash;
    /**
     * The container with background color for {@link #mSurfaceControl}. It is only created if
     * {@link #mSurfaceControl} may be translucent. E.g. visible wallpaper with alpha < 1 (dimmed).
     * That prevents flickering of alpha blending.
     */
    private SurfaceControl mBackEffectSurface;

    // The current active animation to move from the old to the new rotated
    // state.  Which animation is run here will depend on the old and new
    // rotations.
    private Animation mRotateExitAnimation;
    private Animation mRotateEnterAnimation;
    private Animation mRotateAlphaAnimation;

    /** Intensity of light/whiteness of the layout before rotation occurs. */
    private float mStartLuma;
    /** Intensity of light/whiteness of the layout after rotation occurs. */
    private float mEndLuma;

    ScreenRotationAnimation(Context context, TransactionPool pool, Transaction t,
            TransitionInfo.Change change, SurfaceControl rootLeash, int animHint, int flags) {
        mContext = context;
        mTransactionPool = pool;
        mAnimHint = animHint;

        mSurfaceControl = change.getLeash();
        mStartWidth = change.getStartAbsBounds().width();
        mStartHeight = change.getStartAbsBounds().height();
        mEndWidth = change.getEndAbsBounds().width();
        mEndHeight = change.getEndAbsBounds().height();
        mStartRotation = change.getStartRotation();
        mEndRotation = change.getEndRotation();

        mAnimLeash = new SurfaceControl.Builder()
                .setParent(rootLeash)
                .setEffectLayer()
                .setCallsite("ShellRotationAnimation")
                .setName("Animation leash of screenshot rotation")
                .build();

        try {
            if (change.getSnapshot() != null) {
                mScreenshotLayer = change.getSnapshot();
                t.reparent(mScreenshotLayer, mAnimLeash);
                mStartLuma = change.getSnapshotLuma();
            } else {
                ScreenCapture.LayerCaptureArgs args =
                        new ScreenCapture.LayerCaptureArgs.Builder(mSurfaceControl)
                                .setCaptureSecureLayers(true)
                                .setAllowProtected(true)
                                .setSourceCrop(new Rect(0, 0, mStartWidth, mStartHeight))
                                .setHintForSeamlessTransition(true)
                                .build();
                ScreenCapture.ScreenshotHardwareBuffer screenshotBuffer =
                        ScreenCapture.captureLayers(args);
                if (screenshotBuffer == null) {
                    Slog.w(TAG, "Unable to take screenshot of display");
                    return;
                }

                mScreenshotLayer = new SurfaceControl.Builder()
                        .setParent(mAnimLeash)
                        .setBLASTLayer()
                        .setSecure(screenshotBuffer.containsSecureLayers())
                        .setOpaque(true)
                        .setCallsite("ShellRotationAnimation")
                        .setName("RotationLayer")
                        .build();

                TransitionAnimation.configureScreenshotLayer(t, mScreenshotLayer, screenshotBuffer);
                final HardwareBuffer hardwareBuffer = screenshotBuffer.getHardwareBuffer();
                t.show(mScreenshotLayer);
                if (!isCustomRotate()) {
                    mStartLuma = TransitionAnimation.getBorderLuma(hardwareBuffer,
                            screenshotBuffer.getColorSpace(), mSurfaceControl);
                }
                hardwareBuffer.close();
            }
            if ((flags & FLAG_HAS_WALLPAPER) != 0) {
                mBackEffectSurface = new SurfaceControl.Builder()
                        .setCallsite("ShellRotationAnimation").setParent(rootLeash)
                        .setEffectLayer().setOpaque(true).setName("BackEffect").build();
                t.reparent(mSurfaceControl, mBackEffectSurface)
                        .setColor(mBackEffectSurface,
                                new float[] {mStartLuma, mStartLuma, mStartLuma})
                        .show(mBackEffectSurface);
            }

            t.setLayer(mAnimLeash, SCREEN_FREEZE_LAYER_BASE);
            t.show(mAnimLeash);
            // Crop the real content in case it contains a larger child layer, e.g. wallpaper.
            t.setCrop(getEnterSurface(), new Rect(0, 0, mEndWidth, mEndHeight));

            if (!isCustomRotate()) {
                mBackColorSurface = new SurfaceControl.Builder()
                        .setParent(rootLeash)
                        .setColorLayer()
                        .setOpaque(true)
                        .setCallsite("ShellRotationAnimation")
                        .setName("BackColorSurface")
                        .build();

                t.setLayer(mBackColorSurface, -1);
                t.setColor(mBackColorSurface, new float[]{mStartLuma, mStartLuma, mStartLuma});
                t.show(mBackColorSurface);
            }

        } catch (Surface.OutOfResourcesException e) {
            Slog.w(TAG, "Unable to allocate freeze surface", e);
        }

        setScreenshotTransform(t);
        t.apply();
    }

    private boolean isCustomRotate() {
        return mAnimHint == ROTATION_ANIMATION_CROSSFADE || mAnimHint == ROTATION_ANIMATION_JUMPCUT;
    }

    /** Returns the surface which contains the real content to animate enter. */
    private SurfaceControl getEnterSurface() {
        return mBackEffectSurface != null ? mBackEffectSurface : mSurfaceControl;
    }

    private void setScreenshotTransform(SurfaceControl.Transaction t) {
        if (mScreenshotLayer == null) {
            return;
        }
        final Matrix matrix = new Matrix();
        final int delta = deltaRotation(mEndRotation, mStartRotation);
        if (delta != 0) {
            // Compute the transformation matrix that must be applied to the snapshot to make it
            // stay in the same original position with the current screen rotation.
            switch (delta) {
                case Surface.ROTATION_90:
                    matrix.setRotate(90, 0, 0);
                    matrix.postTranslate(mStartHeight, 0);
                    break;
                case Surface.ROTATION_180:
                    matrix.setRotate(180, 0, 0);
                    matrix.postTranslate(mStartWidth, mStartHeight);
                    break;
                case Surface.ROTATION_270:
                    matrix.setRotate(270, 0, 0);
                    matrix.postTranslate(0, mStartWidth);
                    break;
            }
        } else if ((mEndWidth > mStartWidth) == (mEndHeight > mStartHeight)
                && (mEndWidth != mStartWidth || mEndHeight != mStartHeight)) {
            // Display resizes without rotation change.
            final float scale = Math.max((float) mEndWidth / mStartWidth,
                    (float) mEndHeight / mStartHeight);
            matrix.setScale(scale, scale);
        }
        matrix.getValues(mTmpFloats);
        float x = mTmpFloats[Matrix.MTRANS_X];
        float y = mTmpFloats[Matrix.MTRANS_Y];
        t.setPosition(mScreenshotLayer, x, y);
        t.setMatrix(mScreenshotLayer,
                mTmpFloats[Matrix.MSCALE_X], mTmpFloats[Matrix.MSKEW_Y],
                mTmpFloats[Matrix.MSKEW_X], mTmpFloats[Matrix.MSCALE_Y]);
    }

    /**
     * Returns true if any animations were added to `animations`.
     */
    boolean buildAnimation(@NonNull ArrayList<Animator> animations,
            @NonNull Runnable finishCallback, float animationScale,
            @NonNull ShellExecutor mainExecutor) {
        if (mScreenshotLayer == null) {
            // Can't do animation.
            return false;
        }

        // TODO : Found a way to get right end luma and re-enable color frame animation.
        // End luma value is very not stable so it will cause more flicker is we run background
        // color frame animation.
        //mEndLuma = getLumaOfSurfaceControl(mEndBounds, mSurfaceControl);

        final boolean customRotate = isCustomRotate();
        if (customRotate) {
            mRotateExitAnimation = AnimationUtils.loadAnimation(mContext,
                    mAnimHint == ROTATION_ANIMATION_JUMPCUT ? R.anim.rotation_animation_jump_exit
                            : R.anim.rotation_animation_xfade_exit);
            mRotateEnterAnimation = AnimationUtils.loadAnimation(mContext,
                    R.anim.rotation_animation_enter);
            mRotateAlphaAnimation = AnimationUtils.loadAnimation(mContext,
                    R.anim.screen_rotate_alpha);
        } else {
            // Figure out how the screen has moved from the original rotation.
            int delta = deltaRotation(mEndRotation, mStartRotation);
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

        mRotateExitAnimation.initialize(mEndWidth, mEndHeight, mStartWidth, mStartHeight);
        mRotateExitAnimation.restrictDuration(MAX_ANIMATION_DURATION);
        mRotateExitAnimation.scaleCurrentDuration(animationScale);
        mRotateEnterAnimation.initialize(mEndWidth, mEndHeight, mStartWidth, mStartHeight);
        mRotateEnterAnimation.restrictDuration(MAX_ANIMATION_DURATION);
        mRotateEnterAnimation.scaleCurrentDuration(animationScale);

        if (customRotate) {
            mRotateAlphaAnimation.initialize(mEndWidth, mEndHeight, mStartWidth, mStartHeight);
            mRotateAlphaAnimation.restrictDuration(MAX_ANIMATION_DURATION);
            mRotateAlphaAnimation.scaleCurrentDuration(animationScale);

            buildScreenshotAlphaAnimation(animations, finishCallback, mainExecutor);
            startDisplayRotation(animations, finishCallback, mainExecutor);
        } else {
            startDisplayRotation(animations, finishCallback, mainExecutor);
            startScreenshotRotationAnimation(animations, finishCallback, mainExecutor);
            if (mBackEffectSurface != null && mStartLuma > 0.1f) {
                // Animate from the color of background to black for smooth alpha blending.
                buildLumaAnimation(animations, mStartLuma, 0f /* endLuma */, mBackEffectSurface,
                        animationScale, finishCallback, mainExecutor);
            }
        }

        return true;
    }

    private void startDisplayRotation(@NonNull ArrayList<Animator> animations,
            @NonNull Runnable finishCallback, @NonNull ShellExecutor mainExecutor) {
        buildSurfaceAnimation(animations, mRotateEnterAnimation, getEnterSurface(), finishCallback,
                mTransactionPool, mainExecutor, null /* position */, 0 /* cornerRadius */,
                null /* clipRect */, false /* isActivity */);
    }

    private void startScreenshotRotationAnimation(@NonNull ArrayList<Animator> animations,
            @NonNull Runnable finishCallback, @NonNull ShellExecutor mainExecutor) {
        buildSurfaceAnimation(animations, mRotateExitAnimation, mAnimLeash, finishCallback,
                mTransactionPool, mainExecutor, null /* position */, 0 /* cornerRadius */,
                null /* clipRect */, false /* isActivity */);
    }

    private void buildScreenshotAlphaAnimation(@NonNull ArrayList<Animator> animations,
            @NonNull Runnable finishCallback, @NonNull ShellExecutor mainExecutor) {
        buildSurfaceAnimation(animations, mRotateAlphaAnimation, mAnimLeash, finishCallback,
                mTransactionPool, mainExecutor, null /* position */, 0 /* cornerRadius */,
                null /* clipRect */, false /* isActivity */);
    }

    private void buildLumaAnimation(@NonNull ArrayList<Animator> animations,
            float startLuma, float endLuma, SurfaceControl surface, float animationScale,
            @NonNull Runnable finishCallback, @NonNull ShellExecutor mainExecutor) {
        final long durationMillis = (long) (mContext.getResources().getInteger(
                R.integer.config_screen_rotation_color_transition) * animationScale);
        final LumaAnimation animation = new LumaAnimation(durationMillis);
        // Align the end with the enter animation.
        animation.setStartOffset(mRotateEnterAnimation.getDuration() - durationMillis);
        final LumaAnimationAdapter adapter = new LumaAnimationAdapter(surface, startLuma, endLuma);
        DefaultSurfaceAnimator.buildSurfaceAnimation(animations, animation, finishCallback,
                mTransactionPool, mainExecutor, adapter);
    }

    public void kill() {
        final Transaction t = mTransactionPool.acquire();
        if (mAnimLeash.isValid()) {
            t.remove(mAnimLeash);
        }

        if (mScreenshotLayer != null && mScreenshotLayer.isValid()) {
            t.remove(mScreenshotLayer);
        }
        if (mBackColorSurface != null && mBackColorSurface.isValid()) {
            t.remove(mBackColorSurface);
        }
        if (mBackEffectSurface != null && mBackEffectSurface.isValid()) {
            t.remove(mBackEffectSurface);
        }
        t.apply();
        mTransactionPool.release(t);
    }

    /** A no-op wrapper to provide animation duration. */
    private static class LumaAnimation extends Animation {
        LumaAnimation(long durationMillis) {
            setDuration(durationMillis);
        }
    }

    private static class LumaAnimationAdapter extends DefaultSurfaceAnimator.AnimationAdapter {
        final float[] mColorArray = new float[3];
        final float mStartLuma;
        final float mEndLuma;
        final AccelerateInterpolator mInterpolation;

        LumaAnimationAdapter(@NonNull SurfaceControl leash, float startLuma, float endLuma) {
            super(leash);
            mStartLuma = startLuma;
            mEndLuma = endLuma;
            // Make the initial progress color lighter if the background is light. That avoids
            // darker content when fading into the entering surface.
            final float factor = Math.min(3f, (Math.max(0.5f, mStartLuma) - 0.5f) * 10);
            Slog.d(TAG, "Luma=" + mStartLuma + " factor=" + factor);
            mInterpolation = factor > 0.5f ? new AccelerateInterpolator(factor) : null;
        }

        @Override
        void applyTransformation(ValueAnimator animator, long currentPlayTime) {
            final float fraction = mInterpolation != null
                    ? mInterpolation.getInterpolation(animator.getAnimatedFraction())
                    : animator.getAnimatedFraction();
            final float luma = mStartLuma + fraction * (mEndLuma - mStartLuma);
            mColorArray[0] = luma;
            mColorArray[1] = luma;
            mColorArray[2] = luma;
            mTransaction.setColor(mLeash, mColorArray);
        }
    }
}
