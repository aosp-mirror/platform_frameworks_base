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
package com.android.systemui.qs.customize;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toolbar;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.R;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.qs.QSContainerController;
import com.android.systemui.qs.QSDetailClipper;
import com.android.systemui.qs.QSUtils;
import com.android.systemui.statusbar.phone.LightBarController;

/**
 * Allows full-screen customization of QS, through show() and hide().
 *
 * This adds itself to the status bar window, so it can appear on top of quick settings and
 * *someday* do fancy animations to get into/out of it.
 */
public class QSCustomizer extends LinearLayout {

    static final int MENU_RESET = Menu.FIRST;
    static final String EXTRA_QS_CUSTOMIZING = "qs_customizing";

    private final QSDetailClipper mClipper;
    private final View mTransparentView;

    private boolean isShown;
    private final RecyclerView mRecyclerView;
    private boolean mCustomizing;
    private QSContainerController mQsContainerController;
    private QS mQs;
    private int mX;
    private int mY;
    private boolean mOpening;
    private boolean mIsShowingNavBackdrop;

    public QSCustomizer(Context context, AttributeSet attrs) {
        super(context, attrs);

        LayoutInflater.from(getContext()).inflate(R.layout.qs_customize_panel_content, this);
        mClipper = new QSDetailClipper(findViewById(R.id.customize_container));
        Toolbar toolbar = findViewById(com.android.internal.R.id.action_bar);
        TypedValue value = new TypedValue();
        mContext.getTheme().resolveAttribute(android.R.attr.homeAsUpIndicator, value, true);
        toolbar.setNavigationIcon(
                getResources().getDrawable(value.resourceId, mContext.getTheme()));

        toolbar.getMenu().add(Menu.NONE, MENU_RESET, 0,
                mContext.getString(com.android.internal.R.string.reset));
        toolbar.setTitle(R.string.qs_edit);
        mRecyclerView = findViewById(android.R.id.list);
        mTransparentView = findViewById(R.id.customizer_transparent_view);
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setMoveDuration(TileAdapter.MOVE_DURATION);
        mRecyclerView.setItemAnimator(animator);
    }

    void updateResources() {
        LayoutParams lp = (LayoutParams) mTransparentView.getLayoutParams();
        lp.height = QSUtils.getQsHeaderSystemIconsAreaHeight(mContext);
        mTransparentView.setLayoutParams(lp);
        mRecyclerView.getAdapter().notifyItemChanged(0);
    }

    void updateNavBackDrop(Configuration newConfig, LightBarController lightBarController) {
        View navBackdrop = findViewById(R.id.nav_bar_background);
        mIsShowingNavBackdrop = newConfig.smallestScreenWidthDp >= 600
                || newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE;
        if (navBackdrop != null) {
            navBackdrop.setVisibility(mIsShowingNavBackdrop ? View.VISIBLE : View.GONE);
        }
        updateNavColors(lightBarController);
    }

    void updateNavColors(LightBarController lightBarController) {
        lightBarController.setQsCustomizing(mIsShowingNavBackdrop && isShown);
    }

    public void setContainerController(QSContainerController controller) {
        mQsContainerController = controller;
    }

    public void setQs(@Nullable QS qs) {
        mQs = qs;
    }

    /** Animate and show QSCustomizer panel.
     * @param x,y Location on screen of {@code edit} button to determine center of animation.
     */
    void show(int x, int y, TileAdapter tileAdapter) {
        if (!isShown) {
            mRecyclerView.getLayoutManager().scrollToPosition(0);
            int[] containerLocation = findViewById(R.id.customize_container).getLocationOnScreen();
            mX = x - containerLocation[0];
            mY = y - containerLocation[1];
            isShown = true;
            mOpening = true;
            setVisibility(View.VISIBLE);
            long duration = mClipper.animateCircularClip(
                    mX, mY, true, new ExpandAnimatorListener(tileAdapter));
            mQsContainerController.setCustomizerAnimating(true);
            mQsContainerController.setCustomizerShowing(true, duration);
        }
    }


    void showImmediately() {
        if (!isShown) {
            mRecyclerView.getLayoutManager().scrollToPosition(0);
            setVisibility(VISIBLE);
            mClipper.cancelAnimator();
            mClipper.showBackground();
            isShown = true;
            setCustomizing(true);
            mQsContainerController.setCustomizerAnimating(false);
            mQsContainerController.setCustomizerShowing(true);
        }
    }

    /** Hide the customizer. */
    public void hide(boolean animate) {
        if (isShown) {
            isShown = false;
            mClipper.cancelAnimator();
            // Make sure we're not opening (because we're closing). Nobody can think we are
            // customizing after the next two lines.
            mOpening = false;
            long duration = 0;
            if (animate) {
                duration = mClipper.animateCircularClip(mX, mY, false, mCollapseAnimationListener);
            } else {
                setVisibility(View.GONE);
            }
            mQsContainerController.setCustomizerAnimating(animate);
            mQsContainerController.setCustomizerShowing(false, duration);
        }
    }

    public boolean isShown() {
        return isShown;
    }

    void setCustomizing(boolean customizing) {
        mCustomizing = customizing;
        mQs.notifyCustomizeChanged();
    }

    public boolean isCustomizing() {
        return mCustomizing || mOpening;
    }

    /** @param x,y Location on screen of animation center.
     */
    public void setEditLocation(int x, int y) {
        int[] containerLocation = findViewById(R.id.customize_container).getLocationOnScreen();
        mX = x - containerLocation[0];
        mY = y - containerLocation[1];
    }

    class ExpandAnimatorListener extends AnimatorListenerAdapter {
        private final TileAdapter mTileAdapter;

        ExpandAnimatorListener(TileAdapter tileAdapter) {
            mTileAdapter = tileAdapter;
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            if (isShown) {
                setCustomizing(true);
            }
            mOpening = false;
            mQsContainerController.setCustomizerAnimating(false);
            mRecyclerView.setAdapter(mTileAdapter);
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            mOpening = false;
            mQs.notifyCustomizeChanged();
            mQsContainerController.setCustomizerAnimating(false);
        }
    }

    private final AnimatorListener mCollapseAnimationListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            if (!isShown) {
                setVisibility(View.GONE);
            }
            mQsContainerController.setCustomizerAnimating(false);
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            if (!isShown) {
                setVisibility(View.GONE);
            }
            mQsContainerController.setCustomizerAnimating(false);
        }
    };

    public RecyclerView getRecyclerView() {
        return mRecyclerView;
    }

    public boolean isOpening() {
        return mOpening;
    }
}