/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.qs.car;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Fragment;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.qs.QSFooter;
import com.android.systemui.statusbar.car.PageIndicator;
import com.android.systemui.statusbar.car.UserGridView;
import com.android.systemui.statusbar.policy.UserSwitcherController;

/**
 * A quick settings fragment for the car. For auto, there is no row for quick settings or ability
 * to expand the quick settings panel. Instead, the only thing is that displayed is the
 * status bar, and a static row with access to the user switcher and settings.
 */
public class CarQSFragment extends Fragment implements QS {
    private View mHeader;
    private View mUserSwitcherContainer;
    private CarQSFooter mFooter;
    private UserGridView mUserGridView;
    private PageIndicator mPageIndicator;
    private ValueAnimator mHeightAnimator;

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.car_qs_panel, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mHeader = view.findViewById(R.id.header);
        mFooter = view.findViewById(R.id.qs_footer);

        mUserSwitcherContainer = view.findViewById(R.id.user_switcher_container);

        updateUserSwitcherHeight(0);

        mUserGridView = view.findViewById(R.id.user_grid);
        mUserGridView.init(null, Dependency.get(UserSwitcherController.class));

        mPageIndicator = view.findViewById(R.id.user_switcher_page_indicator);
        mPageIndicator.setupWithViewPager(mUserGridView);

        mFooter.setUserSwitchCallback(new UserSwitchCallback());
    }

    @Override
    public void hideImmediately() {
        getView().setVisibility(View.INVISIBLE);
    }

    @Override
    public void setQsExpansion(float qsExpansionFraction, float headerTranslation) {
        // If the header is to be completed translated down, then set it to be visible.
        getView().setVisibility(headerTranslation == 0 ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    public View getHeader() {
        return mHeader;
    }

    @VisibleForTesting
    QSFooter getFooter() {
        return mFooter;
    }

    @Override
    public void setHeaderListening(boolean listening) {
        mFooter.setListening(listening);
    }

    @Override
    public void setListening(boolean listening) {
        mFooter.setListening(listening);
    }

    @Override
    public int getQsMinExpansionHeight() {
        return getView().getHeight();
    }

    @Override
    public int getDesiredHeight() {
        return getView().getHeight();
    }

    @Override
    public void setPanelView(HeightListener notificationPanelView) {
        // No quick settings panel.
    }

    @Override
    public void setHeightOverride(int desiredHeight) {
        // No ability to expand quick settings.
    }

    @Override
    public void setHeaderClickable(boolean qsExpansionEnabled) {
        // Usually this sets the expand button to be clickable, but there is no quick settings to
        // expand.
    }

    @Override
    public boolean isCustomizing() {
        // No ability to customize the quick settings.
        return false;
    }

    @Override
    public void setOverscrolling(boolean overscrolling) {
        // No overscrolling to reveal quick settings.
    }

    @Override
    public void setExpanded(boolean qsExpanded) {
        // No quick settings to expand
    }

    @Override
    public boolean isShowingDetail() {
        // No detail panel to close.
        return false;
    }

    @Override
    public void closeDetail() {
        // No detail panel to close.
    }

    @Override
    public void setKeyguardShowing(boolean keyguardShowing) {
        // No keyguard to show.
    }

    @Override
    public void animateHeaderSlidingIn(long delay) {
        // No header to animate.
    }

    @Override
    public void animateHeaderSlidingOut() {
        // No header to animate.
    }

    @Override
    public void notifyCustomizeChanged() {
        // There is no ability to customize quick settings.
    }

    @Override
    public void setContainer(ViewGroup container) {
        // No quick settings, so no container to set.
    }

    @Override
    public void setExpandClickListener(OnClickListener onClickListener) {
        // No ability to expand the quick settings.
    }

    public class UserSwitchCallback {
        private boolean mShowing;

        public boolean isShowing() {
            return mShowing;
        }

        public void show() {
            mShowing = true;
            animateHeightChange(true /* opening */);
        }

        public void hide() {
            mShowing = false;
            animateHeightChange(false /* opening */);
        }
    }

    private void updateUserSwitcherHeight(int height) {
        ViewGroup.LayoutParams layoutParams = mUserSwitcherContainer.getLayoutParams();
        layoutParams.height = height;
        mUserSwitcherContainer.requestLayout();
    }

    private void animateHeightChange(boolean opening) {
        // Animation in progress; cancel it to avoid contention.
        if (mHeightAnimator != null){
            mHeightAnimator.cancel();
        }

        mHeightAnimator = (ValueAnimator) AnimatorInflater.loadAnimator(getContext(),
                opening ? R.anim.car_user_switcher_open_animation
                        : R.anim.car_user_switcher_close_animation);
        mHeightAnimator.addUpdateListener(valueAnimator -> {
            updateUserSwitcherHeight((Integer)valueAnimator.getAnimatedValue());
        });
        mHeightAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mHeightAnimator = null;
            }
        });
        mHeightAnimator.start();
    }
}
