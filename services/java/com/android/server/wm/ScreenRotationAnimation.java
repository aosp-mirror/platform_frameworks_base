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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.util.Slog;
import android.view.Surface;
import android.view.SurfaceSession;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Transformation;

class ScreenRotationAnimation {
    static final String TAG = "ScreenRotationAnimation";
    static final boolean DEBUG = false;

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

    Animation mExitAnimation;
    final Transformation mExitTransformation = new Transformation();
    Animation mEnterAnimation;
    final Transformation mEnterTransformation = new Transformation();
    boolean mStarted;

    final Matrix mSnapshotInitialMatrix = new Matrix();
    final Matrix mSnapshotFinalMatrix = new Matrix();
    final Matrix mTmpMatrix = new Matrix();
    final float[] mTmpFloats = new float[9];

    public ScreenRotationAnimation(Context context, SurfaceSession session,
            boolean inTransaction, int originalWidth, int originalHeight, int originalRotation) {
        mContext = context;

        Bitmap screenshot = Surface.screenshot(0, 0);

        if (screenshot == null) {
            // Device is not capable of screenshots...  we can't do an animation.
            return;
        }

        // Screenshot does NOT include rotation!
        mSnapshotRotation = 0;
        mWidth = screenshot.getWidth();
        mHeight = screenshot.getHeight();

        mOriginalRotation = originalRotation;
        mOriginalWidth = originalWidth;
        mOriginalHeight = originalHeight;

        if (!inTransaction) {
            if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(WindowManagerService.TAG,
                    ">>> OPEN TRANSACTION ScreenRotationAnimation");
            Surface.openTransaction();
        }
        
        try {
            try {
                mSurface = new Surface(session, 0, "FreezeSurface",
                        -1, mWidth, mHeight, PixelFormat.OPAQUE, 0);
                mSurface.setLayer(FREEZE_LAYER + 1);
            } catch (Surface.OutOfResourcesException e) {
                Slog.w(TAG, "Unable to allocate freeze surface", e);
            }

            if (WindowManagerService.SHOW_TRANSACTIONS ||
                    WindowManagerService.SHOW_SURFACE_ALLOC) Slog.i(WindowManagerService.TAG,
                            "  FREEZE " + mSurface + ": CREATE");

            setRotation(originalRotation);

            if (mSurface != null) {
                Rect dirty = new Rect(0, 0, mWidth, mHeight);
                Canvas c = null;
                try {
                    c = mSurface.lockCanvas(dirty);
                } catch (IllegalArgumentException e) {
                    Slog.w(TAG, "Unable to lock surface", e);
                } catch (Surface.OutOfResourcesException e) {
                    Slog.w(TAG, "Unable to lock surface", e);
                }
                if (c == null) {
                    Slog.w(TAG, "Null surface canvas");
                    mSurface.destroy();
                    mSurface = null;
                    return;
                }
        
                Paint paint = new Paint(0);
                paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
                c.drawBitmap(screenshot, 0, 0, paint);

                mSurface.unlockCanvasAndPost(c);
            }
        } finally {
            if (!inTransaction) {
                Surface.closeTransaction();
                if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(WindowManagerService.TAG,
                        "<<< CLOSE TRANSACTION ScreenRotationAnimation");
            }
    
            screenshot.recycle();
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
            if (DEBUG) {
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
    public void setRotation(int rotation) {
        mCurRotation = rotation;

        // Compute the transformation matrix that must be applied
        // to the snapshot to make it stay in the same original position
        // with the current screen rotation.
        int delta = deltaRotation(rotation, mSnapshotRotation);
        createRotationMatrix(delta, mWidth, mHeight, mSnapshotInitialMatrix);

        if (DEBUG) Slog.v(TAG, "**** ROTATION: " + delta);
        setSnapshotTransform(mSnapshotInitialMatrix, 1.0f);
    }

    /**
     * Returns true if animating.
     */
    public boolean dismiss(SurfaceSession session, long maxAnimationDuration,
            float animationScale, int finalWidth, int finalHeight) {
        if (mSurface == null) {
            // Can't do animation.
            return false;
        }

        // Figure out how the screen has moved from the original rotation.
        int delta = deltaRotation(mCurRotation, mOriginalRotation);

        switch (delta) {
            case Surface.ROTATION_0:
                mExitAnimation = AnimationUtils.loadAnimation(mContext,
                        com.android.internal.R.anim.screen_rotate_0_exit);
                mEnterAnimation = AnimationUtils.loadAnimation(mContext,
                        com.android.internal.R.anim.screen_rotate_0_enter);
                break;
            case Surface.ROTATION_90:
                mExitAnimation = AnimationUtils.loadAnimation(mContext,
                        com.android.internal.R.anim.screen_rotate_plus_90_exit);
                mEnterAnimation = AnimationUtils.loadAnimation(mContext,
                        com.android.internal.R.anim.screen_rotate_plus_90_enter);
                break;
            case Surface.ROTATION_180:
                mExitAnimation = AnimationUtils.loadAnimation(mContext,
                        com.android.internal.R.anim.screen_rotate_180_exit);
                mEnterAnimation = AnimationUtils.loadAnimation(mContext,
                        com.android.internal.R.anim.screen_rotate_180_enter);
                break;
            case Surface.ROTATION_270:
                mExitAnimation = AnimationUtils.loadAnimation(mContext,
                        com.android.internal.R.anim.screen_rotate_minus_90_exit);
                mEnterAnimation = AnimationUtils.loadAnimation(mContext,
                        com.android.internal.R.anim.screen_rotate_minus_90_enter);
                break;
        }

        // Initialize the animations.  This is a hack, redefining what "parent"
        // means to allow supplying the last and next size.  In this definition
        // "%p" is the original (let's call it "previous") size, and "%" is the
        // screen's current/new size.
        mEnterAnimation.initialize(finalWidth, finalHeight, mOriginalWidth, mOriginalHeight);
        mExitAnimation.initialize(finalWidth, finalHeight, mOriginalWidth, mOriginalHeight);
        mStarted = false;

        mExitAnimation.restrictDuration(maxAnimationDuration);
        mExitAnimation.scaleCurrentDuration(animationScale);
        mEnterAnimation.restrictDuration(maxAnimationDuration);
        mEnterAnimation.scaleCurrentDuration(animationScale);

        if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(WindowManagerService.TAG,
                ">>> OPEN TRANSACTION ScreenRotationAnimation.dismiss");
        Surface.openTransaction();

        try {
            Rect outer = new Rect(-finalWidth, -finalHeight, finalWidth * 2, finalHeight * 2);
            Rect inner = new Rect(0, 0, finalWidth, finalHeight);
            mBlackFrame = new BlackFrame(session, outer, inner, FREEZE_LAYER);
        } catch (Surface.OutOfResourcesException e) {
            Slog.w(TAG, "Unable to allocate black surface", e);
        } finally {
            Surface.closeTransaction();
            if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(WindowManagerService.TAG,
                    "<<< CLOSE TRANSACTION ScreenRotationAnimation.dismiss");
        }

        return true;
    }

    public void kill() {
        if (mSurface != null) {
            if (WindowManagerService.SHOW_TRANSACTIONS ||
                    WindowManagerService.SHOW_SURFACE_ALLOC) Slog.i(WindowManagerService.TAG,
                            "  FREEZE " + mSurface + ": DESTROY");
            mSurface.destroy();
            mSurface = null;
        }
        if (mBlackFrame != null) {
            mBlackFrame.kill();
        }
        if (mExitAnimation != null) {
            mExitAnimation.cancel();
            mExitAnimation = null;
        }
        if (mEnterAnimation != null) {
            mEnterAnimation.cancel();
            mEnterAnimation = null;
        }
    }

    public boolean isAnimating() {
        return mEnterAnimation != null || mExitAnimation != null;
    }

    public boolean stepAnimation(long now) {
        if (mEnterAnimation == null && mExitAnimation == null) {
            return false;
        }

        if (!mStarted) {
            mEnterAnimation.setStartTime(now);
            mExitAnimation.setStartTime(now);
            mStarted = true;
        }

        mExitTransformation.clear();
        boolean moreExit = false;
        if (mExitAnimation != null) {
            moreExit = mExitAnimation.getTransformation(now, mExitTransformation);
            if (DEBUG) Slog.v(TAG, "Stepped exit: " + mExitTransformation);
            if (!moreExit) {
                if (DEBUG) Slog.v(TAG, "Exit animation done!");
                mExitAnimation.cancel();
                mExitAnimation = null;
                mExitTransformation.clear();
                if (mSurface != null) {
                    mSurface.hide();
                }
            }
        }

        mEnterTransformation.clear();
        boolean moreEnter = false;
        if (mEnterAnimation != null) {
            moreEnter = mEnterAnimation.getTransformation(now, mEnterTransformation);
            if (!moreEnter) {
                mEnterAnimation.cancel();
                mEnterAnimation = null;
                mEnterTransformation.clear();
                if (mBlackFrame != null) {
                    mBlackFrame.hide();
                }
            } else {
                if (mBlackFrame != null) {
                    mBlackFrame.setMatrix(mEnterTransformation.getMatrix());
                }
            }
        }

        mSnapshotFinalMatrix.setConcat(mExitTransformation.getMatrix(), mSnapshotInitialMatrix);
        setSnapshotTransform(mSnapshotFinalMatrix, mExitTransformation.getAlpha());

        return moreEnter || moreExit;
    }

    public Transformation getEnterTransformation() {
        return mEnterTransformation;
    }
}
