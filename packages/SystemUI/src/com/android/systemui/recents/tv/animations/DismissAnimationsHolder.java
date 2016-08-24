/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.systemui.recents.tv.animations;

import android.animation.Animator.AnimatorListener;
import android.content.res.Resources;
import android.graphics.drawable.TransitionDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.android.systemui.Interpolators;
import com.android.systemui.recents.tv.views.TaskCardView;

import com.android.systemui.R;

public class DismissAnimationsHolder {
    private LinearLayout mInfoField;
    private View mThumbnailView;

    private int mDismissEnterYDelta;
    private int mDismissStartYDelta;

    private ImageView mCardDismissIcon;
    private TransitionDrawable mDismissDrawable;
    private TextView mDismissText;

    private float mDismissIconNotInDismissStateAlpha;
    private long mShortDuration;
    private long mLongDuration;

    public DismissAnimationsHolder(TaskCardView taskCardView) {

        mInfoField = (LinearLayout) taskCardView.findViewById(R.id.card_info_field);
        mThumbnailView = taskCardView.findViewById(R.id.card_view_thumbnail);
        mCardDismissIcon = (ImageView) taskCardView.findViewById(R.id.dismiss_icon);
        mDismissDrawable = (TransitionDrawable) mCardDismissIcon.getDrawable();
        mDismissDrawable.setCrossFadeEnabled(true);
        mDismissText = (TextView) taskCardView.findViewById(R.id.card_dismiss_text);

        Resources res = taskCardView.getResources();
        mDismissEnterYDelta = res.getDimensionPixelOffset(R.dimen.recents_tv_dismiss_shift_down);
        mDismissStartYDelta = mDismissEnterYDelta * 2;
        mShortDuration =  res.getInteger(R.integer.dismiss_short_duration);
        mLongDuration =  res.getInteger(R.integer.dismiss_long_duration);
        mDismissIconNotInDismissStateAlpha = res.getFloat(R.integer.dismiss_unselected_alpha);
    }

    public void startEnterAnimation() {
        mCardDismissIcon.animate()
                .setDuration(mShortDuration)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .alpha(1.0f)
                .withStartAction(new Runnable() {
                    @Override
                    public void run() {
                        mDismissDrawable.startTransition(0);
                    }
                });

        mDismissText.animate()
                .setDuration(mShortDuration)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .alpha(1.0f);

        mInfoField.animate()
                .setDuration(mShortDuration)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .translationY(mDismissEnterYDelta)
                .alpha(0.5f);

        mThumbnailView.animate()
                .setDuration(mShortDuration)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .translationY(mDismissEnterYDelta)
                .alpha(0.5f);
    }

    public void startExitAnimation() {
        mCardDismissIcon.animate()
                .setDuration(mShortDuration)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .alpha(mDismissIconNotInDismissStateAlpha)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        mDismissDrawable.reverseTransition(0);
                    }
                });

        mDismissText.animate()
                .setDuration(mShortDuration)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .alpha(0.0f);

        mInfoField.animate()
                .setDuration(mShortDuration)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .translationY(0)
                .alpha(1.0f);

        mThumbnailView.animate()
                .setDuration(mShortDuration)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .translationY(0)
                .alpha(1.0f);
    }

    public void startDismissAnimation(AnimatorListener listener) {
        mCardDismissIcon.animate()
                .setDuration(mShortDuration)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .alpha(0.0f)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        mDismissDrawable.reverseTransition(0);
                    }
                });

        mDismissText.animate()
                .setDuration(mShortDuration)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .alpha(0.0f);

        mInfoField.animate()
                .setDuration(mLongDuration)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .translationY(mDismissStartYDelta)
                .alpha(0.0f)
                .setListener(listener);

        mThumbnailView.animate()
                .setDuration(mLongDuration)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .translationY(mDismissStartYDelta)
                .alpha(0.0f);
    }

    public void reset() {
        mInfoField.setAlpha(1.0f);
        mInfoField.setTranslationY(0);
        mInfoField.animate().setListener(null);
        mThumbnailView.setAlpha(1.0f);
        mThumbnailView.setTranslationY(0);
        mCardDismissIcon.setAlpha(0.0f);
        mDismissText.setAlpha(0.0f);
    }
}
