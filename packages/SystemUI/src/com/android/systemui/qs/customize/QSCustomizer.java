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

import static com.android.systemui.Flags.gsfQuickSettings;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toolbar;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.qs.QSContainerController;
import com.android.systemui.qs.QSDetailClipper;
import com.android.systemui.qs.QSUtils;
import com.android.systemui.res.R;
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
    private final Toolbar mToolbar;
    private QS mQs;
    private int mX;
    private int mY;
    private boolean mOpening;
    private boolean mIsShowingNavBackdrop;

    private boolean mSceneContainerEnabled;

    public QSCustomizer(Context context, AttributeSet attrs) {
        super(context, attrs);

        LayoutInflater.from(getContext()).inflate(R.layout.qs_customize_panel_content, this);
        mClipper = new QSDetailClipper(findViewById(R.id.customize_container));
        mToolbar = findViewById(com.android.internal.R.id.action_bar);
        TypedValue value = new TypedValue();
        mContext.getTheme().resolveAttribute(android.R.attr.homeAsUpIndicator, value, true);
        mToolbar.setNavigationIcon(
                getResources().getDrawable(value.resourceId, mContext.getTheme()));

        mToolbar.getMenu().add(Menu.NONE, MENU_RESET, 0, com.android.internal.R.string.reset)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        mToolbar.setTitle(R.string.qs_edit);
        if (gsfQuickSettings()) {
            mToolbar.setTitleTextAppearance(context, R.style.TextAppearance_QSEditTitle);
        }

        mRecyclerView = findViewById(android.R.id.list);
        mTransparentView = findViewById(R.id.customizer_transparent_view);
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setMoveDuration(TileAdapter.MOVE_DURATION);
        mRecyclerView.setItemAnimator(animator);

        updateTransparentViewHeight();
    }

    void applyBottomNavBarToPadding(int padding) {
        mRecyclerView.setPadding(
                /* left= */ mRecyclerView.getPaddingLeft(),
                /* top= */ mRecyclerView.getPaddingTop(),
                /* right= */ mRecyclerView.getPaddingRight(),
                /* bottom= */ padding
        );
    }

    void setSceneContainerEnabled(boolean enabled) {
        if (enabled != mSceneContainerEnabled) {
            mSceneContainerEnabled = enabled;
            updateTransparentViewHeight();
            if (mSceneContainerEnabled) {
                findViewById(R.id.nav_bar_background).setVisibility(View.GONE);
            } else {
                findViewById(R.id.nav_bar_background)
                        .setVisibility(mIsShowingNavBackdrop ? View.VISIBLE : View.GONE);
            }
        }
    }

    void updateResources() {
        updateTransparentViewHeight();
        mRecyclerView.getAdapter().notifyItemChanged(0);
    }

    void updateNavBackDrop(Configuration newConfig, LightBarController lightBarController) {
        View navBackdrop = findViewById(R.id.nav_bar_background);
        mIsShowingNavBackdrop = newConfig.smallestScreenWidthDp >= 600
                || newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE;
        if (navBackdrop != null) {
            navBackdrop.setVisibility(
                    mIsShowingNavBackdrop && !mSceneContainerEnabled ? View.VISIBLE : View.GONE);
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

    private void reloadAdapterTileHeight(@Nullable RecyclerView.Adapter adapter) {
        if (adapter instanceof TileAdapter) {
            ((TileAdapter) adapter).reloadTileHeight();
        }
    }

    /** Animate and show QSCustomizer panel.
     * @param x,y Location on screen of {@code edit} button to determine center of animation.
     */
    void show(int x, int y, TileAdapter tileAdapter) {
        if (!isShown) {
            reloadAdapterTileHeight(tileAdapter);
            mRecyclerView.getLayoutManager().scrollToPosition(0);
            int[] containerLocation = findViewById(R.id.customize_container).getLocationOnScreen();
            mX = x - containerLocation[0];
            mY = y - containerLocation[1];
            isShown = true;
            mOpening = true;
            setVisibility(View.VISIBLE);
            long duration = mClipper.animateCircularClip(
                    mX, mY, true, new ExpandAnimatorListener(tileAdapter));
            if (mQsContainerController != null) {
                mQsContainerController.setCustomizerAnimating(true);
                mQsContainerController.setCustomizerShowing(true, duration);
            }
        }
    }


    void showImmediately() {
        if (!isShown) {
            reloadAdapterTileHeight(mRecyclerView.getAdapter());
            mRecyclerView.getLayoutManager().scrollToPosition(0);
            setVisibility(VISIBLE);
            mClipper.cancelAnimator();
            mClipper.showBackground();
            isShown = true;
            setCustomizing(true);
            if (mQsContainerController != null) {
                mQsContainerController.setCustomizerAnimating(false);
                mQsContainerController.setCustomizerShowing(true);
            }
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
            if (mQsContainerController != null) {
                mQsContainerController.setCustomizerAnimating(animate);
                mQsContainerController.setCustomizerShowing(false, duration);
            }
        }
    }

    public boolean isShown() {
        return isShown;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mToolbar.setTitleTextAppearance(mContext,
                android.R.style.TextAppearance_DeviceDefault_Widget_ActionBar_Title);
        updateToolbarMenuFontSize();
    }

    void setCustomizing(boolean customizing) {
        mCustomizing = customizing;
        if (mQs != null) {
            mQs.notifyCustomizeChanged();
        }
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
            if (mQsContainerController != null) {
                mQsContainerController.setCustomizerAnimating(false);
            }
            mRecyclerView.setAdapter(mTileAdapter);
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            mOpening = false;
            if (mQs != null) {
                mQs.notifyCustomizeChanged();
            }
            if (mQsContainerController != null) {
                mQsContainerController.setCustomizerAnimating(false);
            }
        }
    }

    private final AnimatorListener mCollapseAnimationListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            if (!isShown) {
                setVisibility(View.GONE);
            }
            if (mQsContainerController != null) {
                mQsContainerController.setCustomizerAnimating(false);
            }
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            if (!isShown) {
                setVisibility(View.GONE);
            }
            if (mQsContainerController != null) {
                mQsContainerController.setCustomizerAnimating(false);
            }
        }
    };

    public RecyclerView getRecyclerView() {
        return mRecyclerView;
    }

    public boolean isOpening() {
        return mOpening;
    }

    private void updateTransparentViewHeight() {
        LayoutParams lp = (LayoutParams) mTransparentView.getLayoutParams();
        lp.height = mSceneContainerEnabled ? 0 : QSUtils.getQsHeaderSystemIconsAreaHeight(mContext);
        mTransparentView.setLayoutParams(lp);
    }

    private void updateToolbarMenuFontSize() {
        // Clearing and re-adding the toolbar action force updates the font size
        mToolbar.getMenu().clear();
        mToolbar.getMenu().add(Menu.NONE, MENU_RESET, 0, com.android.internal.R.string.reset)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
    }
}