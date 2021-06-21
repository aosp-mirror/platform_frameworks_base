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

package com.android.systemui.privacy.television;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.IntDef;
import android.annotation.UiThread;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.privacy.PrivacyChipBuilder;
import com.android.systemui.privacy.PrivacyItem;
import com.android.systemui.privacy.PrivacyItemController;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import javax.inject.Inject;

/**
 * A SystemUI component responsible for notifying the user whenever an application is
 * recording audio, accessing the camera or accessing the location.
 */
@SysUISingleton
public class TvOngoingPrivacyChip extends SystemUI implements PrivacyItemController.Callback {
    private static final String TAG = "TvOngoingPrivacyChip";
    static final boolean DEBUG = false;

    // This title is used in CameraMicIndicatorsPermissionTest and
    // RecognitionServiceMicIndicatorTest.
    private static final String LAYOUT_PARAMS_TITLE = "MicrophoneCaptureIndicator";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"STATE_"}, value = {
            STATE_NOT_SHOWN,
            STATE_APPEARING,
            STATE_SHOWN,
            STATE_DISAPPEARING
    })
    public @interface State {
    }

    private static final int STATE_NOT_SHOWN = 0;
    private static final int STATE_APPEARING = 1;
    private static final int STATE_SHOWN = 2;
    private static final int STATE_DISAPPEARING = 3;

    private static final int ANIMATION_DURATION_MS = 200;

    private final Context mContext;
    private final PrivacyItemController mPrivacyItemController;

    private View mIndicatorView;
    private boolean mViewAndWindowAdded;
    private ObjectAnimator mAnimator;

    private boolean mMicCameraIndicatorFlagEnabled;
    private boolean mLocationIndicatorEnabled;
    private List<PrivacyItem> mPrivacyItems;

    private LinearLayout mIconsContainer;
    private final int mIconSize;
    private final int mIconMarginStart;

    @State
    private int mState = STATE_NOT_SHOWN;

    @Inject
    public TvOngoingPrivacyChip(Context context, PrivacyItemController privacyItemController) {
        super(context);
        Log.d(TAG, "Privacy chip running without id");
        mContext = context;
        mPrivacyItemController = privacyItemController;

        Resources res = mContext.getResources();
        mIconMarginStart = Math.round(res.getDimension(R.dimen.privacy_chip_icon_margin));
        mIconSize = res.getDimensionPixelSize(R.dimen.privacy_chip_icon_size);

        mMicCameraIndicatorFlagEnabled = privacyItemController.getMicCameraAvailable();
        mLocationIndicatorEnabled = privacyItemController.getLocationAvailable();

        if (DEBUG) {
            Log.d(TAG, "micCameraIndicators: " + mMicCameraIndicatorFlagEnabled);
            Log.d(TAG, "locationIndicators: " + mLocationIndicatorEnabled);
        }
    }

    @Override
    public void start() {
        mPrivacyItemController.addCallback(this);
    }

    @Override
    public void onPrivacyItemsChanged(List<PrivacyItem> privacyItems) {
        if (DEBUG) Log.d(TAG, "PrivacyItemsChanged");
        mPrivacyItems = privacyItems;
        updateUI();
    }

    @Override
    public void onFlagMicCameraChanged(boolean flag) {
        if (DEBUG) Log.d(TAG, "mic/camera indicators enabled: " + flag);
        mMicCameraIndicatorFlagEnabled = flag;
    }

    @Override
    public void onFlagLocationChanged(boolean flag) {
        if (DEBUG) Log.d(TAG, "location indicators enabled: " + flag);
        mLocationIndicatorEnabled = flag;
    }

    private void updateUI() {
        if (DEBUG) Log.d(TAG, mPrivacyItems.size() + " privacy items");

        if ((mMicCameraIndicatorFlagEnabled || mLocationIndicatorEnabled)
                && !mPrivacyItems.isEmpty()) {
            if (mState == STATE_NOT_SHOWN || mState == STATE_DISAPPEARING) {
                showIndicator();
            } else {
                if (DEBUG) Log.d(TAG, "only updating icons");
                PrivacyChipBuilder builder = new PrivacyChipBuilder(mContext, mPrivacyItems);
                setIcons(builder.generateIcons(), mIconsContainer);
                mIconsContainer.requestLayout();
            }
        } else {
            hideIndicatorIfNeeded();
        }
    }

    @UiThread
    private void hideIndicatorIfNeeded() {
        if (mState == STATE_NOT_SHOWN || mState == STATE_DISAPPEARING) return;

        if (mViewAndWindowAdded) {
            mState = STATE_DISAPPEARING;
            animateDisappearance();
        } else {
            // Appearing animation has not started yet, as we were still waiting for the View to be
            // laid out.
            mState = STATE_NOT_SHOWN;
            removeIndicatorView();
        }
    }

    @UiThread
    private void showIndicator() {
        mState = STATE_APPEARING;

        // Inflate the indicator view
        mIndicatorView = LayoutInflater.from(mContext).inflate(
                R.layout.tv_ongoing_privacy_chip, null);

        // 1. Set alpha to 0.
        // 2. Wait until the window is shown and the view is laid out.
        // 3. Start a "fade in" (alpha) animation.
        mIndicatorView.setAlpha(0f);
        mIndicatorView
                .getViewTreeObserver()
                .addOnGlobalLayoutListener(
                        new ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override
                            public void onGlobalLayout() {
                                // State could have changed to NOT_SHOWN (if all the recorders are
                                // already gone)
                                if (mState != STATE_APPEARING) return;

                                mViewAndWindowAdded = true;
                                // Remove the observer
                                mIndicatorView.getViewTreeObserver().removeOnGlobalLayoutListener(
                                        this);

                                animateAppearance();
                            }
                        });

        mIconsContainer = mIndicatorView.findViewById(R.id.icons_container);
        PrivacyChipBuilder builder = new PrivacyChipBuilder(mContext, mPrivacyItems);
        setIcons(builder.generateIcons(), mIconsContainer);

        final WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                WRAP_CONTENT,
                WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.END;
        layoutParams.setTitle(LAYOUT_PARAMS_TITLE);
        layoutParams.packageName = mContext.getPackageName();
        final WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        windowManager.addView(mIndicatorView, layoutParams);

    }

    private void setIcons(List<Drawable> icons, ViewGroup iconsContainer) {
        iconsContainer.removeAllViews();
        for (int i = 0; i < icons.size(); i++) {
            Drawable icon = icons.get(i);
            icon.mutate().setTint(Color.WHITE);
            ImageView imageView = new ImageView(mContext);
            imageView.setImageDrawable(icon);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mIconsContainer.addView(imageView, mIconSize, mIconSize);
            if (i != 0) {
                ViewGroup.MarginLayoutParams layoutParams =
                        (ViewGroup.MarginLayoutParams) imageView.getLayoutParams();
                layoutParams.setMarginStart(mIconMarginStart);
                imageView.setLayoutParams(layoutParams);
            }
        }
    }

    private void animateAppearance() {
        animateAlphaTo(1f);
    }

    private void animateDisappearance() {
        animateAlphaTo(0f);
    }

    private void animateAlphaTo(final float endValue) {
        if (mAnimator == null) {
            if (DEBUG) Log.d(TAG, "set up animator");

            mAnimator = new ObjectAnimator();
            mAnimator.setTarget(mIndicatorView);
            mAnimator.setProperty(View.ALPHA);
            mAnimator.addListener(new AnimatorListenerAdapter() {
                boolean mCancelled;

                @Override
                public void onAnimationStart(Animator animation, boolean isReverse) {
                    if (DEBUG) Log.d(TAG, "AnimatorListenerAdapter#onAnimationStart");
                    mCancelled = false;
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    if (DEBUG) Log.d(TAG, "AnimatorListenerAdapter#onAnimationCancel");
                    mCancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (DEBUG) Log.d(TAG, "AnimatorListenerAdapter#onAnimationEnd");
                    // When ValueAnimator#cancel() is called it always calls onAnimationCancel(...)
                    // and then onAnimationEnd(...). We, however, only want to proceed here if the
                    // animation ended "naturally".
                    if (!mCancelled) {
                        onAnimationFinished();
                    }
                }
            });
        } else if (mAnimator.isRunning()) {
            if (DEBUG) Log.d(TAG, "cancel running animation");
            mAnimator.cancel();
        }

        final float currentValue = mIndicatorView.getAlpha();
        if (DEBUG) Log.d(TAG, "animate alpha to " + endValue + " from " + currentValue);

        mAnimator.setDuration((int) (Math.abs(currentValue - endValue) * ANIMATION_DURATION_MS));
        mAnimator.setFloatValues(endValue);
        mAnimator.start();
    }

    private void onAnimationFinished() {
        if (DEBUG) Log.d(TAG, "onAnimationFinished");

        if (mState == STATE_APPEARING) {
            mState = STATE_SHOWN;
        } else if (mState == STATE_DISAPPEARING) {
            removeIndicatorView();
            mState = STATE_NOT_SHOWN;
        }
    }

    private void removeIndicatorView() {
        if (DEBUG) Log.d(TAG, "removeIndicatorView");

        final WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        if (windowManager != null) {
            windowManager.removeView(mIndicatorView);
        }

        mIndicatorView = null;
        mAnimator = null;

        mViewAndWindowAdded = false;
    }

}
