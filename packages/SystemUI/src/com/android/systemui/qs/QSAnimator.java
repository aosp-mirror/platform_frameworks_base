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
import android.annotation.NonNull;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.view.View.OnLayoutChangeListener;

import androidx.annotation.Nullable;

import com.android.systemui.animation.Interpolators;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTileView;
import com.android.systemui.qs.QSPanel.QSTileLayout;
import com.android.systemui.qs.TouchAnimator.Builder;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.qs.tileimpl.HeightOverrideable;
import com.android.systemui.tuner.TunerService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Performs the animated transition between the QQS and QS views.
 *
 * <p>The transition is driven externally via {@link #setPosition(float)}, where 0 is a fully
 * collapsed QQS and one a fully expanded QS.
 *
 * <p>This implementation maintains a set of {@code TouchAnimator} to transition the properties of
 * views both in QQS and QS. These {@code TouchAnimator} are re-created lazily if contents of either
 * view change, see {@link #requestAnimatorUpdate()}.
 *
 * <p>During the transition, both QS and QQS are visible. For overlapping tiles (Whenever the QS
 * shows the first page), the corresponding QS tiles are hidden until QS is fully expanded.
 */
@QSScope
public class QSAnimator implements QSHost.Callback, PagedTileLayout.PageListener,
        TouchAnimator.Listener, OnLayoutChangeListener,
        OnAttachStateChangeListener {

    private static final String TAG = "QSAnimator";

    private static final float EXPANDED_TILE_DELAY = .86f;
    //Non first page delays
    private static final float QS_TILE_LABEL_FADE_OUT_START = 0.15f;
    private static final float QS_TILE_LABEL_FADE_OUT_END = 0.7f;
    private static final float QQS_FADE_IN_INTERVAL = 0.1f;

    public static final float SHORT_PARALLAX_AMOUNT = 0.1f;

    /**
     * List of all views that will be reset when clearing animation state
     * see {@link #clearAnimationState()} }
     */
    private final ArrayList<View> mAllViews = new ArrayList<>();
    /**
     * List of {@link View}s representing Quick Settings that are being animated from the quick QS
     * position to the normal QS panel. These views will only show once the animation is complete,
     * to prevent overlapping of semi transparent views
     */
    private final ArrayList<View> mAnimatedQsViews = new ArrayList<>();
    private final QuickQSPanel mQuickQsPanel;
    private final QSPanelController mQsPanelController;
    private final QuickQSPanelController mQuickQSPanelController;
    private final QuickStatusBarHeader mQuickStatusBarHeader;
    private final QS mQs;

    @Nullable
    private PagedTileLayout mPagedLayout;

    private boolean mOnFirstPage = true;
    private int mCurrentPage = 0;
    private final QSExpansionPathInterpolator mQSExpansionPathInterpolator;
    // Animator for elements in the first page, including secondary labels and qqs brightness
    // slider, as well as animating the alpha of the QS tile layout (as we are tracking QQS tiles)
    @Nullable
    private TouchAnimator mFirstPageAnimator;
    // TranslationX animator for QQS/QS tiles. Only used on the first page!
    private TouchAnimator mTranslationXAnimator;
    // TranslationY animator for QS tiles (and their components) in the first page
    private TouchAnimator mTranslationYAnimator;
    // TranslationY animator for QQS tiles (and their components)
    private TouchAnimator mQQSTranslationYAnimator;
    // Animates alpha of permanent views (QS tile layout, QQS tiles) when not in first page
    private TouchAnimator mNonfirstPageAlphaAnimator;
    // This animates fading of media player
    private TouchAnimator mAllPagesDelayedAnimator;
    // Brightness slider translation driver, uses mQSExpansionPathInterpolator.yInterpolator
    @Nullable
    private TouchAnimator mBrightnessTranslationAnimator;
    // Brightness slider opacity driver. Uses linear interpolator.
    @Nullable
    private TouchAnimator mBrightnessOpacityAnimator;
    // Animator for Footer actions in QQS
    private TouchAnimator mQQSFooterActionsAnimator;
    // Height animator for QQS tiles (height changing from QQS size to QS size)
    @Nullable
    private HeightExpansionAnimator mQQSTileHeightAnimator;
    // Height animator for QS tile in first page but not in QQS, to present the illusion that they
    // are expanding alongside the QQS tiles
    @Nullable
    private HeightExpansionAnimator mOtherFirstPageTilesHeightAnimator;
    // Pair of animators for each non first page. The creation is delayed until the user first
    // scrolls to that page, in order to get the proper measures and layout.
    private final SparseArray<Pair<HeightExpansionAnimator, TouchAnimator>>
            mNonFirstPageQSAnimators = new SparseArray<>();

    private boolean mNeedsAnimatorUpdate = false;
    private boolean mOnKeyguard;

    private int mNumQuickTiles;
    private int mLastQQSTileHeight;
    private float mLastPosition;
    private final QSTileHost mHost;
    private final Executor mExecutor;
    private boolean mShowCollapsedOnKeyguard;
    private int mQQSTop;

    private int[] mTmpLoc1 = new int[2];
    private int[] mTmpLoc2 = new int[2];

    @Inject
    public QSAnimator(QS qs, QuickQSPanel quickPanel, QuickStatusBarHeader quickStatusBarHeader,
            QSPanelController qsPanelController,
            QuickQSPanelController quickQSPanelController, QSTileHost qsTileHost,
            @Main Executor executor, TunerService tunerService,
            QSExpansionPathInterpolator qsExpansionPathInterpolator) {
        mQs = qs;
        mQuickQsPanel = quickPanel;
        mQsPanelController = qsPanelController;
        mQuickQSPanelController = quickQSPanelController;
        mQuickStatusBarHeader = quickStatusBarHeader;
        mHost = qsTileHost;
        mExecutor = executor;
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

    /**
     * Request an update to the animators. This will update them lazily next time the position
     * is changed.
     */
    public void requestAnimatorUpdate() {
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
    public void onViewAttachedToWindow(@NonNull View view) {
        updateAnimators();
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull View v) {
        mHost.removeCallback(this);
    }

    private void addNonFirstPageAnimators(int page) {
        Pair<HeightExpansionAnimator, TouchAnimator> pair = createSecondaryPageAnimators(page);
        if (pair != null) {
            // pair is null in one of two cases:
            // * mPagedTileLayout is null, meaning we are still setting up.
            // * the page has no tiles
            // In either case, don't add the animators to the map.
            mNonFirstPageQSAnimators.put(page, pair);
        }
    }

    @Override
    public void onPageChanged(boolean isFirst, int currentPage) {
        if (currentPage != INVALID_PAGE && mCurrentPage != currentPage) {
            mCurrentPage = currentPage;
            if (!isFirst && !mNonFirstPageQSAnimators.contains(currentPage)) {
                addNonFirstPageAnimators(currentPage);
            }
        }
        if (mOnFirstPage == isFirst) return;
        if (!isFirst) {
            clearAnimationState();
        }
        mOnFirstPage = isFirst;
    }

    private void translateContent(
            View qqsView,
            View qsView,
            View commonParent,
            int xOffset,
            int yOffset,
            int[] temp,
            TouchAnimator.Builder animatorBuilderX,
            TouchAnimator.Builder animatorBuilderY,
            TouchAnimator.Builder qqsAnimatorBuilderY
    ) {
        getRelativePosition(temp, qqsView, commonParent);
        int qqsPosX = temp[0];
        int qqsPosY = temp[1];
        getRelativePosition(temp, qsView, commonParent);
        int qsPosX = temp[0];
        int qsPosY = temp[1];

        int xDiff = qsPosX - qqsPosX - xOffset;
        animatorBuilderX.addFloat(qqsView, "translationX", 0, xDiff);
        animatorBuilderX.addFloat(qsView, "translationX", -xDiff, 0);
        int yDiff = qsPosY - qqsPosY - yOffset;
        qqsAnimatorBuilderY.addFloat(qqsView, "translationY", 0, yDiff);
        animatorBuilderY.addFloat(qsView, "translationY", -yDiff, 0);
        mAllViews.add(qqsView);
        mAllViews.add(qsView);
    }

    private void updateAnimators() {
        mNeedsAnimatorUpdate = false;
        TouchAnimator.Builder firstPageBuilder = new Builder();
        TouchAnimator.Builder translationYBuilder = new Builder();
        TouchAnimator.Builder qqsTranslationYBuilder = new Builder();
        TouchAnimator.Builder translationXBuilder = new Builder();
        TouchAnimator.Builder nonFirstPageAlphaBuilder = new Builder();
        TouchAnimator.Builder quadraticInterpolatorBuilder = new Builder()
                .setInterpolator(Interpolators.ACCELERATE);

        Collection<QSTile> tiles = mHost.getTiles();
        int count = 0;

        clearAnimationState();
        mNonFirstPageQSAnimators.clear();
        mAllViews.clear();
        mAnimatedQsViews.clear();
        mQQSTileHeightAnimator = null;
        mOtherFirstPageTilesHeightAnimator = null;

        mNumQuickTiles = mQuickQsPanel.getNumQuickTiles();

        QSTileLayout tileLayout = mQsPanelController.getTileLayout();
        mAllViews.add((View) tileLayout);

        mLastQQSTileHeight = 0;

        if (mQsPanelController.areThereTiles()) {
            for (QSTile tile : tiles) {
                QSTileView tileView = mQsPanelController.getTileView(tile);

                if (tileView == null) {
                    Log.e(TAG, "tileView is null " + tile.getTileSpec());
                    continue;
                }
                // Only animate tiles in the first page
                if (mPagedLayout != null && count >= mPagedLayout.getNumTilesFirstPage()) {
                    break;
                }

                final View tileIcon = tileView.getIcon().getIconView();
                View view = mQs.getView();

                // This case: less tiles to animate in small displays.
                if (count < mQuickQSPanelController.getTileLayout().getNumVisibleTiles()) {
                    // Quick tiles.
                    QSTileView quickTileView = mQuickQSPanelController.getTileView(tile);
                    if (quickTileView == null) continue;

                    getRelativePosition(mTmpLoc1, quickTileView, view);
                    getRelativePosition(mTmpLoc2, tileView, view);
                    int yOffset = mTmpLoc2[1] - mTmpLoc1[1];
                    int xOffset = mTmpLoc2[0] - mTmpLoc1[0];

                    // Offset the translation animation on the views
                    // (that goes from 0 to getOffsetTranslation)
                    int offsetWithQSBHTranslation =
                            yOffset - mQuickStatusBarHeader.getOffsetTranslation();
                    qqsTranslationYBuilder.addFloat(quickTileView, "translationY", 0,
                            offsetWithQSBHTranslation);
                    translationYBuilder.addFloat(tileView, "translationY",
                            -offsetWithQSBHTranslation, 0);

                    translationXBuilder.addFloat(quickTileView, "translationX", 0, xOffset);
                    translationXBuilder.addFloat(tileView, "translationX", -xOffset, 0);

                    if (mQQSTileHeightAnimator == null) {
                        mQQSTileHeightAnimator = new HeightExpansionAnimator(this,
                                quickTileView.getMeasuredHeight(), tileView.getMeasuredHeight());
                        mLastQQSTileHeight = quickTileView.getMeasuredHeight();
                    }

                    mQQSTileHeightAnimator.addView(quickTileView);

                    // Icons
                    translateContent(
                            quickTileView.getIcon(),
                            tileView.getIcon(),
                            view,
                            xOffset,
                            yOffset,
                            mTmpLoc1,
                            translationXBuilder,
                            translationYBuilder,
                            qqsTranslationYBuilder
                    );

                    // Label containers
                    translateContent(
                            quickTileView.getLabelContainer(),
                            tileView.getLabelContainer(),
                            view,
                            xOffset,
                            yOffset,
                            mTmpLoc1,
                            translationXBuilder,
                            translationYBuilder,
                            qqsTranslationYBuilder
                    );

                    // Secondary icon
                    translateContent(
                            quickTileView.getSecondaryIcon(),
                            tileView.getSecondaryIcon(),
                            view,
                            xOffset,
                            yOffset,
                            mTmpLoc1,
                            translationXBuilder,
                            translationYBuilder,
                            qqsTranslationYBuilder
                    );

                    // Secondary labels on tiles not in QQS have two alpha animation applied:
                    // * on the tile themselves
                    // * on TileLayout
                    // Therefore, we use a quadratic interpolator animator to animate the alpha
                    // for tiles in QQS to match.
                    quadraticInterpolatorBuilder
                            .addFloat(quickTileView.getSecondaryLabel(), "alpha", 0, 1);
                    nonFirstPageAlphaBuilder
                            .addFloat(quickTileView.getSecondaryLabel(), "alpha", 0, 0);

                    mAnimatedQsViews.add(tileView);
                    mAllViews.add(quickTileView);
                    mAllViews.add(quickTileView.getSecondaryLabel());
                } else if (!isIconInAnimatedRow(count)) {
                    // Pretend there's a corresponding QQS tile (for the position) that we are
                    // expanding from.
                    SideLabelTileLayout qqsLayout =
                            (SideLabelTileLayout) mQuickQsPanel.getTileLayout();
                    getRelativePosition(mTmpLoc1, qqsLayout, view);
                    mQQSTop = mTmpLoc1[1];
                    getRelativePosition(mTmpLoc2, tileView, view);
                    int diff = mTmpLoc2[1] - (mTmpLoc1[1] + qqsLayout.getPhantomTopPosition(count));
                    translationYBuilder.addFloat(tileView, "translationY", -diff, 0);
                    if (mOtherFirstPageTilesHeightAnimator == null) {
                        mOtherFirstPageTilesHeightAnimator =
                                new HeightExpansionAnimator(
                                        this, mLastQQSTileHeight, tileView.getMeasuredHeight());
                    }
                    mOtherFirstPageTilesHeightAnimator.addView(tileView);
                    tileView.setClipChildren(true);
                    tileView.setClipToPadding(true);
                    firstPageBuilder.addFloat(tileView.getSecondaryLabel(), "alpha", 0, 1);
                    mAllViews.add(tileView.getSecondaryLabel());
                }

                mAllViews.add(tileView);
                count++;
            }
            if (mCurrentPage != 0) {
                addNonFirstPageAnimators(mCurrentPage);
            }
        }

        animateBrightnessSlider();

        mFirstPageAnimator = firstPageBuilder
                // Fade in the tiles/labels as we reach the final position.
                .addFloat(tileLayout, "alpha", 0, 1)
                .addFloat(quadraticInterpolatorBuilder.build(), "position", 0, 1)
                .setListener(this)
                .build();

        // Fade in the media player as we reach the final position
        Builder builder = new Builder().setStartDelay(EXPANDED_TILE_DELAY);
        if (mQsPanelController.shouldUseHorizontalLayout()
                && mQsPanelController.mMediaHost.hostView != null) {
            builder.addFloat(mQsPanelController.mMediaHost.hostView, "alpha", 0, 1);
        } else {
            // In portrait, media view should always be visible
            mQsPanelController.mMediaHost.hostView.setAlpha(1.0f);
        }
        mAllPagesDelayedAnimator = builder.build();
        translationYBuilder.setInterpolator(mQSExpansionPathInterpolator.getYInterpolator());
        qqsTranslationYBuilder.setInterpolator(mQSExpansionPathInterpolator.getYInterpolator());
        translationXBuilder.setInterpolator(mQSExpansionPathInterpolator.getXInterpolator());
        if (mOnFirstPage) {
            // Only recreate this animator if we're in the first page. That way we know that
            // the first page is attached and has the proper positions/measures.
            mQQSTranslationYAnimator = qqsTranslationYBuilder.build();
        }
        mTranslationYAnimator = translationYBuilder.build();
        mTranslationXAnimator = translationXBuilder.build();
        if (mQQSTileHeightAnimator != null) {
            mQQSTileHeightAnimator.setInterpolator(
                    mQSExpansionPathInterpolator.getYInterpolator());
        }
        if (mOtherFirstPageTilesHeightAnimator != null) {
            mOtherFirstPageTilesHeightAnimator.setInterpolator(
                    mQSExpansionPathInterpolator.getYInterpolator());
        }
        mNonfirstPageAlphaAnimator = nonFirstPageAlphaBuilder
                .addFloat(mQuickQsPanel, "alpha", 1, 0)
                .addFloat(tileLayout, "alpha", 0, 1)
                .setListener(mNonFirstPageListener)
                .setEndDelay(1 - QQS_FADE_IN_INTERVAL)
                .build();
    }

    private Pair<HeightExpansionAnimator, TouchAnimator> createSecondaryPageAnimators(int page) {
        if (mPagedLayout == null) return null;
        HeightExpansionAnimator animator = null;
        TouchAnimator.Builder builder = new Builder()
                .setInterpolator(mQSExpansionPathInterpolator.getYInterpolator());
        TouchAnimator.Builder alphaDelayedBuilder = new Builder()
                .setStartDelay(QS_TILE_LABEL_FADE_OUT_START)
                .setEndDelay(QS_TILE_LABEL_FADE_OUT_END);
        SideLabelTileLayout qqsLayout = (SideLabelTileLayout) mQuickQsPanel.getTileLayout();
        View view = mQs.getView();
        List<String> specs = mPagedLayout.getSpecsForPage(page);
        if (specs.isEmpty()) {
            // specs should not be empty in a valid secondary page, as we scrolled to it.
            // We may crash later on because there's a null animator.
            specs = mQsPanelController.getHost().mTileSpecs;
            Log.e(TAG, "Trying to create animators for empty page " + page + ". Tiles: " + specs);
            // return null;
        }

        int row = -1;
        int lastTileTop = -1;

        for (int i = 0; i < specs.size(); i++) {
            QSTileView tileView = mQsPanelController.getTileView(specs.get(i));
            getRelativePosition(mTmpLoc2, tileView, view);
            int diff = mTmpLoc2[1] - (mQQSTop + qqsLayout.getPhantomTopPosition(i));
            builder.addFloat(tileView, "translationY", -diff, 0);
            // The different elements in the tile should be centered, so maintain them centered
            int centerDiff = (tileView.getMeasuredHeight() - mLastQQSTileHeight) / 2;
            builder.addFloat(tileView.getIcon(), "translationY", -centerDiff, 0);
            builder.addFloat(tileView.getSecondaryIcon(), "translationY", -centerDiff, 0);
            // The labels have different apparent size in QQS vs QS (no secondary label), so the
            // translation needs to account for that.
            int secondaryLabelOffset = 0;
            if (tileView.getSecondaryLabel().getVisibility() == View.VISIBLE) {
                secondaryLabelOffset = tileView.getSecondaryLabel().getMeasuredHeight() / 2;
            }
            int labelDiff = centerDiff - secondaryLabelOffset;
            builder.addFloat(tileView.getLabelContainer(), "translationY", -labelDiff, 0);
            builder.addFloat(tileView.getSecondaryLabel(), "alpha", 0, 0.3f, 1);

            alphaDelayedBuilder.addFloat(tileView.getLabelContainer(), "alpha", 0, 1);
            alphaDelayedBuilder.addFloat(tileView.getIcon(), "alpha", 0, 1);
            alphaDelayedBuilder.addFloat(tileView.getSecondaryIcon(), "alpha", 0, 1);

            final int tileTop = tileView.getTop();
            if (tileTop != lastTileTop) {
                row++;
                lastTileTop = tileTop;
            }
            if (i >= mQuickQsPanel.getTileLayout().getNumVisibleTiles() && row >= 2) {
                // Fade completely the tiles in rows below the ones that will merge into QQS.
                // args is an array of 0s where the length is the current row index (at least third
                // row)
                final float[] args = new float[row];
                args[args.length - 1] = 1f;
                builder.addFloat(tileView, "alpha", args);
            } else {
                // For all the other rows, fade them a bit
                builder.addFloat(tileView, "alpha", 0.6f, 1);
            }

            if (animator == null) {
                animator = new HeightExpansionAnimator(
                        this, mLastQQSTileHeight, tileView.getMeasuredHeight());
                animator.setInterpolator(mQSExpansionPathInterpolator.getYInterpolator());
            }
            animator.addView(tileView);

            tileView.setClipChildren(true);
            tileView.setClipToPadding(true);
            mAllViews.add(tileView);
            mAllViews.add(tileView.getSecondaryLabel());
            mAllViews.add(tileView.getIcon());
            mAllViews.add(tileView.getSecondaryIcon());
            mAllViews.add(tileView.getLabelContainer());
        }
        builder.addFloat(alphaDelayedBuilder.build(), "position", 0, 1);
        return new Pair<>(animator, builder.build());
    }

    private void animateBrightnessSlider() {
        mBrightnessTranslationAnimator = null;
        mBrightnessOpacityAnimator = null;
        View qsBrightness = mQsPanelController.getBrightnessView();
        View qqsBrightness = mQuickQSPanelController.getBrightnessView();
        if (qqsBrightness != null && qqsBrightness.getVisibility() == View.VISIBLE) {
            // animating in split shade mode
            mAnimatedQsViews.add(qsBrightness);
            mAllViews.add(qqsBrightness);
            int translationY = getRelativeTranslationY(qsBrightness, qqsBrightness);
            mBrightnessTranslationAnimator = new Builder()
                    // we need to animate qs brightness even if animation will not be visible,
                    // as we might start from sliderScaleY set to 0.3 if device was in collapsed QS
                    // portrait orientation before
                    .addFloat(qsBrightness, "sliderScaleY", 0.3f, 1)
                    .addFloat(qqsBrightness, "translationY", 0, translationY)
                    .setInterpolator(mQSExpansionPathInterpolator.getYInterpolator())
                    .build();
        } else if (qsBrightness != null) {
            // The brightness slider's visible bottom edge must maintain a constant margin from the
            // QS tiles during transition. Thus the slider must (1) perform the same vertical
            // translation as the tiles, and (2) compensate for the slider scaling.

            // For (1), compute the distance via the vertical distance between QQS and QS tile
            // layout top.
            View quickSettingsRootView = mQs.getView();
            View qsTileLayout = (View) mQsPanelController.getTileLayout();
            View qqsTileLayout = (View) mQuickQSPanelController.getTileLayout();
            getRelativePosition(mTmpLoc1, qsTileLayout, quickSettingsRootView);
            getRelativePosition(mTmpLoc2, qqsTileLayout, quickSettingsRootView);
            int tileMovement = mTmpLoc2[1] - mTmpLoc1[1];

            // For (2), the slider scales to the vertical center, so compensate with half the
            // height at full collapse.
            float scaleCompensation = qsBrightness.getMeasuredHeight() * 0.5f;
            mBrightnessTranslationAnimator = new Builder()
                    .addFloat(qsBrightness, "translationY", scaleCompensation + tileMovement, 0)
                    .addFloat(qsBrightness, "sliderScaleY", 0, 1)
                    .setInterpolator(mQSExpansionPathInterpolator.getYInterpolator())
                    .build();

            // While the slider's position and unfurl is animated throughouth the motion, the
            // fade in happens independently.
            mBrightnessOpacityAnimator = new Builder()
                    .addFloat(qsBrightness, "alpha", 0, 1)
                    .setStartDelay(0.2f)
                    .setEndDelay(1 - 0.5f)
                    .build();
            mAllViews.add(qsBrightness);
        }
    }

    private int getRelativeTranslationY(View view1, View view2) {
        int[] qsPosition = new int[2];
        int[] qqsPosition = new int[2];
        View commonView = mQs.getView();
        getRelativePositionInt(qsPosition, view1, commonView);
        getRelativePositionInt(qqsPosition, view2, commonView);
        return (qsPosition[1] - qqsPosition[1]) - mQuickStatusBarHeader.getOffsetTranslation();
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
        if (view == parent || view == null) return;
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
        return view.getClass().equals(SideLabelTileLayout.class);
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
        if (mOnFirstPage) {
            mQuickQsPanel.setAlpha(1);
            mFirstPageAnimator.setPosition(position);
            mTranslationYAnimator.setPosition(position);
            mTranslationXAnimator.setPosition(position);
            if (mOtherFirstPageTilesHeightAnimator != null) {
                mOtherFirstPageTilesHeightAnimator.setPosition(position);
            }
        } else {
            mNonfirstPageAlphaAnimator.setPosition(position);
        }
        for (int i = 0; i < mNonFirstPageQSAnimators.size(); i++) {
            Pair<HeightExpansionAnimator, TouchAnimator> pair = mNonFirstPageQSAnimators.valueAt(i);
            if (pair != null) {
                pair.first.setPosition(position);
                pair.second.setPosition(position);
            }
        }
        if (mQQSTileHeightAnimator != null) {
            mQQSTileHeightAnimator.setPosition(position);
        }
        mQQSTranslationYAnimator.setPosition(position);
        mAllPagesDelayedAnimator.setPosition(position);
        if (mBrightnessOpacityAnimator != null) {
            mBrightnessOpacityAnimator.setPosition(position);
        }
        if (mBrightnessTranslationAnimator != null) {
            mBrightnessTranslationAnimator.setPosition(position);
        }
        if (mQQSFooterActionsAnimator != null) {
            mQQSFooterActionsAnimator.setPosition(position);
        }
    }

    @Override
    public void onAnimationAtStart() {
        mQuickQsPanel.setVisibility(View.VISIBLE);
    }

    @Override
    public void onAnimationAtEnd() {
        mQuickQsPanel.setVisibility(View.INVISIBLE);
        final int N = mAnimatedQsViews.size();
        for (int i = 0; i < N; i++) {
            mAnimatedQsViews.get(i).setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onAnimationStarted() {
        updateQQSVisibility();
        if (mOnFirstPage) {
            final int N = mAnimatedQsViews.size();
            for (int i = 0; i < N; i++) {
                mAnimatedQsViews.get(i).setVisibility(View.INVISIBLE);
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
            v.setScaleY(1f);
            if (v instanceof SideLabelTileLayout) {
                ((SideLabelTileLayout) v).setClipChildren(false);
                ((SideLabelTileLayout) v).setClipToPadding(false);
            }
        }
        if (mQQSTileHeightAnimator != null) {
            mQQSTileHeightAnimator.resetViewsHeights();
        }
        if (mOtherFirstPageTilesHeightAnimator != null) {
            mOtherFirstPageTilesHeightAnimator.resetViewsHeights();
        }
        for (int i = 0; i < mNonFirstPageQSAnimators.size(); i++) {
            mNonFirstPageQSAnimators.valueAt(i).first.resetViewsHeights();
        }
        final int N2 = mAnimatedQsViews.size();
        for (int i = 0; i < N2; i++) {
            mAnimatedQsViews.get(i).setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
            int oldTop, int oldRight, int oldBottom) {
        boolean actualChange =
                left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom;
        if (actualChange) mExecutor.execute(mUpdateAnimators);
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

    private static class HeightExpansionAnimator {
        private final List<View> mViews = new ArrayList<>();
        private final ValueAnimator mAnimator;
        private final TouchAnimator.Listener mListener;

        private final ValueAnimator.AnimatorUpdateListener mUpdateListener =
                new ValueAnimator.AnimatorUpdateListener() {
                    float mLastT = -1;

                    @Override
                    public void onAnimationUpdate(ValueAnimator valueAnimator) {
                        float t = valueAnimator.getAnimatedFraction();
                        final int viewCount = mViews.size();
                        int height = (Integer) valueAnimator.getAnimatedValue();
                        for (int i = 0; i < viewCount; i++) {
                            View v = mViews.get(i);
                            if (v instanceof HeightOverrideable) {
                                ((HeightOverrideable) v).setHeightOverride(height);
                            } else {
                                v.setBottom(v.getTop() + height);
                            }
                        }
                        if (t == 0f) {
                            mListener.onAnimationAtStart();
                        } else if (t == 1f) {
                            mListener.onAnimationAtEnd();
                        } else if (mLastT <= 0 || mLastT == 1) {
                            mListener.onAnimationStarted();
                        }
                        mLastT = t;
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
                if (v instanceof HeightOverrideable) {
                    ((HeightOverrideable) v).resetOverride();
                } else {
                    v.setBottom(v.getTop() + v.getMeasuredHeight());
                }
            }
        }
    }
}
