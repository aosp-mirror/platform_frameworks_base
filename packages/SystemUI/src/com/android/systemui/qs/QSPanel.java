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

import static com.android.systemui.qs.tileimpl.QSTileImpl.getColorForState;
import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;
import static com.android.systemui.util.Utils.useQsMediaPlayer;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.metrics.LogMaker;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.service.notification.StatusBarNotification;
import android.service.quicksettings.Tile;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.Utils;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.media.InfoMediaManager;
import com.android.settingslib.media.LocalMediaManager;
import com.android.settingslib.media.MediaDevice;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTileView;
import com.android.systemui.qs.QSHost.Callback;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.settings.BrightnessController;
import com.android.systemui.settings.ToggleSliderView;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.statusbar.policy.BrightnessMirrorController.BrightnessMirrorListener;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
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
    private String mCachedSpecs = "";
    protected final View mBrightnessView;
    private final H mHandler = new H();
    private final MetricsLogger mMetricsLogger = Dependency.get(MetricsLogger.class);
    private final QSTileRevealController mQsTileRevealController;

    private final LinearLayout mMediaCarousel;
    private final ArrayList<QSMediaPlayer> mMediaPlayers = new ArrayList<>();
    private final NotificationMediaManager mNotificationMediaManager;
    private final LocalBluetoothManager mLocalBluetoothManager;
    private final Executor mForegroundExecutor;
    private final DelayableExecutor mBackgroundExecutor;
    private LocalMediaManager mLocalMediaManager;
    private MediaDevice mDevice;
    private boolean mUpdateCarousel = false;

    protected boolean mExpanded;
    protected boolean mListening;

    private QSDetail.Callback mCallback;
    private BrightnessController mBrightnessController;
    private final DumpManager mDumpManager;
    private final QSLogger mQSLogger;
    protected QSTileHost mHost;

    protected QSSecurityFooter mFooter;
    private PageIndicator mFooterPageIndicator;
    private boolean mGridContentVisible = true;

    protected QSTileLayout mTileLayout;

    private QSCustomizer mCustomizePanel;
    private Record mDetailRecord;

    private BrightnessMirrorController mBrightnessMirrorController;
    private View mDivider;

    private final LocalMediaManager.DeviceCallback mDeviceCallback =
            new LocalMediaManager.DeviceCallback() {
        @Override
        public void onDeviceListUpdate(List<MediaDevice> devices) {
            if (mLocalMediaManager == null) {
                return;
            }
            MediaDevice currentDevice = mLocalMediaManager.getCurrentConnectedDevice();
            // Check because this can be called several times while changing devices
            if (mDevice == null || !mDevice.equals(currentDevice)) {
                mDevice = currentDevice;
                for (QSMediaPlayer p : mMediaPlayers) {
                    p.updateDevice(mDevice);
                }
            }
        }

        @Override
        public void onSelectedDeviceStateChanged(MediaDevice device, int state) {
            if (mDevice == null || !mDevice.equals(device)) {
                mDevice = device;
                for (QSMediaPlayer p : mMediaPlayers) {
                    p.updateDevice(mDevice);
                }
            }
        }
    };

    @Inject
    public QSPanel(
            @Named(VIEW_CONTEXT) Context context,
            AttributeSet attrs,
            DumpManager dumpManager,
            BroadcastDispatcher broadcastDispatcher,
            QSLogger qsLogger,
            NotificationMediaManager notificationMediaManager,
            @Main Executor foregroundExecutor,
            @Background DelayableExecutor backgroundExecutor,
            @Nullable LocalBluetoothManager localBluetoothManager
    ) {
        super(context, attrs);
        mContext = context;
        mQSLogger = qsLogger;
        mDumpManager = dumpManager;
        mNotificationMediaManager = notificationMediaManager;
        mForegroundExecutor = foregroundExecutor;
        mBackgroundExecutor = backgroundExecutor;
        mLocalBluetoothManager = localBluetoothManager;

        setOrientation(VERTICAL);

        mBrightnessView = LayoutInflater.from(mContext).inflate(
            R.layout.quick_settings_brightness_dialog, this, false);
        addView(mBrightnessView);

        mTileLayout = (QSTileLayout) LayoutInflater.from(mContext).inflate(
                R.layout.qs_paged_tile_layout, this, false);
        mQSLogger.logAllTilesChangeListening(mListening, getDumpableTag(), mCachedSpecs);
        mTileLayout.setListening(mListening);
        addView((View) mTileLayout);

        mQsTileRevealController = new QSTileRevealController(mContext, this,
                (PagedTileLayout) mTileLayout);

        addDivider();

        // Add media carousel
        if (useQsMediaPlayer(context)) {
            HorizontalScrollView mediaScrollView = (HorizontalScrollView) LayoutInflater.from(
                    mContext).inflate(R.layout.media_carousel, this, false);
            mMediaCarousel = mediaScrollView.findViewById(R.id.media_carousel);
            addView(mediaScrollView, 0);
        } else {
            mMediaCarousel = null;
        }

        mFooter = new QSSecurityFooter(this, context);
        addView(mFooter.getView());

        updateResources();

        mBrightnessController = new BrightnessController(getContext(),
                findViewById(R.id.brightness_slider), broadcastDispatcher);
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (!isVisible && mUpdateCarousel) {
            for (QSMediaPlayer player : mMediaPlayers) {
                if (player.isPlaying()) {
                    LayoutParams lp = (LayoutParams) player.getView().getLayoutParams();
                    mMediaCarousel.removeView(player.getView());
                    mMediaCarousel.addView(player.getView(), 0, lp);
                    ((HorizontalScrollView) mMediaCarousel.getParent()).fullScroll(View.FOCUS_LEFT);
                    mUpdateCarousel = false;
                    break;
                }
            }
        }
    }

    /**
     * Add or update a player for the associated media session
     * @param token
     * @param icon
     * @param iconColor
     * @param bgColor
     * @param actionsContainer
     * @param notif
     */
    public void addMediaSession(MediaSession.Token token, Icon icon, int iconColor, int bgColor,
            View actionsContainer, StatusBarNotification notif) {
        if (!useQsMediaPlayer(mContext)) {
            // Shouldn't happen, but just in case
            Log.e(TAG, "Tried to add media session without player!");
            return;
        }
        if (token == null) {
            Log.e(TAG, "Media session token was null!");
            return;
        }

        QSMediaPlayer player = null;
        String packageName = notif.getPackageName();
        for (QSMediaPlayer p : mMediaPlayers) {
            if (p.getMediaSessionToken().equals(token)) {
                Log.d(TAG, "a player for this session already exists");
                player = p;
                break;
            }

            if (packageName.equals(p.getMediaPlayerPackage())) {
                Log.d(TAG, "found an old session for this app");
                player = p;
                break;
            }
        }

        int playerWidth = (int) getResources().getDimension(R.dimen.qs_media_width);
        int padding = (int) getResources().getDimension(R.dimen.qs_media_padding);
        LayoutParams lp = new LayoutParams(playerWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMarginStart(padding);
        lp.setMarginEnd(padding);

        if (player == null) {
            Log.d(TAG, "creating new player");
            player = new QSMediaPlayer(mContext, this, mNotificationMediaManager,
                    mForegroundExecutor, mBackgroundExecutor);
            player.setListening(mListening);
            if (player.isPlaying()) {
                mMediaCarousel.addView(player.getView(), 0, lp); // add in front
            } else {
                mMediaCarousel.addView(player.getView(), lp); // add at end
            }
            mMediaPlayers.add(player);
        } else if (player.isPlaying()) {
            mUpdateCarousel = true;
        }

        Log.d(TAG, "setting player session");
        player.setMediaSession(token, icon, iconColor, bgColor, actionsContainer,
                notif.getNotification(), mDevice);

        if (mMediaPlayers.size() > 0) {
            ((View) mMediaCarousel.getParent()).setVisibility(View.VISIBLE);

            if (mLocalMediaManager == null) {
                // Set up listener for device changes
                // TODO: integrate with MediaTransferManager?
                InfoMediaManager imm =
                        new InfoMediaManager(mContext, null, null, mLocalBluetoothManager);
                mLocalMediaManager = new LocalMediaManager(mContext, mLocalBluetoothManager, imm,
                        null);
                mLocalMediaManager.startScan();
                mDevice = mLocalMediaManager.getCurrentConnectedDevice();
                mLocalMediaManager.registerCallback(mDeviceCallback);
            }
        }
    }

    protected View getMediaPanel() {
        return mMediaCarousel;
    }

    /**
     * Remove the media player from the carousel
     * @param player Player to remove
     * @return true if removed, false if player was not found
     */
    protected boolean removeMediaPlayer(QSMediaPlayer player) {
        // Remove from list
        if (!mMediaPlayers.remove(player)) {
            return false;
        }

        // Check if we need to collapse the carousel now
        mMediaCarousel.removeView(player.getView());
        if (mMediaPlayers.size() == 0) {
            ((View) mMediaCarousel.getParent()).setVisibility(View.GONE);
            if (mLocalMediaManager != null) {
                mLocalMediaManager.stopScan();
                mLocalMediaManager.unregisterCallback(mDeviceCallback);
                mLocalMediaManager = null;
            }
        }
        return true;
    }

    protected void addDivider() {
        mDivider = LayoutInflater.from(mContext).inflate(R.layout.qs_divider, this, false);
        mDivider.setBackgroundColor(Utils.applyAlpha(mDivider.getAlpha(),
                getColorForState(mContext, Tile.STATE_ACTIVE)));
        addView(mDivider);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // We want all the logic of LinearLayout#onMeasure, and for it to assign the excess space
        // not used by the other children to PagedTileLayout. However, in this case, LinearLayout
        // assumes that PagedTileLayout would use all the excess space. This is not the case as
        // PagedTileLayout height is quantized (because it shows a certain number of rows).
        // Therefore, after everything is measured, we need to make sure that we add up the correct
        // total height
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int height = getPaddingBottom() + getPaddingTop();
        int numChildren = getChildCount();
        for (int i = 0; i < numChildren; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.GONE) height += child.getMeasuredHeight();
        }
        setMeasuredDimension(getMeasuredWidth(), height);
    }

    public View getDivider() {
        return mDivider;
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
        for (TileRecord record : mRecords) {
            record.tile.removeCallbacks();
        }
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.removeCallback(this);
        }
        mDumpManager.unregisterDumpable(getDumpableTag());
        if (mLocalMediaManager != null) {
            mLocalMediaManager.stopScan();
            mLocalMediaManager.unregisterCallback(mDeviceCallback);
            mLocalMediaManager = null;
        }
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
        if (QS_SHOW_BRIGHTNESS.equals(key)) {
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
        mFooter.setHostEnvironment(host);
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
        if (mTileLayout instanceof PagedTileLayout) {
            mFooterPageIndicator = pageIndicator;
            updatePageIndicator();
        }
    }

    private void updatePageIndicator() {
        if (mTileLayout instanceof PagedTileLayout) {
            if (mFooterPageIndicator != null) {
                mFooterPageIndicator.setVisibility(View.GONE);

                ((PagedTileLayout) mTileLayout).setPageIndicator(mFooterPageIndicator);
            }
        }
    }

    public QSTileHost getHost() {
        return mHost;
    }

    public void updateResources() {
        final Resources res = mContext.getResources();
        setPadding(0, res.getDimensionPixelSize(R.dimen.qs_panel_padding_top), 0, res.getDimensionPixelSize(R.dimen.qs_panel_padding_bottom));

        updatePageIndicator();

        if (mListening) {
            refreshAllTiles();
        }
        if (mTileLayout != null) {
            mTileLayout.updateResources();
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mFooter.onConfigurationChanged();
        updateResources();

        updateBrightnessMirror();
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
            closeDetail();
        } else {
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
        for (QSMediaPlayer player : mMediaPlayers) {
            player.setListening(mListening);
        }
    }

    private String getTilesSpecs() {
        return mRecords.stream()
                .map(tileRecord ->  tileRecord.tile.getTileSpec())
                .collect(Collectors.joining(","));
    }

    public void setListening(boolean listening, boolean expanded) {
        setListening(listening && expanded);
        getFooter().setListening(listening);
        // Set the listening as soon as the QS fragment starts listening regardless of the expansion,
        // so it will update the current brightness before the slider is visible.
        setBrightnessListening(listening);
    }

    public void setBrightnessListening(boolean listening) {
        if (listening) {
            mBrightnessController.registerCallbacks();
        } else {
            mBrightnessController.unregisterCallbacks();
        }
    }

    public void refreshAllTiles() {
        mBrightnessController.checkRestrictionAndSetEnabled();
        for (TileRecord r : mRecords) {
            r.tile.refreshState();
        }
        mFooter.refreshState();
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

    public QSSecurityFooter getFooter() {
        return mFooter;
    }

    public void showDeviceMonitoringDialog() {
        mFooter.showDeviceMonitoringDialog();
    }

    public void setMargins(int sideMargins) {
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            if (view != mTileLayout) {
                LayoutParams lp = (LayoutParams) view.getLayoutParams();
                lp.leftMargin = sideMargins;
                lp.rightMargin = sideMargins;
            }
        }
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

        default void setExpansion(float expansion) {}

        int getNumVisibleTiles();
    }
}
