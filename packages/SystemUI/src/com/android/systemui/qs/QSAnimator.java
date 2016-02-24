/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.qs;

import android.util.Log;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.view.animation.PathInterpolator;
import android.widget.TextView;
import com.android.systemui.qs.PagedTileLayout.PageListener;
import com.android.systemui.qs.QSPanel.QSTileLayout;
import com.android.systemui.qs.QSTile.Host.Callback;
import com.android.systemui.qs.TouchAnimator.Builder;
import com.android.systemui.qs.TouchAnimator.Listener;
import com.android.systemui.statusbar.phone.QSTileHost;

import java.util.ArrayList;
import java.util.Collection;

public class QSAnimator implements Callback, PageListener, Listener, OnLayoutChangeListener {

    private static final String TAG = "QSAnimator";

    public static final PathInterpolator TRANSLATION_Y_INTERPOLATOR =
            new PathInterpolator(.1f, .3f, 1, 1);

    public static final float EXPANDED_TILE_DELAY = .7f;

    private final ArrayList<View> mAllViews = new ArrayList<>();
    private final ArrayList<View> mTopFiveQs = new ArrayList<>();
    private final QuickQSPanel mQuickQsPanel;
    private final QSPanel mQsPanel;
    private final QSContainer mQsContainer;

    private boolean mOnFirstPage = true;
    private TouchAnimator mFirstPageAnimator;
    private TouchAnimator mFirstPageDelayedAnimator;
    private TouchAnimator mTranslationYAnimator;
    private TouchAnimator mNonfirstPageAnimator;

    public QSAnimator(QSContainer container, QuickQSPanel quickPanel, QSPanel panel) {
        mQsContainer = container;
        mQuickQsPanel = quickPanel;
        mQsPanel = panel;
        mQuickQsPanel.addOnLayoutChangeListener(this);
        mQsPanel.addOnLayoutChangeListener(this);
        QSTileLayout tileLayout = mQsPanel.getTileLayout();
        if (tileLayout instanceof PagedTileLayout) {
            ((PagedTileLayout) tileLayout).setPageListener(this);
        } else {
            Log.w(TAG, "QS Not using page layout");
        }
    }

    public void setHost(QSTileHost qsh) {
        qsh.addCallback(this);
    }

    @Override
    public void onPageChanged(int page) {
        mOnFirstPage = page == 0;
        if (!mOnFirstPage) {
            clearAnimationState();
        }
    }

    private void updateAnimators() {
        TouchAnimator.Builder firstPageBuilder = new Builder();
        TouchAnimator.Builder translationYBuilder = new Builder();
        TouchAnimator.Builder firstPageDelayedBuilder = new Builder();
        Collection<QSTile<?>> tiles = mQsPanel.getHost().getTiles();
        int count = 0;
        int[] loc1 = new int[2];
        int[] loc2 = new int[2];
        firstPageDelayedBuilder.setStartDelay(EXPANDED_TILE_DELAY);
        firstPageBuilder.setListener(this);
        translationYBuilder.setInterpolator(TRANSLATION_Y_INTERPOLATOR);
        // Fade in the tiles/labels as we reach the final position.
        firstPageDelayedBuilder.addFloat(mQsPanel.getTileLayout(), "alpha", 0, 1);
        mAllViews.clear();
        mTopFiveQs.clear();
        for (QSTile<?> tile : tiles) {
            QSTileBaseView tileView = mQsPanel.getTileView(tile);
            final TextView label = ((QSTileView) tileView).getLabel();
            if (count++ < 5) {
                // Quick tiles.
                QSTileBaseView quickTileView = mQuickQsPanel.getTileView(tile);
                final View tileIcon = tileView.getIcon();

                getRelativePosition(loc1, quickTileView.getIcon(), mQsContainer);
                getRelativePosition(loc2, tileIcon, mQsContainer);
                final int xDiff = loc2[0] - loc1[0];
                final int yDiff = loc2[1] - loc1[1];
                // Move the quick tile right from its location to the new one.
                firstPageBuilder.addFloat(quickTileView, "translationX", 0, xDiff);
                translationYBuilder.addFloat(quickTileView, "translationY", 0, yDiff);

                // Counteract the parent translation on the tile. So we have a static base to
                // animate the label position off from.
                firstPageBuilder.addFloat(tileView, "translationY", mQsPanel.getHeight(), 0);

                // Move the real tile's label from the quick tile position to its final
                // location.
                firstPageBuilder.addFloat(label, "translationX", -xDiff, 0);
                translationYBuilder.addFloat(label, "translationY", -yDiff, 0);

                mTopFiveQs.add(tileIcon);
                mAllViews.add(tileIcon);
                mAllViews.add(quickTileView);
            }
            mAllViews.add(tileView);
            mAllViews.add(label);
        }
        mFirstPageAnimator = firstPageBuilder.build();
        mFirstPageDelayedAnimator = firstPageDelayedBuilder.build();
        mTranslationYAnimator = translationYBuilder.build();
        mNonfirstPageAnimator = new TouchAnimator.Builder()
                .addFloat(mQuickQsPanel, "alpha", 1, 0)
                .setEndDelay(.5f)
                .build();
    }

    private void getRelativePosition(int[] loc1, View view, View parent) {
        loc1[0] = 0 + view.getWidth() / 2;
        loc1[1] = 0;
        getRelativePositionInt(loc1, view, parent);
    }

    private void getRelativePositionInt(int[] loc1, View view, View parent) {
        if(view == parent || view == null) return;
        loc1[0] += view.getLeft();
        loc1[1] += view.getTop();
        getRelativePositionInt(loc1, (View) view.getParent(), parent);
    }

    public void setPosition(float position) {
        if (mFirstPageAnimator == null) return;
        if (mOnFirstPage) {
            mQuickQsPanel.setAlpha(1);
            mFirstPageAnimator.setPosition(position);
            mFirstPageDelayedAnimator.setPosition(position);
            mTranslationYAnimator.setPosition(position);
        } else {
            mNonfirstPageAnimator.setPosition(position);
        }
    }

    @Override
    public void onAnimationAtStart() {
    }

    @Override
    public void onAnimationAtEnd() {
        mQuickQsPanel.setVisibility(View.INVISIBLE);
        final int N = mTopFiveQs.size();
        for (int i = 0; i < N; i++) {
            mTopFiveQs.get(i).setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onAnimationStarted() {
        mQuickQsPanel.setVisibility(View.VISIBLE);
        if (mOnFirstPage) {
            final int N = mTopFiveQs.size();
            for (int i = 0; i < N; i++) {
                mTopFiveQs.get(i).setVisibility(View.INVISIBLE);
            }
        }
    }

    private void clearAnimationState() {
        final int N = mAllViews.size();
        mQuickQsPanel.setAlpha(0);
        for (int i = 0; i < N; i++) {
            View v = mAllViews.get(i);
            v.setAlpha(1);
            v.setTranslationX(1);
            v.setTranslationY(1);
        }
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
            int oldTop, int oldRight, int oldBottom) {
        updateAnimators();
    }

    @Override
    public void onTilesChanged() {
        // Give the QS panels a moment to generate their new tiles, then create all new animators
        // hooked up to the new views.
        mQsPanel.post(mUpdateAnimators);
    }

    private Runnable mUpdateAnimators = new Runnable() {
        @Override
        public void run() {
            updateAnimators();
        }
    };
}
