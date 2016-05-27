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

import android.content.Context;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.events.activity.DismissRecentsToHomeAnimationStarted;
import com.android.systemui.recents.tv.views.TaskCardView;
import com.android.systemui.recents.tv.views.TaskStackHorizontalGridView;


public class HomeRecentsEnterExitAnimationHolder {

    private Context mContext;
    private TaskStackHorizontalGridView mGridView;
    private float mDimAlpha;
    private long mDelay;
    private int mDuration;
    private int mTranslationX;

    public HomeRecentsEnterExitAnimationHolder(Context context,
            TaskStackHorizontalGridView gridView) {
        mContext = context;
        mGridView = gridView;
        mDimAlpha = mContext.getResources().getFloat(R.dimen.recents_recents_row_dim_alpha);
        mTranslationX = mContext.getResources()
                .getDimensionPixelSize(R.dimen.recents_tv_home_recents_shift);
        mDelay = mContext.getResources().getInteger(R.integer.recents_home_delay);
        mDuration =  mContext.getResources().getInteger(R.integer.recents_home_duration);
    }

    public void startEnterAnimation(boolean isPipShown) {
        for(int i = 0; i < mGridView.getChildCount(); i++) {
            TaskCardView view = (TaskCardView) mGridView.getChildAt(i);
            view.setTranslationX(-mTranslationX);
            view.animate()
                    .alpha(isPipShown ? mDimAlpha : 1.0f)
                    .translationX(0)
                    .setDuration(mDuration)
                    .setStartDelay(mDelay * i)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        }
    }

    public void startExitAnimation(DismissRecentsToHomeAnimationStarted dismissEvent) {
        for(int i = mGridView.getChildCount() - 1; i >= 0; i--) {
            TaskCardView view = (TaskCardView) mGridView.getChildAt(i);
            view.animate()
                    .alpha(0.0f)
                    .translationXBy(-mTranslationX)
                    .setDuration(mDuration)
                    .setStartDelay(mDelay * (mGridView.getChildCount() - 1 - i))
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
            if(i == 0) {
                view.animate().setListener(dismissEvent.getAnimationTrigger()
                        .decrementOnAnimationEnd());
                dismissEvent.getAnimationTrigger().increment();
            }
        }
    }

    /**
     * Sets the initial values Recents enter animation
     * when Recents is started from the Launcher.
     */
    public void setEnterFromHomeStartingAnimationValues(boolean isPipShown) {
        for(int i = 0; i < mGridView.getChildCount(); i++) {
            TaskCardView view = (TaskCardView) mGridView.getChildAt(i);
            view.setTranslationX(0);
            view.setAlpha(0.0f);
            view.getInfoFieldView().setAlpha(isPipShown ? 0 : 1f);
            if (isPipShown && view.hasFocus()) {
                view.getViewFocusAnimator().changeSize(false);
            }
        }
    }

    /**
     * Sets the initial values Recents enter animation
     * when Recents is started from an app.
     */
    public void setEnterFromAppStartingAnimationValues(boolean isPipShown) {
        for(int i = 0; i < mGridView.getChildCount(); i++) {
            TaskCardView view = (TaskCardView) mGridView.getChildAt(i);
            view.setTranslationX(0);
            view.setAlpha(isPipShown ? mDimAlpha : 1f);
            view.getInfoFieldView().setAlpha(isPipShown ? 0 : 1f);
            if (isPipShown && view.hasFocus()) {
                view.getViewFocusAnimator().changeSize(false);
            }
        }
    }
}
