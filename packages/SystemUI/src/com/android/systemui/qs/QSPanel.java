/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.qs;

import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;
import static com.android.systemui.util.Utils.useQsMediaPlayer;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PointF;
import android.metrics.LogMaker;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.widget.RemeasuringLinearLayout;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.MediaHierarchyManager;
import com.android.systemui.media.MediaHost;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTileView;
import com.android.systemui.qs.QSHost.Callback;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.settings.BrightnessController;
import com.android.systemui.settings.ToggleSliderView;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.statusbar.policy.BrightnessMirrorController.BrightnessMirrorListener;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;
import com.android.systemui.util.animation.DisappearParameters;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;

/** View that represents the quick settings tile panel (when expanded/pulled down). **/
public class QSPanel extends LinearLayout implements Tunable, Callback, BrightnessMirrorListener,
        Dumpable {

    public static final String QS_SHOW_BRIGHTNESS = "qs_show_brightness";
    public static final String QS_SHOW_HEADER = "qs_show_header";

    private static final String TAG = "QSPanel";

    protected final Context mContext;
    protected final ArrayList<TileRecord> mRecords = new ArrayList<>();
    private final BroadcastDispatcher mBroadcastDispatcher;
    protected final MediaHost mMediaHost;

    /**
     * The index where the content starts that needs to be moved between parents
     */
    private final int mMovableContentStartIndex;
    private String mCachedSpecs = "";

    @Nullable
    protected View mBrightnessView;
    @Nullable
    private BrightnessController mBrightnessController;

    private final H mHandler = new H();
    private final MetricsLogger mMetricsLogger = Dependency.get(MetricsLogger.class);
    private QSTileRevealController mQsTileRevealController;
    /** Whether or not the QS media player feature is enabled. */
    protected boolean mUsingMediaPlayer;
    private int mVisualMarginStart;
    private int mVisualMarginEnd;

    protected boolean mExpanded;
    protected boolean mListening;

    private QSDetail.Callback mCallback;
    private final DumpManager mDumpManager;
    private final QSLogger mQSLogger;
    protected final UiEventLogger mUiEventLogger;
    protected QSTileHost mHost;

    @Nullable
    protected QSSecurityFooter mSecurityFooter;

    @Nullable
    protected View mFooter;

    @Nullable
    private ViewGroup mHeaderContainer;
    private PageIndicator mFooterPageIndicator;
    private boolean mGridContentVisible = true;
    private int mContentMarginStart;
    private int mContentMarginEnd;
    private int mVisualTilePadding;
    private boolean mUsingHorizontalLayout;

    private QSCustomizer mCustomizePanel;
    private Record mDetailRecord;

    private BrightnessMirrorController mBrightnessMirrorController;
    private LinearLayout mHorizontalLinearLayout;
    private LinearLayout mHorizontalContentContainer;

    // Only used with media
    private QSTileLayout mHorizontalTileLayout;
    protected QSTileLayout mRegularTileLayout;
    protected QSTileLayout mTileLayout;
    private int mLastOrientation = -1;
    private int mMediaTotalBottomMargin;
    private int mFooterMarginStartHorizontal;
    private Consumer<Boolean> mMediaVisibilityChangedListener;


    @Inject
    public QSPanel(
            @Named(VIEW_CONTEXT) Context context,
            AttributeSet attrs,
            DumpManager dumpManager,
            BroadcastDispatcher broadcastDispatcher,
            QSLogger qsLogger,
            MediaHost mediaHost,
            UiEventLogger uiEventLogger
    ) {
        super(context, attrs);
        mUsingMediaPlayer = useQsMediaPlayer(context);
        mMediaTotalBottomMargin = getResources().getDimensionPixelSize(
                R.dimen.quick_settings_bottom_margin_media);
        mMediaHost = mediaHost;
        mMediaHost.addVisibilityChangeListener((visible) -> {
            onMediaVisibilityChanged(visible);
            return null;
        });
        mContext = context;
        mQSLogger = qsLogger;
        mDumpManager = dumpManager;
        mBroadcastDispatcher = broadcastDispatcher;
        mUiEventLogger = uiEventLogger;

        setOrientation(VERTICAL);

        addViewsAboveTiles();
        mMovableContentStartIndex = getChildCount();
        mRegularTileLayout = createRegularTileLayout();

        if (mUsingMediaPlayer) {
            mHorizontalLinearLayout = new RemeasuringLinearLayout(mContext);
            mHorizontalLinearLayout.setOrientation(LinearLayout.HORIZONTAL);
            mHorizontalLinearLayout.setClipChildren(false);
            mHorizontalLinearLayout.setClipToPadding(false);

            mHorizontalContentContainer = new RemeasuringLinearLayout(mContext);
            mHorizontalContentContainer.setOrientation(LinearLayout.VERTICAL);
            mHorizontalContentContainer.setClipChildren(false);
            mHorizontalContentContainer.setClipToPadding(false);

            mHorizontalTileLayout = createHorizontalTileLayout();
            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
            int marginSize = (int) mContext.getResources().getDimension(R.dimen.qqs_media_spacing);
            lp.setMarginStart(0);
            lp.setMarginEnd(marginSize);
            lp.gravity = Gravity.CENTER_VERTICAL;
            mHorizontalLinearLayout.addView(mHorizontalContentContainer, lp);

            lp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1);
            addView(mHorizontalLinearLayout, lp);

            initMediaHostState();
        }
        addSecurityFooter();
        if (mRegularTileLayout instanceof PagedTileLayout) {
            mQsTileRevealController = new QSTileRevealController(mContext, this,
                    (PagedTileLayout) mRegularTileLayout);
        }
        mQSLogger.logAllTilesChangeListening(mListening, getDumpableTag(), mCachedSpecs);
        updateResources();
    }

    protected void onMediaVisibilityChanged(Boolean visible) {
        switchTileLayout();
        if (mMediaVisibilityChangedListener != null) {
            mMediaVisibilityChangedListener.accept(visible);
        }
    }

    protected void addSecurityFooter() {
        mSecurityFooter = new QSSecurityFooter(this, mContext);
    }

    protected void addViewsAboveTiles() {
        mBrightnessView = LayoutInflater.from(mContext).inflate(
            R.layout.quick_settings_brightness_dialog, this, false);
        addView(mBrightnessView);
        mBrightnessController = new BrightnessController(getContext(),
                findViewById(R.id.brightness_slider), mBroadcastDispatcher);
    }

    protected QSTileLayout createRegularTileLayout() {
        if (mRegularTileLayout == null) {
            mRegularTileLayout = (QSTileLayout) LayoutInflater.from(mContext).inflate(
                    R.layout.qs_paged_tile_layout, this, false);
        }
        return mRegularTileLayout;
    }


    protected QSTileLayout createHorizontalTileLayout() {
        return createRegularTileLayout();
    }

    protected void initMediaHostState() {
        mMediaHost.setExpansion(1.0f);
        mMediaHost.setShowsOnlyActiveMedia(false);
        updateMediaDisappearParameters();
        mMediaHost.init(MediaHierarchyManager.LOCATION_QS);
    }

    /**
     * Update the way the media disappears based on if we're using the horizontal layout
     */
    private void updateMediaDisappearParameters() {
        if (!mUsingMediaPlayer) {
            return;
        }
        DisappearParameters parameters = mMediaHost.getDisappearParameters();
        if (mUsingHorizontalLayout) {
            // Only height remaining
            parameters.getDisappearSize().set(0.0f, 0.4f);
            // Disappearing on the right side on the bottom
            parameters.getGonePivot().set(1.0f, 1.0f);
            // translating a bit horizontal
            parameters.getContentTranslationFraction().set(0.25f, 1.0f);
            parameters.setDisappearEnd(0.6f);
        } else {
            // Only width remaining
            parameters.getDisappearSize().set(1.0f, 0.0f);
            // Disappearing on the bottom
            parameters.getGonePivot().set(0.0f, 1.0f);
            // translating a bit vertical
            parameters.getContentTranslationFraction().set(0.0f, 1.05f);
            parameters.setDisappearEnd(0.95f);
        }
        parameters.setFadeStartPosition(0.95f);
        parameters.setDisappearStart(0.0f);
        mMediaHost.setDisappearParameters(parameters);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mTileLayout instanceof PagedTileLayout) {
            // Since PageIndicator gets measured before PagedTileLayout, we preemptively set the
            // # of pages before the measurement pass so PageIndicator is measured appropriately
            if (mFooterPageIndicator != null) {
                mFooterPageIndicator.setNumPages(((PagedTileLayout) mTileLayout).getNumPages());
            }

            // Allow the UI to be as big as it want's to, we're in a scroll view
            int newHeight = 10000;
            int availableHeight = MeasureSpec.getSize(heightMeasureSpec);
            int excessHeight = newHeight - availableHeight;
            // Measure with EXACTLY. That way, The content will only use excess height and will
            // be measured last, after other views and padding is accounted for. This only
            // works because our Layouts in here remeasure themselves with the exact content
            // height.
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(newHeight, MeasureSpec.EXACTLY);
            ((PagedTileLayout) mTileLayout).setExcessHeight(excessHeight);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // We want all the logic of LinearLayout#onMeasure, and for it to assign the excess space
        // not used by the other children to PagedTileLayout. However, in this case, LinearLayout
        // assumes that PagedTileLayout would use all the excess space. This is not the case as
        // PagedTileLayout height is quantized (because it shows a certain number of rows).
        // Therefore, after everything is measured, we need to make sure that we add up the correct
        // total height
        int height = getPaddingBottom() + getPaddingTop();
        int numChildren = getChildCount();
        for (int i = 0; i < numChildren; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.GONE) {
                height += child.getMeasuredHeight();
                MarginLayoutParams layoutParams = (MarginLayoutParams) child.getLayoutParams();
                height += layoutParams.topMargin + layoutParams.bottomMargin;
            }
        }
        setMeasuredDimension(getMeasuredWidth(), height);
    }

    public QSTileRevealController getQsTileRevealController() {
        return mQsTileRevealController;
    }

    public boolean isShowingCustomize() {
        return mCustomizePanel != null && mCustomizePanel.isCustomizing();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        final TunerService tunerService = Dependency.get(TunerService.class);
        tunerService.addTunable(this, QS_SHOW_BRIGHTNESS);

        if (mHost != null) {
            setTiles(mHost.getTiles());
        }
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.addCallback(this);
        }
        mDumpManager.registerDumpable(getDumpableTag(), this);
    }

    @Override
    protected void onDetachedFromWindow() {
        Dependency.get(TunerService.class).removeTunable(this);
        if (mHost != null) {
            mHost.removeCallback(this);
        }
        if (mTileLayout != null) {
            mTileLayout.setListening(false);
        }
        for (TileRecord record : mRecords) {
            record.tile.removeCallbacks();
        }
        mRecords.clear();
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.removeCallback(this);
        }
        mDumpManager.unregisterDumpable(getDumpableTag());
        super.onDetachedFromWindow();
    }

    protected String getDumpableTag() {
        return TAG;
    }

    @Override
    public void onTilesChanged() {
        setTiles(mHost.getTiles());
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (QS_SHOW_BRIGHTNESS.equals(key) && mBrightnessView != null) {
            updateViewVisibilityForTuningValue(mBrightnessView, newValue);
        }
    }

    private void updateViewVisibilityForTuningValue(View view, @Nullable String newValue) {
        view.setVisibility(TunerService.parseIntegerSwitch(newValue, true) ? VISIBLE : GONE);
    }

    public void openDetails(String subPanel) {
        QSTile tile = getTile(subPanel);
        // If there's no tile with that name (as defined in QSFactoryImpl or other QSFactory),
        // QSFactory will not be able to create a tile and getTile will return null
        if (tile != null) {
            showDetailAdapter(true, tile.getDetailAdapter(), new int[]{getWidth() / 2, 0});
        }
    }

    private QSTile getTile(String subPanel) {
        for (int i = 0; i < mRecords.size(); i++) {
            if (subPanel.equals(mRecords.get(i).tile.getTileSpec())) {
                return mRecords.get(i).tile;
            }
        }
        return mHost.createTile(subPanel);
    }

    public void setBrightnessMirror(BrightnessMirrorController c) {
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.removeCallback(this);
        }
        mBrightnessMirrorController = c;
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.addCallback(this);
        }
        updateBrightnessMirror();
    }

    @Override
    public void onBrightnessMirrorReinflated(View brightnessMirror) {
        updateBrightnessMirror();
    }

    @Nullable
    View getBrightnessView() {
        return mBrightnessView;
    }

    public void setCallback(QSDetail.Callback callback) {
        mCallback = callback;
    }

    public void setHost(QSTileHost host, QSCustomizer customizer) {
        mHost = host;
        mHost.addCallback(this);
        setTiles(mHost.getTiles());
        if (mSecurityFooter != null) {
            mSecurityFooter.setHostEnvironment(host);
        }
        mCustomizePanel = customizer;
        if (mCustomizePanel != null) {
            mCustomizePanel.setHost(mHost);
        }
    }

    /**
     * Links the footer's page indicator, which is used in landscape orientation to save space.
     *
     * @param pageIndicator indicator to use for page scrolling
     */
    public void setFooterPageIndicator(PageIndicator pageIndicator) {
        if (mRegularTileLayout instanceof PagedTileLayout) {
            mFooterPageIndicator = pageIndicator;
            updatePageIndicator();
        }
    }

    private void updatePageIndicator() {
        if (mRegularTileLayout instanceof PagedTileLayout) {
            if (mFooterPageIndicator != null) {
                mFooterPageIndicator.setVisibility(View.GONE);

                ((PagedTileLayout) mRegularTileLayout).setPageIndicator(mFooterPageIndicator);
            }
        }
    }

    public QSTileHost getHost() {
        return mHost;
    }

    public void updateResources() {
        int tileSize = getResources().getDimensionPixelSize(R.dimen.qs_quick_tile_size);
        int tileBg = getResources().getDimensionPixelSize(R.dimen.qs_tile_background_size);
        mFooterMarginStartHorizontal = getResources().getDimensionPixelSize(
                R.dimen.qs_footer_horizontal_margin);
        mVisualTilePadding = (int) ((tileSize - tileBg) / 2.0f);
        updatePadding();

        updatePageIndicator();

        if (mListening) {
            refreshAllTiles();
        }
        if (mTileLayout != null) {
            mTileLayout.updateResources();
        }
    }

    protected void updatePadding() {
        final Resources res = mContext.getResources();
        int padding = res.getDimensionPixelSize(R.dimen.qs_panel_padding_top);
        if (mUsingHorizontalLayout) {
            // When using the horizontal layout, our space is quite constrained. We therefore
            // reduce some of the padding on the top, which makes the brightness bar overlapp,
            // but since that has naturally quite a bit of built in padding, that's fine.
            padding = (int) (padding * 0.6f);
        }
        setPaddingRelative(getPaddingStart(),
                padding,
                getPaddingEnd(),
                res.getDimensionPixelSize(R.dimen.qs_panel_padding_bottom));
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mSecurityFooter != null) {
            mSecurityFooter.onConfigurationChanged();
        }
        updateResources();

        updateBrightnessMirror();

        if (newConfig.orientation != mLastOrientation) {
            mLastOrientation = newConfig.orientation;
            switchTileLayout();
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mFooter = findViewById(R.id.qs_footer);
        switchTileLayout(true /* force */);
    }

    boolean switchTileLayout() {
        return switchTileLayout(false /* force */);
    }

    private boolean switchTileLayout(boolean force) {
        /** Whether or not the QuickQSPanel currently contains a media player. */
        boolean horizontal = shouldUseHorizontalLayout();
        if (horizontal != mUsingHorizontalLayout || force) {
            mUsingHorizontalLayout = horizontal;
            View visibleView = horizontal ? mHorizontalLinearLayout : (View) mRegularTileLayout;
            View hiddenView = horizontal ? (View) mRegularTileLayout : mHorizontalLinearLayout;
            ViewGroup newParent = horizontal ? mHorizontalContentContainer : this;
            QSTileLayout newLayout = horizontal ? mHorizontalTileLayout : mRegularTileLayout;
            if (hiddenView != null &&
                    (mRegularTileLayout != mHorizontalTileLayout ||
                            hiddenView != mRegularTileLayout)) {
                // Only hide the view if the horizontal and the regular view are different,
                // otherwise its reattached.
                hiddenView.setVisibility(View.GONE);
            }
            visibleView.setVisibility(View.VISIBLE);
            switchAllContentToParent(newParent, newLayout);
            reAttachMediaHost();
            if (mTileLayout != null) {
                mTileLayout.setListening(false);
                for (TileRecord record : mRecords) {
                    mTileLayout.removeTile(record);
                    record.tile.removeCallback(record.callback);
                }
            }
            mTileLayout = newLayout;
            if (mHost != null) setTiles(mHost.getTiles());
            newLayout.setListening(mListening);
            if (needsDynamicRowsAndColumns()) {
                newLayout.setMinRows(horizontal ? 2 : 1);
                // Let's use 3 columns to match the current layout
                newLayout.setMaxColumns(horizontal ? 3 : TileLayout.NO_MAX_COLUMNS);
            }
            updateTileLayoutMargins();
            updateFooterMargin();
            updateMediaDisappearParameters();
            updateMediaHostContentMargins();
            updateHorizontalLinearLayoutMargins();
            updatePadding();
            return true;
        }
        return false;
    }

    private void updateHorizontalLinearLayoutMargins() {
        if (mHorizontalLinearLayout != null && !displayMediaMarginsOnMedia()) {
            LayoutParams lp = (LayoutParams) mHorizontalLinearLayout.getLayoutParams();
            lp.bottomMargin = mMediaTotalBottomMargin - getPaddingBottom();
            mHorizontalLinearLayout.setLayoutParams(lp);
        }
    }

    /**
     * @return true if the margin bottom of the media view should be on the media host or false
     *         if they should be on the HorizontalLinearLayout. Returning {@code false} is useful
     *         to visually center the tiles in the Media view, which doesn't work when the
     *         expanded panel actually scrolls.
     */
    protected boolean displayMediaMarginsOnMedia() {
        return true;
    }

    protected boolean needsDynamicRowsAndColumns() {
        return true;
    }

    private void switchAllContentToParent(ViewGroup parent, QSTileLayout newLayout) {
        int index = parent == this ? mMovableContentStartIndex : 0;

        // Let's first move the tileLayout to the new parent, since that should come first.
        switchToParent((View) newLayout, parent, index);
        index++;

        if (mSecurityFooter != null) {
            View view = mSecurityFooter.getView();
            LinearLayout.LayoutParams layoutParams = (LayoutParams) view.getLayoutParams();
            if (mUsingHorizontalLayout && mHeaderContainer != null) {
                // Adding the security view to the header, that enables us to avoid scrolling
                layoutParams.width = 0;
                layoutParams.weight = 1.6f;
                switchToParent(view, mHeaderContainer, 1 /* always in second place */);
            } else {
                layoutParams.width = LayoutParams.WRAP_CONTENT;
                layoutParams.weight = 0;
                switchToParent(view, parent, index);
                index++;
            }
            view.setLayoutParams(layoutParams);
        }

        if (mFooter != null) {
            // Then the footer with the settings
            switchToParent(mFooter, parent, index);
        }
    }

    private void switchToParent(View child, ViewGroup parent, int index) {
        ViewGroup currentParent = (ViewGroup) child.getParent();
        if (currentParent != parent || currentParent.indexOfChild(child) != index) {
            if (currentParent != null) {
                currentParent.removeView(child);
            }
            parent.addView(child, index);
        }
    }

    private boolean shouldUseHorizontalLayout() {
        return mUsingMediaPlayer && mMediaHost.getVisible()
                && getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    protected void reAttachMediaHost() {
        if (!mUsingMediaPlayer) {
            return;
        }
        boolean horizontal = shouldUseHorizontalLayout();
        ViewGroup host = mMediaHost.getHostView();
        ViewGroup newParent = horizontal ? mHorizontalLinearLayout : this;
        ViewGroup currentParent = (ViewGroup) host.getParent();
        if (currentParent != newParent) {
            if (currentParent != null) {
                currentParent.removeView(host);
            }
            newParent.addView(host);
            LinearLayout.LayoutParams layoutParams = (LayoutParams) host.getLayoutParams();
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            layoutParams.width = horizontal ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
            layoutParams.weight = horizontal ? 1.2f : 0;
            // Add any bottom margin, such that the total spacing is correct. This is only
            // necessary if the view isn't horizontal, since otherwise the padding is
            // carried in the parent of this view (to ensure correct vertical alignment)
            layoutParams.bottomMargin = !horizontal || displayMediaMarginsOnMedia()
                    ? mMediaTotalBottomMargin - getPaddingBottom() : 0;
        }
    }

    public void updateBrightnessMirror() {
        if (mBrightnessMirrorController != null) {
            ToggleSliderView brightnessSlider = findViewById(R.id.brightness_slider);
            ToggleSliderView mirrorSlider = mBrightnessMirrorController.getMirror()
                    .findViewById(R.id.brightness_slider);
            brightnessSlider.setMirror(mirrorSlider);
            brightnessSlider.setMirrorController(mBrightnessMirrorController);
        }
    }

    public void onCollapse() {
        if (mCustomizePanel != null && mCustomizePanel.isShown()) {
            mCustomizePanel.hide();
        }
    }

    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mQSLogger.logPanelExpanded(expanded, getDumpableTag());
        mExpanded = expanded;
        if (!mExpanded && mTileLayout instanceof PagedTileLayout) {
            ((PagedTileLayout) mTileLayout).setCurrentItem(0, false);
        }
        mMetricsLogger.visibility(MetricsEvent.QS_PANEL, mExpanded);
        if (!mExpanded) {
            mUiEventLogger.log(closePanelEvent());
            closeDetail();
        } else {
            mUiEventLogger.log(openPanelEvent());
            logTiles();
        }
    }

    public void setPageListener(final PagedTileLayout.PageListener pageListener) {
        if (mTileLayout instanceof PagedTileLayout) {
            ((PagedTileLayout) mTileLayout).setPageListener(pageListener);
        }
    }

    public boolean isExpanded() {
        return mExpanded;
    }

    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        if (mTileLayout != null) {
            mQSLogger.logAllTilesChangeListening(listening, getDumpableTag(), mCachedSpecs);
            mTileLayout.setListening(listening);
        }
        if (mListening) {
            refreshAllTiles();
        }
    }

    private String getTilesSpecs() {
        return mRecords.stream()
                .map(tileRecord ->  tileRecord.tile.getTileSpec())
                .collect(Collectors.joining(","));
    }

    public void setListening(boolean listening, boolean expanded) {
        setListening(listening && expanded);
        if (mSecurityFooter != null) {
            mSecurityFooter.setListening(listening);
        }
        // Set the listening as soon as the QS fragment starts listening regardless of the expansion,
        // so it will update the current brightness before the slider is visible.
        setBrightnessListening(listening);
    }

    public void setBrightnessListening(boolean listening) {
        if (mBrightnessController == null) {
            return;
        }
        if (listening) {
            mBrightnessController.registerCallbacks();
        } else {
            mBrightnessController.unregisterCallbacks();
        }
    }

    public void refreshAllTiles() {
        if (mBrightnessController != null) {
            mBrightnessController.checkRestrictionAndSetEnabled();
        }
        for (TileRecord r : mRecords) {
            r.tile.refreshState();
        }
        if (mSecurityFooter != null) {
            mSecurityFooter.refreshState();
        }
    }

    public void showDetailAdapter(boolean show, DetailAdapter adapter, int[] locationInWindow) {
        int xInWindow = locationInWindow[0];
        int yInWindow = locationInWindow[1];
        ((View) getParent()).getLocationInWindow(locationInWindow);

        Record r = new Record();
        r.detailAdapter = adapter;
        r.x = xInWindow - locationInWindow[0];
        r.y = yInWindow - locationInWindow[1];

        locationInWindow[0] = xInWindow;
        locationInWindow[1] = yInWindow;

        showDetail(show, r);
    }

    protected void showDetail(boolean show, Record r) {
        mHandler.obtainMessage(H.SHOW_DETAIL, show ? 1 : 0, 0, r).sendToTarget();
    }

    public void setTiles(Collection<QSTile> tiles) {
        setTiles(tiles, false);
    }

    public void setTiles(Collection<QSTile> tiles, boolean collapsedView) {
        if (!collapsedView) {
            mQsTileRevealController.updateRevealedTiles(tiles);
        }
        for (TileRecord record : mRecords) {
            mTileLayout.removeTile(record);
            record.tile.removeCallback(record.callback);
        }
        mRecords.clear();
        mCachedSpecs = "";
        for (QSTile tile : tiles) {
            addTile(tile, collapsedView);
        }
    }

    protected void drawTile(TileRecord r, QSTile.State state) {
        r.tileView.onStateChanged(state);
    }

    protected QSTileView createTileView(QSTile tile, boolean collapsedView) {
        return mHost.createTileView(tile, collapsedView);
    }

    protected QSEvent openPanelEvent() {
        return QSEvent.QS_PANEL_EXPANDED;
    }

    protected QSEvent closePanelEvent() {
        return QSEvent.QS_PANEL_COLLAPSED;
    }

    protected QSEvent tileVisibleEvent() {
        return QSEvent.QS_TILE_VISIBLE;
    }

    protected boolean shouldShowDetail() {
        return mExpanded;
    }

    protected TileRecord addTile(final QSTile tile, boolean collapsedView) {
        final TileRecord r = new TileRecord();
        r.tile = tile;
        r.tileView = createTileView(tile, collapsedView);
        final QSTile.Callback callback = new QSTile.Callback() {
            @Override
            public void onStateChanged(QSTile.State state) {
                drawTile(r, state);
            }

            @Override
            public void onShowDetail(boolean show) {
                // Both the collapsed and full QS panels get this callback, this check determines
                // which one should handle showing the detail.
                if (shouldShowDetail()) {
                    QSPanel.this.showDetail(show, r);
                }
            }

            @Override
            public void onToggleStateChanged(boolean state) {
                if (mDetailRecord == r) {
                    fireToggleStateChanged(state);
                }
            }

            @Override
            public void onScanStateChanged(boolean state) {
                r.scanState = state;
                if (mDetailRecord == r) {
                    fireScanStateChanged(r.scanState);
                }
            }

            @Override
            public void onAnnouncementRequested(CharSequence announcement) {
                if (announcement != null) {
                    mHandler.obtainMessage(H.ANNOUNCE_FOR_ACCESSIBILITY, announcement)
                            .sendToTarget();
                }
            }
        };
        r.tile.addCallback(callback);
        r.callback = callback;
        r.tileView.init(r.tile);
        r.tile.refreshState();
        mRecords.add(r);
        mCachedSpecs = getTilesSpecs();

        if (mTileLayout != null) {
            mTileLayout.addTile(r);
        }

        return r;
    }


    public void showEdit(final View v) {
        v.post(new Runnable() {
            @Override
            public void run() {
                if (mCustomizePanel != null) {
                    if (!mCustomizePanel.isCustomizing()) {
                        int[] loc = v.getLocationOnScreen();
                        int x = loc[0] + v.getWidth() / 2;
                        int y = loc[1] + v.getHeight() / 2;
                        mCustomizePanel.show(x, y);
                    }
                }

            }
        });
    }

    public void closeDetail() {
        if (mCustomizePanel != null && mCustomizePanel.isShown()) {
            // Treat this as a detail panel for now, to make things easy.
            mCustomizePanel.hide();
            return;
        }
        showDetail(false, mDetailRecord);
    }

    public int getGridHeight() {
        return getMeasuredHeight();
    }

    protected void handleShowDetail(Record r, boolean show) {
        if (r instanceof TileRecord) {
            handleShowDetailTile((TileRecord) r, show);
        } else {
            int x = 0;
            int y = 0;
            if (r != null) {
                x = r.x;
                y = r.y;
            }
            handleShowDetailImpl(r, show, x, y);
        }
    }

    private void handleShowDetailTile(TileRecord r, boolean show) {
        if ((mDetailRecord != null) == show && mDetailRecord == r) return;

        if (show) {
            r.detailAdapter = r.tile.getDetailAdapter();
            if (r.detailAdapter == null) return;
        }
        r.tile.setDetailListening(show);
        int x = r.tileView.getLeft() + r.tileView.getWidth() / 2;
        int y = r.tileView.getDetailY() + mTileLayout.getOffsetTop(r) + getTop();
        handleShowDetailImpl(r, show, x, y);
    }

    private void handleShowDetailImpl(Record r, boolean show, int x, int y) {
        setDetailRecord(show ? r : null);
        fireShowingDetail(show ? r.detailAdapter : null, x, y);
    }

    protected void setDetailRecord(Record r) {
        if (r == mDetailRecord) return;
        mDetailRecord = r;
        final boolean scanState = mDetailRecord instanceof TileRecord
                && ((TileRecord) mDetailRecord).scanState;
        fireScanStateChanged(scanState);
    }

    void setGridContentVisibility(boolean visible) {
        int newVis = visible ? VISIBLE : INVISIBLE;
        setVisibility(newVis);
        if (mGridContentVisible != visible) {
            mMetricsLogger.visibility(MetricsEvent.QS_PANEL, newVis);
        }
        mGridContentVisible = visible;
    }

    private void logTiles() {
        for (int i = 0; i < mRecords.size(); i++) {
            QSTile tile = mRecords.get(i).tile;
            mMetricsLogger.write(tile.populate(new LogMaker(tile.getMetricsCategory())
                    .setType(MetricsEvent.TYPE_OPEN)));
        }
    }

    private void fireShowingDetail(DetailAdapter detail, int x, int y) {
        if (mCallback != null) {
            mCallback.onShowingDetail(detail, x, y);
        }
    }

    private void fireToggleStateChanged(boolean state) {
        if (mCallback != null) {
            mCallback.onToggleStateChanged(state);
        }
    }

    private void fireScanStateChanged(boolean state) {
        if (mCallback != null) {
            mCallback.onScanStateChanged(state);
        }
    }

    public void clickTile(ComponentName tile) {
        final String spec = CustomTile.toSpec(tile);
        final int N = mRecords.size();
        for (int i = 0; i < N; i++) {
            if (mRecords.get(i).tile.getTileSpec().equals(spec)) {
                mRecords.get(i).tile.click();
                break;
            }
        }
    }

    QSTileLayout getTileLayout() {
        return mTileLayout;
    }

    QSTileView getTileView(QSTile tile) {
        for (TileRecord r : mRecords) {
            if (r.tile == tile) {
                return r.tileView;
            }
        }
        return null;
    }

    @Nullable
    public QSSecurityFooter getSecurityFooter() {
        return mSecurityFooter;
    }

    public void showDeviceMonitoringDialog() {
        if (mSecurityFooter != null) {
            mSecurityFooter.showDeviceMonitoringDialog();
        }
    }

    public void setContentMargins(int startMargin, int endMargin) {
        // Only some views actually want this content padding, others want to go all the way
        // to the edge like the brightness slider
        mContentMarginStart = startMargin;
        mContentMarginEnd = endMargin;
        updateTileLayoutMargins(mContentMarginStart - mVisualTilePadding,
                mContentMarginEnd - mVisualTilePadding);
        updateMediaHostContentMargins();
        updateFooterMargin();
    }

    private void updateFooterMargin() {
        if (mFooter != null) {
            int footerMargin = 0;
            int indicatorMargin = 0;
            if (mUsingHorizontalLayout) {
                footerMargin = mFooterMarginStartHorizontal;
                indicatorMargin = footerMargin - mVisualMarginEnd;
            }
            updateMargins(mFooter, footerMargin, 0);
            // The page indicator isn't centered anymore because of the visual positioning.
            // Let's fix it by adding some margin
            if (mFooterPageIndicator != null) {
                updateMargins(mFooterPageIndicator, 0, indicatorMargin);
            }
        }
    }

    /**
     * Update the margins of all tile Layouts.
     *
     * @param visualMarginStart the visual start margin of the tile, adjusted for local insets
     *                          to the tile. This can be set on a tileLayout
     * @param visualMarginEnd the visual end margin of the tile, adjusted for local insets
     *                        to the tile. This can be set on a tileLayout
     */
    private void updateTileLayoutMargins(int visualMarginStart, int visualMarginEnd) {
        mVisualMarginStart = visualMarginStart;
        mVisualMarginEnd = visualMarginEnd;
        updateTileLayoutMargins();
    }

    private void updateTileLayoutMargins() {
        int marginEnd = mVisualMarginEnd;
        if (mUsingHorizontalLayout) {
            marginEnd = 0;
        }
        updateMargins((View) mTileLayout, mVisualMarginStart, marginEnd);
    }

    /**
     * Update the margins of the media hosts
     */
    protected void updateMediaHostContentMargins() {
        if (mUsingMediaPlayer) {
            int marginStart = mContentMarginStart;
            if (mUsingHorizontalLayout) {
                marginStart = 0;
            }
            updateMargins(mMediaHost.getHostView(), marginStart, mContentMarginEnd);
        }
    }

    /**
     * Update the margins of a view.
     *
     * @param view the view to adjust
     * @param start the start margin to set
     * @param end the end margin to set
     */
    protected void updateMargins(View view, int start, int end) {
        LayoutParams lp = (LayoutParams) view.getLayoutParams();
        lp.setMarginStart(start);
        lp.setMarginEnd(end);
        view.setLayoutParams(lp);
    }

    public MediaHost getMediaHost() {
        return mMediaHost;
    }

    /**
     * Set the header container of quick settings.
     */
    public void setHeaderContainer(@NonNull ViewGroup headerContainer) {
        mHeaderContainer = headerContainer;
    }

    public void setMediaVisibilityChangedListener(Consumer<Boolean> visibilityChangedListener) {
        mMediaVisibilityChangedListener = visibilityChangedListener;
    }

    private class H extends Handler {
        private static final int SHOW_DETAIL = 1;
        private static final int SET_TILE_VISIBILITY = 2;
        private static final int ANNOUNCE_FOR_ACCESSIBILITY = 3;

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == SHOW_DETAIL) {
                handleShowDetail((Record) msg.obj, msg.arg1 != 0);
            } else if (msg.what == ANNOUNCE_FOR_ACCESSIBILITY) {
                announceForAccessibility((CharSequence) msg.obj);
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(getClass().getSimpleName() + ":");
        pw.println("  Tile records:");
        for (TileRecord record : mRecords) {
            if (record.tile instanceof Dumpable) {
                pw.print("    "); ((Dumpable) record.tile).dump(fd, pw, args);
                pw.print("    "); pw.println(record.tileView.toString());
            }
        }
    }


    protected static class Record {
        DetailAdapter detailAdapter;
        int x;
        int y;
    }

    public static final class TileRecord extends Record {
        public QSTile tile;
        public com.android.systemui.plugins.qs.QSTileView tileView;
        public boolean scanState;
        public QSTile.Callback callback;
    }

    public interface QSTileLayout {

        default void saveInstanceState(Bundle outState) {}

        default void restoreInstanceState(Bundle savedInstanceState) {}

        void addTile(TileRecord tile);

        void removeTile(TileRecord tile);

        int getOffsetTop(TileRecord tile);

        boolean updateResources();

        void setListening(boolean listening);

        /**
         * Set the minimum number of rows to show
         *
         * @param minRows the minimum.
         */
        default boolean setMinRows(int minRows) {
            return false;
        }

        /**
         * Set the max number of collums to show
         *
         * @param maxColumns the maximum
         *
         * @return true if the number of visible columns has changed.
         */
        default boolean setMaxColumns(int maxColumns) {
            return false;
        }

        default void setExpansion(float expansion) {}

        int getNumVisibleTiles();
    }
}
