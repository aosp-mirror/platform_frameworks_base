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

package com.android.systemui.qs;

import static com.android.systemui.classifier.Classifier.QS_SWIPE;
import static com.android.systemui.media.dagger.MediaModule.QS_PANEL;
import static com.android.systemui.qs.QSPanel.QS_SHOW_BRIGHTNESS;
import static com.android.systemui.qs.dagger.QSFragmentModule.QS_USING_MEDIA_PLAYER;

import android.annotation.NonNull;
import android.content.res.Configuration;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.MediaHierarchyManager;
import com.android.systemui.media.MediaHost;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.customize.QSCustomizerController;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.settings.brightness.BrightnessController;
import com.android.systemui.settings.brightness.BrightnessSlider;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.tuner.TunerService;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Controller for {@link QSPanel}.
 */
@QSScope
public class QSPanelController extends QSPanelControllerBase<QSPanel> {
    public static final String QS_REMOVE_LABELS = "sysui_remove_labels";

    private final QSSecurityFooter mQsSecurityFooter;
    private final TunerService mTunerService;
    private final QSCustomizerController mQsCustomizerController;
    private final QSTileRevealController.Factory mQsTileRevealControllerFactory;
    private final FalsingManager mFalsingManager;
    private final BrightnessController mBrightnessController;
    private final BrightnessSlider.Factory mBrightnessSliderFactory;
    private final BrightnessSlider mBrightnessSlider;

    private BrightnessMirrorController mBrightnessMirrorController;
    private boolean mGridContentVisible = true;

    private final QSPanel.OnConfigurationChangedListener mOnConfigurationChangedListener =
            new QSPanel.OnConfigurationChangedListener() {
        @Override
        public void onConfigurationChange(Configuration newConfig) {
            mView.updateResources();
            mQsSecurityFooter.onConfigurationChanged();
            if (mView.isListening()) {
                refreshAllTiles();
            }
            updateBrightnessMirror();
        }
    };

    private final BrightnessMirrorController.BrightnessMirrorListener mBrightnessMirrorListener =
            mirror -> updateBrightnessMirror();

    private View.OnTouchListener mTileLayoutTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                mFalsingManager.isFalseTouch(QS_SWIPE);
            }
            return false;
        }
    };

    @Inject
    QSPanelController(QSPanel view, QSSecurityFooter qsSecurityFooter, TunerService tunerService,
            QSTileHost qstileHost, QSCustomizerController qsCustomizerController,
            @Named(QS_USING_MEDIA_PLAYER) boolean usingMediaPlayer,
            @Named(QS_PANEL) MediaHost mediaHost,
            QSTileRevealController.Factory qsTileRevealControllerFactory,
            DumpManager dumpManager, MetricsLogger metricsLogger, UiEventLogger uiEventLogger,
            QSLogger qsLogger, BrightnessController.Factory brightnessControllerFactory,
            BrightnessSlider.Factory brightnessSliderFactory, FalsingManager falsingManager,
            FeatureFlags featureFlags) {
        super(view, qstileHost, qsCustomizerController, usingMediaPlayer, mediaHost,
                metricsLogger, uiEventLogger, qsLogger, dumpManager, featureFlags);
        mQsSecurityFooter = qsSecurityFooter;
        mTunerService = tunerService;
        mQsCustomizerController = qsCustomizerController;
        mQsTileRevealControllerFactory = qsTileRevealControllerFactory;
        mFalsingManager = falsingManager;
        mQsSecurityFooter.setHostEnvironment(qstileHost);
        mBrightnessSliderFactory = brightnessSliderFactory;

        mBrightnessSlider = mBrightnessSliderFactory.create(getContext(), mView);
        mView.setBrightnessView(mBrightnessSlider.getRootView());

        mBrightnessController = brightnessControllerFactory.create(mBrightnessSlider);
    }

    @Override
    public void onInit() {
        super.onInit();
        mMediaHost.setExpansion(1);
        mMediaHost.setShowsOnlyActiveMedia(false);
        mMediaHost.init(MediaHierarchyManager.LOCATION_QS);
        mQsCustomizerController.init();
        mBrightnessSlider.init();
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();

        updateMediaDisappearParameters();

        mTunerService.addTunable(mView, QS_SHOW_BRIGHTNESS);
        mView.updateResources();
        if (mView.isListening()) {
            refreshAllTiles();
        }
        mView.addOnConfigurationChangedListener(mOnConfigurationChangedListener);
        mView.setSecurityFooter(mQsSecurityFooter.getView());
        switchTileLayout(true);
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.addCallback(mBrightnessMirrorListener);
        }

        ((PagedTileLayout) mView.createRegularTileLayout())
                .setOnTouchListener(mTileLayoutTouchListener);
    }

    @Override
    protected QSTileRevealController createTileRevealController() {
        return mQsTileRevealControllerFactory.create(
                this, (PagedTileLayout) mView.createRegularTileLayout());
    }

    @Override
    protected void onViewDetached() {
        mTunerService.removeTunable(mView);
        mView.removeOnConfigurationChangedListener(mOnConfigurationChangedListener);
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.removeCallback(mBrightnessMirrorListener);
        }
        super.onViewDetached();
    }

    /**
     * Set the header container of quick settings.
     */
    public void setHeaderContainer(@NonNull ViewGroup headerContainer) {
        mView.setHeaderContainer(headerContainer);
    }

    /** */
    public void setVisibility(int visibility) {
        mView.setVisibility(visibility);
    }

    /** */
    public void setListening(boolean listening, boolean expanded) {
        setListening(listening && expanded);
        if (mView.isListening()) {
            refreshAllTiles();
        }

        mQsSecurityFooter.setListening(listening);

        // Set the listening as soon as the QS fragment starts listening regardless of the
        //expansion, so it will update the current brightness before the slider is visible.
        if (listening) {
            mBrightnessController.registerCallbacks();
        } else {
            mBrightnessController.unregisterCallbacks();
        }
    }

    /** */
    public void setBrightnessMirror(BrightnessMirrorController brightnessMirrorController) {
        mBrightnessMirrorController = brightnessMirrorController;
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.removeCallback(mBrightnessMirrorListener);
        }
        mBrightnessMirrorController = brightnessMirrorController;
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.addCallback(mBrightnessMirrorListener);
        }
        updateBrightnessMirror();
    }

    private void updateBrightnessMirror() {
        if (mBrightnessMirrorController != null) {
            mBrightnessSlider.setMirrorControllerAndMirror(mBrightnessMirrorController);
        }
    }

    /** Get the QSTileHost this panel uses. */
    public QSTileHost getHost() {
        return mHost;
    }


    /** Open the details for a specific tile.. */
    public void openDetails(String subPanel) {
        QSTile tile = getTile(subPanel);
        if (tile != null) {
            mView.openDetails(tile);
        }
    }

    /** Show the device monitoring dialog. */
    public void showDeviceMonitoringDialog() {
        mQsSecurityFooter.showDeviceMonitoringDialog();
    }

    /** Update appearance of QSPanel. */
    public void updateResources() {
        mView.updateResources();
    }

    /** Update state of all tiles. */
    public void refreshAllTiles() {
        mBrightnessController.checkRestrictionAndSetEnabled();
        super.refreshAllTiles();
        mQsSecurityFooter.refreshState();
    }

    /** Start customizing the Quick Settings. */
    public void showEdit(View view) {
        view.post(() -> {
            if (!mQsCustomizerController.isCustomizing()) {
                int[] loc = view.getLocationOnScreen();
                int x = loc[0] + view.getWidth() / 2;
                int y = loc[1] + view.getHeight() / 2;
                mQsCustomizerController.show(x, y, false);
            }
        });
    }

    /** */
    public void setCallback(QSDetail.Callback qsPanelCallback) {
        mView.setCallback(qsPanelCallback);
    }

    /** */
    public void setGridContentVisibility(boolean visible) {
        int newVis = visible ? View.VISIBLE : View.INVISIBLE;
        setVisibility(newVis);
        if (mGridContentVisible != visible) {
            mMetricsLogger.visibility(MetricsEvent.QS_PANEL, newVis);
        }
        mGridContentVisible = visible;
    }

    public boolean isLayoutRtl() {
        return mView.isLayoutRtl();
    }

    public View getBrightnessView() {
        return mView.getBrightnessView();
    }

    /** */
    public void setPageListener(PagedTileLayout.PageListener listener) {
        mView.setPageListener(listener);
    }

    public boolean isShown() {
        return mView.isShown();
    }

    /** */
    public void setContentMargins(int startMargin, int endMargin) {
        mView.setContentMargins(startMargin, endMargin, mMediaHost.getHostView());
    }

    /** */
    public void showDetailAdapter(DetailAdapter detailAdapter, int x, int y) {
        mView.showDetailAdapter(true, detailAdapter, new int[]{x, y});
    }

    /** */
    public void setFooterPageIndicator(PageIndicator pageIndicator) {
        mView.setFooterPageIndicator(pageIndicator);
    }

    /** */
    public boolean isExpanded() {
        return mView.isExpanded();
    }

    void setPageMargin(int pageMargin) {
        mView.setPageMargin(pageMargin);
    }
}

