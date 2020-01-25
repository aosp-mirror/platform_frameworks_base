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
import android.os.Bundle;
import android.content.res.Resources;
import android.content.Intent;
import android.os.UserHandle;
import android.provider.Settings;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toolbar;
import android.widget.Toolbar.OnMenuItemClickListener;

import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.QSDetailClipper;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.NotificationsQuickSettingsContainer;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.KeyguardMonitor.Callback;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Allows full-screen customization of QS, through show() and hide().
 *
 * This adds itself to the status bar window, so it can appear on top of quick settings and
 * *someday* do fancy animations to get into/out of it.
 */
public class QSCustomizer extends LinearLayout implements OnMenuItemClickListener {

    private static final String EXTRA_QS_CUSTOMIZING = "qs_customizing";
    private static final String TAG = "QSCustomizer";

    private final QSDetailClipper mClipper;
    private final LightBarController mLightBarController;
    private KeyguardMonitor mKeyguardMonitor;
    private final ScreenLifecycle mScreenLifecycle;
    private final TileQueryHelper mTileQueryHelper;
    private final View mTransparentView;

    private boolean isShown;
    private QSTileHost mHost;
    private RecyclerView mRecyclerView;
    private TileAdapter mTileAdapter;
    private Toolbar mToolbar;
    private boolean mCustomizing;
    private NotificationsQuickSettingsContainer mNotifQsContainer;
    private QS mQs;
    private int mX;
    private int mY;
    private boolean mOpening;
    private boolean mIsShowingNavBackdrop;
    private GridLayoutManager mLayout;
    private Menu mColumnsSubMenu;
    private Menu mColumnsLandscapeSubMenu;

    @Inject
    public QSCustomizer(Context context, AttributeSet attrs,
            LightBarController lightBarController,
            KeyguardMonitor keyguardMonitor,
            ScreenLifecycle screenLifecycle) {
        super(new ContextThemeWrapper(context, R.style.edit_theme), attrs);

        LayoutInflater.from(getContext()).inflate(R.layout.qs_customize_panel_content, this);
        mClipper = new QSDetailClipper(findViewById(R.id.customize_container));
        mToolbar = findViewById(com.android.internal.R.id.action_bar);
        TypedValue value = new TypedValue();
        mContext.getTheme().resolveAttribute(android.R.attr.homeAsUpIndicator, value, true);
        mToolbar.setNavigationIcon(
                getResources().getDrawable(value.resourceId, mContext.getTheme()));
        mToolbar.setNavigationOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hide();
            }
        });
        mToolbar.setOnMenuItemClickListener(this);
        MenuInflater menuInflater = new MenuInflater(mContext);
        menuInflater.inflate(R.menu.qs_customize_menu, mToolbar.getMenu());
        MenuItem menuItem = mToolbar.getMenu().findItem(R.id.menu_item_columns);
        if (menuItem != null) {
            mColumnsSubMenu = menuItem.getSubMenu();
        }
        MenuItem menuItemLand = mToolbar.getMenu().findItem(R.id.menu_item_columns_landscape);
        if (menuItemLand != null) {
            mColumnsLandscapeSubMenu = menuItemLand.getSubMenu();
        }
        int accentColor = Utils.getColorAccentDefaultColor(context);
        int qsTitlesValue = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.QS_TILE_TITLE_VISIBILITY, 1,
                UserHandle.USER_CURRENT);
        MenuItem qsTitlesMenuItem = mToolbar.getMenu().findItem(R.id.menu_item_titles);
        qsTitlesMenuItem.setChecked(qsTitlesValue == 1);

        mToolbar.setTitleTextColor(accentColor);
        mToolbar.getNavigationIcon().setTint(accentColor);
        mToolbar.getOverflowIcon().setTint(accentColor);
        mToolbar.setTitle(R.string.qs_edit);
        int defaultColumns = Math.max(1,
                    mContext.getResources().getInteger(R.integer.quick_settings_num_columns));
        mRecyclerView = (RecyclerView) findViewById(android.R.id.list);
        mTransparentView = findViewById(R.id.customizer_transparent_view);
        mTileAdapter = new TileAdapter(getContext());
        mTileQueryHelper = new TileQueryHelper(context, mTileAdapter);
        mRecyclerView.setAdapter(mTileAdapter);
        mTileAdapter.getItemTouchHelper().attachToRecyclerView(mRecyclerView);
        mLayout = new GridLayoutManager(getContext(), defaultColumns);
        mLayout.setSpanSizeLookup(mTileAdapter.getSizeLookup());
        mRecyclerView.setLayoutManager(mLayout);
        mRecyclerView.addItemDecoration(mTileAdapter.getItemDecoration());
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setMoveDuration(TileAdapter.MOVE_DURATION);
        mRecyclerView.setItemAnimator(animator);
        mLightBarController = lightBarController;
        mKeyguardMonitor = keyguardMonitor;
        mScreenLifecycle = screenLifecycle;
        updateNavBackDrop(getResources().getConfiguration());

        updateSettings();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateNavBackDrop(newConfig);
        updateResources();
    }

    private void updateResources() {
        LayoutParams lp = (LayoutParams) mTransparentView.getLayoutParams();
        lp.height = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height);
        mTransparentView.setLayoutParams(lp);
    }

    private void updateNavBackDrop(Configuration newConfig) {
        View navBackdrop = findViewById(R.id.nav_bar_background);
        mIsShowingNavBackdrop = newConfig.smallestScreenWidthDp >= 600
                || newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE;
        if (navBackdrop != null) {
            navBackdrop.setVisibility(mIsShowingNavBackdrop ? View.VISIBLE : View.GONE);
        }
        updateNavColors();
        updateSettings();
    }

    private void updateNavColors() {
        mLightBarController.setQsCustomizing(mIsShowingNavBackdrop && isShown);
    }

    public void setHost(QSTileHost host) {
        mHost = host;
        mTileAdapter.setHost(host);
    }

    public void setContainer(NotificationsQuickSettingsContainer notificationsQsContainer) {
        mNotifQsContainer = notificationsQsContainer;
    }

    public void setQs(QS qs) {
        mQs = qs;
    }

    /** Animate and show QSCustomizer panel.
     * @param x,y Location on screen of {@code edit} button to determine center of animation.
     */
    public void show(int x, int y) {
        if (!isShown) {
            int containerLocation[] = findViewById(R.id.customize_container).getLocationOnScreen();
            mX = x - containerLocation[0];
            mY = y - containerLocation[1];
            MetricsLogger.visible(getContext(), MetricsProto.MetricsEvent.QS_EDIT);
            isShown = true;
            mOpening = true;
            setTileSpecs();
            setVisibility(View.VISIBLE);
            mClipper.animateCircularClip(mX, mY, true, mExpandAnimationListener);
            queryTiles();
            mNotifQsContainer.setCustomizerAnimating(true);
            mNotifQsContainer.setCustomizerShowing(true);
            mKeyguardMonitor.addCallback(mKeyguardCallback);
            updateNavColors();
        }
    }


    public void showImmediately() {
        if (!isShown) {
            setVisibility(VISIBLE);
            mClipper.showBackground();
            isShown = true;
            setTileSpecs();
            setCustomizing(true);
            queryTiles();
            mNotifQsContainer.setCustomizerAnimating(false);
            mNotifQsContainer.setCustomizerShowing(true);
            mKeyguardMonitor.addCallback(mKeyguardCallback);
            updateNavColors();
        }
    }

    private void queryTiles() {
        mTileQueryHelper.queryTiles(mHost);
    }

    public void hide() {
        final boolean animate = mScreenLifecycle.getScreenState() != ScreenLifecycle.SCREEN_OFF;
        if (isShown) {
            MetricsLogger.hidden(getContext(), MetricsProto.MetricsEvent.QS_EDIT);
            isShown = false;
            if (mColumnsSubMenu != null) {
                mColumnsSubMenu.close();
            }
            if (mColumnsLandscapeSubMenu != null) {
                mColumnsLandscapeSubMenu.close();
            }
            mToolbar.dismissPopupMenus();
            setCustomizing(false);
            save();
            if (animate) {
                mClipper.animateCircularClip(mX, mY, false, mCollapseAnimationListener);
            } else {
                setVisibility(View.GONE);
            }
            mNotifQsContainer.setCustomizerAnimating(animate);
            mNotifQsContainer.setCustomizerShowing(false);
            mKeyguardMonitor.removeCallback(mKeyguardCallback);
            updateNavColors();
        }
    }

    public boolean isShown() {
        return isShown;
    }

    private void setCustomizing(boolean customizing) {
        mCustomizing = customizing;
        mQs.notifyCustomizeChanged();
    }

    public boolean isCustomizing() {
        return mCustomizing || mOpening;
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_item_reset) {
            MetricsLogger.action(getContext(), MetricsProto.MetricsEvent.ACTION_QS_EDIT_RESET);
            reset();
        } else if (id == R.id.menu_item_columns_three) {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.QS_LAYOUT_COLUMNS, 3, UserHandle.USER_CURRENT);
        } else if (id == R.id.menu_item_columns_four) {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.QS_LAYOUT_COLUMNS, 4, UserHandle.USER_CURRENT);
        } else if (id == R.id.menu_item_columns_five) {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.QS_LAYOUT_COLUMNS, 5, UserHandle.USER_CURRENT);
        } else if (id == R.id.menu_item_columns_six) {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.QS_LAYOUT_COLUMNS, 6, UserHandle.USER_CURRENT);
        } else if (id == R.id.menu_item_columns_seven) {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.QS_LAYOUT_COLUMNS, 7, UserHandle.USER_CURRENT);
        } else if (id == R.id.menu_item_columns_eight) {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.QS_LAYOUT_COLUMNS, 8, UserHandle.USER_CURRENT);
        } else if (id == R.id.menu_item_columns_landscape_four) {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.QS_LAYOUT_COLUMNS_LANDSCAPE, 4, UserHandle.USER_CURRENT);
        } else if (id == R.id.menu_item_columns_landscape_five) {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.QS_LAYOUT_COLUMNS_LANDSCAPE, 5, UserHandle.USER_CURRENT);
        } else if (id == R.id.menu_item_columns_landscape_six) {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.QS_LAYOUT_COLUMNS_LANDSCAPE, 6, UserHandle.USER_CURRENT);
        } else if (id == R.id.menu_item_columns_landscape_seven) {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.QS_LAYOUT_COLUMNS_LANDSCAPE, 7, UserHandle.USER_CURRENT);
        } else if (id ==  R.id.menu_item_columns_landscape_eight) {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.QS_LAYOUT_COLUMNS_LANDSCAPE, 8, UserHandle.USER_CURRENT);
        } else if (id == R.id.menu_item_titles) {
            item.setChecked(!item.isChecked());
            Settings.System.putIntForUser(mContext.getContentResolver(),
                   Settings.System.QS_TILE_TITLE_VISIBILITY, item.isChecked() ? 1 : 0,
                   UserHandle.USER_CURRENT);
        }
        updateSettings();
        return false;
    }

    private void reset() {
        ArrayList<String> tiles = new ArrayList<>();
        String defTiles = mContext.getString(R.string.quick_settings_tiles_default);
        for (String tile : defTiles.split(",")) {
            tiles.add(tile);
        }
        mTileAdapter.resetTileSpecs(mHost, tiles);
    }

    private void setTileSpecs() {
        List<String> specs = new ArrayList<>();
        for (QSTile tile : mHost.getTiles()) {
            specs.add(tile.getTileSpec());
        }
        mTileAdapter.setTileSpecs(specs);
        mRecyclerView.setAdapter(mTileAdapter);
    }

    private void save() {
        if (mTileQueryHelper.isFinished()) {
            mTileAdapter.saveSpecs(mHost);
        }
    }


    public void saveInstanceState(Bundle outState) {
        if (isShown) {
            mKeyguardMonitor.removeCallback(mKeyguardCallback);
        }
        outState.putBoolean(EXTRA_QS_CUSTOMIZING, mCustomizing);
    }

    public void restoreInstanceState(Bundle savedInstanceState) {
        boolean customizing = savedInstanceState.getBoolean(EXTRA_QS_CUSTOMIZING);
        if (customizing) {
            setVisibility(VISIBLE);
            addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft,
                        int oldTop, int oldRight, int oldBottom) {
                    removeOnLayoutChangeListener(this);
                    showImmediately();
                }
            });
        }
    }
    /** @param x,y Location on screen of animation center.
     */
    public void setEditLocation(int x, int y) {
        int containerLocation[] = findViewById(R.id.customize_container).getLocationOnScreen();
        mX = x - containerLocation[0];
        mY = y - containerLocation[1];
    }

    private final Callback mKeyguardCallback = new Callback() {
        @Override
        public void onKeyguardShowingChanged() {
            if (!isAttachedToWindow()) return;
            if (mKeyguardMonitor.isShowing() && !mOpening) {
                hide();
            }
        }
    };

    private final AnimatorListener mExpandAnimationListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            if (isShown) {
                setCustomizing(true);
            }
            mOpening = false;
            mNotifQsContainer.setCustomizerAnimating(false);
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            mOpening = false;
            mNotifQsContainer.setCustomizerAnimating(false);
        }
    };

    private final AnimatorListener mCollapseAnimationListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            if (!isShown) {
                setVisibility(View.GONE);
            }
            mNotifQsContainer.setCustomizerAnimating(false);
            mRecyclerView.setAdapter(mTileAdapter);
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            if (!isShown) {
                setVisibility(View.GONE);
            }
            mNotifQsContainer.setCustomizerAnimating(false);
        }
    };

    public void updateSettings() {
        final Resources res = mContext.getResources();
        boolean isPortrait = res.getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
        int defaultColumns = Math.max(1, mContext.getResources().getInteger(R.integer.quick_settings_num_columns));
        int columns = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.QS_LAYOUT_COLUMNS, defaultColumns,
                UserHandle.USER_CURRENT);
        int columnsLandscape = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.QS_LAYOUT_COLUMNS_LANDSCAPE, defaultColumns,
                UserHandle.USER_CURRENT);
        boolean showTitles = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.QS_TILE_TITLE_VISIBILITY, 1,
                UserHandle.USER_CURRENT) == 1;
        mTileAdapter.setColumnCount(isPortrait ? columns : columnsLandscape);
        mTileAdapter.setHideLabel(!showTitles);
        mLayout.setSpanCount(isPortrait ? columns : columnsLandscape);
        updateColumnsMenu(defaultColumns);
    }
    private void updateColumnsMenu(int defaultColumns) {
        int columns = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.QS_LAYOUT_COLUMNS, defaultColumns,
                UserHandle.USER_CURRENT);
        MenuItem menuItemThree = mToolbar.getMenu().findItem(R.id.menu_item_columns_three);
        menuItemThree.setChecked(columns == 3);
        MenuItem menuItemFour = mToolbar.getMenu().findItem(R.id.menu_item_columns_four);
        menuItemFour.setChecked(columns == 4);
        MenuItem menuItemFive = mToolbar.getMenu().findItem(R.id.menu_item_columns_five);
        menuItemFive.setChecked(columns == 5);
        MenuItem menuItemSix = mToolbar.getMenu().findItem(R.id.menu_item_columns_six);
        menuItemSix.setChecked(columns == 6);

         int columnsLandscape = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.QS_LAYOUT_COLUMNS_LANDSCAPE, defaultColumns,
                UserHandle.USER_CURRENT);
        menuItemThree = mToolbar.getMenu().findItem(R.id.menu_item_columns_landscape_three);
        menuItemThree.setChecked(columnsLandscape == 3);
        menuItemFour = mToolbar.getMenu().findItem(R.id.menu_item_columns_landscape_four);
        menuItemFour.setChecked(columnsLandscape == 4);
        menuItemFive = mToolbar.getMenu().findItem(R.id.menu_item_columns_landscape_five);
        menuItemFive.setChecked(columnsLandscape == 5);
        menuItemSix = mToolbar.getMenu().findItem(R.id.menu_item_columns_landscape_six);
        menuItemSix.setChecked(columnsLandscape == 6);
        MenuItem menuItemSeven = mToolbar.getMenu().findItem(R.id.menu_item_columns_landscape_seven);
        menuItemSeven.setChecked(columnsLandscape == 7);
        MenuItem menuItemEight = mToolbar.getMenu().findItem(R.id.menu_item_columns_landscape_eight);
        menuItemEight.setChecked(columnsLandscape == 8);
    }
}
