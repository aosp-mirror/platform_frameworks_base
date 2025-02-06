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

import static com.android.systemui.classifier.Classifier.QS_SWIPE_SIDE;
import static com.android.systemui.media.dagger.MediaModule.QS_PANEL;
import static com.android.systemui.qs.QSPanel.QS_SHOW_BRIGHTNESS;
import static com.android.systemui.qs.dagger.QSScopeModule.QS_USING_MEDIA_PLAYER;

import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.haptics.qs.QSLongPressEffect;
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor;
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager;
import com.android.systemui.media.controls.ui.view.MediaHost;
import com.android.systemui.media.controls.ui.view.MediaHostState;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.qs.customize.QSCustomizerController;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.settings.brightness.BrightnessController;
import com.android.systemui.settings.brightness.BrightnessMirrorHandler;
import com.android.systemui.settings.brightness.BrightnessSliderController;
import com.android.systemui.settings.brightness.MirrorController;
import com.android.systemui.shade.ShadeDisplayAware;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.SplitShadeStateController;
import com.android.systemui.tuner.TunerService;

import kotlinx.coroutines.flow.StateFlow;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;


/**
 * Controller for {@link QSPanel}.
 */
@QSScope
public class QSPanelController extends QSPanelControllerBase<QSPanel> {

    private final TunerService mTunerService;
    private final QSCustomizerController mQsCustomizerController;
    private final QSTileRevealController.Factory mQsTileRevealControllerFactory;
    private final FalsingManager mFalsingManager;
    private BrightnessController mBrightnessController;
    private BrightnessSliderController mBrightnessSliderController;
    private BrightnessMirrorHandler mBrightnessMirrorHandler;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private boolean mListening;

    private final boolean mSceneContainerEnabled;

    private int mLastDensity;
    private final BrightnessSliderController.Factory mBrightnessSliderControllerFactory;
    private final BrightnessController.Factory mBrightnessControllerFactory;

    protected final MediaCarouselInteractor mMediaCarouselInteractor;

    private View.OnTouchListener mTileLayoutTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                mFalsingManager.isFalseTouch(QS_SWIPE_SIDE);
            }
            return false;
        }
    };

    @Inject
    QSPanelController(QSPanel view, TunerService tunerService,
            QSHost qsHost, QSCustomizerController qsCustomizerController,
            @Named(QS_USING_MEDIA_PLAYER) boolean usingMediaPlayer,
            @Named(QS_PANEL) MediaHost mediaHost,
            QSTileRevealController.Factory qsTileRevealControllerFactory,
            DumpManager dumpManager, MetricsLogger metricsLogger, UiEventLogger uiEventLogger,
            QSLogger qsLogger, BrightnessController.Factory brightnessControllerFactory,
            BrightnessSliderController.Factory brightnessSliderFactory,
            FalsingManager falsingManager,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            SplitShadeStateController splitShadeStateController,
            Provider<QSLongPressEffect> longPRessEffectProvider,
            MediaCarouselInteractor mediaCarouselInteractor,
            @ShadeDisplayAware ConfigurationController configurationController) {
        super(view, qsHost, qsCustomizerController, usingMediaPlayer, mediaHost,
                metricsLogger, uiEventLogger, qsLogger, dumpManager, splitShadeStateController,
                longPRessEffectProvider, configurationController);
        mTunerService = tunerService;
        mQsCustomizerController = qsCustomizerController;
        mQsTileRevealControllerFactory = qsTileRevealControllerFactory;
        mFalsingManager = falsingManager;
        mBrightnessSliderControllerFactory = brightnessSliderFactory;
        mBrightnessControllerFactory = brightnessControllerFactory;

        mBrightnessSliderController = brightnessSliderFactory.create(getContext(), mView);
        mView.setBrightnessView(mBrightnessSliderController.getRootView());

        mBrightnessController = brightnessControllerFactory.create(mBrightnessSliderController);
        mBrightnessMirrorHandler = new BrightnessMirrorHandler(mBrightnessController);
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mLastDensity = view.getResources().getConfiguration().densityDpi;
        mSceneContainerEnabled = SceneContainerFlag.isEnabled();
        mMediaCarouselInteractor = mediaCarouselInteractor;
    }

    @Override
    public void onInit() {
        super.onInit();
        mMediaHost.setExpansion(MediaHostState.EXPANDED);
        mMediaHost.setShowsOnlyActiveMedia(false);
        mMediaHost.init(MediaHierarchyManager.LOCATION_QS);
        mQsCustomizerController.init();
        mBrightnessSliderController.init();
    }

    @Override
    StateFlow<Boolean> getMediaVisibleFlow() {
        return mMediaCarouselInteractor.getHasAnyMediaOrRecommendation();
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();

        updateMediaDisappearParameters();

        mTunerService.addTunable(mView, QS_SHOW_BRIGHTNESS);
        mView.updateResources();
        mView.setSceneContainerEnabled(mSceneContainerEnabled);
        if (mView.isListening()) {
            refreshAllTiles();
        }
        switchTileLayout(true);
        mBrightnessMirrorHandler.onQsPanelAttached();
        PagedTileLayout pagedTileLayout= ((PagedTileLayout) mView.getOrCreateTileLayout());
        pagedTileLayout.setOnTouchListener(mTileLayoutTouchListener);
        maybeReinflateBrightnessSlider();
    }

    @Override
    protected QSTileRevealController createTileRevealController() {
        return mQsTileRevealControllerFactory.create(
                this, (PagedTileLayout) mView.getOrCreateTileLayout());
    }

    @Override
    protected void onViewDetached() {
        mTunerService.removeTunable(mView);
        mBrightnessMirrorHandler.onQsPanelDettached();
        super.onViewDetached();
    }

    @Override
    protected void onConfigurationChanged() {
        mView.updateResources();
        maybeReinflateBrightnessSlider();
        if (mView.isListening()) {
            refreshAllTiles();
        }
    }

    private void maybeReinflateBrightnessSlider() {
        int newDensity = mView.getResources().getConfiguration().densityDpi;
        if (newDensity != mLastDensity) {
            mLastDensity = newDensity;
            reinflateBrightnessSlider();
        }
    }

    private void reinflateBrightnessSlider() {
        mBrightnessController.unregisterCallbacks();
        mBrightnessSliderController =
                mBrightnessSliderControllerFactory.create(getContext(), mView);
        mView.setBrightnessView(mBrightnessSliderController.getRootView());
        mBrightnessController = mBrightnessControllerFactory.create(mBrightnessSliderController);
        mBrightnessMirrorHandler.setBrightnessController(mBrightnessController);
        mBrightnessSliderController.init();
        if (mListening) {
            mBrightnessController.registerCallbacks();
        }
    }


    @Override
    protected void onSplitShadeChanged(boolean shouldUseSplitNotificationShade) {
        ((PagedTileLayout) mView.getOrCreateTileLayout())
                .forceTilesRedistribution("Split shade state changed");
        mView.setCanCollapse(!shouldUseSplitNotificationShade);
    }

    /** */
    public void setVisibility(int visibility) {
        mView.setVisibility(visibility);
    }

    /** */
    public void setListening(boolean listening, boolean expanded) {
        setListening(listening && expanded);

        if (listening != mListening) {
            mListening = listening;
            // Set the listening as soon as the QS fragment starts listening regardless of the
            //expansion, so it will update the current brightness before the slider is visible.
            if (listening) {
                mBrightnessController.registerCallbacks();
            } else {
                mBrightnessController.unregisterCallbacks();
            }
        }
    }

    public void setBrightnessMirror(@Nullable MirrorController brightnessMirrorController) {
        mBrightnessMirrorHandler.setController(brightnessMirrorController);
    }

    /** Update appearance of QSPanel. */
    public void updateResources() {
        mView.updateResources();
    }

    /** Update state of all tiles. */
    public void refreshAllTiles() {
        mBrightnessController.checkRestrictionAndSetEnabled();
        super.refreshAllTiles();
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

    public boolean isLayoutRtl() {
        return mView.isLayoutRtl();
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
    public void setFooterPageIndicator(PageIndicator pageIndicator) {
        mView.setFooterPageIndicator(pageIndicator);
    }

    /** */
    public boolean isExpanded() {
        return mView.isExpanded();
    }

    void setPageMargin(int pageMarginStart, int pageMarginEnd) {
        mView.setPageMargin(pageMarginStart, pageMarginEnd);
    }

    /**
     * Determines if bouncer expansion is between 0 and 1 non-inclusive.
     *
     * @return if bouncer is in transit
     */
    public boolean isBouncerInTransit() {
        return mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit();
    }

    public int getPaddingBottom() {
        return mView.getPaddingBottom();
    }

    int getViewBottom() {
        return mView.getBottom();
    }
}

