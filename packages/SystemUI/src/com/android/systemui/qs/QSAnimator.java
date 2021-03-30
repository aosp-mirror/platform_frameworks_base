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

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.util.Log;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.view.View.OnLayoutChangeListener;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTileView;
import com.android.systemui.qs.PagedTileLayout.PageListener;
import com.android.systemui.qs.QSHost.Callback;
import com.android.systemui.qs.QSPanel.QSTileLayout;
import com.android.systemui.qs.TouchAnimator.Builder;
import com.android.systemui.qs.TouchAnimator.Listener;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/** */
@QSScope
public class QSAnimator implements Callback, PageListener, Listener, OnLayoutChangeListener,
        OnAttachStateChangeListener, Tunable {

    private static final String TAG = "QSAnimator";

    private static final String ALLOW_FANCY_ANIMATION = "sysui_qs_fancy_anim";
    private static final String MOVE_FULL_ROWS = "sysui_qs_move_whole_rows";

    public static final float EXPANDED_TILE_DELAY = .86f;


    private final ArrayList<View> mAllViews = new ArrayList<>();
    /**
     * List of {@link View}s representing Quick Settings that are being animated from the quick QS
     * position to the normal QS panel. These views will only show once the animation is complete,
     * to prevent overlapping of semi transparent views
     */
    private final ArrayList<View> mQuickQsViews = new ArrayList<>();
    private final QuickQSPanel mQuickQsPanel;
    private final QSPanelController mQsPanelController;
    private final QuickQSPanelController mQuickQSPanelController;
    private final QuickStatusBarHeader mQuickStatusBarHeader;
    private final QSSecurityFooter mSecurityFooter;
    private final QS mQs;

    private PagedTileLayout mPagedLayout;

    private boolean mOnFirstPage = true;
    private QSExpansionPathInterpolator mQSExpansionPathInterpolator;
    private TouchAnimator mFirstPageAnimator;
    private TouchAnimator mFirstPageDelayedAnimator;
    private TouchAnimator mTranslationXAnimator;
    private TouchAnimator mTranslationYAnimator;
    private TouchAnimator mNonfirstPageAnimator;
    private TouchAnimator mNonfirstPageDelayedAnimator;
    // This animates fading of SecurityFooter and media divider
    private TouchAnimator mAllPagesDelayedAnimator;
    private TouchAnimator mBrightnessAnimator;
    private HeightExpansionAnimator mQQSTileHeightAnimator;
    private HeightExpansionAnimator mOtherTilesExpandAnimator;

    private boolean mNeedsAnimatorUpdate = false;

    private boolean mOnKeyguard;

    private boolean mAllowFancy;
    private boolean mFullRows;
    private int mNumQuickTiles;
    private float mLastPosition;
    private final QSTileHost mHost;
    private final Executor mExecutor;
    private final TunerService mTunerService;
    private boolean mShowCollapsedOnKeyguard;
    private final FeatureFlags mFeatureFlags;

    @Inject
    public QSAnimator(QS qs, QuickQSPanel quickPanel, QuickStatusBarHeader quickStatusBarHeader,
            QSPanelController qsPanelController,
            QuickQSPanelController quickQSPanelController, QSTileHost qsTileHost,
            QSSecurityFooter securityFooter, @Main Executor executor, TunerService tunerService,
            FeatureFlags featureFlags, QSExpansionPathInterpolator qsExpansionPathInterpolator) {
        mQs = qs;
        mQuickQsPanel = quickPanel;
        mQsPanelController = qsPanelController;
        mQuickQSPanelController = quickQSPanelController;
        mQuickStatusBarHeader = quickStatusBarHeader;
        mSecurityFooter = securityFooter;
        mHost = qsTileHost;
        mExecutor = executor;
        mTunerService = tunerService;
        mFeatureFlags = featureFlags;
        mQSExpansionPathInterpolator = qsExpansionPathInterpolator;
        mHost.addCallback(this);
        mQsPanelController.addOnAttachStateChangeListener(this);
        qs.getView().addOnLayoutChangeListener(this);
        if (mQsPanelController.isAttachedToWindow()) {
            onViewAttachedToWindow(null);
        }
        QSTileLayout tileLayout = mQsPanelController.getTileLayout();
        if (tileLayout instanceof PagedTileLayout) {
            mPagedLayout = ((PagedTileLayout) tileLayout);
        } else {
            Log.w(TAG, "QS Not using page layout");
        }
        mQsPanelController.setPageListener(this);
    }

    public void onRtlChanged() {
        updateAnimators();
    }


    public void onQsScrollingChanged() {
        // Lazily update animators whenever the scrolling changes
        mNeedsAnimatorUpdate = true;
    }

    public void setOnKeyguard(boolean onKeyguard) {
        mOnKeyguard = onKeyguard;
        updateQQSVisibility();
        if (mOnKeyguard) {
            clearAnimationState();
        }
    }


    /**
     * Sets whether or not the keyguard is currently being shown with a collapsed header.
     */
    void setShowCollapsedOnKeyguard(boolean showCollapsedOnKeyguard) {
        mShowCollapsedOnKeyguard = showCollapsedOnKeyguard;
        updateQQSVisibility();
        setCurrentPosition();
    }


    private void setCurrentPosition() {
        setPosition(mLastPosition);
    }

    private void updateQQSVisibility() {
        mQuickQsPanel.setVisibility(mOnKeyguard
                && !mShowCollapsedOnKeyguard ? View.INVISIBLE : View.VISIBLE);
    }

    @Override
    public void onViewAttachedToWindow(View v) {
        mTunerService.addTunable(this, ALLOW_FANCY_ANIMATION,
                MOVE_FULL_ROWS);
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        mHost.removeCallback(this);
        mTunerService.removeTunable(this);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (ALLOW_FANCY_ANIMATION.equals(key)) {
            mAllowFancy = TunerService.parseIntegerSwitch(newValue, true);
            if (!mAllowFancy) {
                clearAnimationState();
            }
        } else if (MOVE_FULL_ROWS.equals(key)) {
            mFullRows = TunerService.parseIntegerSwitch(newValue, true);
        }
        updateAnimators();
    }

    @Override
    public void onPageChanged(boolean isFirst) {
        if (mOnFirstPage == isFirst) return;
        if (!isFirst) {
            clearAnimationState();
        }
        mOnFirstPage = isFirst;
    }

    private void updateAnimators() {
        mNeedsAnimatorUpdate = false;
        TouchAnimator.Builder firstPageBuilder = new Builder();
        TouchAnimator.Builder translationXBuilder = new Builder();
        TouchAnimator.Builder translationYBuilder = new Builder();

        Collection<QSTile> tiles = mHost.getTiles();
        int count = 0;
        int[] loc1 = new int[2];
        int[] loc2 = new int[2];

        clearAnimationState();
        mAllViews.clear();
        mQuickQsViews.clear();
        mQQSTileHeightAnimator = null;
        mOtherTilesExpandAnimator = null;

        mNumQuickTiles = mQuickQsPanel.getNumQuickTiles();

        QSTileLayout tileLayout = mQsPanelController.getTileLayout();
        mAllViews.add((View) tileLayout);
        int height = mQs.getView() != null ? mQs.getView().getMeasuredHeight() : 0;
        int width = mQs.getView() != null ? mQs.getView().getMeasuredWidth() : 0;
        int heightDiff = height - mQs.getHeader().getBottom()
                + mQs.getHeader().getPaddingBottom();
        firstPageBuilder.addFloat(tileLayout, "translationY", heightDiff, 0);

        boolean qsSideLabelsEnabled = mFeatureFlags.isQSLabelsEnabled();
        int qqsTileHeight = 0;

        for (QSTile tile : tiles) {
            QSTileView tileView = mQsPanelController.getTileView(tile);
            if (tileView == null) {
                Log.e(TAG, "tileView is null " + tile.getTileSpec());
                continue;
            }
            final View tileIcon = tileView.getIcon().getIconView();
            View view = mQs.getView();

            // This case: less tiles to animate in small displays.
            if (count < mQuickQSPanelController.getTileLayout().getNumVisibleTiles()
                    && mAllowFancy) {
                // Quick tiles.
                QSTileView quickTileView = mQuickQSPanelController.getTileView(tile);
                if (quickTileView == null) continue;

                getRelativePosition(loc1, quickTileView.getIcon().getIconView(), view);
                getRelativePosition(loc2, tileIcon, view);
                final int xDiff = loc2[0] - loc1[0];
                int yDiff = loc2[1] - loc1[1];

                if (count < tileLayout.getNumVisibleTiles()) {
                    getRelativePosition(loc1, quickTileView, view);
                    getRelativePosition(loc2, tileView, view);
                    int yOffset = qsSideLabelsEnabled
                            ? loc2[1] - loc1[1]
                            : mQuickStatusBarHeader.getOffsetTranslation();
                    // Move the quick tile right from its location to the new one.
                    View v = qsSideLabelsEnabled ? quickTileView.getIcon() : quickTileView;
                    translationXBuilder.addFloat(v, "translationX", 0, xDiff);
                    translationYBuilder.addFloat(v, "translationY", 0, yDiff - yOffset);
                    mAllViews.add(v);

                    // Move the real tile from the quick tile position to its final
                    // location.
                    v = qsSideLabelsEnabled ? tileIcon : tileView;
                    translationXBuilder.addFloat(v, "translationX", -xDiff, 0);
                    translationYBuilder.addFloat(v, "translationY", -yDiff + yOffset, 0);

                    if (qsSideLabelsEnabled) {
                        // Offset the translation animation on the views
                        // (that goes from 0 to getOffsetTranslation)
                        int offsetWithQSBHTranslation =
                                yOffset - mQuickStatusBarHeader.getOffsetTranslation();
                        translationYBuilder.addFloat(quickTileView, "translationY", 0,
                                offsetWithQSBHTranslation);
                        translationYBuilder.addFloat(tileView, "translationY",
                                -offsetWithQSBHTranslation, 0);

                        if (mQQSTileHeightAnimator == null) {
                            mQQSTileHeightAnimator = new HeightExpansionAnimator(this,
                                    quickTileView.getHeight(), tileView.getHeight());
                            qqsTileHeight = quickTileView.getHeight();
                        }

                        mQQSTileHeightAnimator.addView(quickTileView);
                        View qqsLabelContainer = quickTileView.getLabelContainer();
                        View qsLabelContainer = tileView.getLabelContainer();

                        getRelativePosition(loc1, qqsLabelContainer, view);
                        getRelativePosition(loc2, qsLabelContainer, view);
                        yDiff = loc2[1] - loc1[1] - yOffset;

                        translationYBuilder.addFloat(qqsLabelContainer, "translationY", 0, yDiff);
                        translationYBuilder.addFloat(qsLabelContainer, "translationY", -yDiff, 0);
                        mAllViews.add(qqsLabelContainer);
                        mAllViews.add(qsLabelContainer);
                    }

                } else { // These tiles disappear when expanding
                    firstPageBuilder.addFloat(quickTileView, "alpha", 1, 0);
                    translationYBuilder.addFloat(quickTileView, "translationY", 0, yDiff);

                    // xDiff is negative here and this makes it "more" negative
                    final int translationX =
                            mQsPanelController.isLayoutRtl() ? xDiff - width : xDiff + width;
                    translationXBuilder.addFloat(quickTileView, "translationX", 0,
                            translationX);
                }

                if (qsSideLabelsEnabled) {
                    mQuickQsViews.add(tileView);
                } else {
                    mQuickQsViews.add(tileView.getIconWithBackground());
                }
                mAllViews.add(tileView.getIcon());
                mAllViews.add(quickTileView);
            } else if (mFullRows && isIconInAnimatedRow(count)) {

                firstPageBuilder.addFloat(tileView, "translationY", -heightDiff, 0);

                mAllViews.add(tileIcon);
            } else {
                if (!qsSideLabelsEnabled) {
                    firstPageBuilder.addFloat(tileView, "alpha", 0, 1);
                    firstPageBuilder.addFloat(tileView, "translationY", -heightDiff, 0);
                } else {
                    // Pretend there's a corresponding QQS tile (for the position) that we are
                    // expanding from.
                    SideLabelTileLayout qqsLayout =
                            (SideLabelTileLayout) mQuickQsPanel.getTileLayout();
                    getRelativePosition(loc1, qqsLayout, view);
                    getRelativePosition(loc2, tileView, view);
                    int diff = loc2[1] - (loc1[1] + qqsLayout.getPhantomTopPosition(count));
                    translationYBuilder.addFloat(tileView, "translationY", -diff, 0);
                    if (mOtherTilesExpandAnimator == null) {
                        mOtherTilesExpandAnimator =
                                new HeightExpansionAnimator(
                                        this, qqsTileHeight, tileView.getHeight());
                    }
                    mOtherTilesExpandAnimator.addView(tileView);
                    tileView.setClipChildren(true);
                    tileView.setClipToPadding(true);
                }
            }

            mAllViews.add(tileView);
            count++;
        }

        if (mAllowFancy) {
            // Make brightness appear static position and alpha in through second half.
            View brightness = mQsPanelController.getBrightnessView();
            if (brightness != null) {
                firstPageBuilder.addFloat(brightness, "translationY", heightDiff, 0);
                mBrightnessAnimator = new TouchAnimator.Builder()
                        .addFloat(brightness, "alpha", 0, 1)
                        .setStartDelay(.5f)
                        .build();
                mAllViews.add(brightness);
            } else {
                mBrightnessAnimator = null;
            }
            mFirstPageAnimator = firstPageBuilder
                    .setListener(this)
                    .build();
            // Fade in the tiles/labels as we reach the final position.
            Builder builder = new Builder()
                    .setStartDelay(qsSideLabelsEnabled ? 0 : EXPANDED_TILE_DELAY)
                    .addFloat(tileLayout, "alpha", 0, 1);
            mFirstPageDelayedAnimator = builder.build();

            // Fade in the security footer and the divider as we reach the final position
            builder = new Builder().setStartDelay(EXPANDED_TILE_DELAY);
            builder.addFloat(mSecurityFooter.getView(), "alpha", 0, 1);
            if (mQsPanelController.getDivider() != null) {
                builder.addFloat(mQsPanelController.getDivider(), "alpha", 0, 1);
            }
            mAllPagesDelayedAnimator = builder.build();
            mAllViews.add(mSecurityFooter.getView());
            if (mQsPanelController.getDivider() != null) {
                mAllViews.add(mQsPanelController.getDivider());
            }

            float px = 0;
            if (tiles.size() <= 3) {
                px = 1;
            } else if (tiles.size() <= 6) {
                px = .4f;
            }
            mQSExpansionPathInterpolator.setControlX2(px);
            translationXBuilder.setInterpolator(mQSExpansionPathInterpolator.getXInterpolator());
            translationYBuilder.setInterpolator(mQSExpansionPathInterpolator.getYInterpolator());
            mTranslationXAnimator = translationXBuilder.build();
            mTranslationYAnimator = translationYBuilder.build();
            if (mQQSTileHeightAnimator != null) {
                mQQSTileHeightAnimator.setInterpolator(
                        mQSExpansionPathInterpolator.getYInterpolator());
            }
            if (mOtherTilesExpandAnimator != null) {
                mOtherTilesExpandAnimator.setInterpolator(
                        mQSExpansionPathInterpolator.getYInterpolator());
            }
        }
        mNonfirstPageAnimator = new TouchAnimator.Builder()
                .addFloat(mQuickQsPanel, "alpha", 1, 0)
                .setListener(mNonFirstPageListener)
                .setEndDelay(.5f)
                .build();
        mNonfirstPageDelayedAnimator = new TouchAnimator.Builder()
                .setStartDelay(.14f)
                .addFloat(tileLayout, "alpha", 0, 1).build();
    }

    private boolean isIconInAnimatedRow(int count) {
        if (mPagedLayout == null) {
            return false;
        }
        final int columnCount = mPagedLayout.getColumnCount();
        return count < ((mNumQuickTiles + columnCount - 1) / columnCount) * columnCount;
    }

    private void getRelativePosition(int[] loc1, View view, View parent) {
        loc1[0] = 0 + view.getWidth() / 2;
        loc1[1] = 0;
        getRelativePositionInt(loc1, view, parent);
    }

    private void getRelativePositionInt(int[] loc1, View view, View parent) {
        if(view == parent || view == null) return;
        // Ignore tile pages as they can have some offset we don't want to take into account in
        // RTL.
        if (!isAPage(view)) {
            loc1[0] += view.getLeft();
            loc1[1] += view.getTop();
        }
        if (!(view instanceof PagedTileLayout)) {
            // Remove the scrolling position of all scroll views other than the viewpager
            loc1[0] -= view.getScrollX();
            loc1[1] -= view.getScrollY();
        }
        getRelativePositionInt(loc1, (View) view.getParent(), parent);
    }

    // Returns true if the view is a possible page in PagedTileLayout
    private boolean isAPage(View view) {
        if (view instanceof PagedTileLayout.TilePage) {
            return true;
        } else if (view instanceof SideLabelTileLayout) {
            return !(view instanceof QuickQSPanel.QQSSideLabelTileLayout);
        }
        return false;
    }

    public void setPosition(float position) {
        if (mNeedsAnimatorUpdate) {
            updateAnimators();
        }
        if (mFirstPageAnimator == null) return;
        if (mOnKeyguard) {
            if (mShowCollapsedOnKeyguard) {
                position = 0;
            } else {
                position = 1;
            }
        }
        mLastPosition = position;
        if (mOnFirstPage && mAllowFancy) {
            mQuickQsPanel.setAlpha(1);
            mFirstPageAnimator.setPosition(position);
            mFirstPageDelayedAnimator.setPosition(position);
            mTranslationXAnimator.setPosition(position);
            mTranslationYAnimator.setPosition(position);
            if (mBrightnessAnimator != null) {
                mBrightnessAnimator.setPosition(position);
            }
            if (mQQSTileHeightAnimator != null) {
                mQQSTileHeightAnimator.setPosition(position);
            }
            if (mOtherTilesExpandAnimator != null) {
                mOtherTilesExpandAnimator.setPosition(position);
            }
        } else {
            mNonfirstPageAnimator.setPosition(position);
            mNonfirstPageDelayedAnimator.setPosition(position);
        }
        if (mAllowFancy) {
            mAllPagesDelayedAnimator.setPosition(position);
        }
    }

    @Override
    public void onAnimationAtStart() {
        mQuickQsPanel.setVisibility(View.VISIBLE);
    }

    @Override
    public void onAnimationAtEnd() {
        mQuickQsPanel.setVisibility(View.INVISIBLE);
        final int N = mQuickQsViews.size();
        for (int i = 0; i < N; i++) {
            mQuickQsViews.get(i).setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onAnimationStarted() {
        updateQQSVisibility();
        if (mOnFirstPage) {
            final int N = mQuickQsViews.size();
            for (int i = 0; i < N; i++) {
                mQuickQsViews.get(i).setVisibility(View.INVISIBLE);
            }
        }
    }

    private void clearAnimationState() {
        final int N = mAllViews.size();
        mQuickQsPanel.setAlpha(0);
        for (int i = 0; i < N; i++) {
            View v = mAllViews.get(i);
            v.setAlpha(1);
            v.setTranslationX(0);
            v.setTranslationY(0);
            if (v instanceof SideLabelTileLayout) {
                ((SideLabelTileLayout) v).setClipChildren(false);
                ((SideLabelTileLayout) v).setClipToPadding(false);
            }
        }
        if (mQQSTileHeightAnimator != null) {
            mQQSTileHeightAnimator.resetViewsHeights();
        }
        if (mOtherTilesExpandAnimator != null) {
            mOtherTilesExpandAnimator.resetViewsHeights();
        }
        final int N2 = mQuickQsViews.size();
        for (int i = 0; i < N2; i++) {
            mQuickQsViews.get(i).setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
            int oldTop, int oldRight, int oldBottom) {
        mExecutor.execute(mUpdateAnimators);
    }

    @Override
    public void onTilesChanged() {
        // Give the QS panels a moment to generate their new tiles, then create all new animators
        // hooked up to the new views.
        mExecutor.execute(mUpdateAnimators);
    }

    private final TouchAnimator.Listener mNonFirstPageListener =
            new TouchAnimator.ListenerAdapter() {
                @Override
                public void onAnimationAtEnd() {
                    mQuickQsPanel.setVisibility(View.INVISIBLE);
                }

                @Override
                public void onAnimationStarted() {
                    mQuickQsPanel.setVisibility(View.VISIBLE);
                }
            };

    private final Runnable mUpdateAnimators = () -> {
        updateAnimators();
        setCurrentPosition();
    };

    static class HeightExpansionAnimator {
        private final List<View> mViews = new ArrayList<>();
        private final ValueAnimator mAnimator;
        private final TouchAnimator.Listener mListener;

        private final ValueAnimator.AnimatorUpdateListener mUpdateListener =
                new ValueAnimator.AnimatorUpdateListener() {
            float mLastT = -1;
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                float t = valueAnimator.getAnimatedFraction();
                if (t == 0f) {
                    mListener.onAnimationAtStart();
                } else if (t == 1f) {
                    mListener.onAnimationAtEnd();
                } else if (mLastT <= 0 || mLastT == 1) {
                    mListener.onAnimationStarted();
                }
                mLastT = t;
                final int viewCount = mViews.size();
                int height = (Integer) valueAnimator.getAnimatedValue();
                for (int i = 0; i < viewCount; i++) {
                    View v = mViews.get(i);
                    v.setBottom(v.getTop() + height);
                }
            }
        };

        HeightExpansionAnimator(TouchAnimator.Listener listener, int startHeight, int endHeight) {
            mListener = listener;
            mAnimator = ValueAnimator.ofInt(startHeight, endHeight);
            mAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mAnimator.setRepeatMode(ValueAnimator.REVERSE);
            mAnimator.addUpdateListener(mUpdateListener);
        }

        void addView(View v) {
            mViews.add(v);
        }

        void setInterpolator(TimeInterpolator interpolator) {
            mAnimator.setInterpolator(interpolator);
        }

        void setPosition(float position) {
            mAnimator.setCurrentFraction(position);
        }

        void resetViewsHeights() {
            final int viewsCount = mViews.size();
            for (int i = 0; i < viewsCount; i++) {
                View v = mViews.get(i);
                v.setBottom(v.getTop() + v.getMeasuredHeight());
            }
        }
    }
}
