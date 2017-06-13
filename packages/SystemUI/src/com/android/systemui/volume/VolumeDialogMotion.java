/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.volume;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnDismissListener;
import android.content.DialogInterface.OnShowListener;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

public class VolumeDialogMotion {
    private static final String TAG = Util.logTag(VolumeDialogMotion.class);

    private static final float ANIMATION_SCALE = 1.0f;
    private static final int PRE_DISMISS_DELAY = 50;

    private final Dialog mDialog;
    private final View mDialogView;
    private final ViewGroup mContents;  // volume rows + zen footer
    private final View mChevron;
    private final Handler mHandler = new Handler();
    private final Callback mCallback;

    private boolean mAnimating;  // show or dismiss animation is running
    private boolean mShowing;  // show animation is running
    private boolean mDismissing;  // dismiss animation is running
    private ValueAnimator mChevronPositionAnimator;
    private ValueAnimator mContentsPositionAnimator;

    public VolumeDialogMotion(Dialog dialog, View dialogView, ViewGroup contents, View chevron,
            Callback callback) {
        mDialog = dialog;
        mDialogView = dialogView;
        mContents = contents;
        mChevron = chevron;
        mCallback = callback;
        mDialog.setOnDismissListener(new OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (D.BUG) Log.d(TAG, "mDialog.onDismiss");
            }
        });
        mDialog.setOnShowListener(new OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                if (D.BUG) Log.d(TAG, "mDialog.onShow");
                final int h = mDialogView.getHeight();
                mDialogView.setTranslationY(-h);
                startShowAnimation();
            }
        });
    }

    public boolean isAnimating() {
        return mAnimating;
    }

    private void setShowing(boolean showing) {
        if (showing == mShowing) return;
        mShowing = showing;
        if (D.BUG) Log.d(TAG, "mShowing = " + mShowing);
        updateAnimating();
    }

    private void setDismissing(boolean dismissing) {
        if (dismissing == mDismissing) return;
        mDismissing = dismissing;
        if (D.BUG) Log.d(TAG, "mDismissing = " + mDismissing);
        updateAnimating();
    }

    private void updateAnimating() {
        final boolean animating = mShowing || mDismissing;
        if (animating == mAnimating) return;
        mAnimating = animating;
        if (D.BUG) Log.d(TAG, "mAnimating = " + mAnimating);
        if (mCallback != null) {
            mCallback.onAnimatingChanged(mAnimating);
        }
    }

    public void startShow() {
        if (D.BUG) Log.d(TAG, "startShow");
        if (mShowing) return;
        setShowing(true);
        if (mDismissing) {
            mDialogView.animate().cancel();
            setDismissing(false);
            startShowAnimation();
            return;
        }
        if (D.BUG) Log.d(TAG, "mDialog.show()");
        mDialog.show();
    }

    private int chevronDistance() {
        return mChevron.getHeight() / 6;
    }

    private int chevronPosY() {
        final Object tag = mChevron == null ? null : mChevron.getTag();
        return tag == null ? 0 : (Integer) tag;
    }

    private void startShowAnimation() {
        if (D.BUG) Log.d(TAG, "startShowAnimation");
        mDialogView.animate()
                .translationY(0)
                .setDuration(scaledDuration(300))
                .setInterpolator(new LogDecelerateInterpolator())
                .setListener(null)
                .setUpdateListener(animation -> {
                    if (mChevronPositionAnimator != null) {
                        final float v = (Float) mChevronPositionAnimator.getAnimatedValue();
                        if (mChevronPositionAnimator == null) return;
                        // reposition chevron
                        final int posY = chevronPosY();
                        mChevron.setTranslationY(posY + v + -mDialogView.getTranslationY());
                    }
                })
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (mChevronPositionAnimator == null) return;
                        // reposition chevron
                        final int posY = chevronPosY();
                        mChevron.setTranslationY(posY + -mDialogView.getTranslationY());
                    }
                })
                .start();

        mContentsPositionAnimator = ValueAnimator.ofFloat(-chevronDistance(), 0)
                .setDuration(scaledDuration(400));
        mContentsPositionAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mCancelled) return;
                if (D.BUG) Log.d(TAG, "show.onAnimationEnd");
                setShowing(false);
            }
            @Override
            public void onAnimationCancel(Animator animation) {
                if (D.BUG) Log.d(TAG, "show.onAnimationCancel");
                mCancelled = true;
            }
        });
        mContentsPositionAnimator.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float v = (Float) animation.getAnimatedValue();
                mContents.setTranslationY(v + -mDialogView.getTranslationY());
            }
        });
        mContentsPositionAnimator.setInterpolator(new LogDecelerateInterpolator());
        mContentsPositionAnimator.start();

        mContents.setAlpha(0);
        mContents.animate()
                .alpha(1)
                .setDuration(scaledDuration(150))
                .setInterpolator(new PathInterpolator(0f, 0f, .2f, 1f))
                .start();

        mChevronPositionAnimator = ValueAnimator.ofFloat(-chevronDistance(), 0)
                .setDuration(scaledDuration(250));
        mChevronPositionAnimator.setInterpolator(new PathInterpolator(.4f, 0f, .2f, 1f));
        mChevronPositionAnimator.start();

        mChevron.setAlpha(0);
        mChevron.animate()
                .alpha(1)
                .setStartDelay(scaledDuration(50))
                .setDuration(scaledDuration(150))
                .setInterpolator(new PathInterpolator(.4f, 0f, 1f, 1f))
                .start();
    }

    public void startDismiss(final Runnable onComplete) {
        if (D.BUG) Log.d(TAG, "startDismiss");
        if (mDismissing) return;
        setDismissing(true);
        if (mShowing) {
            mDialogView.animate().cancel();
            if (mContentsPositionAnimator != null) {
                mContentsPositionAnimator.cancel();
            }
            mContents.animate().cancel();
            if (mChevronPositionAnimator != null) {
                mChevronPositionAnimator.cancel();
            }
            mChevron.animate().cancel();
            setShowing(false);
        }
        mDialogView.animate()
                .translationY(-mDialogView.getHeight())
                .setDuration(scaledDuration(250))
                .setInterpolator(new LogAccelerateInterpolator())
                .setUpdateListener(new AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mContents.setTranslationY(-mDialogView.getTranslationY());
                        final int posY = chevronPosY();
                        mChevron.setTranslationY(posY + -mDialogView.getTranslationY());
                    }
                })
                .setListener(new AnimatorListenerAdapter() {
                    private boolean mCancelled;
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (mCancelled) return;
                        if (D.BUG) Log.d(TAG, "dismiss.onAnimationEnd");
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (D.BUG) Log.d(TAG, "mDialog.dismiss()");
                                mDialog.dismiss();
                                onComplete.run();
                                setDismissing(false);
                            }
                        }, PRE_DISMISS_DELAY);

                    }
                    @Override
                    public void onAnimationCancel(Animator animation) {
                        if (D.BUG) Log.d(TAG, "dismiss.onAnimationCancel");
                        mCancelled = true;
                    }
                }).start();
    }

    private static int scaledDuration(int base) {
        return (int) (base * ANIMATION_SCALE);
    }

    public static final class LogDecelerateInterpolator implements TimeInterpolator {
        private final float mBase;
        private final float mDrift;
        private final float mTimeScale;
        private final float mOutputScale;

        public LogDecelerateInterpolator() {
            this(400f, 1.4f, 0);
        }

        private LogDecelerateInterpolator(float base, float timeScale, float drift) {
            mBase = base;
            mDrift = drift;
            mTimeScale = 1f / timeScale;

            mOutputScale = 1f / computeLog(1f);
        }

        private float computeLog(float t) {
            return 1f - (float) Math.pow(mBase, -t * mTimeScale) + (mDrift * t);
        }

        @Override
        public float getInterpolation(float t) {
            return computeLog(t) * mOutputScale;
        }
    }

    public static final class LogAccelerateInterpolator implements TimeInterpolator {
        private final int mBase;
        private final int mDrift;
        private final float mLogScale;

        public LogAccelerateInterpolator() {
            this(100, 0);
        }

        private LogAccelerateInterpolator(int base, int drift) {
            mBase = base;
            mDrift = drift;
            mLogScale = 1f / computeLog(1, mBase, mDrift);
        }

        private static float computeLog(float t, int base, int drift) {
            return (float) -Math.pow(base, -t) + 1 + (drift * t);
        }

        @Override
        public float getInterpolation(float t) {
            return 1 - computeLog(1 - t, mBase, mDrift) * mLogScale;
        }
    }

    public interface Callback {
        void onAnimatingChanged(boolean animating);
    }
}
