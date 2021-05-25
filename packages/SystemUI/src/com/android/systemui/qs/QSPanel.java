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

import static com.android.systemui.util.Utils.useQsMediaPlayer;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.widget.RemeasuringLinearLayout;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.settings.brightness.BrightnessSlider;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** View that represents the quick settings tile panel (when expanded/pulled down). **/
public class QSPanel extends LinearLayout implements Tunable {

    public static final String QS_SHOW_BRIGHTNESS = "qs_show_brightness";
    public static final String QS_SHOW_HEADER = "qs_show_header";

    private static final String TAG = "QSPanel";

    protected final Context mContext;

    /**
     * The index where the content starts that needs to be moved between parents
     */
    private int mMovableContentStartIndex;

    @Nullable
    protected View mBrightnessView;
    @Nullable
    protected BrightnessSlider mToggleSliderController;

    private final H mHandler = new H();
    /** Whether or not the QS media player feature is enabled. */
    protected boolean mUsingMediaPlayer;

    protected boolean mExpanded;
    protected boolean mListening;

    private QSDetail.Callback mCallback;
    protected QSTileHost mHost;
    private final List<OnConfigurationChangedListener> mOnConfigurationChangedListeners =
            new ArrayList<>();

    @Nullable
    protected View mSecurityFooter;

    @Nullable
    protected View mFooter;

    @Nullable
    private ViewGroup mHeaderContainer;
    private PageIndicator mFooterPageIndicator;
    private int mContentMarginStart;
    private int mContentMarginEnd;
    private boolean mUsingHorizontalLayout;

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
    private Consumer<Boolean> mMediaVisibilityChangedListener;

    public QSPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mUsingMediaPlayer = useQsMediaPlayer(context);
        mMediaTotalBottomMargin = getResources().getDimensionPixelSize(
                R.dimen.quick_settings_bottom_margin_media);
        mContext = context;

        setOrientation(VERTICAL);

        mMovableContentStartIndex = getChildCount();

    }

    void initialize() {
        mRegularTileLayout = createRegularTileLayout();
        mTileLayout = mRegularTileLayout;

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
            int marginSize = (int) mContext.getResources().getDimension(R.dimen.qs_media_padding);
            lp.setMarginStart(0);
            lp.setMarginEnd(marginSize);
            lp.gravity = Gravity.CENTER_VERTICAL;
            mHorizontalLinearLayout.addView(mHorizontalContentContainer, lp);

            lp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1);
            addView(mHorizontalLinearLayout, lp);
        }
    }

    protected void onMediaVisibilityChanged(Boolean visible) {
        if (mMediaVisibilityChangedListener != null) {
            mMediaVisibilityChangedListener.accept(visible);
        }
    }

    /**
     * Add brightness view above the tile layout.
     *
     * Used to add the brightness slider after construction.
     */
    public void setBrightnessView(@NonNull View view) {
        if (mBrightnessView != null) {
            removeView(mBrightnessView);
            mMovableContentStartIndex--;
        }
        addView(view, 0);
        mBrightnessView = view;

        setBrightnessViewMargin();

        mMovableContentStartIndex++;
    }

    private void setBrightnessViewMargin() {
        if (mBrightnessView != null) {
            MarginLayoutParams lp = (MarginLayoutParams) mBrightnessView.getLayoutParams();
            lp.topMargin = mContext.getResources()
                    .getDimensionPixelSize(R.dimen.qs_brightness_margin_top);
            lp.bottomMargin = mContext.getResources()
                    .getDimensionPixelSize(R.dimen.qs_brightness_margin_bottom);
            mBrightnessView.setLayoutParams(lp);
        }
    }

    /** */
    public QSTileLayout createRegularTileLayout() {
        if (mRegularTileLayout == null) {
            mRegularTileLayout = (QSTileLayout) LayoutInflater.from(mContext)
                    .inflate(R.layout.qs_paged_tile_layout, this, false);
        }
        return mRegularTileLayout;
    }


    protected QSTileLayout createHorizontalTileLayout() {
        return createRegularTileLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mTileLayout instanceof PagedTileLayout) {
            // Since PageIndicator gets measured before PagedTileLayout, we preemptively set the
            // # of pages before the measurement pass so PageIndicator is measured appropriately
            if (mFooterPageIndicator != null) {
                mFooterPageIndicator.setNumPages(((PagedTileLayout) mTileLayout).getNumPages());
            }

            // In landscape, mTileLayout's parent is not the panel but a view that contains the
            // tile layout and the media controls.
            if (((View) mTileLayout).getParent() == this) {
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

    protected String getDumpableTag() {
        return TAG;
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

    /** */
    public void openDetails(QSTile tile) {
        // If there's no tile with that name (as defined in QSFactoryImpl or other QSFactory),
        // QSFactory will not be able to create a tile and getTile will return null
        if (tile != null) {
            showDetailAdapter(true, tile.getDetailAdapter(), new int[]{getWidth() / 2, 0});
        }
    }

    @Nullable
    View getBrightnessView() {
        return mBrightnessView;
    }

    public void setCallback(QSDetail.Callback callback) {
        mCallback = callback;
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
        updatePadding();

        updatePageIndicator();

        setBrightnessViewMargin();

        if (mTileLayout != null) {
            mTileLayout.updateResources();
        }
    }

    protected void updatePadding() {
        final Resources res = mContext.getResources();
        int padding = res.getDimensionPixelSize(R.dimen.qs_panel_padding_top);
        setPaddingRelative(getPaddingStart(),
                padding,
                getPaddingEnd(),
                res.getDimensionPixelSize(R.dimen.qs_panel_padding_bottom));
    }

    void addOnConfigurationChangedListener(OnConfigurationChangedListener listener) {
        mOnConfigurationChangedListeners.add(listener);
    }

    void removeOnConfigurationChangedListener(OnConfigurationChangedListener listener) {
        mOnConfigurationChangedListeners.remove(listener);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mOnConfigurationChangedListeners.forEach(
                listener -> listener.onConfigurationChange(newConfig));
        switchSecurityFooter();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mFooter = findViewById(R.id.qs_footer);
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

        if (mFooter != null) {
            // Then the footer with the settings
            switchToParent(mFooter, parent, index);
            index++;
        }

        // The security footer is switched on orientation changes
    }

    private void switchSecurityFooter() {
        if (mSecurityFooter != null) {
            if (mContext.getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE && mHeaderContainer != null
                    && !mSecurityFooter.getParent().equals(mHeaderContainer)) {
                // Adding the security view to the header, that enables us to avoid scrolling
                switchToParent(mSecurityFooter, mHeaderContainer, 0);
            } else {
                switchToParent(mSecurityFooter, this, -1);
            }
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

    /** Call when orientation has changed and MediaHost needs to be adjusted. */
    private void reAttachMediaHost(ViewGroup hostView, boolean horizontal) {
        if (!mUsingMediaPlayer) {
            return;
        }
        ViewGroup newParent = horizontal ? mHorizontalLinearLayout : this;
        ViewGroup currentParent = (ViewGroup) hostView.getParent();
        if (currentParent != newParent) {
            if (currentParent != null) {
                currentParent.removeView(hostView);
            }
            newParent.addView(hostView);
            LinearLayout.LayoutParams layoutParams = (LayoutParams) hostView.getLayoutParams();
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

    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        if (!mExpanded && mTileLayout instanceof PagedTileLayout) {
            ((PagedTileLayout) mTileLayout).setCurrentItem(0, false);
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

    /** */
    public void setListening(boolean listening) {
        mListening = listening;
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

    protected void drawTile(QSPanelControllerBase.TileRecord r, QSTile.State state) {
        r.tileView.onStateChanged(state);
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

    void addTile(QSPanelControllerBase.TileRecord tileRecord) {
        final QSTile.Callback callback = new QSTile.Callback() {
            @Override
            public void onStateChanged(QSTile.State state) {
                drawTile(tileRecord, state);
            }

            @Override
            public void onShowDetail(boolean show) {
                // Both the collapsed and full QS panels get this callback, this check determines
                // which one should handle showing the detail.
                if (shouldShowDetail()) {
                    QSPanel.this.showDetail(show, tileRecord);
                }
            }

            @Override
            public void onToggleStateChanged(boolean state) {
                if (mDetailRecord == tileRecord) {
                    fireToggleStateChanged(state);
                }
            }

            @Override
            public void onScanStateChanged(boolean state) {
                tileRecord.scanState = state;
                if (mDetailRecord == tileRecord) {
                    fireScanStateChanged(tileRecord.scanState);
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

        tileRecord.tile.addCallback(callback);
        tileRecord.callback = callback;
        tileRecord.tileView.init(tileRecord.tile);
        tileRecord.tile.refreshState();

        if (mTileLayout != null) {
            mTileLayout.addTile(tileRecord);
        }
    }

    void removeTile(QSPanelControllerBase.TileRecord tileRecord) {
        mTileLayout.removeTile(tileRecord);
    }

    void closeDetail() {
        showDetail(false, mDetailRecord);
    }

    public int getGridHeight() {
        return getMeasuredHeight();
    }

    protected void handleShowDetail(Record r, boolean show) {
        if (r instanceof QSPanelControllerBase.TileRecord) {
            handleShowDetailTile((QSPanelControllerBase.TileRecord) r, show);
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

    private void handleShowDetailTile(QSPanelControllerBase.TileRecord r, boolean show) {
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
        final boolean scanState = mDetailRecord instanceof QSPanelControllerBase.TileRecord
                && ((QSPanelControllerBase.TileRecord) mDetailRecord).scanState;
        fireScanStateChanged(scanState);
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

    QSTileLayout getTileLayout() {
        return mTileLayout;
    }

    /** */
    public void setContentMargins(int startMargin, int endMargin, ViewGroup mediaHostView) {
        // Only some views actually want this content padding, others want to go all the way
        // to the edge like the brightness slider
        mContentMarginStart = startMargin;
        mContentMarginEnd = endMargin;
        updateMediaHostContentMargins(mediaHostView);
    }

    /**
     * Update the margins of the media hosts
     */
    protected void updateMediaHostContentMargins(ViewGroup mediaHostView) {
        if (mUsingMediaPlayer) {
            int marginStart = 0;
            int marginEnd = 0;
            if (mUsingHorizontalLayout) {
                marginEnd = mContentMarginEnd;
            }
            updateMargins(mediaHostView, marginStart, marginEnd);
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
        if (lp != null) {
            lp.setMarginStart(start);
            lp.setMarginEnd(end);
            view.setLayoutParams(lp);
        }
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

    public boolean isListening() {
        return mListening;
    }

    public void setSecurityFooter(View view) {
        mSecurityFooter = view;
        switchSecurityFooter();
    }

    protected void setPageMargin(int pageMargin) {
        if (mRegularTileLayout instanceof PagedTileLayout) {
            ((PagedTileLayout) mRegularTileLayout).setPageMargin(pageMargin);
        }
        if (mHorizontalTileLayout != mRegularTileLayout
                && mHorizontalTileLayout instanceof PagedTileLayout) {
            ((PagedTileLayout) mHorizontalTileLayout).setPageMargin(pageMargin);
        }
    }

    void setUsingHorizontalLayout(boolean horizontal, ViewGroup mediaHostView, boolean force,
            UiEventLogger uiEventLogger) {
        if (horizontal != mUsingHorizontalLayout || force) {
            mUsingHorizontalLayout = horizontal;
            View visibleView = horizontal ? mHorizontalLinearLayout : (View) mRegularTileLayout;
            View hiddenView = horizontal ? (View) mRegularTileLayout : mHorizontalLinearLayout;
            ViewGroup newParent = horizontal ? mHorizontalContentContainer : this;
            QSPanel.QSTileLayout newLayout = horizontal
                    ? mHorizontalTileLayout : mRegularTileLayout;
            if (hiddenView != null
                    && (mRegularTileLayout != mHorizontalTileLayout
                    || hiddenView != mRegularTileLayout)) {
                // Only hide the view if the horizontal and the regular view are different,
                // otherwise its reattached.
                hiddenView.setVisibility(View.GONE);
            }
            visibleView.setVisibility(View.VISIBLE);
            switchAllContentToParent(newParent, newLayout);
            reAttachMediaHost(mediaHostView, horizontal);
            mTileLayout = newLayout;
            newLayout.setListening(mListening, uiEventLogger);
            if (needsDynamicRowsAndColumns()) {
                newLayout.setMinRows(horizontal ? 2 : 1);
                newLayout.setMaxColumns(horizontal ? 2 : 4);
            }
            updateMargins(mediaHostView);
        }
    }

    private void updateMargins(ViewGroup mediaHostView) {
        updateMediaHostContentMargins(mediaHostView);
        updateHorizontalLinearLayoutMargins();
        updatePadding();
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

    protected static class Record {
        DetailAdapter detailAdapter;
        int x;
        int y;
    }

    public interface QSTileLayout {
        /** */
        default void saveInstanceState(Bundle outState) {}

        /** */
        default void restoreInstanceState(Bundle savedInstanceState) {}

        /** */
        void addTile(QSPanelControllerBase.TileRecord tile);

        /** */
        void removeTile(QSPanelControllerBase.TileRecord tile);

        /** */
        int getOffsetTop(QSPanelControllerBase.TileRecord tile);

        /** */
        boolean updateResources();

        /** */
        void setListening(boolean listening, UiEventLogger uiEventLogger);

        /**
         * Set the minimum number of rows to show
         *
         * @param minRows the minimum.
         */
        default boolean setMinRows(int minRows) {
            return false;
        }

        /**
         * Set the max number of columns to show
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

    interface OnConfigurationChangedListener {
        void onConfigurationChange(Configuration newConfig);
    }
}
