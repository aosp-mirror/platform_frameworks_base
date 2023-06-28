/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.systemui.qs.customize.QSCustomizer.EXTRA_QS_CUSTOMIZING;
import static com.android.systemui.qs.customize.QSCustomizer.MENU_RESET;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toolbar;
import android.widget.Toolbar.OnMenuItemClickListener;

import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.plugins.qs.QSContainerController;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.QSEditEvent;
import com.android.systemui.qs.QSFragment;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ViewController;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/** {@link ViewController} for {@link QSCustomizer}. */
@QSScope
public class QSCustomizerController extends ViewController<QSCustomizer> {
    private final TileQueryHelper mTileQueryHelper;
    private final QSHost mQsHost;
    private final TileAdapter mTileAdapter;
    private final ScreenLifecycle mScreenLifecycle;
    private final KeyguardStateController mKeyguardStateController;
    private final LightBarController mLightBarController;
    private final ConfigurationController mConfigurationController;
    private final UiEventLogger mUiEventLogger;
    private final Toolbar mToolbar;

    private final OnMenuItemClickListener mOnMenuItemClickListener = new OnMenuItemClickListener() {
        @Override
        public boolean onMenuItemClick(MenuItem item) {
            if (item.getItemId() == MENU_RESET) {
                mUiEventLogger.log(QSEditEvent.QS_EDIT_RESET);
                reset();
            }
            return false;
        }
    };

    private final KeyguardStateController.Callback mKeyguardCallback =
            new KeyguardStateController.Callback() {
        @Override
        public void onKeyguardShowingChanged() {
            if (!mView.isAttachedToWindow()) return;
            if (mKeyguardStateController.isShowing() && !mView.isOpening()) {
                hide();
            }
        }
    };

    private final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onConfigChanged(Configuration newConfig) {
            mView.updateNavBackDrop(newConfig, mLightBarController);
            mView.updateResources();
            if (mTileAdapter.updateNumColumns()) {
                RecyclerView.LayoutManager lm = mView.getRecyclerView().getLayoutManager();
                if (lm instanceof GridLayoutManager) {
                    ((GridLayoutManager) lm).setSpanCount(mTileAdapter.getNumColumns());
                }
            }
        }
    };

    @Inject
    protected QSCustomizerController(QSCustomizer view, TileQueryHelper tileQueryHelper,
            QSHost qsHost, TileAdapter tileAdapter, ScreenLifecycle screenLifecycle,
            KeyguardStateController keyguardStateController, LightBarController lightBarController,
            ConfigurationController configurationController, UiEventLogger uiEventLogger) {
        super(view);
        mTileQueryHelper = tileQueryHelper;
        mQsHost = qsHost;
        mTileAdapter = tileAdapter;
        mScreenLifecycle = screenLifecycle;
        mKeyguardStateController = keyguardStateController;
        mLightBarController = lightBarController;
        mConfigurationController = configurationController;
        mUiEventLogger = uiEventLogger;

        mToolbar = mView.findViewById(com.android.internal.R.id.action_bar);
    }


    @Override
    protected void onViewAttached() {
        mView.updateNavBackDrop(getResources().getConfiguration(), mLightBarController);

        mConfigurationController.addCallback(mConfigurationListener);

        mTileQueryHelper.setListener(mTileAdapter);
        int halfMargin =
                getResources().getDimensionPixelSize(R.dimen.qs_tile_margin_horizontal) / 2;
        mTileAdapter.changeHalfMargin(halfMargin);

        RecyclerView recyclerView = mView.getRecyclerView();
        recyclerView.setAdapter(mTileAdapter);
        mTileAdapter.getItemTouchHelper().attachToRecyclerView(recyclerView);
        GridLayoutManager layout =
                new GridLayoutManager(getContext(), mTileAdapter.getNumColumns()) {
            @Override
            public void onInitializeAccessibilityNodeInfoForItem(RecyclerView.Recycler recycler,
                    RecyclerView.State state, View host, AccessibilityNodeInfoCompat info) {
                // Do not read row and column every time it changes.
            }

            public void calculateItemDecorationsForChild(View child, Rect outRect) {
                // There's only a single item decoration that cares about the itemOffsets, so
                // we just call it manually so they are never cached. This way, it's updated as the
                // tiles are moved around.
                // It only sets the left and right margin and only cares about tiles (not TextView).
                if (!(child instanceof TextView)) {
                    outRect.setEmpty();
                    mTileAdapter.getMarginItemDecoration().getItemOffsets(outRect, child,
                            recyclerView, new RecyclerView.State());
                    ((LayoutParams) child.getLayoutParams()).leftMargin = outRect.left;
                    ((LayoutParams) child.getLayoutParams()).rightMargin = outRect.right;
                }
            }
        };
        layout.setSpanSizeLookup(mTileAdapter.getSizeLookup());
        recyclerView.setLayoutManager(layout);
        recyclerView.addItemDecoration(mTileAdapter.getItemDecoration());
        recyclerView.addItemDecoration(mTileAdapter.getMarginItemDecoration());

        mToolbar.setOnMenuItemClickListener(mOnMenuItemClickListener);
        mToolbar.setNavigationOnClickListener(v -> hide());
    }

    @Override
    protected void onViewDetached() {
        mTileQueryHelper.setListener(null);
        mToolbar.setOnMenuItemClickListener(null);
        mConfigurationController.removeCallback(mConfigurationListener);
    }


    private void reset() {
        mTileAdapter.resetTileSpecs(QSHost.getDefaultSpecs(getContext()));
    }

    public boolean isCustomizing() {
        return mView.isCustomizing();
    }

    /** */
    public void show(int x, int y, boolean immediate) {
        if (!mView.isShown()) {
            setTileSpecs();
            if (immediate) {
                mView.showImmediately();
            } else {
                mView.show(x, y, mTileAdapter);
                mUiEventLogger.log(QSEditEvent.QS_EDIT_OPEN);
            }
            mTileQueryHelper.queryTiles(mQsHost);
            mKeyguardStateController.addCallback(mKeyguardCallback);
            mView.updateNavColors(mLightBarController);
        }
    }

    /** */
    public void setQs(@Nullable QSFragment qsFragment) {
        mView.setQs(qsFragment);
    }

    /** */
    public void restoreInstanceState(Bundle savedInstanceState) {
        boolean customizing = savedInstanceState.getBoolean(EXTRA_QS_CUSTOMIZING);
        if (customizing) {
            mView.setVisibility(View.VISIBLE);
            mView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft,
                        int oldTop, int oldRight, int oldBottom) {
                    mView.removeOnLayoutChangeListener(this);
                    show(0, 0, true);
                }
            });
        }
    }

    /** */
    public void saveInstanceState(Bundle outState) {
        if (mView.isShown()) {
            mKeyguardStateController.removeCallback(mKeyguardCallback);
        }
        outState.putBoolean(EXTRA_QS_CUSTOMIZING, mView.isCustomizing());
    }

    /** */
    public void setEditLocation(int x, int y) {
        mView.setEditLocation(x, y);
    }

    /** */
    public void setContainerController(QSContainerController controller) {
        mView.setContainerController(controller);
    }

    public boolean isShown() {
        return mView.isShown();
    }

    /** Hice the customizer. */
    public void hide() {
        final boolean animate = mScreenLifecycle.getScreenState() != ScreenLifecycle.SCREEN_OFF;
        if (mView.isShown()) {
            mUiEventLogger.log(QSEditEvent.QS_EDIT_CLOSED);
            mToolbar.dismissPopupMenus();
            mView.setCustomizing(false);
            save();
            mView.hide(animate);
            mView.updateNavColors(mLightBarController);
            mKeyguardStateController.removeCallback(mKeyguardCallback);
        }
    }

    private void save() {
        if (mTileQueryHelper.isFinished()) {
            mTileAdapter.saveSpecs(mQsHost);
        }
    }

    private void setTileSpecs() {
        List<String> specs = new ArrayList<>();
        for (QSTile tile : mQsHost.getTiles()) {
            specs.add(tile.getTileSpec());
        }
        mTileAdapter.setTileSpecs(specs);
    }
}
