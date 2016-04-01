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

import android.content.res.Resources;
import android.view.View;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.tv.views.TaskCardView;

/**
 * Collections of Recents row's animation depending on the PIP's focus.
 */
public class FocusAnimationHolder {
    private final float DIM_ALPHA = 0.5f;

    private View mRecentsRowView;
    private int mCardYDelta;
    private long mDuration;

    public FocusAnimationHolder(View recentsRowView) {
        mRecentsRowView = recentsRowView;

        Resources res = recentsRowView.getResources();
        mCardYDelta = res.getDimensionPixelOffset(R.dimen.recents_tv_dismiss_shift_down);
        mDuration = res.getInteger(R.integer.recents_tv_pip_focus_anim_duration);
    }

    public void startFocusGainAnimation() {
        mRecentsRowView.animate()
                .setDuration(mDuration)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .alpha(1f)
                .translationY(0);
    }

    public void startFocusLoseAnimation() {
        mRecentsRowView.animate()
                .setDuration(mDuration)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .alpha(DIM_ALPHA)
                .translationY(mCardYDelta);
    }

    public void reset() {
        mRecentsRowView.setTransitionAlpha(1f);
        mRecentsRowView.setTranslationY(0);
    }
}
